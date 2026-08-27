/*  Title:      mcp/src/mcp_request_scheduler.scala

Replaceable application-work scheduler.  The checkpoint-4 implementations are
deterministic and have no waiting queue; production bounded concurrency lands
in checkpoint 6.
*/

package isabelle.mcp.connection


trait RequestScheduler {
  def submit(task: () => Unit): RequestScheduler.Submission
}


object RequestScheduler {
  sealed trait Submission
  case object Accepted extends Submission
  case object Rejected extends Submission
}


final class DeterministicSequentialScheduler extends RequestScheduler {
  def submit(task: () => Unit): RequestScheduler.Submission = {
    task()
    RequestScheduler.Accepted
  }
}


final class ManualSequentialScheduler extends RequestScheduler {
  /* This is an explicit test-control latch, not an application queue: while
     one task is held for the test to release, every additional submission is
     rejected rather than retained. */
  private var pending: Option[() => Unit] = None

  def submit(task: () => Unit): RequestScheduler.Submission =
    if (pending.isDefined) RequestScheduler.Rejected
    else {
      pending = Some(task)
      RequestScheduler.Accepted
    }

  def hasPending: Boolean = pending.isDefined

  def runPending(): Boolean =
    pending match {
      case None => false
      case Some(task) =>
        pending = None
        task()
        true
    }
}
