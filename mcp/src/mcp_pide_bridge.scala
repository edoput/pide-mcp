/*  Title:      mcp/src/mcp_pide_bridge.scala

Typed control/data-plane boundary for Scala-to-Isabelle/ML calls.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Future, Headless, Markup, Output, Promise, Properties, Prover, Session, XML}
import isabelle.mcp.McpBridgeProfile
import isabelle.mcp.control.{DeadlineScheduler, NonNegativeDuration => ControlNonNegativeDuration,
  PositiveDuration => ControlPositiveDuration}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal


private[pide] opaque type BridgeCallId = String


private[pide] object BridgeCallId {
  def value(id: BridgeCallId): String = id

  private[pide] def generated(value: String): BridgeCallId = value
  private[mcp] def test(value: String): BridgeCallId = value
}


/** One generator per bridge: an unguessable instance prefix plus a monotonic
  * counter gives non-repeating string IDs without retaining an unbounded set.
  */
private[pide] final class BridgeCallIdGenerator private (prefix: String) {
  private val nextValue = new AtomicLong(0L)

  def next(): BridgeCallId = {
    val value = nextValue.getAndIncrement()
    if (value < 0L) throw new IllegalStateException("bridge call id space exhausted")
    BridgeCallId.generated(prefix + ":" + value)
  }
}


private[pide] object BridgeCallIdGenerator {
  def random(): BridgeCallIdGenerator =
    new BridgeCallIdGenerator(UUID.randomUUID().toString)

  private[mcp] def test(prefix: String): BridgeCallIdGenerator =
    new BridgeCallIdGenerator(prefix)
}


sealed trait BridgeFailure {
  def message: String
}


object BridgeFailure {
  case object Cancelled extends BridgeFailure {
    val message = "bridge call cancelled"
  }

  final case class TimedOut(delay: FiniteDuration) extends BridgeFailure {
    val message = "bridge call timed out after " +
      (delay.toNanos.toDouble / 1000000000.0) + " seconds"
  }

  case object SessionStopped extends BridgeFailure {
    val message = "Isabelle session stopped"
  }

  final case class Overloaded(maxPending: Int) extends BridgeFailure {
    val message = "bridge has reached its " + maxPending + " pending-call limit"
  }

  enum Direction {
    case Request, Reply
  }

  final case class TooLarge(direction: Direction, actualBytes: Long, limitBytes: Long)
      extends BridgeFailure {
    val message =
      direction.toString.toLowerCase + " bridge envelope has " + actualBytes +
        " bytes; limit is " + limitBytes
  }

  final case class TransportFailed(detail: String) extends BridgeFailure {
    val message = "bridge transport failed: " + detail
  }

  final case class RemoteError(detail: String) extends BridgeFailure {
    val message = "bridge executor failed: " + detail
  }

  final case class ProtocolError(detail: String) extends BridgeFailure {
    val message = "bridge protocol error: " + detail
  }
}


type BridgeResult[+A] = Either[BridgeFailure, A]


sealed trait BridgeDrainOutcome


object BridgeDrainOutcome {
  case object Acknowledged extends BridgeDrainOutcome
  case object SessionTerminated extends BridgeDrainOutcome
  final case class Failed(failure: BridgeFailure) extends BridgeDrainOutcome
}


final case class PideBridgePolicy(
  maxPending: PideBridgePolicy.MaxPending,
  timing: PideBridgePolicy.Timing,
  envelopes: PideBridgePolicy.Envelopes
)


object PideBridgePolicy {
  opaque type MaxPending = Int
  type PositiveDuration = ControlPositiveDuration
  type NonNegativeDuration = ControlNonNegativeDuration
  opaque type PositiveBytes = Long

  object MaxPending {
    def checked(value: Int): Either[String, MaxPending] =
      if (value > 0) Right(value) else Left("maxPending must be positive")

    def value(value: MaxPending): Int = value
  }

  val PositiveDuration: ControlPositiveDuration.type = ControlPositiveDuration
  val NonNegativeDuration: ControlNonNegativeDuration.type = ControlNonNegativeDuration

  object PositiveBytes {
    def checked(field: String, value: Long): Either[String, PositiveBytes] =
      if (value > 0L) Right(value) else Left(field + " must be positive")

    def value(value: PositiveBytes): Long = value
  }

  final case class Timing(
    callTimeout: PositiveDuration,
    drainTimeout: NonNegativeDuration
  )

