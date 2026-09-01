/*  Title:      mcp/src/mcp_deadline_scheduler.scala

Replaceable one-shot control-plane deadline scheduling.  Request ownership and
terminal races remain with the component that uses the scheduler.
*/

package isabelle.mcp.control

import java.util.concurrent.{ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger


trait DeadlineScheduler {
  def schedule(seconds: Double, task: () => Unit): DeadlineScheduler.Handle
  def shutdown(): Unit
  def isShutdown: Boolean
}


object DeadlineScheduler {
  trait Handle {
    def cancel(): Unit
  }
}


final class ScheduledDeadlineScheduler(
  threadName: String = "mcp-deadline"
) extends DeadlineScheduler {
  private val threadNumber = new AtomicInteger(0)
  private val threads = new ThreadFactory {
    def newThread(task: Runnable): Thread = {
      val thread = new Thread(task, threadName + "-" + threadNumber.incrementAndGet())
      thread.setDaemon(true)
      thread
    }
  }
  private val executor = new ScheduledThreadPoolExecutor(1, threads)
  executor.setRemoveOnCancelPolicy(true)

  def schedule(seconds: Double, task: () => Unit): DeadlineScheduler.Handle = {
    val delay = Math.max(1L, Math.ceil(seconds * 1000000000.0).toLong)
    val future = executor.schedule(
      new Runnable { def run(): Unit = task() }, delay, TimeUnit.NANOSECONDS)
    new DeadlineScheduler.Handle { def cancel(): Unit = future.cancel(false) }
  }

  def shutdown(): Unit = executor.shutdownNow()
  def isShutdown: Boolean = executor.isShutdown
}


/* Deterministic one-shot scheduler for terminal-race tests.  Firing is an
   explicit test action; cancelled entries are removed rather than retained as
   a hidden queue. */
final class ManualDeadlineScheduler extends DeadlineScheduler {
  private final class Entry(task: () => Unit) extends DeadlineScheduler.Handle {
    private var active = true

    def cancel(): Unit = ManualDeadlineScheduler.this.synchronized {
      active = false
      entries = entries.filterNot(_ eq this)
    }

    def take(): Option[() => Unit] = ManualDeadlineScheduler.this.synchronized {
      if (!active) None
      else {
        active = false
        entries = entries.filterNot(_ eq this)
        Some(task)
      }
    }
  }

  private var entries = Vector.empty[Entry]
  private var closed = false

  def schedule(seconds: Double, task: () => Unit): DeadlineScheduler.Handle = synchronized {
    require(seconds > 0.0 && !seconds.isNaN && !seconds.isInfinity,
      "deadline must be finite and positive")
    if (closed) throw new IllegalStateException("deadline scheduler is closed")
    val entry = new Entry(task)
    entries :+= entry
    entry
  }

  def fireNext(): Boolean = {
    val task = synchronized { entries.headOption.flatMap(_.take()) }
    task match {
      case Some(run) => run(); true
      case None => false
    }
  }

  def pendingCount: Int = synchronized { entries.length }

  def shutdown(): Unit = synchronized {
    closed = true
    entries = Vector.empty
  }

  def isShutdown: Boolean = synchronized { closed }
}
