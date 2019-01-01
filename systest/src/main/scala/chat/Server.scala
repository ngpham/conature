package np.conature.systest.chat;

import java.util.concurrent.CountDownLatch
import java.util.UUID
import scala.collection.{ mutable => mc }
import scala.concurrent.duration.Duration

import np.conature.actor.{ ActorContext, Behavior, State, Actor }
import np.conature.remote.NetworkService
import np.conature.remote.events.DisconnectEvent

class ServerAct(val limit: Int, val address: Actor[Message, Nothing])
extends Behavior[Message, Nothing] {
  private var count = 0
  private val ssidToClient: mc.Map[String, Actor[Message, Nothing]] = mc.Map()
  private val clientToSsid: mc.Map[Actor[Message, Nothing], String] = mc.Map()

  def receive(msg: Message): State[Message, Nothing] = msg match {
    case Login(user) =>
      if (!clientToSsid.contains(user)) {
        val ssid = UUID.randomUUID().toString
        clientToSsid += (user -> ssid)
        ssidToClient += (ssid -> user)
        user ! LoginGranted(ssid)
      }
      State.trivial
    case Logout(ssid: String) =>
      ssidToClient.get(ssid) map { user =>
        clientToSsid -= user
        ssidToClient -= ssid
      }
      State.trivial
    case BroadCast(ssid: String, payload: String) =>
      ssidToClient.get(ssid) map { sender =>
        val txt = Text(sender, payload)
        for (v <- ssidToClient.values) v ! txt
      }
      State.trivial
    case SessionEnd(cb) =>
      count += 1
      if (count == limit) { context.stop(); cb() }
      State.trivial
    case _ => State.trivial
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
    NetworkService.Config.uriStr = s"cnt://localhost:${args(0)}"
    NetworkService.Config.bindPort = args(0).toInt
    val latch = new CountDownLatch(1)
    val context = ActorContext.createDefault()
    val netsrv = NetworkService(context)

    context.register("netsrv")(netsrv)
    context.start()

    val address = netsrv.locate[Message, Nothing](
      s"cnt://chatservice@localhost:${args(0)}").get

    val srv = context.spawn(new ServerAct(args(1).toInt, address))
    netsrv.bind(address, srv)

    context.eventBus.subscribe(
      context.liftextend(srv)
        ((_: DisconnectEvent) => SessionEnd(() => latch.countDown()))
        (identity)
    )

    latch.await()
  }
}
