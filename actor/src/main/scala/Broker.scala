package np.conature.actor

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration
import scala.concurrent.{ Promise, Future }

private[actor] class Broker(val ctx: ActorContext) {
  def ask[A, B](
      actor: Actor[A],
      message: Actor[B] => A,
      timeout: Duration): Future[B] = {
    val prm = Promise[B]()
    val shortLife = ctx.spawn(new Behavior[B] {
      def apply(msg: B) = {
        prm trySuccess msg
        terminate()
        this
      }
      setTimeout(timeout) {
        prm tryFailure (new TimeoutException(s"ask() failed to received a reply within $timeout"))
        terminate()
      }
    })

    actor ! message(shortLife)
    prm.future
  }
}
