package np.conature.systest.chat;

import java.util.concurrent.CountDownLatch
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration

import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.NetworkService

trait Message extends Serializable

@SerialVersionUID(1L)
case class UniCast(sender: Actor[Message], sendTo: Actor[Message], payload: String)
extends Message

@SerialVersionUID(1L)
case class BroadCast(sender: Actor[Message], payload: String) extends Message

@SerialVersionUID(1L)
case class Login(sender: Actor[Message]) extends Message

@SerialVersionUID(1L)
case class Logout(sender: Actor[Message]) extends Message

class ServerAct extends Behavior[Message] {
  val members = mc.Set[Actor[Message]]()
  var timeoutCount = 0

  def apply(msg: Message): Behavior[Message] = {
    timeoutCount = 0
    println(s"received $msg ...")
    msg match {
      case Login(usr) =>
        members.add(usr)
        this
      case Logout(usr) =>
        members.remove(usr)
        this
      case UniCast(_, sndto, _) =>
        sndto ! msg
        this
      case BroadCast(snd, pld) =>
        members.map(x => x ! UniCast(snd, x, pld))
        this
      case _ => this
    }
  }

  setTimeout(Duration("5s"))({
    println("ChatServer was idle for 5 secs.")
    timeoutCount += 1
  })
}

// Sample run on localhost:
// jvm1: np.conature.systest.chat.Server 9999
// jvm2: np.conature.systest.chat.Client 8888 9999
// jvm2: np.conature.systest.chat.Client 7777 9999

object Server {
  def main(args: Array[String]): Unit = {
    val localAdr = s"cnt://locahost:${args(0)}"
    val latch = new CountDownLatch(1)
    val context = ActorContext.createDefault({
      latch.countDown()
    })

    context.register("netsrv")(NetworkService(context, localAdr))
    context.start()

    val srv = context.spawn(new ServerAct)
    context.netsrv[NetworkService].register("chatservice", srv)

    latch.await()
  }
}
