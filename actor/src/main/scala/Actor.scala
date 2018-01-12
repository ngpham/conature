package np.conature.actor

import java.util.concurrent.Callable
import java.util.function.Consumer

import scala.util.control.NonFatal
import scala.concurrent.duration.{ Duration, FiniteDuration }

private[conature] trait ActorInner {
  protected[conature] def terminate(): Unit
  protected[conature] def cancelTimeout(): Unit
}

trait Behavior[-T] extends Function1[T, Behavior[T]] {
  protected[this] var selfref: Actor[T] = null

  private var _timeoutAction: Callable[Unit] = null
  private var _timeoutDuration: Duration = Duration.Undefined

  def timeoutAction = _timeoutAction
  def timeoutDuration = _timeoutDuration
  def timeoutAction_=(c: Callable[Unit]): Unit = _timeoutAction = c
  def timeoutDuration_=(d: Duration): Unit = _timeoutDuration = d

  private[conature] def updateSelf(a: ActorInner): Unit = { selfref = a.asInstanceOf[Actor[T]] }

  def terminate(): Unit = selfref.terminate()

  def setTimeout(duration: Duration)(callback: => Unit) = {
    timeoutDuration = duration
    timeoutAction = new Callable[Unit] { def call = callback }
  }
  def removeTimeout(): Unit = {
    disableTimeout()
    timeoutAction = null
  }
  def disableTimeout(): Unit = {
    selfref.cancelTimeout()
    timeoutDuration = Duration.Undefined
  }
  def enableTimeout(duration: FiniteDuration): Unit = {
    assert(timeoutAction ne null)
    timeoutDuration = duration
  }
}

final class Actor[-A] private
    (private[this] var behavior: Behavior[A], onError: Throwable => Unit)
    (context: ActorContext) extends JActor with ActorInner { actorA =>
  private[this] val mailbox: ConQueue[A] = new MpscQueue[A]()
  private[this] var scheduledTimeout: Cancellable = null

  @volatile private var terminated: Boolean = false

  def !(a: A): Unit = { mailbox.offer(a); trySchedule() }
  def apply(a: A): Unit = this ! a

  override def terminate(): Unit = { terminated = true; cancelTimeout() }
  def isTerminated: Boolean = terminated
  def isActive: Boolean = !terminated

  def contramap[B](f: B => A): Actor[B] =
    Actor(new Behavior[B] {
      def apply(b: B) = { actorA ! f(b); this }
    }, onError)(context)

  override protected[conature] def cancelTimeout(): Unit = if (scheduledTimeout ne null) {
    scheduledTimeout.cancel()
    scheduledTimeout = null
  }

  private def trySchedule(): Unit = if (unblock()) schedule()

  private def schedule(): Unit = {
    cancelTimeout()
    context.strategy(act())
    ()
  }

  private def scheduleTimeout(): Unit = {
    if ((behavior.timeoutDuration.isFinite) && actorA.isActive && unblock()) {
      val _fd = behavior.timeoutDuration.asInstanceOf[FiniteDuration]
      scheduledTimeout = context.scheduler.schedule(_fd) {
        if (unblock()) {
          behavior.timeoutAction.call()
          suspend()
          if (mailbox.isEmpty) scheduleTimeout()
          else trySchedule()
        }
      }

      suspend()
      if (mailbox.isLoaded) trySchedule()
    }
  }

  private[this] val _mboxAct = new Consumer[A] {
    def accept(a: A): Unit =
      if (!terminated)
        try {
          val _b = behavior(a)
          if (_b ne behavior) { cancelTimeout(); behavior = _b }
        } catch { case ex: Throwable => onError(ex) }
  }

  private def act(): Unit = {
    mailbox.batchConsume(16, _mboxAct)

    if (mailbox.isLoaded) schedule()
    else {
      suspend()
      if (mailbox.isLoaded) trySchedule()
      else scheduleTimeout()
    }
  }
}

object Actor {
  def apply[A]
      (behavior: Behavior[A], onError: Throwable => Unit = Actor.rethrow)
      (context: ActorContext): Actor[A] = {
    val actor = new Actor(behavior, onError)(context)
    behavior.updateSelf(actor)
    actor.scheduleTimeout()
    actor
  }

  val rethrow: Throwable => Unit = e => e match {
    case _: InterruptedException => throw e
    case NonFatal(e) => throw e
    case _ => throw e
  }
}
