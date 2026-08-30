/*  Title:      mcp/src/mcp_pide_bridge.scala

Typed control/data-plane boundary for Scala-to-Isabelle/ML calls.
*/

package isabelle.mcp.pide

import isabelle.{Bytes, Future, Promise}

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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

  final case class TimedOut(seconds: Double) extends BridgeFailure {
    val message = "bridge call timed out after " + seconds + " seconds"
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


final case class PideBridgePolicy(
  maxPending: PideBridgePolicy.MaxPending,
  timing: PideBridgePolicy.Timing,
  envelopes: PideBridgePolicy.Envelopes
)


object PideBridgePolicy {
  opaque type MaxPending = Int
  opaque type PositiveSeconds = Double
  opaque type PositiveBytes = Long

  object MaxPending {
    def checked(value: Int): Either[String, MaxPending] =
      if (value > 0) Right(value) else Left("maxPending must be positive")

    def value(value: MaxPending): Int = value
  }

  object PositiveSeconds {
    def checked(field: String, value: Double): Either[String, PositiveSeconds] =
      if (!value.isNaN && !value.isInfinity && value > 0.0) Right(value)
      else Left(field + " must be finite and positive")

    def value(value: PositiveSeconds): Double = value
  }

  object PositiveBytes {
    def checked(field: String, value: Long): Either[String, PositiveBytes] =
      if (value > 0L) Right(value) else Left(field + " must be positive")

    def value(value: PositiveBytes): Long = value
  }

  final case class Timing(
    callTimeout: PositiveSeconds,
    drainTimeout: PositiveSeconds
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
    val call = PositiveSeconds.checked("callTimeout", callTimeoutSeconds)
    val drain = PositiveSeconds.checked("drainTimeout", drainTimeoutSeconds)
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
  def requestPayload: Bytes
  def decodeReply(payload: Bytes): Either[String, A]
}


/** Replaceable byte delivery only.  The bridge owns call state and codecs;
  * the composition root retains ownership of Headless.Session.stop.
  */
trait PideTransport {
  def start(receive: Bytes => Unit, terminated: PideTransport.Termination => Unit): Unit
  def send(envelope: Bytes): Unit
  def close(): Unit
}


object PideTransport {
  sealed trait Termination
  case object Closed extends Termination
  final case class Failed(detail: String) extends Termination
}


private[pide] object PendingRegistry {
  sealed trait Diagnostic {
    def id: BridgeCallId
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
    def complete(payload: Bytes): Boolean
    def fail(failure: BridgeFailure): Unit
  }

  private final class TypedEntry[A](
    descriptor: BridgeOperation[A],
    promise: Promise[BridgeResult[A]]
  ) extends Entry {
    val operation: String = descriptor.name

    def complete(payload: Bytes): Boolean =
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

  def complete(id: BridgeCallId, operation: String, payload: Bytes): Completion = {
    val owner = synchronized {
      pending.get(id) match {
        case Some(entry) => pending -= id; Some(entry)
        case None => None
      }
    }
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
}


/** Temporary characterization of the wire that checkpoint 1 must preserve.
  * It is deleted when every operation moves to the single v1 envelope.
  */
private[mcp] object LegacyWire {
  enum ReplyShape {
    case ToolsYxml, TheoriesYxml, StatusText, StatusYxml, ResourcesYxml
  }

  final case class Operation(
    name: String,
    command: String,
    resultFunction: String,
    argumentNames: List[String],
    replyShape: ReplyShape
  )

  final case class Change(function: String, event: String)

  val Tools = Operation(
    "tools", "MCP.tools", "MCP.tools_result",
    List("id", "designation", "bundles_yxml"), ReplyShape.ToolsYxml)
  val Theories = Operation(
    "theories", "MCP.theories", "MCP.theories_result",
    List("id"), ReplyShape.TheoriesYxml)
  val RunTool = Operation(
    "run_tool", "MCP.run_tool", "MCP.run_tool_result",
    List("id", "designation", "bundles_yxml", "name", "args_yxml"),
    ReplyShape.StatusText)
  val CheckDesignation =
    Operation(
      "check_designation", "MCP.check_designation", "MCP.check_designation_result",
      List("id", "designation", "bundles_yxml"), ReplyShape.StatusText)
  val Ir = Operation(
    "ir", "MCP.ir", "MCP.ir_result",
    List("id", "fname", "args_yxml"), ReplyShape.StatusYxml)
  val Resources = Operation(
    "resources", "MCP.resources", "MCP.resources_result",
    List("id", "designation"), ReplyShape.ResourcesYxml)
  val ReadResource =
    Operation(
      "read_resource", "MCP.read_resource", "MCP.read_resource_result",
      List("id", "designation", "name"), ReplyShape.StatusText)

  val operations: List[Operation] =
    List(Tools, Theories, RunTool, CheckDesignation, Ir, Resources, ReadResource)

  val ToolsChanged = Change("MCP.tools_changed", "tools")
  val ResourcesChanged = Change("MCP.resources_changed", "resources")
  val changes: List[Change] = List(ToolsChanged, ResourcesChanged)

  def arguments(operation: Operation, values: (String, Bytes)*): List[Bytes] = {
    val names = values.map(_._1).toList
    if (names.distinct != names || names.toSet != operation.argumentNames.toSet)
      throw new IllegalArgumentException(
        operation.command + " expects arguments " + operation.argumentNames.mkString(", ") +
          "; received " + names.mkString(", "))
    val byName = values.toMap
    operation.argumentNames.map(byName)
  }

  def expectReply(operation: Operation, expected: ReplyShape): Unit =
    if (operation.replyShape != expected)
      throw new IllegalStateException(
        operation.resultFunction + " is declared as " + operation.replyShape +
          "; decoder expects " + expected)
}
