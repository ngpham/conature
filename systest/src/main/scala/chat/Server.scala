package np.conature.systest.chat;

import java.util.concurrent.CountDownLatch
import java.util.UUID
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration

import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.NetworkService
import np.conature.remote.events.DisconnectEvent

class ServerAct(val limit: Int, val address: Actor[Message]) extends Behavior[Message] {
  private var count = 0
  private val ssidToClient: mc.Map[String, Actor[Message]] = mc.Map()
  private val clientToSsid: mc.Map[Actor[Message], String] = mc.Map()

  def apply(msg: Message): Behavior[Message] = msg match {
    case Login(user) =>
      if (!clientToSsid.contains(user)) {
        val ssid = UUID.randomUUID().toString
        clientToSsid += (user -> ssid)
        ssidToClient += (ssid -> user)
        user ! LoginGranted(ssid)
      }
      this
    case Logout(ssid: String) =>
      ssidToClient.get(ssid) map { user =>
        clientToSsid -= user
        ssidToClient -= ssid
      }
      this
    case BroadCast(ssid: String, payload: String) =>
      ssidToClient.get(ssid) map { sender =>
        val txt = Text(sender, payload)
        for (v <- ssidToClient.values) v ! txt
      }
      this
    case SessionEnd(cb) =>
      count += 1
      if (count == limit) { context.stop(); cb() }
      this
    case _ => this
  }

  setTimeout(Duration("1s"))({
    println("ChatServer was idle for 1 secs.")
  })
}

// Sample run on localhost:
// jvm1: np.conature.systest.chat.Server 9999 2
// jvm2: np.conature.systest.chat.Client 8888 9999
// jvm2: np.conature.systest.chat.Client 7777 9999

object Server {
  def main(args: Array[String]): Unit = {
    val localAdr = s"cnt://localhost:${args(0)}"
    val latch = new CountDownLatch(1)
    val context = ActorContext.createDefault()

    context.register("netsrv")(NetworkService(context, localAdr, serverMode = true))
    context.start()

    val address = context.netsrv[NetworkService].locate[Message](
      s"cnt://chatservice@localhost:${args(0)}").get

    val srv = context.spawn(new ServerAct(args(1).toInt, address))

    context.eventBus.subscribe(Actor.contramap(srv){
      e: DisconnectEvent => SessionEnd(() => latch.countDown())
    })

    context.netsrv[NetworkService].register("chatservice", srv)

    latch.await()
  }
}
