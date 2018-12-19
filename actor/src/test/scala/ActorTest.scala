package np.actortesting

import org.scalatest.FlatSpec
import org.scalatest.Assertions.{ assertThrows }
import java.util.concurrent.{ CountDownLatch, TimeoutException }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import np.conature.actor.{ Behavior, State, ActorContext, Actor }

trait IRequest
trait IReply

case class Request(x: Int) extends IRequest
case class Reply(x: Int) extends IReply

trait ISubRequest
trait ISubReply

// case class SubRequest(x: Int) extends ISubRequest
// case class SubReply(x: Int) extends ISubReply

class RequestHandler extends Behavior[IRequest, IReply] {
  def receive(msg: IRequest) = msg match {
    case Request(x) =>
      State(Behavior.same, Some(Reply(x * 2)))
    case _ =>
      State.trivial
  }
}

// class SubReplyBehavior extends Behavior[ISubReply, Any] {
//   def receive(msg: ISubReply) = msg match {
//     case _ => State.trivial
//   }
// }

// class AcceptAnyMessage extends Behavior[Any, Any] {
//   def receive(msg: Any) = msg match {
//     case _ => State.trivial
//   }
// }

class AskFailureRequestHandler extends Behavior[Request, Reply] {
  def receive(msg: Request) = {
    State.trivial
  }
}

class LongRunningRequestHandler extends Behavior[Request, Reply] {
  def receive(msg: Request) = {
    Thread.sleep(1000)  // sensitive for testing timeout
    State.trivial
  }
}

case class Simple(x: Int)

class SimpleBehavior extends Behavior[Simple, Nothing] {
  def receive(msg: Simple) = {
    println(s"received msg: $msg")
    State.trivial
  }
}

case class PingPongMsg(sender: Actor[PingPongMsg, Nothing], x: Int)

class PingPongBehavior(val limit: Int, val latch: CountDownLatch)
extends Behavior[PingPongMsg, Nothing] {
  var i = 0
  def receive(msg: PingPongMsg) = {
    i = msg.x
    if (msg.x < limit)
      msg.sender ! PingPongMsg(selfref, msg.x + 1)
    State.trivial
  }

  def destruct(): Unit = {
    try {
      // FixMe: with the current Scheduler.exceptionHandler, the exception does not have a chance
      // to propagate to test report.
      assert((i == limit) || (i == limit - 1))
    } finally {
      latch.countDown()
      selfref.terminate()
    }
  }

  setTimeout(Duration("500ms")) { destruct() }
}

case class GenericMessage[T](x: T)

class GenericBehavior[T](latch: CountDownLatch) extends Behavior[GenericMessage[T], Nothing] {
  def receive(x: GenericMessage[T]) = {
    latch.countDown()
    State.trivial
  }
}

class ActorTest extends FlatSpec {
  "An Actor" should "be askable" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new RequestHandler)

    val fut: Future[IReply] = actor ? Request(3)

    assert((Await.result(fut, Duration.Inf) match {
      case Reply(x) => x
      case _ => 0
    }) == 6)

    context.stop()
  }

  "Asking an Actor" should "timeout somewhat properly" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new LongRunningRequestHandler)

    val fut: Future[Reply] = actor ? (Request(3), Duration("300ms"))
    assertThrows[TimeoutException] {
      val _ = Await.result(fut, Duration("600ms"))
    }
    context.stop()
  }

  "An Actor without message timeout" should "terminate() properly when invoked externally" in {
    val latch = new CountDownLatch(1)

    val context = ActorContext.createDefault()
    @volatile var sum = 0

    val actor = context.spawn((msg: Simple) => {
      sum += msg.x
      if (msg.x == 4) latch.countDown()
    })

    for (i <- (1 to 4)) { actor ! Simple(i) }
    latch.await()
    actor.terminate()
    for (i <- (5 to 8)) { actor ! Simple(i) }
    context.stop()
    assert(sum == 10)
  }

  "An Actor without message timeout" should "terminate() properly when invoked internally" in {
    val latch = new CountDownLatch(1)

    val context = ActorContext.createDefault()
    @volatile var sum = 0

    val actor = context.spawn(new Behavior[Simple, Nothing] {
      def receive(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { selfref.terminate(); latch.countDown() }
        State.trivial
      }
    })

    for (i <- (1 to 8)) { actor ! Simple(i) }
    latch.await()
    context.stop()
    assert(sum == 10)
  }

  "An Actor with message timeout" should "terminate() properly when invoked internally" in {
    val latch = new CountDownLatch(1)

    val context = ActorContext.createDefault()
    @volatile var sum = 0

    val actor = context.spawn(new Behavior[Simple, Any] {
      def receive(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { selfref.terminate(); latch.countDown() }
        State.trivial
      }

      setTimeout(Duration("50ms")) { sum = sum * 2 }
    })

    for (i <- (1 to 8)) { actor ! Simple(i) }
    latch.await()
    context.stop()
    assert(sum == 10)
  }

  "An Actor with message timeout" should "terminate() properly when invoked externally" in {
    val latch = new CountDownLatch(1)

    val context = ActorContext.createDefault()
    @volatile var sum = 0

    val actor = context.spawn(new Behavior[Simple, Nothing] {
      def receive(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { selfref.terminate(); latch.countDown() }
        State.trivial
      }

      setTimeout(Duration("50ms")) { sum = sum * 2 }
    })

    for (i <- (1 to 8)) { actor ! Simple(i) }
    Thread.sleep(100)
    latch.await()
    actor.terminate()
    context.stop()
    assert(sum == 10)
  }

  "Actor with message timeout" should "terminate() properly when invoked by timeout action" in {
    val latch = new CountDownLatch(2)
    val context = ActorContext.createDefault()

    val a = context.spawn(new PingPongBehavior(4, latch))
    val b = context.spawn(new PingPongBehavior(4, latch))
    a ! PingPongMsg(b, 0)
    latch.await()
    context.stop()
  }

  "Actor ask()" should "timeout somewhat properly by Await on Future" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new LongRunningRequestHandler)

    val fut: Future[Reply] = actor ? (Request(3), Duration("600ms"))

    assertThrows[TimeoutException] {
      val _ = Await.result(fut, Duration("300ms"))
    }

    context.stop()
  }

  "Actor ask()" should "failed fast when askee does not implement reply message" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new AskFailureRequestHandler)

    val fut: Future[Reply] = actor ? Request(3)

    assertThrows[Exception] {
      val _ = Await.result(fut, Duration.Inf)
    }

    context.stop()
  }

  "A generic Actor" should "handle messages properly" in {
    val latch = new CountDownLatch(4)

    val context = ActorContext.createDefault()
    val a = context.spawn(new GenericBehavior[Int](latch))
    val b = context.spawn(new GenericBehavior[String](latch))

    a ! GenericMessage(4)
    b ! GenericMessage("tara")

    a ! GenericMessage(3)
    b ! GenericMessage("msg")

    latch.await()
    context.stop()
  }

  "liftextend-ed Actor" should "handle messages properly" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new RequestHandler)

    val lea: Actor[Int, Int] = context.liftextend(actor)(
      (x: Int) => Request(x))(
      (ir: IReply) => ir match {
        case Reply(x) => x
      })

    lea ! 4
    lea ! 5

    val fut: Future[Int] = lea ? 3

    assert(Await.result(fut, Duration.Inf) == 6)

    context.stop()
  }

}
