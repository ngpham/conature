package np.conature.actor

import java.util.concurrent.{ ExecutorService, ForkJoinPool }
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration
import scala.concurrent.{ Future, ExecutionContext }
import scala.language.dynamics
import scala.reflect.runtime.universe.{ TypeTag, typeOf, Type }
import np.conature.util.{ EventBus, Scheduler, HashedWheelScheduler, Log }

trait Extension {
  def stop(): Unit
  def start(): Unit
}

trait ActorContext extends Dynamic { context =>
  def executionPool: ExecutorService
  def strategy: ExecutionContext
  def scheduler: Scheduler
  def eventBus: EventBus[Actor]

  private val registry = new mc.HashMap[String, (Any, Type)]

  def ask[A, B](
      actor: Actor[A],
      message: Actor[B] => A,
      timeout: Duration = Duration.Inf): Future[B]

  def spawn[A](behavior: Behavior[A], onError: Throwable => Unit = Actor.rethrow): Actor[A] =
    Actor(behavior, onError)(context)

  def spawn[A](f: A => Unit): Actor[A] =
    spawn(
      new Behavior[A] {
        def apply(a: A) = {
          f(a)
          this
        }
      })

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
  }
}

object ActorContext {
  def createDefault(): ActorContext = new ActorContext {
    val executionPool = new ForkJoinPool()
    val strategy = ExecutionContext.fromExecutorService(executionPool)
    val eventBus = new EventBusActor()

    val scheduler = new HashedWheelScheduler(
      exceptionHandler =
        (t: Throwable) => Log.warn(ActorContext.logger, "Exception in timed task", t)
    )

    private val broker = new Broker(this)

    def ask[A, B](
        actor: Actor[A],
        message: Actor[B] => A,
        timeout: Duration = Duration.Inf): Future[B] =
      broker.ask(actor, message, timeout)
  }

  val reuseThreadStrategy = new ExecutionContext {
    def execute(r: Runnable): Unit = r.run()

    def reportFailure(t: Throwable): Unit =
      Log.info(Actor.logger, "Error in reuseThreadStrategy Execution:", t)
  }

  val logger = Log.logger(classOf[ActorContext].getName)
}
