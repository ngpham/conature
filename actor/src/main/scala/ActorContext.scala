package np.conature.actor

import java.util.concurrent.{ Callable, ExecutorService, ForkJoinPool, RejectedExecutionException }
import scala.collection.{ mutable => mc }
import scala.language.dynamics
import scala.reflect.runtime.universe.{ TypeTag, typeOf, Type }

trait Extension {
  def stop(): Unit
  def start(): Unit
}

trait ActorContext extends Dynamic { context =>
  private val registry = new mc.HashMap[String, (Any, Type)]

  def executionPool: ExecutorService
  def strategy: Strategy
  def scheduler: Scheduler
  def spawn[A](behavior: Behavior[A], onError: Throwable => Unit = Actor.rethrow): Actor[A] = {
    Actor(behavior, onError)(context)
  }

  def selectDynamic[T: TypeTag](name: String): T = registry.get(name) match {
    case None => throw new ClassNotFoundException("Cannot find a matched registered instance")
    case Some(vt) =>
      if (vt._2 <:< typeOf[T]) vt._1.asInstanceOf[T]
      else throw new ClassNotFoundException("Cannot find a matched registered instance")
  }

  // cannot use udpateDynamic[T], scalac rewrites T to Nothing
  def register[T <: Extension : TypeTag](name: String)(value: T): Unit = {
    registry(name) = (value, typeOf[T])
  }

  def start(): Unit = {
    for ((_, vt) <- registry) {
      vt._1.asInstanceOf[Extension].start()
    }
  }

  def stop(): Unit = {
    scheduler.shutdown()
    for ((_, vt) <- registry) {
      vt._1.asInstanceOf[Extension].stop()
    }
    executionPool.shutdown()
    postStop()
  }

  def postStop: () => Unit
}

object ActorContext {
  def createDefault(postStopHook: => Unit = ()): ActorContext = new ActorContext {
    val executionPool = new ForkJoinPool()
    val strategy = Strategy.fromExecutorService(executionPool)
    val scheduler = new HashedWheelScheduler()
    val postStop = () => postStopHook
  }
}

trait Strategy {
  def apply(a: => Unit): Unit
}

object Strategy {
  def fromExecutorService(es: ExecutorService): Strategy = new Strategy {
    override def apply(a: => Unit): Unit= {
      try {
        es execute (new Runnable { def run() = a })
      } catch {
        case e: RejectedExecutionException => println(e)
      }
    }
  }

  def sequential: Strategy = new Strategy {
    override def apply(a: => Unit): Unit = a
  }
}
