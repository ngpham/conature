package np.conature.utiltesting.scheduler

import org.scalatest.FlatSpec
import scala.concurrent.duration._
import np.conature.util.{ Scheduler, HashedWheelScheduler }

class SchedulerTest extends FlatSpec {
  "A HasheWheelScheduler" should "schedule and cancel (non-)recurrent tasks" in {
    val s: Scheduler = new HashedWheelScheduler()

    @volatile var x: Int = 0
    @volatile var y: Int = 0

    val _ = s.schedule(100.millisecond) { x += 2 }
    val repeat = s.schedule(200.millisecond, true) { y += 2 }

    Thread.sleep(1000)
    repeat.cancel()
    Thread.sleep(500)

    assert(x == 2)
    assert( y >= 6 && y < 12)
    s.shutdown()
  }

  it must "ignore exceptions in scheduled tasks" in {
    val s: Scheduler = new HashedWheelScheduler()

    @volatile var x: Int = 0
    @volatile var y: Int = 0

    val _ = s.schedule(100.millisecond) {
      x += 2
      throw new RuntimeException("Thrown for testing")
    }

    val repeat = s.schedule(100.millisecond, true) {
      y += 2
      if (y == 4) throw new RuntimeException("Thrown for testing")
    }

    Thread.sleep(500)
    repeat.cancel()

    assert(x == 2)
    assert(y == 4)
    s.shutdown()
  }
}
