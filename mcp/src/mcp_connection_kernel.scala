/*  Title:      mcp/src/mcp_connection_kernel.scala

Connection control path: revision classification, lifecycle, request ownership,
bounded scheduling, deadlines, single/batch JSON-RPC completion, and EOF drain
over a DataPlane.
*/

package isabelle.mcp.connection

import isabelle.{Exn, JSON, error}
import isabelle.mcp.application.McpApplication
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.DataPlane

import scala.util.control.NonFatal


object ConnectionLifecycle {
  sealed trait Phase
  case object Fresh extends Phase
  case object Initializing extends Phase
  case object AwaitingInitialized extends Phase
  case object Ready extends Phase
  case object Closing extends Phase
  case object Closed extends Phase

  sealed trait Decision
  final case class InitializeAccepted(id: RequestId, requestedVersion: String) extends Decision
  case object InitializedAccepted extends Decision
  final case class PingAccepted(id: RequestId) extends Decision
  final case class OperationAccepted(operation: RevisionRules.Application) extends Decision
  final case class Overloaded(id: RequestId) extends Decision
  case object Ignored extends Decision
  final case class Rejected(
    reply: RevisionRules.ReplyTarget,
    code: Int,
    message: String
  ) extends Decision
}


final class ConnectionLifecycle {
  import ConnectionLifecycle._

  private var current: Phase = Fresh

  def phase: Phase = synchronized { current }

  def admit(message: RevisionRules.Message): Decision = synchronized {
    message match {
      case RevisionRules.Invalid(reply, code, message) => Rejected(reply, code, message)
      case RevisionRules.Ignored => Ignored
      case RevisionRules.Cancelled(_, _) => Ignored
      case RevisionRules.Batch(_) => Rejected(RevisionRules.NullReply,
        RevisionRules.InvalidRequest, "Batch must be expanded before lifecycle admission")
      case RevisionRules.Initialize(id, requestedVersion) if current == Fresh =>
        current = Initializing
        InitializeAccepted(id, requestedVersion)
      case RevisionRules.Initialize(id, _) =>
        Rejected(RevisionRules.ReplyId(id), RevisionRules.InvalidRequest,
          "initialize is only valid in Fresh")
      case RevisionRules.Initialized if current == AwaitingInitialized =>
        current = Ready
        InitializedAccepted
      case RevisionRules.Initialized =>
        Rejected(RevisionRules.NoReply, RevisionRules.InvalidRequest,
          "notifications/initialized is only valid after initialize")
      case RevisionRules.Ping(id) if current == AwaitingInitialized => PingAccepted(id)
      case RevisionRules.Ping(id) if current == Ready => PingAccepted(id)
      case RevisionRules.Ping(id) => Rejected(RevisionRules.ReplyId(id), RevisionRules.InvalidRequest,
        "ping is not valid in " + current)
      case application: RevisionRules.Application if current == Ready =>
        OperationAccepted(application)
      case RevisionRules.Application(_, id) =>
        Rejected(RevisionRules.ReplyId(id), RevisionRules.InvalidRequest,
          "ordinary operations are only valid in Ready")
    }
  }

  def initializeCompleted(): Unit = synchronized {
    if (current != Initializing) error("initialize completion is only valid in Initializing")
    else current = AwaitingInitialized
  }

  def beginClosing(): Unit = synchronized {
    if (current != Closed) current = Closing
  }

  def finishClosing(): Unit = synchronized {
    if (current != Closing) error("close completion is only valid in Closing")
    else current = Closed
  }

  /* Holding this lock across one complete transport send establishes the
     notification/close linearization: an event is either sent while Ready or
     ignored before capability negotiation and after Closing. */
  def whileReady(body: => Unit): Unit = synchronized {
    current match {
      case Ready => body
      case _ => ()
    }
  }

}


