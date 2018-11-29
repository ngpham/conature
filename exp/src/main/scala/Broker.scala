package np.conature.exp

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration
import scala.concurrent.{ Promise, Future }

private[exp] class Broker(val ctx: ActorContext) {
  def ask[A, B, X >: A](
      actor: Actor[X, B],
      message: A,
      timeout: Duration): Future[B] = {

    val prm = Promise[B]()

    val shortLife = ctx.spawn(new Behavior[B, Nothing] {
      def apply(msg: B) = {
        prm trySuccess msg
        selfref.terminate()
        State(this, None)
      }
    })

    actor ! (message, shortLife)
    prm.future
  }
}
