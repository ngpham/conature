package np.conature.exp

import scala.reflect.runtime.universe.TypeTag
import np.conature.util.EventBus

class EventBusActor extends EventBus[({type F[-T] = Actor[T, Any]})#F] {
  def callback[T : TypeTag](handler: Actor[T, Any], event: T): Unit = {
    if (handler.isTerminated)
      this.unsubscribe(handler)
    else
      handler ! event
  }
}
