package np.conature.actor

import java.util.function.Consumer
import scala.concurrent.duration.{ FiniteDuration, DurationInt }
import np.conature.util.{ ConQueue, MpscQueue }

trait Cancellable {
  def cancel(): Unit
  def isCancelled: Boolean
}

trait Scheduler {
  def schedule(delay: FiniteDuration)(f: => Unit): Cancellable
  def shutdown(): Unit
}

class HashedWheelScheduler
    (wheelSizeInBits: Int = 8, tickDuration: FiniteDuration = 100.millisecond)
    extends Scheduler {
  import HashedWheelScheduler.{ Task, Bucket }

  val tickDurationMillis: Long = tickDuration.toMillis

  require(tickDurationMillis >= 10, "tick duration should be at least 10 milliseconds.")
  require((wheelSizeInBits > 0) && (wheelSizeInBits < 10), "wheel size: 2 to 2^10.")

  @volatile private var status = 1
  private val wheelSize = 1 << wheelSizeInBits
  private val wheel = Array.fill(wheelSize)(new Bucket())
  private val tasks: ConQueue[Task] = new MpscQueue[Task]()
  private val wheelMask = wheelSize - 1
  private val startTime = System.nanoTime / 1000000

  private val timer = new Thread(new Runnable {
    private var currentTick: Int = 0

    private val _taskAct = new Consumer[Task] {
      def accept(t: Task): Unit = if (!t.isCancelled) {
        val countdownInTicks = t.countdown + currentTick
        val countdownInCycles = countdownInTicks >> wheelSizeInBits
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

      wheel(bucketIndex).expireTasks()

      currentTick = currentTick + 1
      val wakeupAt = tickDurationMillis * (currentTick)
      val timeToSleep = wakeupAt - (System.nanoTime / 1000000 - startTime)

      if (timeToSleep > 0) try Thread.sleep(timeToSleep)
      catch { case _: InterruptedException => }
    }
  })
  timer.setDaemon(true)
  timer.start()

  override def schedule(delay: FiniteDuration)(f: => Unit): Cancellable = {
    val task = new Task(() => f, delay.toMillis / tickDurationMillis)
    tasks.offer(task)
    task
  }

  override def shutdown(): Unit = { status = 0 }
}

object HashedWheelScheduler {
  class Bucket {
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
              case e: Throwable => println(e)
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

  class Task(val exec: () => Unit, var countdown: Long) extends JCancellable with Cancellable {
    var prev: Task = null
    var next: Task = null
    var bucket: Bucket = null
    def isCancelled: Boolean = state == 0
  }
}
