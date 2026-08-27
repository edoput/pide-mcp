/*  Title:      mcp/src/mcp_request_registry.scala

Connection-owned request identity, terminal ownership, cancellation, and
invariant detection.  Scheduling and wire output deliberately remain outside
this checkpoint.
*/

package isabelle.mcp.connection

import isabelle.{JSON, error}
import isabelle.mcp.application.McpApplication


sealed trait RequestId {
  def json: JSON.T
}


object RequestId {
  /* Isabelle's JSON parser materializes every numeric token as Double.  Do
     not manufacture an identity from a rounded token: this is deliberately
     the IEEE-754 exact-integer range, inclusive. */
  private val MaxExactWireInteger = 9007199254740991L

  final case class StringId private[connection] (value: String) extends RequestId {
    def json: JSON.T = value
  }

  final class IntegerId private[connection] (
    val value: BigInt,
    val json: JSON.T
  ) extends RequestId {
    override def equals(that: Any): Boolean =
      that match {
        case other: IntegerId => value == other.value
        case _ => false
      }

    override def hashCode: Int = value.hashCode
    override def toString: String = "RequestId.IntegerId(" + value + ")"
  }

  def fromJson(json: JSON.T): Either[String, RequestId] =
    json match {
      case value: String => Right(StringId(value))
      case value: Byte => integerValue(value.toLong)
      case value: Short => integerValue(value.toLong)
      case value: Int => integerValue(value.toLong)
      case value: Long => integerValue(value)
      case value: Double if value.isFinite && value.isWhole &&
          value >= -MaxExactWireInteger.toDouble && value <= MaxExactWireInteger.toDouble =>
        integerValue(value.toLong)
      case _ => Left("id must be a string or exactly representable integer")
    }

  def string(value: String): RequestId = StringId(value)
  def integer(value: Long): RequestId = {
    require(inSafeRange(value), "request integer is outside the exact JSON wire range")
    new IntegerId(BigInt(value), value)
  }

  private def inSafeRange(value: Long): Boolean =
    value >= -MaxExactWireInteger && value <= MaxExactWireInteger

  private def integerValue(value: Long): Either[String, RequestId] =
    if (inSafeRange(value)) Right(integer(value))
    else Left("id must be a string or exactly representable integer")
}


final class CompletionToken private[connection] ()


object RequestRegistry {
  sealed trait AdmissionKind {
    def consumesCapacity: Boolean
    def cancellable: Boolean
  }

  object AdmissionKind {
    case object Initialize extends AdmissionKind {
      val consumesCapacity = false
      val cancellable = false
    }
    case object Ping extends AdmissionKind {
      val consumesCapacity = false
      val cancellable = true
    }
    case object Control extends AdmissionKind {
      val consumesCapacity = false
      val cancellable = true
    }
    case object Ordinary extends AdmissionKind {
      val consumesCapacity = true
      val cancellable = true
    }
    case object Diagnostic extends AdmissionKind {
      val consumesCapacity = false
      val cancellable = true
    }
  }

  sealed trait TerminalDisposition {
    def expectsLateCompletion: Boolean
  }

  object TerminalDisposition {
    case object Success extends TerminalDisposition {
      val expectsLateCompletion = false
    }
    case object ApplicationError extends TerminalDisposition {
      val expectsLateCompletion = false
    }
    case object Exception extends TerminalDisposition {
      val expectsLateCompletion = false
    }
    case object ClientCancelled extends TerminalDisposition {
      val expectsLateCompletion = true
    }
    case object Timeout extends TerminalDisposition {
      val expectsLateCompletion = true
    }
    case object Shutdown extends TerminalDisposition {
      val expectsLateCompletion = true
    }
  }

  sealed trait WorkerDisposition {
    private[connection] def terminal: TerminalDisposition
  }

  object WorkerDisposition {
    case object Success extends WorkerDisposition {
      private[connection] val terminal = TerminalDisposition.Success
    }
    case object ApplicationError extends WorkerDisposition {
      private[connection] val terminal = TerminalDisposition.ApplicationError
    }
    case object Exception extends WorkerDisposition {
      private[connection] val terminal = TerminalDisposition.Exception
    }
  }

