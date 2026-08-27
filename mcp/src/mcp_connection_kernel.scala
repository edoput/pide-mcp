/*  Title:      mcp/src/mcp_connection_kernel.scala

Checkpoint-6 connection control path: revision classification, lifecycle,
request ownership, bounded scheduling, and single-request wire completion over
a DataPlane. Batch aggregation and EOF draining remain later checkpoints.
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
        RevisionRules.InvalidRequest, "Batch aggregation is not available yet")
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
  val registry: RequestRegistry,
  val application: McpApplication,
  val serverInfo: ConnectionKernel.ServerInfo
) {
  require(policy.revision == revisionRules.revision,
    "connection policy and revision rules disagree")
  require(ConnectionPolicy.MaxInFlight.value(policy.admission.maxInFlight) == scheduler.capacity,
    "connection policy and scheduler capacity disagree")

  private val lifecycle0 = new ConnectionLifecycle

  def phase: ConnectionLifecycle.Phase = lifecycle0.phase

  def receive(): Option[ConnectionKernel.Admission] =
    dataPlane.receive().map(inbound => admit(revisionRules.classify(inbound)))

  /* The composition root uses this as its one single-request path.  Keeping
     admit separate lets deterministic tests inspect lifecycle decisions before
     they deliberately execute an accepted request. */
  def receiveAndExecute(): Option[ConnectionKernel.Admission] =
    receive().map(execute)

  def handle(message: RevisionRules.Message): ConnectionKernel.Admission =
    execute(admit(message))

  def admit(message: RevisionRules.Message): ConnectionKernel.Admission =
    message match {
      case RevisionRules.Cancelled(id, reason) =>
        registry.cancel(id, reason)
        ConnectionKernel.Decided(ConnectionLifecycle.Ignored)
      case _ =>
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

  def initializeCompleted(): Unit = lifecycle0.initializeCompleted()
  def beginClosing(): Unit = lifecycle0.beginClosing()
  def finishClosing(): Unit = lifecycle0.finishClosing()

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
      send(JSON.Object(
        "jsonrpc" -> "2.0", "method" -> ("notifications/" + what + "/list_changed")))
    }

  /* This is deliberately a begin-close operation, not the final EOF drain
     protocol.  EOF calls it after the temporary policy-bounded drain;
     invariant and output failures call it immediately. */
  def close(): Unit = {
    lifecycle0.beginClosing()
    registry.shutdown()
    scheduler.shutdown()
  }

  def execute(admission: ConnectionKernel.Admission): ConnectionKernel.Admission = {
    admission match {
      case accepted @ ConnectionKernel.Accepted(ConnectionLifecycle.InitializeAccepted(_, _), request, _) =>
        completeControl(request, successResponse(request.id,
          JSON.Object(
            "protocolVersion" -> policy.revision.value,
            "capabilities" -> JSON.Object(
              "tools" -> JSON.Object("listChanged" -> true),
              "resources" -> JSON.Object("listChanged" -> true)),
            "serverInfo" -> JSON.Object("name" -> serverInfo.name, "version" -> serverInfo.version))))
        lifecycle0.initializeCompleted()
        accepted

      case accepted @ ConnectionKernel.Accepted(ConnectionLifecycle.PingAccepted(_), request, _) =>
        completeControl(request, successResponse(request.id, JSON.Object()))
        accepted

      case accepted @ ConnectionKernel.Accepted(
          ConnectionLifecycle.OperationAccepted(RevisionRules.Application(operation, _)), request, Some(permit)) =>
        if (request.cancellation.isCancelled) {
          scheduler.abandon(permit)
          accepted
        }
        else scheduler.start(permit, () => executeWorker(request, operation)) match {
          case RequestScheduler.Started => accepted
          case RequestScheduler.StartRejected =>
            registry.shutdown(request.token)
            error("reserved scheduler permit could not start")
        }

      case ConnectionKernel.Decided(ConnectionLifecycle.Overloaded(id)) =>
        send(overloadResponse(id))
        admission

      case ConnectionKernel.Decided(rejected: ConnectionLifecycle.Rejected) =>
        reply(rejected.reply, rejected.code, rejected.message)
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
    operation: McpApplication.Operation
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

    registry.complete(request.token, disposition) match {
      case RequestRegistry.Completed(_) => send(response)
      case _: RequestRegistry.LateIgnored => ()
      case _: RequestRegistry.InvariantViolation => ()
      case _ => error("worker completion did not resolve to a terminal result")
    }
  }

  private def completeControl(request: RequestRegistry.Admitted, response: JSON.Object.T): Unit =
    registry.complete(request.token, RequestRegistry.WorkerDisposition.Success) match {
      case RequestRegistry.Completed(_) => send(response)
      case _: RequestRegistry.InvariantViolation => ()
      case _ => error("control completion did not resolve to a terminal result")
    }

  private def reply(target: RevisionRules.ReplyTarget, code: Int, message: String): Unit =
    target match {
      case RevisionRules.ReplyId(id) => send(errorResponse(id, code, message))
      case RevisionRules.NullReply => send(RevisionRules.error(null, code, message))
      case RevisionRules.NoReply => ()
    }

  private def send(response: JSON.Object.T): Unit =
    try dataPlane.send(JsonRpc.Outbound.Single(response))
    catch {
      case exn: Throwable =>
        lifecycle0.beginClosing()
        registry.shutdown()
        scheduler.shutdown()
        throw exn
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
}


object ConnectionKernel {
  val Overloaded = -32001
  val InternalError = -32603
  final case class ServerInfo(name: String, version: String)

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
    serverInfo: ServerInfo
  ): ConnectionKernel =
    new ConnectionKernel(policy, dataPlane, revisionRules, scheduler, registry, application, serverInfo)
}