final class ConnectionKernel private (
  val policy: ConnectionPolicy,
  val dataPlane: DataPlane,
  val revisionRules: RevisionRules,
  val scheduler: RequestScheduler,
  val deadlineScheduler: DeadlineScheduler,
  val registry: RequestRegistry,
  val application: McpApplication,
  val serverInfo: ConnectionKernel.ServerInfo
) {
  require(policy.revision == revisionRules.revision,
    "connection policy and revision rules disagree")
  require(ConnectionPolicy.MaxInFlight.value(policy.admission.maxInFlight) == scheduler.capacity,
    "connection policy and scheduler capacity disagree")

  private val lifecycle0 = new ConnectionLifecycle
  private val terminalLock = new AnyRef
  private var deadlineHandles = Map.empty[RequestId, DeadlineScheduler.Handle]
  private var responseWrites = 0
  private val outputLock = new AnyRef
  private var outputOpen = true
  private val batchLock = new AnyRef
  private var batchSlots = Map.empty[RequestId, BatchSlot]
  private var batchContexts = Set.empty[BatchContext]
  private var batchOutputClosed = false

  private sealed trait CompletionSink {
    def response(value: JSON.Object.T): Unit
    def omit(): Unit
  }

  private object DirectSink extends CompletionSink {
    def response(value: JSON.Object.T): Unit = sendOutbound(JsonRpc.Outbound.Single(value))
    def omit(): Unit = ()
  }

  private object DiscardSink extends CompletionSink {
    def response(value: JSON.Object.T): Unit = ()
    def omit(): Unit = ()
  }

  private final class BatchContext(expected: Int) {
    private var nextSlot = 0
    private var unresolved = (0 until expected).toSet
    private var values = Vector.empty[JSON.Object.T]
    private var finished = false

    def newSlot(): BatchSlot = synchronized {
      require(nextSlot < expected, "batch allocated too many response slots")
      val slot = new BatchSlot(this, nextSlot)
      nextSlot += 1
      slot
    }

    def resolve(slot: Int, value: Option[JSON.Object.T]): Unit = {
      val completed = synchronized {
        if (finished || !unresolved(slot)) None
        else {
          unresolved -= slot
          values ++= value.toList
          if (unresolved.isEmpty) {
            finished = true
            Some(values)
          }
          else None
        }
      }
      completed.foreach(completeBatch(this, _))
    }

    def abort(): Unit = synchronized { finished = true }
  }

  private final class BatchSlot(context: BatchContext, index: Int) extends CompletionSink {
    def response(value: JSON.Object.T): Unit = context.resolve(index, Some(value))
    def omit(): Unit = context.resolve(index, None)
  }

  def phase: ConnectionLifecycle.Phase = lifecycle0.phase

  def receive(): Option[ConnectionKernel.Admission] =
    dataPlane.receive().map(inbound => admit(revisionRules.classify(inbound)))

  /* The composition root uses this as its one single-request path.  Keeping
     admit separate lets deterministic tests inspect lifecycle decisions before
     they deliberately execute an accepted request. */
  def receiveAndExecute(): Option[ConnectionKernel.Admission] =
    dataPlane.receive().map(inbound => handle(revisionRules.classify(inbound)))

  def handle(message: RevisionRules.Message): ConnectionKernel.Admission =
    message match {
      case RevisionRules.Batch(elements) => executeBatch(elements)
      case _ => execute(admit(message))
    }

  def admit(message: RevisionRules.Message): ConnectionKernel.Admission =
    message match {
      case RevisionRules.Cancelled(id, reason) =>
        val prepared = terminalLock.synchronized {
          val result = registry.prepareCancel(id, reason)
          result.result match {
            case result: RequestRegistry.Cancelled =>
              cancelDeadline(id)
              terminalLock.notifyAll()
            case _ => ()
          }
          result
        }
        prepared.deliver()
        val cancelled = prepared.result match {
          case result: RequestRegistry.Cancelled => Some(result)
          case _ => None
        }
        cancelled.foreach(_ =>
          takeBatchSlot(id).foreach(slot => withResponseWrite(slot.omit())))
        ConnectionKernel.Decided(ConnectionLifecycle.Ignored)
      case _ => terminalLock.synchronized {
        responseId(message) match {
          case Some(id) =>
            registry.reserve(id) match {
              case RequestRegistry.Reused(_) =>
                ConnectionKernel.Decided(ConnectionLifecycle.Rejected(
                  RevisionRules.ReplyId(id), RevisionRules.InvalidRequest,
                  "Request id must not be reused during a connection"))
              case reserved: RequestRegistry.Reserved =>
                decideReserved(message, id, reserved.handle)
            }
          case None => ConnectionKernel.Decided(lifecycle0.admit(message))
        }
      }
    }

  def initializeCompleted(): Unit = lifecycle0.initializeCompleted()
  def beginClosing(): Unit = terminalLock.synchronized { lifecycle0.beginClosing() }
  def finishClosing(): Unit = terminalLock.synchronized { lifecycle0.finishClosing() }

  /* Server-initiated notifications stay on the same atomic data-plane write
     path as responses.  A backend can still finish emitting an event after
     stdin has closed, or before capabilities were negotiated, but that event
     no longer belongs to this connection. */
  def listChanged(change: ConnectionKernel.ListChanged): Unit =
    lifecycle0.whileReady {
      val what = change match {
        case ConnectionKernel.ListChanged.Tools => "tools"
        case ConnectionKernel.ListChanged.Resources => "resources"
      }
      sendOutbound(JsonRpc.Outbound.Single(JSON.Object(
        "jsonrpc" -> "2.0", "method" -> ("notifications/" + what + "/list_changed"))))
    }

  /* Immediate failure close: no waiting is allowed from an output-failure or
     invariant callback.  Normal EOF uses drainAndClose below. */
  def close(): Unit = {
    beginClosing()
    val shutdown = terminalLock.synchronized {
      val prepared = registry.prepareShutdown()
      cancelAllDeadlines()
      terminalLock.notifyAll()
      prepared
    }
    shutdown.deliver()
    closeOutput()
    abortBatches()
    scheduler.shutdown()
    deadlineScheduler.shutdown()
  }

  /* One configured shutdown deadline covers both admitted work and a response
     that has won terminal ownership but is still inside DataPlane.send. */
  def drainAndClose(): ConnectionKernel.DrainResult = {
    beginClosing()
    val seconds = ConnectionPolicy.ShutdownDrain.seconds(policy.timing.shutdownDrain)
    val deadline = System.nanoTime() + Math.ceil(seconds * 1000000000.0).toLong
    val (result, shutdown) = terminalLock.synchronized {
      def complete: Boolean = registry.snapshot.activeIds.isEmpty && responseWrites == 0
      var remaining = deadline - System.nanoTime()
      while (!complete && remaining > 0L) {
        val millis = remaining / 1000000L
        val nanos = (remaining % 1000000L).toInt
        terminalLock.wait(millis, nanos)
        remaining = deadline - System.nanoTime()
      }
      val drained = complete
      val prepared =
        if (drained) None
        else Some(registry.prepareShutdown())
      cancelAllDeadlines()
      terminalLock.notifyAll()
      (ConnectionKernel.DrainResult(drained, prepared.toList.flatMap(_.result)), prepared)
    }
    shutdown.foreach(_.deliver())
    closeOutput()
    abortBatches()
    scheduler.shutdown()
    deadlineScheduler.shutdown()
    lifecycle0.finishClosing()
    result
  }

  def execute(admission: ConnectionKernel.Admission): ConnectionKernel.Admission =
    execute(admission, DirectSink)

  private def execute(
    admission: ConnectionKernel.Admission,
    sink: CompletionSink
  ): ConnectionKernel.Admission = {
    admission match {
      case accepted @ ConnectionKernel.Accepted(ConnectionLifecycle.InitializeAccepted(_, _), request, _) =>
        bindBatchSlot(request.id, sink)
        completeControl(request, successResponse(request.id,
          JSON.Object(
            "protocolVersion" -> policy.revision.value,
            "capabilities" -> JSON.Object(
              "tools" -> JSON.Object("listChanged" -> true),
              "resources" -> JSON.Object("listChanged" -> true)),
            "serverInfo" -> JSON.Object("name" -> serverInfo.name, "version" -> serverInfo.version))), sink)
        lifecycle0.initializeCompleted()
        accepted

      case accepted @ ConnectionKernel.Accepted(ConnectionLifecycle.PingAccepted(_), request, _) =>
        bindBatchSlot(request.id, sink)
        completeControl(request, successResponse(request.id, JSON.Object()), sink)
        accepted

      case accepted @ ConnectionKernel.Accepted(
          ConnectionLifecycle.OperationAccepted(RevisionRules.Application(operation, _)), request, Some(permit)) =>
        bindBatchSlot(request.id, sink)
        if (request.cancellation.isCancelled) {
          scheduler.abandon(permit)
          accepted
        }
        else {
          scheduleDeadline(request)
          scheduler.start(permit, () => executeWorker(request, operation, sink)) match {
            case RequestScheduler.Started => accepted
            case RequestScheduler.StartRejected =>
              val shutdown = terminalLock.synchronized {
                cancelDeadline(request.id)
                val prepared =
                  if (registry.snapshot.activeIds.contains(request.id))
                    Some(registry.prepareShutdown(request.token))
                  else None
                terminalLock.notifyAll()
                prepared
              }
              shutdown.foreach(_.deliver())
              error("reserved scheduler permit could not start")
          }
        }

      case ConnectionKernel.Decided(ConnectionLifecycle.Overloaded(id)) =>
        sink.response(overloadResponse(id))
        admission

      case ConnectionKernel.Decided(rejected: ConnectionLifecycle.Rejected) =>
        reply(rejected.reply, rejected.code, rejected.message, sink)
        admission

      case _ => admission
    }
  }

  private def responseId(message: RevisionRules.Message): Option[RequestId] =
    message match {
      case RevisionRules.Initialize(id, _) => Some(id)
      case RevisionRules.Ping(id) => Some(id)
      case RevisionRules.Application(_, id) => Some(id)
      case RevisionRules.Invalid(RevisionRules.ReplyId(id), _, _) => Some(id)
      case _ => None
    }

  private def decideReserved(
    message: RevisionRules.Message,
    id: RequestId,
    reservation: Reservation
  ): ConnectionKernel.Admission = {
    val decision = lifecycle0.admit(message)
    admissionKind(decision) match {
      case None =>
        registry.abandon(reservation)
        ConnectionKernel.Decided(decision)
      case Some((acceptedId, RequestRegistry.AdmissionKind.Ordinary)) =>
        require(acceptedId == id, "lifecycle changed the reserved request id")
        scheduler.tryReserve() match {
          case RequestScheduler.Rejected =>
            registry.abandon(reservation)
            ConnectionKernel.Decided(ConnectionLifecycle.Overloaded(id))
          case RequestScheduler.Reserved(permit) =>
            registry.admitReserved(reservation, RequestRegistry.AdmissionKind.Ordinary) match {
              case request: RequestRegistry.Admitted => ConnectionKernel.Accepted(decision, request, Some(permit))
              case RequestRegistry.Broken(_) =>
                scheduler.abandon(permit)
                ConnectionKernel.Decided(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(id), -32000,
                  "Connection is broken"))
              case RequestRegistry.DuplicateId(_) =>
                scheduler.abandon(permit)
                error("request registry disagrees about request-id reuse")
            }
        }

      case Some((acceptedId, kind)) =>
        require(acceptedId == id, "lifecycle changed the reserved request id")
        registry.admitReserved(reservation, kind) match {
          case request: RequestRegistry.Admitted => ConnectionKernel.Accepted(decision, request, None)
          case RequestRegistry.Broken(_) =>
            ConnectionKernel.Decided(ConnectionLifecycle.Rejected(RevisionRules.ReplyId(id), -32000,
              "Connection is broken"))
          case RequestRegistry.DuplicateId(_) =>
            error("request registry disagrees about request-id reuse")
        }
    }
  }

  private def admissionKind(
    decision: ConnectionLifecycle.Decision
  ): Option[(RequestId, RequestRegistry.AdmissionKind)] =
    decision match {
      case ConnectionLifecycle.InitializeAccepted(id, _) =>
        Some((id, RequestRegistry.AdmissionKind.Initialize))
      case ConnectionLifecycle.PingAccepted(id) =>
        Some((id, RequestRegistry.AdmissionKind.Ping))
      case ConnectionLifecycle.OperationAccepted(RevisionRules.Application(_, id)) =>
        Some((id, RequestRegistry.AdmissionKind.Ordinary))
      case _ => None
    }

  private def executeWorker(
    request: RequestRegistry.Admitted,
    operation: McpApplication.Operation,
    sink: CompletionSink
  ): Unit = {
    val (disposition, response) =
      try {
        application.execute(operation, request.cancellation) match {
          case McpApplication.Outcome.Result(value) =>
            (RequestRegistry.WorkerDisposition.Success, successResponse(request.id, value))
          case McpApplication.Outcome.InvalidParams(message) =>
            (RequestRegistry.WorkerDisposition.ApplicationError,
              errorResponse(request.id, RevisionRules.InvalidParams, message))
        }
      }
      catch {
        case NonFatal(exn) if !Exn.is_interrupt(exn) =>
          (RequestRegistry.WorkerDisposition.Exception,
            errorResponse(request.id, ConnectionKernel.InternalError, "Internal error"))
      }

    completeTerminal(request, disposition) match {
      case RequestRegistry.Completed(_) =>
        try completionSink(request.id, sink).response(response)
        catch { case NonFatal(exn) if !Exn.is_interrupt(exn) => () }
        finally finishResponseWrite()
      case _: RequestRegistry.LateIgnored => ()
      case _: RequestRegistry.InvariantViolation => ()
      case _ => error("worker completion did not resolve to a terminal result")
    }
  }

  private def completeControl(
    request: RequestRegistry.Admitted,
    response: JSON.Object.T,
    sink: CompletionSink
  ): Unit =
    completeTerminal(request, RequestRegistry.WorkerDisposition.Success) match {
      case RequestRegistry.Completed(_) =>
        try completionSink(request.id, sink).response(response)
        finally finishResponseWrite()
      case _: RequestRegistry.InvariantViolation => ()
      case _ => error("control completion did not resolve to a terminal result")
    }

  private def reply(
    target: RevisionRules.ReplyTarget,
    code: Int,
    message: String,
    sink: CompletionSink
  ): Unit =
    target match {
      case RevisionRules.ReplyId(id) => sink.response(errorResponse(id, code, message))
      case RevisionRules.NullReply => sink.response(RevisionRules.error(null, code, message))
      case RevisionRules.NoReply => ()
    }

  private def executeBatch(elements: List[JSON.T]): ConnectionKernel.Admission = {
    /* RevisionRules admits only non-empty batches without initialize.  Classify
       and allocate every response slot before an accepted worker can start:
       a fast first element must never flush a partial array. */
    val messages = elements.map(classifyBatchElement)
    val responseCount = messages.count(responseRequired)
    val sinks =
      if (responseCount == 0) messages.map(_ => DiscardSink: CompletionSink)
      else {
        val context = new BatchContext(responseCount)
        batchLock.synchronized { batchContexts += context }
        messages.map(message =>
          if (responseRequired(message)) context.newSlot(): CompletionSink else DiscardSink)
      }
    messages.lazyZip(sinks).foreach { (message, sink) => execute(admit(message), sink) }
    ConnectionKernel.Decided(ConnectionLifecycle.Ignored)
  }

  private def classifyBatchElement(value: JSON.T): RevisionRules.Message =
    revisionRules.classify(JsonRpc.Inbound.Decoded(JsonRpc.Envelope.Single(value)))

  private def responseRequired(message: RevisionRules.Message): Boolean =
    responseId(message).isDefined ||
      (message match {
        case RevisionRules.Invalid(RevisionRules.NullReply, _, _) => true
        case _ => false
      })

  private def bindBatchSlot(id: RequestId, sink: CompletionSink): Unit =
    sink match {
      case slot: BatchSlot => batchLock.synchronized { batchSlots += id -> slot }
      case _ => ()
    }

  private def takeBatchSlot(id: RequestId): Option[BatchSlot] =
    batchLock.synchronized {
      val slot = batchSlots.get(id)
      batchSlots -= id
      slot
    }

  private def completionSink(id: RequestId, fallback: CompletionSink): CompletionSink =
    takeBatchSlot(id).getOrElse(fallback)

  private def completeBatch(context: BatchContext, values: Vector[JSON.Object.T]): Unit = {
    val outbound = batchLock.synchronized {
      if (!batchOutputClosed && batchContexts(context)) {
        batchContexts -= context
        if (values.nonEmpty) Some(JsonRpc.Outbound.Batch(values.toList)) else None
      }
      else None
    }
    outbound.foreach(sendOutbound)
  }

  private def abortBatches(): Unit = batchLock.synchronized {
    batchOutputClosed = true
    batchContexts.foreach(_.abort())
    batchContexts = Set.empty
    batchSlots = Map.empty
  }

  private def sendOutbound(outbound: JsonRpc.Outbound): Unit = {
    val failure = outputLock.synchronized {
      if (!outputOpen) None
      else {
        try { dataPlane.send(outbound); None }
        catch {
          case exn: Throwable =>
            outputOpen = false
            Some(exn)
        }
      }
    }
    failure.foreach { exn => close(); throw exn }
  }

  private def closeOutput(): Unit = outputLock.synchronized { outputOpen = false }

  private def scheduleDeadline(request: RequestRegistry.Admitted): Unit =
    terminalLock.synchronized {
      if (registry.snapshot.activeIds.contains(request.id)) {
        val seconds = ConnectionPolicy.RequestTimeout.seconds(policy.timing.requestTimeout)
        val handle = deadlineScheduler.schedule(seconds, () => timeout(request))
        deadlineHandles += request.id -> handle
      }
    }

  private def cancelDeadline(id: RequestId): Unit = {
    deadlineHandles.get(id).foreach(_.cancel())
    deadlineHandles -= id
  }

  private def cancelAllDeadlines(): Unit = {
    deadlineHandles.values.foreach(_.cancel())
    deadlineHandles = Map.empty
  }

  private def timeout(request: RequestRegistry.Admitted): Unit = {
    val prepared = terminalLock.synchronized {
      deadlineHandles -= request.id
      val result = registry.prepareTimeout(request.token)
      result.result match {
        case _: RequestRegistry.Completed => responseWrites += 1
        case _ => ()
      }
      terminalLock.notifyAll()
      result
    }
    prepared.deliver()
    val result = prepared.result
    result match {
      case _: RequestRegistry.Completed =>
        try completionSink(request.id, DirectSink).response(timeoutResponse(request.id))
        finally finishResponseWrite()
      case _: RequestRegistry.TimerIgnored => ()
      case _: RequestRegistry.InvariantViolation => ()
      case _ => error("request timeout did not resolve to a terminal result")
    }
  }

  private def completeTerminal(
    request: RequestRegistry.Admitted,
    disposition: RequestRegistry.WorkerDisposition
  ): RequestRegistry.Completion =
    terminalLock.synchronized {
      val completed = registry.complete(request.token, disposition)
      cancelDeadline(request.id)
      completed match {
        case _: RequestRegistry.Completed => responseWrites += 1
        case _ => ()
      }
      terminalLock.notifyAll()
      completed
    }

  private def withResponseWrite(body: => Unit): Unit = {
    terminalLock.synchronized { responseWrites += 1 }
    try body
    finally finishResponseWrite()
  }

  private def finishResponseWrite(): Unit = terminalLock.synchronized {
    responseWrites -= 1
    require(responseWrites >= 0, "response-write barrier underflow")
    terminalLock.notifyAll()
  }

  private def successResponse(id: RequestId, result: JSON.T): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.json, "result" -> result)

  private def errorResponse(id: RequestId, code: Int, message: String): JSON.Object.T =
    RevisionRules.error(id.json, code, message)

  private def overloadResponse(id: RequestId): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.json,
      "error" -> JSON.Object(
        "code" -> ConnectionKernel.Overloaded,
        "message" -> "Overloaded",
        "data" -> JSON.Object(
          "reason" -> "maxInFlight",
          "maxInFlight" -> ConnectionPolicy.MaxInFlight.value(policy.admission.maxInFlight))))

  private def timeoutResponse(id: RequestId): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.json,
      "error" -> JSON.Object(
        "code" -> ConnectionKernel.RequestTimedOut,
        "message" -> "Request timed out",
        "data" -> JSON.Object(
          "reason" -> "requestTimeout",
          "seconds" -> ConnectionPolicy.RequestTimeout.seconds(policy.timing.requestTimeout))))
}


