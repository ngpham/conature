package np.conature.util

import java.util.function.Consumer
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{ FiniteDuration, DurationInt }

trait Cancellable {
  def cancel(): Unit
  def isCancelled: Boolean
}

trait Scheduler {
  def schedule(delay: FiniteDuration, recurrent: Boolean = false)(f: => Unit): Cancellable
  def shutdown(): Unit

  // Currently this handler must not rethrow, to protect the scheduler thread.
  // This limits the usage a bit (i.e. test with failed assertion will not propagate),
  // but it is not critical for now.
  // FixMe: make the scheduler fully async with a task queue and a respawnable thread.
  // Or simply use the one-thread pool.
  def exceptionHandler: Throwable => Unit

  // Java API
  def schedule(delay: Long, recurrent: Boolean, f: Runnable): Cancellable =
    schedule(delay, TimeUnit.SECONDS, recurrent, f)
  // Java API
  def schedule(delay: Long, timeUnit: TimeUnit, recurrent: Boolean, f: Runnable): Cancellable =
    schedule(new FiniteDuration(delay, timeUnit), recurrent){ f.run() }
}

// Singlethreaded, non-hierarchical
// Varghese, George, and Tony Lauck. "Hashed and hierarchical timing wheels"
class HashedWheelScheduler (
    wheelSizeInBits: Int = 8,
    tickDuration: FiniteDuration = 100.millisecond,
    override val exceptionHandler: Throwable => Unit = _ => ())
extends Scheduler {
  import HashedWheelScheduler.{ Task, Bucket }

  val tickDurationMillis: Long = tickDuration.toMillis

  require(tickDurationMillis >= 10, "tick duration should be at least 10 milliseconds.")
  require((wheelSizeInBits > 0) && (wheelSizeInBits < 10), "wheel size: 2 to 2^10.")

  @volatile private var status = 1
  private val wheelSize = 1 << wheelSizeInBits
  private val wheel = Array.fill(wheelSize)(new Bucket(exceptionHandler))
  private val tasks: ConQueue[Task] = new MpscQueue[Task]()
  private val wheelMask = wheelSize - 1
  private val startTime = System.nanoTime / 1000000

  private val timer = new Thread(new Runnable {
    private var currentTick: Int = 0

    private val _taskAct = new Consumer[Task] {
      def accept(t: Task): Unit = if (!t.isCancelled) {
        val countdownInTicks = t.countdown + currentTick
        val countdownInCycles = t.countdown >> wheelSizeInBits
        val offset = countdownInTicks & wheelMask

        t.countdown = countdownInCycles   // valid abuse
        wheel(offset.asInstanceOf[Int]).add(t)
      }
    }

    private def fetchIntoWheel(): Unit = {
      tasks.batchConsume(1024, _taskAct)
    }

    override def run(): Unit = while(status == 1) {
      fetchIntoWheel()
      val bucketIndex = currentTick & wheelMask
      val wakeupAt = tickDurationMillis * (currentTick)

      wheel(bucketIndex).expireTasks()

      val timeToSleep = wakeupAt - (System.nanoTime / 1000000 - startTime)

      if (timeToSleep > 0) try Thread.sleep(timeToSleep)
      catch { case _: InterruptedException => }
      currentTick = currentTick + 1
    }
  })
  timer.setDaemon(true)
  timer.start()

  override def schedule
      (delay: FiniteDuration, recurrent: Boolean = false)
      (f: => Unit): Cancellable = {
    val numTicks = delay.toMillis / tickDurationMillis
    val task =
      if (!recurrent) new Task(() => f, numTicks)
      else (new Task(() => (), numTicks) { wrapper =>
        override val exec: () => Unit = () => {
          if (!wrapper.isCancelled) {
            f
            val _ = schedule(delay, false)(wrapper.exec())
          }
        }
      })

    tasks.offer(task)
    task
  }

  override def shutdown(): Unit = { status = 0 }
}

object HashedWheelScheduler {
  // Java API
  def apply(wheelSizeInBits: Int, tickDuration: Int, timeUnit: TimeUnit): Scheduler =
    new HashedWheelScheduler(
      wheelSizeInBits,
      new FiniteDuration(tickDuration.asInstanceOf[Long], timeUnit))

  // Java API
  def apply(): Scheduler = new HashedWheelScheduler()

  private[util] class Bucket(val exceptionHandler: Throwable => Unit) {
    private var head: Task = null
    private var tail: Task = null

    def add(t: Task): Unit = {
      t.bucket = this
      if (head eq null) { head = t; tail = t }
      else { tail.next = t; t.prev = tail; tail = t }
    }

    def remove(t: Task): Unit = {
      if (t.prev != null) t.prev.next = t.next
      if (t.next != null) t.next.prev = t.prev
      if (t eq head) head = t.next
      if (t eq tail) tail = t.prev
      t.next = null; t.prev = null; t.bucket = null
    }

    def expireTasks(): Unit = {
      @annotation.tailrec def rec(t: Task): Unit = {
        if (t ne null) {
          val _next = t.next
          if (t.isCancelled) remove(t)
          else if (t.countdown <= 0) {
            try t.exec()
            catch {
              case e: Throwable => exceptionHandler(e)
            } finally {
              remove(t)
            }
          } else t.countdown -= 1
          rec(_next)
        }
      }
      rec(head)
    }
  }

  private[util] class Task(val exec: () => Unit, var countdown: Long)
  extends JCancellable with Cancellable {
    var prev: Task = null
    var next: Task = null
    var bucket: Bucket = null
  }
}
