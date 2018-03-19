package np.conature.actortesting

import org.scalatest.FlatSpec
import scala.concurrent.duration._
import np.conature.actor.{ Scheduler, HashedWheelScheduler, Cancellable }

class SchedulerTest extends FlatSpec {
  "A HasheWheelScheduler" should "schedule and cancel tasks" in {
    val s: Scheduler = new HashedWheelScheduler()

    s.schedule(100.millisecond){ println("100ms") }
    s.schedule(200.millisecond){ println("200ms") }
    val task = s.schedule(300.millisecond)(println("300ms"))
    s.schedule(2.second)(println("2 secs"))

    task.cancel()

    Thread.sleep(3000)
    s.shutdown()
  }
}
