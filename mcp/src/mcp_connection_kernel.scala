/*  Title:      mcp/src/mcp_connection_kernel.scala

Checkpoint-5 connection control path: revision classification, lifecycle
admission, and connection-owned request reservations over a DataPlane. Wire
output, batches, scheduler dispatch, and shutdown draining remain later
checkpoints.
*/

package isabelle.mcp.connection

import isabelle.{JSON, error}
import isabelle.mcp.protocol.JsonRpc
import isabelle.mcp.transport.DataPlane


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

  def phase: Phase = current

  def admit(message: RevisionRules.Message): Decision =
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

  def initializeCompleted(): Unit =
    if (current != Initializing) error("initialize completion is only valid in Initializing")
    else current = AwaitingInitialized

  def beginClosing(): Unit =
    if (current != Closed) current = Closing

  def finishClosing(): Unit =
    if (current != Closing) error("close completion is only valid in Closing")
    else current = Closed
}


final class ConnectionKernel private (
  val policy: ConnectionPolicy,
  val dataPlane: DataPlane,
  val revisionRules: RevisionRules,
  val scheduler: RequestScheduler,
  val registry: RequestRegistry
) {
  require(policy.revision == revisionRules.revision,
    "connection policy and revision rules disagree")

  private val lifecycle0 = new ConnectionLifecycle

  def phase: ConnectionLifecycle.Phase = lifecycle0.phase

  def receive(): Option[ConnectionKernel.Admission] =
    dataPlane.receive().map(inbound => admit(revisionRules.classify(inbound)))

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
      case Some((acceptedId, kind)) =>
        require(acceptedId == id, "lifecycle changed the reserved request id")
        registry.admitReserved(reservation, kind) match {
          case request: RequestRegistry.Admitted => ConnectionKernel.Accepted(decision, request)
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
}


object ConnectionKernel {
  sealed trait Admission {
    def decision: ConnectionLifecycle.Decision
  }

  final case class Accepted(
    decision: ConnectionLifecycle.Decision,
    request: RequestRegistry.Admitted
  ) extends Admission

  final case class Decided(decision: ConnectionLifecycle.Decision) extends Admission

  def apply(
    policy: ConnectionPolicy,
    dataPlane: DataPlane,
    revisionRules: RevisionRules,
    scheduler: RequestScheduler,
    registry: RequestRegistry
  ): ConnectionKernel =
    new ConnectionKernel(policy, dataPlane, revisionRules, scheduler, registry)
}
