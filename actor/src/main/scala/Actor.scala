package np.conature.actor

import java.util.function.Consumer
import java.util.concurrent.RejectedExecutionException
import scala.util.control.NonFatal
import scala.concurrent.duration.{ Duration, FiniteDuration }
import np.conature.util.{ ConQueue, MpscQueue, Cancellable, Log }

trait Actor[-A] {
  def !(message: A): Unit

  def terminate(): Unit

  @inline def isTerminated: Boolean
  @inline def isActive: Boolean
}

object Behavior {
  val nop = new Runnable { def run = () }

  val empty = new Behavior[Any] {
    def apply(x: Any): Behavior[Any] = {
      Log.info(Actor.logger, "Behavior.empty received: {0}", x)
      this
    }
  }

  val same = new Behavior[Any] {
    def apply(x: Any): Behavior[Any] = {
      Log.info(Actor.logger, "Behavior.same should NOT be invoked. Unhandled message: {0}", x)
      this
    }
  }
}

trait Behavior[-T] extends Function1[T, Behavior[T]] {
  private[this] var inner: ActorImplementation[T] = null

  // timeout is set/modified during Behavior construct, or during message processing,
  // thus is safe.
  private[actor] var timeoutAction: Runnable = Behavior.nop
  private[actor] var timeoutDuration: Duration = Duration.Undefined

  private[actor]
  def updateSelf(a: ActorImplementation[T @annotation.unchecked.uncheckedVariance]): Unit =
    inner = a

  def selfref = inner.asInstanceOf[Actor[T]]
  def context = inner.context
  def terminate(): Unit = inner.terminate()

  def setTimeout(duration: Duration)(callback: => Unit) = {
    if (timeoutAction ne Behavior.nop) inner.cancelTimeout()
    timeoutDuration = duration
    timeoutAction = new Runnable { def run = callback }
  }
  def removeTimeout(): Unit = {
    disableTimeout()
    timeoutAction = Behavior.nop
  }
  def disableTimeout(): Unit = {
    inner.cancelTimeout()
    timeoutDuration = Duration.Undefined
  }
  def enableTimeout(duration: FiniteDuration): Unit = { timeoutDuration = duration }
}

private[actor] final class ActorImplementation[-A] private
    (private[this] var behavior: Behavior[A], val onError: Throwable => Behavior[A])
    (val context: ActorContext)
extends JActor with Actor[A] { actorA =>

  private[this] val mailbox: ConQueue[A] = new MpscQueue[A]()
  private[this] var scheduledTimeout: Cancellable = null

  def !(a: A): Unit = { mailbox.offer(a); trySchedule() }

  def terminate(): Unit = if (tryTerminate()) {
    if (unblock()) {
      behavior = Behavior.empty
      schedule()
    }
  }

  def isTerminated: Boolean = terminated == 1
  def isActive: Boolean = terminated == 0

  def cancelTimeout(): Unit = if (scheduledTimeout ne null) {
    scheduledTimeout.cancel()
    scheduledTimeout = null
  }

  private def trySchedule(): Unit = if (unblock()) schedule()

  private def schedule(): Unit = {
    cancelTimeout()
    try {
      context.strategy.execute(act)
    } catch {
      case e: RejectedExecutionException =>
        Log.info(Actor.logger, "Failed to schedule actor {0}, error {1}", actorA, e)
    }
  }

  private def scheduleTimeout(): Unit = {
    if ((behavior.timeoutDuration.isFinite) && actorA.isActive && unblock()) {
      val _fd = behavior.timeoutDuration.asInstanceOf[FiniteDuration]

      scheduledTimeout = context.scheduler.schedule(_fd) {
        if (actorA.isActive && unblock()) {
          behavior.timeoutAction.run()
          suspend()
          if (mailbox.isEmpty) scheduleTimeout()
          else trySchedule()
        }
      }

      suspend()
      if (mailbox.isLoaded) trySchedule()
    }
  }

  private[this] val mboxAct = new Consumer[A] {
    def accept(a: A): Unit = {
      val b = try {
        behavior(a)
      } catch {
        case ex: Throwable => onError(ex)
      }

      if (isTerminated) {
        stayTerminated()
      } else if (b eq Behavior.empty) {
        terminate()
      } else if (b ne Behavior.same) {
        cancelTimeout()
        behavior = b
        b.updateSelf(actorA)
      }
    }
  }

  private def stayTerminated() : Unit =
    if (behavior ne Behavior.empty) {
      cancelTimeout()
      behavior = Behavior.empty
    }

  private val act = new Runnable {
    def run = {
      mailbox.batchConsume(16, mboxAct)
      if (mailbox.isLoaded) schedule()
      else {
        suspend()
        if (mailbox.isLoaded) trySchedule()
        else scheduleTimeout()
      }
    }
  }
}

private[actor] object ActorImplementation {
  def apply[A]
      (behavior: Behavior[A], onError: Throwable => Behavior[A])
      (context: ActorContext): Actor[A] = {
    val actor = new ActorImplementation(behavior, onError)(context)
    behavior.updateSelf(actor)
    actor.scheduleTimeout()
    actor
  }
}

object Actor {
  def apply[A]
      (behavior: Behavior[A], onError: Throwable => Behavior[A] = (_: Throwable) => Behavior.empty)
      (context: ActorContext): Actor[A] =
    ActorImplementation(behavior, onError)(context)

  // Thin adapter for ADT message, will be obsolete when Union Type is available in Scala 3.
  def contramap[A, B](a: Actor[A])(f: B => A): Actor[B] =
    new Actor[B] {
      def !(msg: B): Unit = a ! f(msg)
      def terminate() = a.terminate()
      @inline def isTerminated = a.isTerminated
      @inline def isActive = a.isActive
    }

  @deprecated("This function should not be used for Actor error handling.")
  val rethrow: Throwable => Unit = e => e match {
    case _: InterruptedException => throw e
    case NonFatal(e) => throw e
    case _: Throwable => throw e
  }

  val logger = Log.logger(classOf[Actor[_]].getName)
}
