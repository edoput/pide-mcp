/*  Title:      mcp/src/mcp_request_scheduler.scala

Replaceable application-work scheduler.  The opaque permit is a direct
handoff lease: there is never a task queue between reservation and execution.
The request registry remains the source of request ownership and capacity
truth; this port owns only worker resources.
*/

package isabelle.mcp.connection

import java.util.concurrent.{Semaphore, SynchronousQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger


trait RequestScheduler {
  def capacity: Int
  def tryReserve(): RequestScheduler.Reservation
  def start(permit: RequestScheduler.Permit, task: () => Unit): RequestScheduler.Start
  def abandon(permit: RequestScheduler.Permit): Unit
  def shutdown(): Unit
  def isShutdown: Boolean
}


object RequestScheduler {
  sealed trait Reservation
  final class Permit private[connection] ()
  final case class Reserved(permit: Permit) extends Reservation
  case object Rejected extends Reservation

  sealed trait Start
  case object Started extends Start
  case object StartRejected extends Start
}


private[connection] trait PermitScheduler extends RequestScheduler {
  import RequestScheduler._

  private sealed trait PermitState
  private case object ReservedState extends PermitState
  private case object StartedState extends PermitState

  private var live = Map.empty[Permit, PermitState]
  private var closed = false

  protected def reserveWorker(): Boolean
  protected def releaseWorker(): Unit
  protected def run(task: () => Unit): Boolean
  protected def stopWorkers(): Unit
  protected def dropsStartedTasksOnShutdown: Boolean

  final def tryReserve(): Reservation = synchronized {
    if (closed || !reserveWorker()) Rejected
    else {
      val permit = new Permit
      live += permit -> ReservedState
      Reserved(permit)
    }
  }

  final def start(permit: Permit, task: () => Unit): Start = synchronized {
    live.get(permit) match {
      case Some(ReservedState) if !closed =>
        live += permit -> StartedState
        /* Concrete asynchronous schedulers turn executor refusal into false.
           Deterministic run executes task inline, so its exception must retain
           its real meaning after the wrapper has released the permit. */
        val started = run(() => try task() finally finish(permit))
        if (started) Started
        else {
          finish(permit)
          StartRejected
        }
      case _ => StartRejected
    }
  }

  final def abandon(permit: Permit): Unit = synchronized {
    if (live.get(permit).contains(ReservedState)) release(permit)
  }

  final def shutdown(): Unit = synchronized {
    if (!closed) {
      closed = true
      stopWorkers()
      live.collect { case (permit, ReservedState) => permit }.toList.foreach(release)
      if (dropsStartedTasksOnShutdown)
        live.collect { case (permit, StartedState) => permit }.toList.foreach(release)
    }
  }

  final def isShutdown: Boolean = synchronized { closed }

  private def finish(permit: Permit): Unit = synchronized {
    if (live.get(permit).contains(StartedState)) release(permit)
  }

  private def release(permit: Permit): Unit =
    if (live.contains(permit)) {
      live -= permit
      releaseWorker()
    }
}


final class DeterministicSequentialScheduler(val capacity: Int) extends PermitScheduler {
  require(capacity > 0, "capacity must be positive")
  private var occupied = 0

  protected def reserveWorker(): Boolean =
    if (occupied >= capacity) false else { occupied += 1; true }
  protected def releaseWorker(): Unit = occupied -= 1
  protected def run(task: () => Unit): Boolean = { task(); true }
  protected def stopWorkers(): Unit = ()
  protected def dropsStartedTasksOnShutdown: Boolean = false
}


/* One explicit test latch, never an application waiting queue.  Kernel tests
   using this scheduler therefore use the matching one-request policy. */
final class ManualSequentialScheduler extends PermitScheduler {
  val capacity = 1
  private var pending: Option[() => Unit] = None
  private var occupied = false
  private var taken = false

  protected def reserveWorker(): Boolean =
    if (occupied) false else { occupied = true; true }
  protected def releaseWorker(): Unit = occupied = false
  protected def run(task: () => Unit): Boolean =
    synchronized {
      if (pending.isDefined) false else { pending = Some(task); true }
    }
  protected def stopWorkers(): Unit = synchronized { pending = None }
  protected def dropsStartedTasksOnShutdown: Boolean = synchronized { !taken }

  /* This is an explicit test-control latch, not an application queue: a
     permit has already been reserved, and every other reservation rejects. */
  def hasPending: Boolean = synchronized { pending.isDefined }

  def runPending(): Boolean = {
    val task = synchronized {
      pending match {
        case None => None
        case Some(value) =>
          pending = None
          taken = true
          Some(value)
      }
    }
    task match {
      case None => false
      case Some(value) =>
        /* Shutdown may proceed while this explicitly taken test task runs;
           its permit remains owned until the wrapper's finally path finishes. */
        try { value(); true }
        finally synchronized { taken = false }
    }
  }
}


final class BoundedConcurrentScheduler(
  val capacity: Int,
  workerName: String = "mcp-worker"
) extends PermitScheduler {
  require(capacity > 0, "capacity must be positive")

  private val workerNumber = new AtomicInteger(0)
  private val workers = new ThreadFactory {
    def newThread(task: Runnable): Thread = {
      val worker = new Thread(task, workerName + "-" + workerNumber.incrementAndGet())
      worker.setDaemon(true)
      worker
    }
  }

  /* A SynchronousQueue has zero element capacity. Permits are acquired before
     an entry reaches the registry, so execute cannot enqueue work. */
  private val executor = new ThreadPoolExecutor(
    0, capacity, 0L, TimeUnit.MILLISECONDS,
    new SynchronousQueue[Runnable](), workers, new ThreadPoolExecutor.AbortPolicy)
  private val available = new Semaphore(capacity, true)

  protected def reserveWorker(): Boolean = available.tryAcquire()
  protected def releaseWorker(): Unit = available.release()
  protected def run(task: () => Unit): Boolean =
    try {
      executor.execute(new Runnable { def run(): Unit = task() })
      true
    }
    catch { case _: java.util.concurrent.RejectedExecutionException => false }
  protected def stopWorkers(): Unit = executor.shutdown()
  protected def dropsStartedTasksOnShutdown: Boolean = false
}
