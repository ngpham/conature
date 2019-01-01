package np.conature.actor

import java.util.concurrent.{ ExecutorService, ForkJoinPool, TimeoutException }
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration
import scala.concurrent.{ Future, Promise, ExecutionContext }
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
  def eventBus: EventBus[({type F[-X] = Actor[X, Any]})#F]

  private val registry = new mc.HashMap[String, (Any, Type)]

  def spawn[A, R](
      behavior: Behavior[A, R],
      onError: Throwable => Behavior[A, R] = Actor.onErrorTerminate): Actor[A, R] =
    Actor(behavior, onError)(context)

  def spawn[A](f: A => Unit): Actor[A, Nothing] =
    spawn(
      new Behavior[A, Nothing] {
        def receive(msg: A) = {
          f(msg)
          State.trivial
        }
      })

  def ask[A, B, X](
      actor: Actor[X, B],
      message: A,
      timeout: Duration = Duration.Inf)(implicit ev: A <:< X): Future[B]

  // Thin adapter: Actor[A, X] is adapted as Actor[B, Y] - which just performs forwarding.
  // The use case is rare, i.e. for a given sealed/final message type A, if we have to extend
  // or combine with others type: we create ADT i.e. Sum type B. See systest.TypedChat
  //
  // A better solution is to favor composition over interface adapting.
  // Or: Union Type, in Scala 3.
  //
  // A -----> X
  // ↑        |
  // |        ↓
  // B -----> Y
  def liftextend[A, X, B, Y](a: Actor[A, X])(f: B => A)(g: X => Y): Actor[B, Y]

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

private[actor] trait ActorContextPattern { context: ActorContext =>
  override def ask[A, B, X](
      actor: Actor[X, B],
      message: A,
      timeout: Duration = Duration.Inf)(implicit ev: A <:< X): Future[B] = {

    val prm = Promise[B]()
    val shortLife = context.spawn(new Behavior[Option[B], Nothing] {
      def receive(msg: Option[B]) = {
        msg match {
          case Some(m) => prm trySuccess m
          case None =>
            prm tryFailure (
              new Exception("ask() failed, likely due to terminated askee."))
        }
        selfref.terminate()
        State.trivial
      }

      setTimeout(timeout) {
        prm tryFailure (
          new TimeoutException(s"ask() failed to received a reply within $timeout"))
        selfref.terminate()
      }
    })

    actor.send(message, shortLife)
    prm.future
  }

  override def liftextend[A, X, B, Y](a: Actor[A, X])(f: B => A)(g: X => Y): Actor[B, Y] =
    new Actor[B, Y] {
      private[conature]
      def send(message: B, repTo: Actor[Option[Y], Any]): Unit = {
        // Valid, but not efficient, and not needed, since we handle ask() separately.
        //
        // val forwarder = new Actor[Option[X], Any] {
        //   private[actor]
        //   def send(msg: Option[X], dontcare: Actor[Option[Any], Any]): Unit = {
        //     repTo ! (msg map (g(_)))
        //   }
        //   def ?(msg: Option[X], timeout: Duration = Duration.Inf): Future[Any] =
        //     Future.failed[Any](new NonAskableException("The forwarder actor is not questionable!"))
        // }
        // a.send(f(message), forwarder)

        a.send(f(message), Actor.empty)
      }

      def ?(message: B, timeout: Duration = Duration.Inf): Future[Y] = {
        (a ? f(message)).map(g(_))(context.strategy)
      }

      @inline override def terminate() = a.terminate()

      @inline override def isTerminated = a.isTerminated
      @inline override def isActive = a.isActive
    }
}

object ActorContext {
  def createDefault(): ActorContext = new ActorContext with ActorContextPattern { ctx =>
    val executionPool = new ForkJoinPool()
    val strategy = ExecutionContext.fromExecutorService(executionPool)
    val eventBus = new EventBusActor()

    val scheduler = new HashedWheelScheduler(
      exceptionHandler =
        (t: Throwable) => Log.warn(ActorContext.logger, "Exception in timed task", t)
    )
  }

  val reuseThreadStrategy = new ExecutionContext {
    def execute(r: Runnable): Unit = r.run()

    def reportFailure(t: Throwable): Unit =
      Log.info(Actor.logger, "Error in reuseThreadStrategy Execution:", t)
  }

  val logger = Log.logger(classOf[ActorContext].getName)
}
