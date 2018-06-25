package np.conature.actor

import scala.reflect.runtime.universe.TypeTag
import np.conature.util.EventBus

class EventBusActor extends EventBus[Actor] {
  def callback[T : TypeTag](handler: Actor[T], event: T): Unit = {
    if (handler.isTerminated)
      this.unsubscribe(handler)
    else
      handler ! event
  }
}