  final case class Envelopes(
    maxRequestBytes: PositiveBytes,
    maxReplyBytes: PositiveBytes
  )

  def checked(
    maxPending: Int,
    callTimeoutSeconds: Double,
    drainTimeoutSeconds: Double,
    maxRequestBytes: Long,
    maxReplyBytes: Long
  ): Either[List[String], PideBridgePolicy] = {
    val pending = MaxPending.checked(maxPending)
    val call = PositiveDuration.checked("callTimeout", callTimeoutSeconds)
    val drain = NonNegativeDuration.checked("drainTimeout", drainTimeoutSeconds)
    val request = PositiveBytes.checked("maxRequestBytes", maxRequestBytes)
    val reply = PositiveBytes.checked("maxReplyBytes", maxReplyBytes)
    val errors = List(pending, call, drain, request, reply).collect { case Left(error) => error }

    if (errors.nonEmpty) Left(errors)
    else
      Right(PideBridgePolicy(
        maxPending = pending.toOption.get,
        timing = Timing(call.toOption.get, drain.toOption.get),
        envelopes = Envelopes(request.toOption.get, reply.toOption.get)))
  }
}


/** One typed call description.  The operation value contains its encoded
  * request, so callers cannot pair a payload with the wrong result decoder.
  */
trait BridgeOperation[A] {
  def name: String
  def requestPayload: XML.Body
  def decodeReply(payload: XML.Body): Either[String, A]
}


/** Replaceable byte delivery only. send enqueues the command or fails; it
  * never waits for remote execution, though an implementation may deliver a
  * reply synchronously/reentrantly. The bridge owns call state and codecs;
  * the composition root retains ownership of Headless.Session.stop.
  */
trait PideTransport {
  def start(receive: PideTransport.Inbound => Unit,
    terminated: PideTransport.Termination => Unit): Unit
  def send(outbound: PideTransport.Outbound): Unit
  def close(): Unit
}


object PideTransport {
  final case class Outbound(command: String, arguments: List[Bytes])
  final case class Inbound(
    function: String,
    properties: Properties.T,
    body: Bytes,
    text: String
  ) {
    def property(name: String): Option[String] = Properties.get(properties, name)
  }

  sealed trait Termination
  case object Closed extends Termination
  final case class Failed(detail: String) extends Termination
}


private[mcp] object SessionPideTransport {
  def unknownResultFunction(
    resultFunctions: Set[String],
    properties: Properties.T
  ): Option[String] =
    for {
      function <- Properties.get(properties, Markup.FUNCTION)
      if function.startsWith("MCP.")
      if !resultFunctions.contains(function)
      if Properties.get(properties, "id").isDefined
    } yield function
}


/** Production data plane for a known set of protocol-result functions. */
private[mcp] final class SessionPideTransport(
  session: Headless.Session,
  resultFunctions: Set[String]
) extends PideTransport {
  import PideTransport._

  private var receiver: Option[Inbound => Unit] = None
  private var termination: Option[Termination => Unit] = None
  private var closed = false
  private var started = false

  private def deliver(function: String, message: Prover.Protocol_Output): Unit = {
    val target = synchronized {
      if (closed) None else receiver
    }
    target.foreach(_(Inbound(function, message.properties, message.chunk, message.text)))
  }

  private object Handler extends Session.Protocol_Handler {
    private def handle(function: String)(message: Prover.Protocol_Output): Boolean = {
      deliver(function, message)
      true
    }

    override val functions: Session.Protocol_Functions =
      resultFunctions.toList.sorted.map(function => function -> handle(function))
  }

  /* Protocol handlers are exact-name maps.  Observe only unknown correlated
     MCP outputs here; known results stay on Handler and unrelated PIDE traffic
     never enters the bridge. */
  private val unknownResults =
    Session.Consumer[Prover.Message]("MCP PIDE bridge unknown results") {
      case message: Prover.Protocol_Output =>
        SessionPideTransport.unknownResultFunction(resultFunctions, message.properties)
          .foreach(deliver(_, message))
      case _ => ()
    }

  private def detachUnknownResults(): Unit =
    session.all_messages -= unknownResults

  private def attachUnknownResults(): Unit =
    session.all_messages += unknownResults

  private def notifyClosed(): Option[Termination => Unit] = synchronized {
    if (closed) None
    else {
      closed = true
      receiver = None
      val result = termination
      termination = None
      result
    }
  }

  def start(receive: Inbound => Unit, terminated: Termination => Unit): Unit = synchronized {
    if (started) throw new IllegalStateException("PIDE transport already started")
    if (closed) throw new IllegalStateException("PIDE transport is closed")
    receiver = Some(receive)
    termination = Some(terminated)
    started = true
    session.init_protocol_handler(Handler)
    attachUnknownResults()
  }

  def send(outbound: Outbound): Unit = synchronized {
    if (!started) throw new IllegalStateException("PIDE transport is not started")
    if (closed) throw new IllegalStateException("PIDE transport is closed")
    /* protocol_command_raw posts work to the Headless session manager: this
       is enqueue-or-fail, not a wait for ML execution or its reply. */
    session.protocol_command_raw(outbound.command, outbound.arguments)
  }

  def close(): Unit = {
    val notify = notifyClosed()
    if (notify.isDefined) detachUnknownResults()
    notify.foreach(_(Closed))
  }
}


