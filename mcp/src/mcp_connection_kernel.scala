/*  Title:      mcp/src/mcp_connection_kernel.scala

Checkpoint-4 connection control path: revision classification and lifecycle
admission over a DataPlane.  Request ownership, output, batches, cancellation,
and shutdown draining remain later checkpoints.
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
  final case class InitializeAccepted(id: JSON.T, requestedVersion: String) extends Decision
  case object InitializedAccepted extends Decision
  final case class PingAccepted(id: JSON.T) extends Decision
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
  val scheduler: RequestScheduler
) {
  require(policy.revision == revisionRules.revision,
    "connection policy and revision rules disagree")

  private val lifecycle0 = new ConnectionLifecycle

  def phase: ConnectionLifecycle.Phase = lifecycle0.phase

  def receive(): Option[ConnectionLifecycle.Decision] =
    dataPlane.receive().map(inbound => lifecycle0.admit(revisionRules.classify(inbound)))

  def admit(message: RevisionRules.Message): ConnectionLifecycle.Decision = lifecycle0.admit(message)

  def initializeCompleted(): Unit = lifecycle0.initializeCompleted()
  def beginClosing(): Unit = lifecycle0.beginClosing()
  def finishClosing(): Unit = lifecycle0.finishClosing()
}


object ConnectionKernel {
  def apply(
    policy: ConnectionPolicy,
    dataPlane: DataPlane,
    revisionRules: RevisionRules,
    scheduler: RequestScheduler
  ): ConnectionKernel =
    new ConnectionKernel(policy, dataPlane, revisionRules, scheduler)
}
