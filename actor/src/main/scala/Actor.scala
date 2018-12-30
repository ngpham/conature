package np.conature.actor

import java.util.function.Consumer
import java.util.concurrent.RejectedExecutionException
import scala.util.control.NonFatal
import scala.concurrent.Future
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.{ Duration, FiniteDuration }
import np.conature.util.{ ConQueue, MpscQueue, Cancellable, Log, Misc }

trait Actor[-A, +R] {
  def !(message: A): Unit = send(message, Actor.empty)

  private[conature] def send(message: A, repTo: Actor[Option[R], Any]): Unit

  // Safety: if actor is terminated, Future should fail fast
  def ?(message: A, timeout: Duration = Duration.Inf): Future[R]

  def terminate(): Unit = ()

  @inline def isTerminated: Boolean = true
  @inline def isActive: Boolean = false
}

class NonAskableException(msg: String) extends Exception(msg: String) {}

trait NonAskableActor[-A] extends Actor[A, Nothing] {
  def ?(message: A, timeout: Duration = Duration.Inf): Future[Nothing] =
    Future.failed[Nothing](
      new NonAskableException(s"NonAskableActor $this is not questionable!"))
}

case class State[-A, +R](behavior: Behavior[A, R], reply: Option[R])

object State {
  val trivial = State[Any, Nothing](Behavior.same, None)
}

object Behavior {
  val empty = new Behavior[Any, Nothing] {
    def receive(x: Any): State[Any, Nothing] = {
      // Since ask() is safe, we should not litter the logging of message sent to
      // terminated actor.
      // FIXME: Make this configurable.
      // Log.info(Actor.logger, "Behavior.empty received: {0}", x)
      State.trivial
    }
  }

  val same: Behavior[Any, Nothing] = new Behavior[Any, Nothing] {
    def receive(x: Any): State[Any, Nothing] = {
      Log.info(Actor.logger, "Behavior.same should NOT be invoked. Unhandled message: {0}", x)
      State(same, None)
    }
  }
}

trait Behavior[-A, +R] {
  def receive(message: A): State[A, R]

  private[this] var inner: ActorImplementation[A, R] = null

  // timeout is set/modified during Behavior construct, or during message processing,
  // thus is safe.
  private[actor] var timeoutAction: () => Unit = Misc.Nop
  private[actor] var timeoutDuration: Duration = Duration.Undefined

  private[actor]
  def updateSelf(a: ActorImplementation[A @uncheckedVariance, R @uncheckedVariance]): Unit =
    inner = a

  def selfref: Actor[A, R] = inner
  def context = inner.context

  def setTimeout(duration: Duration)(callback: => Unit) = {
    if (timeoutAction ne Misc.Nop) inner.cancelTimeout()
    timeoutDuration = duration
    timeoutAction = () => callback
  }
  def removeTimeout(): Unit = {
    disableTimeout()
    timeoutAction = Misc.Nop
  }
  def disableTimeout(): Unit = {
    inner.cancelTimeout()
    timeoutDuration = Duration.Undefined
  }
  def enableTimeout(duration: FiniteDuration): Unit = { timeoutDuration = duration }
}

private[actor] case class Mail[A, R](message: A, repTo: Actor[Option[R], Any])

private[actor] final class ActorImplementation[-A, +R] private
    (private[this] var behavior: Behavior[A, R], val onError: Throwable => Behavior[A, R])
    (val context: ActorContext)
extends JActor with Actor[A, R] { actorAR =>

  private[this] val mailbox: ConQueue[Mail[A, R]] = new MpscQueue[Mail[A, R]]()
  private[this] var scheduledTimeout: Cancellable = null

  private[conature] def send(message: A, repTo: Actor[Option[R], Any]): Unit = {
    mailbox.offer(Mail(message, repTo))
    trySchedule()
  }

  def ?(message: A, timeout: Duration = Duration.Inf): Future[R] =
    context.ask(actorAR, message, timeout)

  override def terminate(): Unit = if (tryTerminate()) {
    if (unblock()) {
      behavior = Behavior.empty
      schedule()
    }
  }

  @inline override def isTerminated: Boolean = terminated == 1
  @inline override def isActive: Boolean = terminated == 0

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
        Log.info(Actor.logger, "Failed to schedule actor {0}, error {1}", actorAR, e)
    }
  }

  private def scheduleTimeout(): Unit = {
    if ((behavior.timeoutDuration.isFinite) && actorAR.isActive && unblock()) {
      val _fd = behavior.timeoutDuration.asInstanceOf[FiniteDuration]

      scheduledTimeout = context.scheduler.schedule(_fd) {
        if (actorAR.isActive && unblock()) {
          behavior.timeoutAction()
          suspend()
          if (mailbox.isEmpty) scheduleTimeout()
          else trySchedule()
        }
      }

      suspend()
      if (mailbox.isLoaded) trySchedule()
    }
  }

  private[this] val mboxAct: Consumer[Mail[A, R]] = (mail: Mail[A, R]) => {
    val state = try {
      behavior.receive(mail.message)
    } catch {
      case ex: Throwable => State(onError(ex), None)
    }

    mail.repTo ! state.reply

    if (isTerminated) {
      stayTerminated()
    } else if (state.behavior eq Behavior.empty) {
      terminate()
    } else if ((state.behavior ne Behavior.same) && (state.behavior ne behavior)) {
      cancelTimeout()
      state.behavior.updateSelf(actorAR)
      behavior = state.behavior
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
  def apply[A, R]
      (behavior: Behavior[A, R], onError: Throwable => Behavior[A, R])
      (context: ActorContext): Actor[A, R] = {
    val actor = new ActorImplementation(behavior, onError)(context)
    behavior.updateSelf(actor)
    actor.scheduleTimeout()
    actor
  }
}

object Actor {
  private[actor] def apply[A, R]
      (behavior: Behavior[A, R], onError: Throwable => Behavior[A, R])
      (context: ActorContext): Actor[A, R] =
    ActorImplementation(behavior, onError)(context)

  val empty: NonAskableActor[Any] = new NonAskableActor[Any] {
    private[conature]
    def send(message: Any, repTo: Actor[Option[Nothing], Any]) = ()

    override def ?(message: Any, timeout: Duration = Duration.Inf): Future[Nothing] =
      Future.failed(new NonAskableException("Actor.empty is not questionable!"))
  }

  // Log the exception, then terminate the actor
  val onErrorTerminate: Throwable => Behavior[Any, Nothing] = e => e match {
    case _: InterruptedException | NonFatal(_) | _: Throwable =>
      Log.error(logger, "Exception in actor behavior:", e)
      Behavior.empty
  }

  val logger = Log.logger(classOf[Actor[_, _]].getName)
}
