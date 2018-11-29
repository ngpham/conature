package np.actortesting.exp

import org.scalatest.FlatSpec
import org.scalatest.Assertions._
import java.util.concurrent.{ CountDownLatch, TimeoutException }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import np.conature.exp.{ Behavior, ActorContext, Actor, State }

trait IRequest
trait IReply

case class Request(x: Int) extends IRequest
case class Reply(x: Int) extends IReply

trait ISubRequest
trait ISubReply

case class SubRequest(x: Int) extends ISubRequest
case class SubReply(x: Int) extends ISubReply


class RequestHandler extends Behavior[IRequest, IReply] {

  def apply(msg: IRequest) = msg match {
    case Request(x) => State(this, Some(Reply(2 * x)))
    case _ => State(this, None)
  }
}

class SubReplyBehavior extends Behavior[ISubReply, Any] {
  def apply(msg: ISubReply) = msg match {
    case _ => State(this, None)
  }
}

class AcceptAnyMessage extends Behavior[Any, Any] {
  def apply(msg: Any) = msg match {
    case _ => State(this, None)
  }
}

class ActorTest extends FlatSpec {

  "An Actor" should "be askable" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new RequestHandler)

    val fut = actor ? Request(3)

    assert((Await.result(fut, Duration.Inf) match {
      case Reply(x) => x
      case _ => 0
    }) == 6)

    context.stop()
  }

  "Actor Ask" should "type check at compilation" in {
    val context = ActorContext.createDefault()

    val a = context.spawn(new RequestHandler)
    val b = context.spawn(new SubReplyBehavior)
    val c = context.spawn(new AcceptAnyMessage)

    assertTypeError("a ! (Request(3), b)")

    assertCompiles("a ! (Request(3), c)")

    context.stop()
  }
}