  final case class Tombstone(
    id: RequestId,
    kind: AdmissionKind,
    disposition: TerminalDisposition,
    cancellationReason: Option[String],
    capacityReleased: Boolean
  )

  final case class Snapshot(
    seenIds: Set[RequestId],
    activeIds: Set[RequestId],
    tombstones: List[Tombstone],
    activeCapacity: Int
  )

  sealed trait Violation {
    def message: String
  }

  final case class UnknownCompletion(token: CompletionToken) extends Violation {
    val message = "completion token is not owned by this connection"
  }
  final case class DuplicateCompletion(token: CompletionToken) extends Violation {
    val message = "completion token has already reached a terminal disposition"
  }

  trait InvariantViolationPolicy {
    def onViolation(violation: Violation, snapshot: Snapshot): Unit
    def isBroken: Boolean = false
  }

  object InvariantViolationPolicy {
    object FailFast extends InvariantViolationPolicy {
      def onViolation(violation: Violation, snapshot: Snapshot): Unit =
        throw new IllegalStateException("MCP request registry invariant violated: " + violation.message)
    }

    final class LogAndClose(close: () => Unit, log: String => Unit) extends InvariantViolationPolicy {
      def onViolation(violation: Violation, snapshot: Snapshot): Unit = {
        log("MCP request registry invariant violated: " + violation.message)
        close()
      }
    }

    final class MarkBroken extends InvariantViolationPolicy {
      private var latest: Option[(Violation, Snapshot)] = None

      def onViolation(violation: Violation, snapshot: Snapshot): Unit = synchronized {
        latest = Some((violation, snapshot))
      }

      override def isBroken: Boolean = synchronized { latest.isDefined }
      def violation: Option[(Violation, Snapshot)] = synchronized { latest }
    }
  }

  sealed trait Admission
  final case class Admitted(
    id: RequestId,
    token: CompletionToken,
    cancellation: McpApplication.Cancellation
  ) extends Admission
  final case class DuplicateId(id: RequestId) extends Admission
  final case class Broken(id: RequestId) extends Admission

  sealed trait IdReservation
  final class Reserved private[connection] (
    private[connection] val handle: Reservation
  ) extends IdReservation
  final case class Reused(id: RequestId) extends IdReservation

  sealed trait Completion
  final case class Completed(tombstone: Tombstone) extends Completion
  final case class LateIgnored(tombstone: Tombstone) extends Completion
  final case class TimerIgnored(tombstone: Tombstone) extends Completion
  final case class InvariantViolation(violation: Violation) extends Completion

  sealed trait Cancellation
  final case class Cancelled(tombstone: Tombstone) extends Cancellation
  case object CancellationIgnored extends Cancellation
}


private[connection] final class Reservation private[connection] (
  private[connection] val owner: RequestRegistry,
  val id: RequestId
)


