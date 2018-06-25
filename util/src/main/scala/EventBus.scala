
package np.conature.util

import scala.reflect.runtime.universe.{ TypeTag, typeOf, Type }
import scala.collection.mutable.{ SortedSet => MSortedSet, Map => MMap }
import scala.language.higherKinds

object EventBus {
  val identityOrdering = new Ordering[AnyRef] {
    def compare(x: AnyRef, y: AnyRef): Int = {
      if (x eq y) 0
      else if (System.identityHashCode(x) < System.identityHashCode(y)) -1
      else 1 // We believe in identityHashCode()
    }
  }
}

final case class DeadEvent(event: Any)

trait EventBus[F[-_]] {
  // F[_] instances are free to override hashCode(), thus, we ensure uniqueness,
  // required for Set, by using System.identityHashCode()
  protected val reg = MMap.empty[Type, MSortedSet[F[_]]]

  def subscribe[T: TypeTag](handler: F[T]): Unit = reg.synchronized {
    val ss = reg.getOrElseUpdate(
      typeOf[T],
      MSortedSet.empty[F[_]](EventBus.identityOrdering.asInstanceOf[Ordering[F[_]]]))
    ss += handler
    ()
  }

  def unsubscribe[T: TypeTag](handler: F[T]): Unit = reg.synchronized {
    reg.get(typeOf[T]) map { ss =>
      ss -= handler
      if (ss.isEmpty) reg -= typeOf[T]
    }
    ()
  }

  def publish[T : TypeTag](event: T): Unit = {
    var unhandled = true
    for ((k, v) <- reg)
      if (typeOf[T] <:< k)
        for (h <- v) {
          unhandled = false
          callback(h.asInstanceOf[F[T]], event)
        }
    if (unhandled && !event.isInstanceOf[DeadEvent])
      publish(DeadEvent(event))
  }

  def callback[T : TypeTag](handler: F[T], event: T): Unit
}
