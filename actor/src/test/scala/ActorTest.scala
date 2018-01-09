package np.conature.testing

import org.scalatest.FlatSpec
import scala.concurrent.duration.{ DurationInt, Duration }
import np.conature.actor.{ Behavior, ActorContext, Actor }

case class Message(x: Int, peer: Actor[Message])

class TestBehavior extends Behavior[Message] {
  var timeoutCounter = 0
  var msgCount = 0
  var lastMsg: Message = null

  def apply(msg: Message) = {
    msgCount = msgCount + 1
    lastMsg = msg
    println(s"$selfref received: $msg")
    if (msg.peer ne null) msg.peer ! Message(msg.x, null)
    selfref ! Message(msg.x, msg.peer)
    if (msgCount > 2) {
      println("enable timeout")
      enableTimeout(1.second)
      msgCount = 0
    }
    this
  }

  setTimeout(Duration.Undefined) {
    timeoutCounter += 1
    println(s"last received message: $lastMsg. timeout counts: $timeoutCounter.")
    if (timeoutCounter > 3) {
      println("diable timeout.")
      disableTimeout()
    }
  }
}

class ActorTest extends FlatSpec {
  "A StateActor" should "handle timeouts and messages" in {
    val context = ActorContext.default()
    val a = context.create(new TestBehavior)
    val b = context.create(new TestBehavior)

    a ! Message(1, b)
    // Thread.sleep(2000)
    a ! Message(2, b)
    a ! Message(3, b)
    Thread.sleep(2000)
    // a ! Message(4, b)
    // Thread.sleep(2000)
    // a ! Message(5, b)
    // Thread.sleep(4000)
    a.terminate()
    b.terminate()
    context.shutdown()
  }
}
