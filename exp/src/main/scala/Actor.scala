package np.conature.exp

import java.util.function.Consumer
import java.util.concurrent.RejectedExecutionException

import scala.util.control.NonFatal
import annotation.unchecked.uncheckedVariance
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.concurrent.Future
import np.conature.util.{ ConQueue, MpscQueue, Cancellable, Log }

trait Actor[-A, +R] {
  def !(message: A, repTo: Actor[R, Any] = Actor.empty): Unit

  def ?(message: A, timeout: Duration = Duration.Inf): Future[R]

  def terminate(): Unit

  @inline def isTerminated: Boolean
  @inline def isActive: Boolean
}

case class State[-A, +R](behavior: Behavior[A, R], reply: Option[R])
case class Mail[A, R](message: A, repTo: Actor[R, Any])

trait Behavior[-A, +R] {
  def apply(message: A): State[A, R]

  private[this] var inner: ActorImplementation[A, R] = null

  private[exp]
  def updateSelf(a: ActorImplementation[A @uncheckedVariance, R @uncheckedVariance]): Unit =
    inner = a

  def selfref: Actor[A, R] = inner
}

object Behavior {
  val nop = new Runnable { def run = () }
  val empty = new Behavior[Any, Nothing] {
    def apply(x: Any): State[Any, Nothing] = {
      println(s"Behavior.empty received: $x.")
      State(this, None)
    }
  }
}

private[exp] class ActorImplementation[-A, +R] private
  (private[this] var behavior: Behavior[A, R], val onError: Throwable => Unit)
  (val context: ActorContext)
extends JActor with Actor[A, R] { actorAR =>

  private[this] val mailbox: ConQueue[Mail[A, R]] = new MpscQueue[Mail[A, R]]()

  def !(message: A, repTo: Actor[R, Any] = Actor.empty): Unit = {
    mailbox.offer(Mail(message, repTo))
    trySchedule()
  }

  def ?(message: A, timeout: Duration = Duration.Inf): Future[R] =
    context.ask(actorAR, message, timeout)

  def terminate(): Unit = if (tryTerminate()) {
    if (unblock()) {
      behavior = Behavior.empty
      schedule()
    }
  }

  def isTerminated: Boolean = terminated == 1
  def isActive: Boolean = terminated == 0

  private def trySchedule(): Unit = if (unblock()) schedule()

  private def schedule(): Unit = {
    try {
      act.run()
    } catch {
      case e: RejectedExecutionException => println(e)
    }
  }

  private[this] val mboxAct = new Consumer[Mail[A, R]] {
    def accept(mail: Mail[A, R]): Unit =
      try {
        val state = behavior(mail.message)
        state.reply map { mail.repTo ! _ }
        if (isTerminated) {
          stayTerminated()
        } else if (state.behavior ne behavior) {
          behavior = state.behavior
          state.behavior.updateSelf(actorAR)
        }
      } catch { case ex: Throwable => () }
  }

  private def stayTerminated() : Unit =
    if (behavior ne Behavior.empty) {
      behavior = Behavior.empty
    }

  private val act = new Runnable {
    def run = {
      mailbox.batchConsume(16, mboxAct)
      if (mailbox.isLoaded) schedule()
      else {
        suspend()
        if (mailbox.isLoaded) trySchedule()
      }
    }
  }
}

private[exp] object ActorImplementation {
  def apply[A, R]
      (behavior: Behavior[A, R], onError: Throwable => Unit)
      (context: ActorContext): Actor[A, R] = {
    val actor = new ActorImplementation(behavior, onError)(context)
    behavior.updateSelf(actor)
    actor
  }
}

object Actor {
  private[exp] def apply[A, R]
      (behavior: Behavior[A, R], onError: Throwable => Unit = rethrow)
      (context: ActorContext): Actor[A, R] =
    ActorImplementation(behavior, onError)(context)

  val empty: Actor[Any, Nothing] = new Actor[Any, Nothing] {
    def !(message: Any, repTo: Actor[Nothing, Any] = Actor.empty) = ()

    def ?(message: Any, timeout: Duration = Duration.Inf): Future[Nothing] =
      Future.failed(new Exception("Actor.empty does not process message."))

    def terminate(): Unit = ()
    def isTerminated: Boolean = true
    def isActive: Boolean = false
  }

  val rethrow: Throwable => Unit = e => e match {
    case _: InterruptedException => throw e
    case NonFatal(e) => throw e
    case _: Throwable => throw e
  }
}