final class RequestRegistry(
  policy: RequestRegistry.InvariantViolationPolicy
) {
  import RequestRegistry._

  private final class OwnedCancellation extends McpApplication.Cancellation {
    private var cancelled = false
    def isCancelled: Boolean = synchronized { cancelled }
    def cancel(): Unit = synchronized { cancelled = true }
  }

  private final case class Active(
    id: RequestId,
    kind: AdmissionKind,
    token: CompletionToken,
    cancellation: OwnedCancellation
  )

  private final case class Past(tombstone: Tombstone, lateCompletionSeen: Boolean)

  private var seen = Set.empty[RequestId]
  private var activeByToken = Map.empty[CompletionToken, Active]
  private var activeById = Map.empty[RequestId, Active]
  private var pastByToken = Map.empty[CompletionToken, Past]
  private var consumedReservations = Set.empty[Reservation]
  private var capacity = 0

  def reserve(id: RequestId): IdReservation = synchronized {
    if (seen.contains(id)) Reused(id)
    else {
      seen += id
      new Reserved(new Reservation(this, id))
    }
  }

  def admit(id: RequestId, kind: AdmissionKind): Admission =
    reserve(id) match {
      case reserved: Reserved => admitReserved(reserved.handle, kind)
      case Reused(_) => DuplicateId(id)
    }

  private[connection] def admitReserved(
    reservation: Reservation,
    kind: AdmissionKind
  ): Admission = synchronized {
    val id = reservation.id
    if ((reservation.owner ne this) || consumedReservations.contains(reservation) ||
        !seen.contains(id) || activeById.contains(id))
      error("request registry admission requires one prior id reservation")
    else {
      consumedReservations += reservation
      if (policy.isBroken && kind == AdmissionKind.Ordinary) Broken(id)
      else {
        val token = new CompletionToken
        val cancellation = new OwnedCancellation
        val active = Active(id, kind, token, cancellation)
        activeByToken += token -> active
        activeById += id -> active
        if (kind.consumesCapacity) capacity += 1
        Admitted(id, token, cancellation)
      }
    }
  }

  private[connection] def abandon(reservation: Reservation): Unit = synchronized {
    if ((reservation.owner ne this) || consumedReservations.contains(reservation) ||
        !seen.contains(reservation.id))
      error("request registry abandonment requires one live reservation")
    consumedReservations += reservation
  }

  def cancel(id: RequestId, reason: Option[String]): Cancellation = synchronized {
    activeById.get(id) match {
      case Some(active) if active.kind.cancellable =>
        active.cancellation.cancel()
        Cancelled(terminal(active, TerminalDisposition.ClientCancelled, reason))
      case _ => CancellationIgnored
    }
  }

  def complete(token: CompletionToken, disposition: WorkerDisposition): Completion = {
    val result = synchronized {
      activeByToken.get(token) match {
        case Some(active) => Right(Completed(terminal(active, disposition.terminal, None)))
        case None =>
          pastByToken.get(token) match {
            case Some(past) if past.tombstone.disposition.expectsLateCompletion && !past.lateCompletionSeen =>
              pastByToken += token -> past.copy(lateCompletionSeen = true)
              Right(LateIgnored(past.tombstone))
            case Some(_) => Left((DuplicateCompletion(token), snapshot0))
            case None => Left((UnknownCompletion(token), snapshot0))
          }
      }
    }
    react(result)
  }

  def timeout(token: CompletionToken): Completion = {
    val result = synchronized {
      activeByToken.get(token) match {
        case Some(active) =>
          active.cancellation.cancel()
          Right(Completed(terminal(active, TerminalDisposition.Timeout, None)))
        case None =>
          pastByToken.get(token) match {
            case Some(past) => Right(TimerIgnored(past.tombstone))
            case None => Left((UnknownCompletion(token), snapshot0))
          }
      }
    }
    react(result)
  }

  def shutdown(): List[Tombstone] = synchronized {
    activeByToken.values.toList.map { active =>
      active.cancellation.cancel()
      terminal(active, TerminalDisposition.Shutdown, None)
    }
  }

  def snapshot: Snapshot = synchronized { snapshot0 }

  private def terminal(
    active: Active,
    disposition: TerminalDisposition,
    reason: Option[String]
  ): Tombstone = {
    activeByToken -= active.token
    activeById -= active.id
    val released = active.kind.consumesCapacity
    if (released) capacity -= 1
    val tombstone = Tombstone(active.id, active.kind, disposition, reason, released)
    pastByToken += active.token -> Past(tombstone, lateCompletionSeen = false)
    tombstone
  }

  private def react(
    result: Either[(Violation, Snapshot), Completion]
  ): Completion =
    result.fold({ case (violation, snapshot) =>
      policy.onViolation(violation, snapshot)
      InvariantViolation(violation)
    }, identity)

  private def snapshot0: Snapshot =
    Snapshot(seen, activeById.keySet,
      pastByToken.values.toList.map(_.tombstone).sortBy(tombstone => JSON.Format(tombstone.id.json)),
      capacity)
}
