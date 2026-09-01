/*  Title:      mcp/src/mcp_deadline_scheduler.scala

Replaceable one-shot control-plane deadline scheduling.  Request ownership and
terminal races remain with the component that uses the scheduler.
*/

package isabelle.mcp.control

import java.util.concurrent.{ScheduledThreadPoolExecutor, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.NANOSECONDS


/** A finite, strictly positive control-plane duration. Configuration parsing
  * is the only seconds-to-duration conversion boundary; extreme finite input
  * saturates at the largest representable nanosecond delay. */
opaque type PositiveDuration = FiniteDuration
opaque type NonNegativeDuration = FiniteDuration


object PositiveDuration {
  def checked(field: String, seconds: Double): Either[String, PositiveDuration] =
    checkedFinite(field, seconds, positive = true).map[PositiveDuration](value => value)

  def duration(value: PositiveDuration): FiniteDuration = value
  def seconds(value: PositiveDuration): Double = value.toNanos.toDouble / 1000000000.0
}


object NonNegativeDuration {
  def checked(field: String, seconds: Double): Either[String, NonNegativeDuration] =
    checkedFinite(field, seconds, positive = false).map[NonNegativeDuration](value => value)

  def duration(value: NonNegativeDuration): FiniteDuration = value
  def seconds(value: NonNegativeDuration): Double = value.toNanos.toDouble / 1000000000.0
}


private def checkedFinite(field: String, seconds: Double, positive: Boolean)
    : Either[String, FiniteDuration] = {
  val valid = !seconds.isNaN && !seconds.isInfinity &&
    (if (positive) seconds > 0.0 else seconds >= 0.0)
  if (!valid)
    Left(field + " must be finite and " + (if (positive) "positive" else "non-negative"))
  else {
    val saturation = Long.MaxValue.toDouble / 1000000000.0
    val nanos =
      if (seconds >= saturation) Long.MaxValue
      else math.max(if (positive) 1L else 0L, math.ceil(seconds * 1000000000.0).toLong)
    Right(FiniteDuration(nanos, NANOSECONDS))
  }
}


trait DeadlineScheduler {
  def schedule(delay: PositiveDuration, task: () => Unit): DeadlineScheduler.Handle
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

  def schedule(delay: PositiveDuration, task: () => Unit): DeadlineScheduler.Handle = {
    val finite = PositiveDuration.duration(delay)
    val future = executor.schedule(
      new Runnable { def run(): Unit = task() }, finite.length, finite.unit)
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

  def schedule(delay: PositiveDuration, task: () => Unit): DeadlineScheduler.Handle = synchronized {
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
