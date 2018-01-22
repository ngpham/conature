
package np.conature.systest;

import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.NetworkService

import java.util.concurrent.CountDownLatch

sealed trait Message extends Serializable

@SerialVersionUID(1L)
case class Hello(x: Int) extends Message

@SerialVersionUID(1L)
case class Begin(partner: Actor[Message], cdl: CountDownLatch) extends Message

class PendingAct extends Behavior[Message] {
  def apply(msg: Message): Behavior[Message] = msg match {
    case Begin(partner, cdl) =>
      new PingPongAct(partner, cdl)
    case _ =>
      println("I have no idea.")
      this
  }
}

class PingPongAct(val partner: Actor[Message], val cdl: CountDownLatch)
extends Behavior[Message] {
  var tries: Int = 5
  def apply(msg: Message): Behavior[Message] = msg match {
    case Hello(x) =>
      println(s"I got this $msg.")
      if (x <= 10) partner ! Hello(x + 2)
      tries -= 1
      if (tries == 0) { cdl.countDown(); terminate() }
      this
    case _ =>
      println("I have no idea.")
      this
  }
}

// Sample run on localhost:
// jvm1: np.conature.systest.Peer 8888 9999
// jvm2: np.conature.systest.Peer 9999 8888

object Peer {
  def main(args: Array[String]): Unit = {
    val localAdr = s"cnt://locahost:${args(0)}"
    val context = ActorContext.createDefault()
    val ns = NetworkService(context, localAdr)

    ns.start()

    val a = context.create(new PendingAct)
    ns.register("theactor", a)

    val b = ns.locate[Message](s"cnt://theactor@localhost:${args(1)}").get
    val cdl = new CountDownLatch(1)

    a ! Begin(b, cdl)
    Thread.sleep(2000)

    if (args(0) < args(1)) b ! Hello(0)
    else b ! Hello(1)

    cdl.await()
    Thread.sleep(500)
    ns.stop()
    a.terminate()
  }
}