private[pide] object PendingRegistry {
  sealed trait Diagnostic {
    def id: BridgeCallId
    def operation: String
  }

  final case class UnownedReply(id: BridgeCallId, operation: String) extends Diagnostic

  trait Diagnostics {
    def report(diagnostic: Diagnostic): Unit
  }

  object Diagnostics {
    object Ignore extends Diagnostics {
      def report(diagnostic: Diagnostic): Unit = ()
    }
  }

  sealed trait Completion
  case object Completed extends Completion
  case object Rejected extends Completion
  case object Unowned extends Completion

  final class Call[A] private[pide] (
    val id: BridgeCallId,
    private[pide] val promise: Promise[BridgeResult[A]]
  ) {
    def result: BridgeResult[A] = promise.join
  }
}


/** Heterogeneous ownership table: every entry retains its own result decoder
  * and typed promise.  No cast is needed when an untyped protocol reply wins.
  */
private[pide] final class PendingRegistry(
  maxPending: PideBridgePolicy.MaxPending,
  diagnostics: PendingRegistry.Diagnostics = PendingRegistry.Diagnostics.Ignore
) {
  import BridgeFailure.{Overloaded, ProtocolError}
  import PendingRegistry._

  private sealed trait Entry {
    def operation: String
    def complete(payload: XML.Body): Boolean
    def fail(failure: BridgeFailure): Unit
  }

  private final class TypedEntry[A](
    descriptor: BridgeOperation[A],
    promise: Promise[BridgeResult[A]]
  ) extends Entry {
    val operation: String = descriptor.name

    def complete(payload: XML.Body): Boolean =
      try {
        descriptor.decodeReply(payload) match {
          case Right(value) => promise.fulfill(Right(value)); true
          case Left(error) => promise.fulfill(Left(ProtocolError(error))); false
        }
      }
      catch {
        case NonFatal(exn) =>
          promise.fulfill(Left(ProtocolError(
            "reply decoder for " + descriptor.name + " failed: " +
              Option(exn.getMessage).getOrElse(exn.getClass.getName))))
          false
      }

    def fail(failure: BridgeFailure): Unit = promise.fulfill(Left(failure))
  }

  private var pending = Map.empty[BridgeCallId, Entry]

  def register[A](id: BridgeCallId, operation: BridgeOperation[A])
      : Either[BridgeFailure, Call[A]] = synchronized {
    if (pending.contains(id))
      Left(ProtocolError("duplicate bridge call id " + BridgeCallId.value(id)))
    else if (pending.size >= PideBridgePolicy.MaxPending.value(maxPending))
      Left(Overloaded(PideBridgePolicy.MaxPending.value(maxPending)))
    else {
      val promise = Future.promise[BridgeResult[A]]
      pending += id -> new TypedEntry(operation, promise)
      Right(new Call(id, promise))
    }
  }

  private def remove(id: BridgeCallId): Option[Entry] = synchronized {
    val result = pending.get(id)
    pending -= id
    result
  }

  def complete(id: BridgeCallId, operation: String,
    payload: XML.Body): Completion = {
    val owner = remove(id)
    owner match {
      case Some(entry) if entry.operation == operation =>
        if (entry.complete(payload)) Completed else Rejected
      case Some(entry) =>
        entry.fail(ProtocolError(
          "reply operation " + operation + " does not match pending " + entry.operation))
        Rejected
      case None =>
        diagnostics.report(UnownedReply(id, operation))
        Unowned
    }
  }

  def reject(id: BridgeCallId, operation: String,
    failure: BridgeFailure): Completion =
    remove(id) match {
      case Some(entry) if entry.operation == operation =>
        entry.fail(failure)
        Completed
      case Some(entry) =>
        entry.fail(ProtocolError(
          "reply operation " + operation + " does not match pending " + entry.operation))
        Rejected
      case None =>
        diagnostics.report(UnownedReply(id, operation))
        Unowned
    }

  def fail(id: BridgeCallId, failure: BridgeFailure): Boolean = {
    val owner = synchronized {
      val result = pending.get(id)
      pending -= id
      result
    }
    owner.foreach(_.fail(failure))
    owner.isDefined
  }

  def drain(failure: BridgeFailure): Int = {
    val owners = synchronized {
      val result = pending.values.toList
      pending = Map.empty
      result
    }
    owners.foreach(_.fail(failure))
    owners.length
  }

  def size: Int = synchronized { pending.size }

  def contains(id: BridgeCallId): Boolean = synchronized { pending.contains(id) }
}