object ConnectionKernel {
  val Overloaded = -32001
  val RequestTimedOut = -32002
  val InternalError = -32603
  final case class ServerInfo(name: String, version: String)
  final case class DrainResult(drained: Boolean, cancelled: List[RequestRegistry.Tombstone])

  sealed trait ListChanged
  object ListChanged {
    case object Tools extends ListChanged
    case object Resources extends ListChanged

    def fromBackend(value: String): Option[ListChanged] =
      value match {
        case "tools" => Some(Tools)
        case "resources" => Some(Resources)
        case _ => None
      }
  }

  sealed trait Admission {
    def decision: ConnectionLifecycle.Decision
  }

  final case class Accepted(
    decision: ConnectionLifecycle.Decision,
    request: RequestRegistry.Admitted,
    private[connection] val permit: Option[RequestScheduler.Permit]
  ) extends Admission

  final case class Decided(decision: ConnectionLifecycle.Decision) extends Admission

  def apply(
    policy: ConnectionPolicy,
    dataPlane: DataPlane,
    revisionRules: RevisionRules,
    scheduler: RequestScheduler,
    registry: RequestRegistry,
    application: McpApplication,
    serverInfo: ServerInfo,
    deadlineScheduler: DeadlineScheduler
  ): ConnectionKernel =
    new ConnectionKernel(policy, dataPlane, revisionRules, scheduler, deadlineScheduler,
      registry, application, serverInfo)
}
