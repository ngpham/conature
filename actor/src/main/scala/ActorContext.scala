package np.conature.actor

import java.util.concurrent.{ Callable, ExecutorService, ForkJoinPool }

trait ActorContext { context =>
  def executionPool: ExecutorService
  def strategy: Strategy
  def scheduler: Scheduler
  def create[A](behavior: Behavior[A], onError: Throwable => Unit = Actor.rethrow): Actor[A] = {
    Actor(behavior, onError)(context)
  }
  def shutdown(): Unit = {
    executionPool.shutdown()
    scheduler.shutdown()
  }
}

object ActorContext {
  def default(): ActorContext = new ActorContext {
    val executionPool = new ForkJoinPool()
    val strategy = Strategy.fromExecutorService(executionPool)
    val scheduler = new HashedWheelScheduler()
  }
}

trait Strategy {
  def apply[A](a: => A): () => A
}

object Strategy {
  def fromExecutorService(es: ExecutorService): Strategy = new Strategy {
    override def apply[A](a: => A): () => A = {
      val f = es submit (new Callable[A] { def call = a })
      () => f.get()
    }
  }
  def sequential: Strategy = new Strategy {
    override def apply[A](a: => A): () => A = {
      val r = a
      () => r
    }
  }
}
