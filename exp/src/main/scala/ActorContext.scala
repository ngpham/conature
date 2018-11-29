package np.conature.exp

import java.util.concurrent.{ ExecutorService, ForkJoinPool }
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration
import scala.concurrent.{ Future, ExecutionContext }
import scala.language.dynamics
import scala.reflect.runtime.universe.{ TypeTag, typeOf, Type }
import np.conature.util.{ EventBus, Scheduler, HashedWheelScheduler, Log }

trait ActorContext extends Dynamic { context =>
  def executionPool: ExecutorService
  def strategy: ExecutionContext

  def ask[A, B, X >: A](
      actor: Actor[X, B],
      message: A,
      timeout: Duration = Duration.Inf): Future[B]

  def spawn[A, R](
      behavior: Behavior[A, R],
      onError: Throwable => Unit = Actor.rethrow): Actor[A, R] =
    Actor(behavior, onError)(context)

  def spawn[A](f: A => Unit): Actor[A, Nothing] =
    spawn(
      new Behavior[A, Nothing] {
        def apply(a: A) = {
          f(a)
          State(this, None)
        }
      })

  def start(): Unit = ()

  def stop(): Unit = {
    executionPool.shutdown()
  }
}

object ActorContext {
  def createDefault(): ActorContext = new ActorContext {
    val executionPool = new ForkJoinPool()
    val strategy = ExecutionContext.fromExecutorService(executionPool)

    private val broker = new Broker(this)

    def ask[A, B, X >: A](
        actor: Actor[X, B],
        message: A,
        timeout: Duration): Future[B] =
      broker.ask(actor, message, timeout)
  }

  val reuseThreadStrategy = new ExecutionContext {
    def execute(r: Runnable): Unit = r.run()

    def reportFailure(t: Throwable): Unit = println(t)
  }
}
