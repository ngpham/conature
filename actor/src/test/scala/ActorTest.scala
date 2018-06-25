package np.actortesting

import org.scalatest.FlatSpec
import org.scalatest.Assertions._
import java.util.concurrent.{ CountDownLatch, TimeoutException }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import np.conature.actor.{ Behavior, ActorContext, Actor }

case class Request(x: Int, repTo: Actor[Reply])
case class Reply(x: Int)

class RequestHandler extends Behavior[Request] {
  def apply(msg: Request) = {
    msg.repTo ! Reply(2 * msg.x)
    this
  }
}

class LongRunningRequestHandler extends Behavior[Request] {
  def apply(msg: Request) = {
    // silently ignore the request
    this
  }
}

case class Simple(x: Int)

class SimpleBehavior extends Behavior[Simple] {
  def apply(msg: Simple) = {
    println(s"received msg: $msg")
    this
  }
}

case class PingPongMsg(sender: Actor[PingPongMsg], x: Int)

class PingPongBehavior(val limit: Int, val latch: CountDownLatch) extends Behavior[PingPongMsg] {
  var i = 0
  def apply(msg: PingPongMsg) = {
    i = msg.x
    if (msg.x < limit)
      msg.sender ! PingPongMsg(selfref, msg.x + 1)
    this
  }
  def destruct(): Unit = {
    try {
      // FixMe: for a sane Scheduler.exceptionHandler, the exception does not have a chance to
      // propagate to test report.
      assert((i == limit) || (i == limit - 1))
    } finally {
      latch.countDown()
      terminate()
    }
  }
  setTimeout(Duration("500ms")) { destruct() }
}

case class GenericMessage[T](x: T)

class GenericBehavior[T](latch: CountDownLatch) extends Behavior[GenericMessage[T]] {
  def apply(x: GenericMessage[T]): GenericBehavior[T] = {
    latch.countDown()
    this
  }
}

class ActorTest extends FlatSpec {
  "An Actor" should "be askable" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new RequestHandler)

    val fut: Future[Reply] = context.ask(actor, Request(3, _))

    assert(Await.result(fut, Duration.Inf).x == 6)
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

    val actor = context.spawn(new Behavior[Simple] {
      def apply(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { terminate(); latch.countDown() }
        this
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

    val actor = context.spawn(new Behavior[Simple] {
      def apply(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { terminate(); latch.countDown() }
        this
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

    val actor = context.spawn(new Behavior[Simple] {
      def apply(msg: Simple) = {
        sum += msg.x
        if (msg.x == 4) { terminate(); latch.countDown() }
        this
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

  "ActorContext.ask()" should "timeout somewhat properly by the asked-actor" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new LongRunningRequestHandler)

    val fut: Future[Reply] = context.ask(actor, Request(3, _), Duration("300ms"))
    assertThrows[TimeoutException] {
      val _ = Await.result(fut, Duration("600ms")).x
    }
    context.stop()
  }

  "ActorContext.ask()" should "timeout somewhat properly by future await" in {
    val context = ActorContext.createDefault()
    val actor = context.spawn(new LongRunningRequestHandler)

    val fut: Future[Reply] = context.ask(actor, Request(3, _), Duration("600ms"))
    assertThrows[TimeoutException] {
      val _ = Await.result(fut, Duration("300ms")).x
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
}
