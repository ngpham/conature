
package np.conature.utiltesting.scheduler

import org.scalatest.FlatSpec
import org.scalatest.Assertions.{ assertCompiles }
import scala.reflect.runtime.universe.TypeTag

import np.conature.util.EventBus

trait Handler[-T] {
  def handle(event: T): Unit
}

trait InvariantHanlder[T] {
  def handle(event: T): Unit
}

class StringHandler extends Handler[String] {
  override def handle(event: String): Unit = ()
}

class MapHandler extends Handler[Map[Int, Int]] {
  override def handle(event: Map[Int, Int]): Unit = ()
}

class EventBusClass extends EventBus[Handler] {
  def callback[T : TypeTag](handler: Handler[T], event: T): Unit = {
    handler.handle(event)
  }
}

class EventBusTest extends FlatSpec {
  "EventBus" should "be specialized" in {
    val bus = new EventBusClass

    bus.subscribe(new StringHandler)
    bus.subscribe(new MapHandler)

    bus.publish("tara")
    bus.publish(Map(3 -> 4))
  }

  "EventBus" should "require contravariance in specialization" in {
    // Bug in ScalaTest? This code should not compile, due to kind of types validation failed.
    assertCompiles("""
      class FailToCompile extends EventBus[InvariantHanlder] {
        def callback[T : TypeTag](handler: InvariantHanlder[T], event: T): Unit = {
          handler.handle(event)
        }
      }
    """)
  }
}