/** Cancellation is a lower-level port than the MCP application. */
trait BridgeCancellation {
  def isCancelled: Boolean
  def onCancel(callback: () => Unit): Unit
}


/** One terminal-race owner over typed operations, the versioned protocol,
  * and replaceable byte delivery.
  */
private[mcp] final class PideBridge(
  transport: PideTransport,
  maxPending: PideBridgePolicy.MaxPending,
  callTimeout: PideBridgePolicy.PositiveDuration,
  drainTimeout: PideBridgePolicy.NonNegativeDuration,
  deadlineScheduler: DeadlineScheduler,
  theory: String,
  operationNames: Set[String],
  bridgeProfile: McpBridgeProfile,
  protocol: PideBridgeProtocol,
  diagnostics: String => Unit = message => Output.error_message(message)
) {
  import BridgeFailure._
  import BridgeDrainOutcome._
  import PendingRegistry._

  private enum State { case Starting, Open, Stopping, Stopped }
  private final case class Hello(id: BridgeCallId, var result: Option[BridgeResult[Unit]])
  private final class Drain(
    val id: BridgeCallId,
    val startedAtNanos: Long,
    var result: Option[BridgeDrainOutcome]
  )

  private val ids = BridgeCallIdGenerator.random()
  private val pending = new PendingRegistry(maxPending, new Diagnostics {
    def report(diagnostic: Diagnostic): Unit =
      diagnostics("Unowned PIDE bridge reply for " + diagnostic.operation +
        " id " + BridgeCallId.value(diagnostic.id))
  })
  private var state: State = State.Starting
  private var sent = Set.empty[BridgeCallId]
  /* The bridge owns its scheduler.  Each ordinary pending call owns exactly
    * one removable deadline entry; hello retains its independent startup wait. */
  private var deadlines = Map.empty[BridgeCallId, DeadlineScheduler.Handle]
  private var hello: Option[Hello] = None
  private var drain: Option[Drain] = None
  private var stopOutcome: Option[BridgeDrainOutcome] = None
  private var transportAlive = true
  private var advertisedOperations = Set.empty[String]

  transport.start(receive, transportTerminated)

  /** The single-flight startup control-plane exchange. It deliberately does
    * not consume ordinary pending-call capacity or use a public operation
    * name; a concurrent second waiter is rejected without replacing its owner.
    */
  def awaitReady(timeout: PideBridgePolicy.PositiveDuration): BridgeResult[Unit] = {
    val timeoutNanos = PideBridgePolicy.PositiveDuration.duration(timeout).toNanos
    val waiting = synchronized {
      if (state == State.Open) return Right(())
      if (state != State.Starting) return Left(SessionStopped)
      if (hello.isDefined)
        return Left(ProtocolError("PIDE bridge startup hello is already in flight"))
      val entry = Hello(ids.next(), None)
      hello = Some(entry)
      try transport.send(protocol.hello(BridgeCallId.value(entry.id), theory))
      catch {
        case NonFatal(exn) =>
          entry.result = Some(Left(TransportFailed(
            Option(exn.getMessage).getOrElse(exn.getClass.getName))))
          hello = None
          state = State.Stopping
      }
      entry
    }

    val startedAt = System.nanoTime()
    synchronized {
      while (waiting.result.isEmpty && state == State.Starting) {
        val remaining = timeoutNanos - (System.nanoTime() - startedAt)
        if (remaining <= 0L) {
          waiting.result = Some(Left(TimedOut(PideBridgePolicy.PositiveDuration.duration(timeout))))
          if (hello.contains(waiting)) hello = None
          state = State.Stopping
        }
        else wait(math.max(1L, remaining / 1000000L))
      }
      waiting.result.getOrElse(Left(SessionStopped))
    }
  }

  def call[A](operation: BridgeOperation[A], cancellation: BridgeCancellation): BridgeResult[A] = {
    if (cancellation.isCancelled) Left(Cancelled)
    else {
      val id = ids.next()
      val admitted = synchronized {
        if (state == State.Starting) Left(ProtocolError("PIDE bridge is not ready"))
        else if (state != State.Open) Left(SessionStopped)
        else if (!operationNames.contains(operation.name))
          Left(ProtocolError("unknown bridge operation " + operation.name))
        else if (!advertisedOperations.contains(operation.name))
          Left(ProtocolError("bridge operation was not advertised by ML: " + operation.name))
        else pending.register(id, operation)
      }

      admitted match {
        case Left(failure) => Left(failure)
        case Right(call) =>
          cancellation.onCancel(() => cancel(id))
          synchronized {
            if (pending.contains(id)) {
              armDeadline(id)
              dispatch(id, operation, cancellation)
            }
          }
          call.result
      }
    }
  }

  private def armDeadline(id: BridgeCallId): Unit = {
    val handle = deadlineScheduler.schedule(callTimeout,
      () => timeout(id))
    deadlines += id -> handle
  }

  private def clearDeadline(id: BridgeCallId): Unit =
    deadlines.get(id).foreach { handle =>
      deadlines -= id
      handle.cancel()
    }

  private def clearDeadlines(): Unit = {
    val handles = deadlines.values
    deadlines = Map.empty
    handles.foreach(_.cancel())
  }

  private def timeout(id: BridgeCallId): Unit = synchronized {
    /* Firing removes itself from ManualDeadlineScheduler, but also remove our
      * ownership entry before settling the terminal race. */
    deadlines -= id
    val owned = pending.fail(id, TimedOut(PideBridgePolicy.PositiveDuration.duration(callTimeout)))
    val wasSent = sent.contains(id)
    sent -= id
    if (owned && wasSent) sendCancel(id)
  }

  private def dispatch[A](id: BridgeCallId, operation: BridgeOperation[A],
    cancellation: BridgeCancellation): Unit = synchronized {
    if (state != State.Open) {
      pending.fail(id, SessionStopped)
      clearDeadline(id)
    }
    else if (cancellation.isCancelled) {
      pending.fail(id, Cancelled)
      clearDeadline(id)
    }
    else if (pending.contains(id)) {
      try {
        /* A replaceable transport may deliver synchronously from send().  Mark
           the call as sent first so a rejected reply can order its cancel
           after the call even before send returns. */
        sent += id
        transport.send(protocol.call(
          BridgeCallId.value(id), theory, operation.name, operation.requestPayload))
      }
      catch {
        case NonFatal(exn) =>
          sent -= id
          pending.fail(id, TransportFailed(
            Option(exn.getMessage).getOrElse(exn.getClass.getName)))
          clearDeadline(id)
      }
    }
  }

  private def cancel(id: BridgeCallId): Unit = synchronized {
    val owned = pending.fail(id, Cancelled)
    clearDeadline(id)
    val wasSent = sent.contains(id)
    sent -= id
    if (owned && wasSent) sendCancel(id)
  }

  private def sendCancel(id: BridgeCallId): Unit =
    try transport.send(protocol.cancel(BridgeCallId.value(id)))
    catch {
      case NonFatal(exn) =>
        diagnostics("Failed to send PIDE bridge cancellation for " +
          BridgeCallId.value(id) + ": " +
          Option(exn.getMessage).getOrElse(exn.getClass.getName))
    }

  private def receive(reply: PideTransport.Inbound): Unit = synchronized {
    protocol.decode(reply) match {
      case PideBridgeReply.DrainAck(id) =>
        if (hello.exists(_.id == BridgeCallId.test(id)))
          failHello(ProtocolError("received drain acknowledgement for startup hello"))
        else completeDrain(id)
      case PideBridgeReply.DrainFailure(id, failure) =>
        if (hello.exists(_.id == BridgeCallId.test(id)))
          failHello(ProtocolError("received drain failure for startup hello: " + failure.message))
        else failDrain(id, failure)
      case PideBridgeReply.Success(rawId, operation, payload) =>
        val id = BridgeCallId.test(rawId)
        if (drain.exists(_.id == id))
          failDrain(rawId, ProtocolError("received operation result for drain control id"))
        else if (hello.exists(_.id == id)) completeHello(operation, payload)
        else {
          val result = pending.complete(id, operation, payload)
          clearDeadline(id)
          val wasSent = sent.contains(id)
          sent -= id
          if (result == Rejected && wasSent) sendCancel(id)
        }
      case PideBridgeReply.Failure(rawId, operation, failure) =>
        val id = BridgeCallId.test(rawId)
        if (drain.exists(_.id == id))
          failDrain(rawId, ProtocolError("received operation failure for drain control id: " +
            failure.message))
        else if (hello.exists(_.id == id)) failHello(ProtocolError(failure.message))
        else {
          val result = pending.reject(id, operation, failure)
          clearDeadline(id)
          val wasSent = sent.contains(id)
          sent -= id
          if (result == Rejected && wasSent) sendCancel(id)
        }
      case PideBridgeReply.Malformed(Some(rawId), operation, detail) =>
        val id = BridgeCallId.test(rawId)
        if (drain.exists(_.id == id)) failDrain(rawId, ProtocolError(detail))
        else if (hello.exists(_.id == id)) failHello(ProtocolError(detail))
        else {
          val owned = operation match {
            case Some(name) => pending.reject(id, name, ProtocolError(detail)) != Unowned
            case None => pending.fail(id, ProtocolError(detail))
          }
          clearDeadline(id)
          val wasSent = sent.contains(id)
          sent -= id
          if (owned && wasSent) sendCancel(id)
        }
      case PideBridgeReply.Malformed(None, _, detail) =>
        diagnostics("Uncorrelated PIDE bridge result: " + detail)
    }
  }

  /** Close admission, cancel all admitted calls, and wait for the one ML drain
    * owner. A failed drain deliberately leaves the bridge Stopping: only the
    * composition root can prove termination by stopping the Isabelle session
    * and calling sessionStopped().
    */
  def beginStop(): BridgeDrainOutcome = {
    val waiting = synchronized {
      stopOutcome match {
        case Some(outcome) => return outcome
        case None => ()
      }
      drain match {
        case Some(entry) => entry
        case None if state == State.Stopped =>
          val outcome = SessionTerminated
          stopOutcome = Some(outcome)
          return outcome
        case None =>
          state = State.Stopping
          hello.foreach(_.result = Some(Left(SessionStopped)))
          hello = None
          notifyAll()

          val sentToCancel = sent.toList.sortBy(BridgeCallId.value)
          pending.drain(SessionStopped)
          clearDeadlines()
          deadlineScheduler.shutdown()
          sent = Set.empty

          val entry = new Drain(ids.next(), System.nanoTime(), None)
          drain = Some(entry)
          if (!transportAlive)
            settleDrainFailure(entry, TransportFailed("transport terminated"))
          else {
            sentToCancel.foreach(sendCancel)
            if (entry.result.isEmpty) {
              try transport.send(protocol.drain(BridgeCallId.value(entry.id)))
              catch {
                case NonFatal(exn) =>
                  settleDrainFailure(entry, TransportFailed(
                    Option(exn.getMessage).getOrElse(exn.getClass.getName)))
              }
            }
          }
          entry
      }
    }

    val timeoutNanos = PideBridgePolicy.NonNegativeDuration.duration(drainTimeout).toNanos
    synchronized {
      while (waiting.result.isEmpty && state == State.Stopping) {
        val remaining = timeoutNanos - (System.nanoTime() - waiting.startedAtNanos)
        if (remaining <= 0L)
          settleDrainFailure(waiting,
            TimedOut(PideBridgePolicy.NonNegativeDuration.duration(drainTimeout)))
        else {
          val millis = remaining / 1000000L
          val nanos = (remaining % 1000000L).toInt
          wait(millis, nanos)
        }
      }
      waiting.result.orElse(stopOutcome).getOrElse(SessionTerminated)
    }
  }

  def sessionStopped(): Unit = synchronized {
    val outcome = stopOutcome.getOrElse(SessionTerminated)
    stopOutcome = Some(outcome)
    drain.foreach(entry => if (entry.result.isEmpty) entry.result = Some(outcome))
    drain = None
    hello = None
    state = State.Stopped
    clearDeadlines()
    deadlineScheduler.shutdown()
    notifyAll()
    transport.close()
  }

  private def transportTerminated(termination: PideTransport.Termination): Unit = synchronized {
    transportAlive = false
    termination match {
      case PideTransport.Closed => ()
      case PideTransport.Failed(detail) =>
        diagnostics("PIDE bridge transport terminated: " + detail)
    }
    if (state != State.Stopped) {
      val failure = TransportFailed("transport terminated")
      state = State.Stopping
      hello.foreach(_.result = Some(Left(failure)))
      hello = None
      notifyAll()
      pending.drain(failure)
      clearDeadlines()
      sent = Set.empty
      deadlineScheduler.shutdown()
      drain match {
        case Some(entry) if entry.result.isEmpty => settleDrainFailure(entry, failure)
        case None if stopOutcome.isEmpty => stopOutcome = Some(Failed(failure))
        case _ => ()
      }
    }
  }

  private def completeDrain(rawId: String): Unit = {
    val owned = drain.filter(entry => BridgeCallId.value(entry.id) == rawId)
    owned match {
      case Some(entry) if entry.result.isEmpty && stopOutcome.isEmpty && state == State.Stopping =>
        val outcome = Acknowledged
        entry.result = Some(outcome)
        stopOutcome = Some(outcome)
        drain = None
        state = State.Stopped
        notifyAll()
        transport.close()
        deadlineScheduler.shutdown()
      case _ => diagnostics("Unowned PIDE bridge drain acknowledgement " + rawId)
    }
  }

  private def failDrain(rawId: String, failure: BridgeFailure): Unit =
    drain.filter(entry => BridgeCallId.value(entry.id) == rawId) match {
      case Some(entry) if entry.result.isEmpty && stopOutcome.isEmpty =>
        settleDrainFailure(entry, failure)
      case _ => diagnostics("Unowned PIDE bridge drain failure " + rawId + ": " + failure.message)
    }

  private def settleDrainFailure(entry: Drain, failure: BridgeFailure): Unit = {
    val outcome = Failed(failure)
    entry.result = Some(outcome)
    stopOutcome = Some(outcome)
    notifyAll()
  }

  private[mcp] def pendingCount: Int = pending.size
  private[mcp] def deadlineCount: Int = synchronized { deadlines.size }
  private[mcp] def startupHelloPending: Boolean = synchronized { hello.isDefined }
  private[mcp] def isStopping: Boolean = synchronized { state == State.Stopping }
  private[mcp] def isStopped: Boolean = synchronized { state == State.Stopped }

  private def completeHello(operation: String, payload: XML.Body): Unit = {
    if (state != State.Starting) ()
    else if (operation != "hello") failHello(ProtocolError("invalid hello operation " + operation))
    else {
      val result =
        try {
          val (actualRevision, advertised) =
            XML.Decode.pair(XML.Decode.string, XML.Decode.list(XML.Decode.string))(payload)
          if (actualRevision != protocol.revision)
            Left(ProtocolError("unsupported bridge revision " + actualRevision))
          else if (!bridgeProfile.requiredOperationNames.subsetOf(advertised.toSet))
            Left(ProtocolError("hello is missing required bridge operations: " +
              (bridgeProfile.requiredOperationNames -- advertised.toSet).toList.sorted.mkString(", ")))
          else Right(advertised.toSet)
        }
        catch {
          case NonFatal(exn) => Left(ProtocolError("malformed hello reply: " +
            Option(exn.getMessage).getOrElse(exn.getClass.getName)))
        }
      result match {
        case Right(advertised) =>
          advertisedOperations = advertised
          hello.foreach(_.result = Some(Right(())))
          hello = None
          state = State.Open
          notifyAll()
        case Left(failure) => failHello(failure)
      }
    }
  }

  private def failHello(failure: BridgeFailure): Unit = {
    hello.foreach(_.result = Some(Left(failure)))
    hello = None
    state = State.Stopping
    notifyAll()
  }

  private[mcp] def advertisedOperationNames: Set[String] = synchronized {
    advertisedOperations
  }
}
