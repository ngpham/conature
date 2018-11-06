package np.conature.systest.chat

import java.util.concurrent.CountDownLatch
import java.net.InetSocketAddress
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import scala.io.StdIn
import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.{ NetworkService }
import np.conature.remote.events.DisconnectEvent

trait ClientBehavior extends Behavior[Any]

class ClientOffline(val myRemoteRef: Actor[Message])(val hook: Text => Unit)
extends ClientBehavior {
  var retries = 0
  var server: Option[Actor[Message]] = None
  var notifyee: Option[Actor[LoginResult]] = None

  def apply(msg: Any): ClientBehavior = msg match {
    case DoLogin(srv, repTo) =>
      srv ! Login(myRemoteRef)
      server = Some(srv)
      retries += 1
      notifyee = Some(repTo)
      this
    case LoginGranted(ssid) =>
      notifyee map (_ ! LoginSuccess(ssid))
      new ClientActive(myRemoteRef, server.get, ssid)(hook)
    case _ => this
  }
}

class ClientActive
  (val myRemoteRef: Actor[Message], val server: Actor[Message], val ssid: String)
  (val hook: Text => Unit)
extends ClientBehavior {
  def apply(msg: Any): ClientBehavior = msg match {
    case SendMessage(payload) =>
      server ! BroadCast(ssid, payload)
      this
    case DoLogout =>
      server ! Logout(ssid)
      new ClientOffline(myRemoteRef: Actor[Message])(hook)
    case m @ Text(_, _) =>
      hook(m)
      this
    case _ => this
  }
}

// Sample run on localhost:
// jvm1: np.conature.systest.chat.Server 9999 2
// jvm2: np.conature.systest.chat.Client 8888 9999
// jvm2: np.conature.systest.chat.Client 7777 9999

object Client {
  def main(args: Array[String]): Unit = {
    NetworkService.Config.uriStr = s"cnt://localhost:${args(0)}"
    NetworkService.Config.bindPort = args(0).toInt
    NetworkService.Config.serverMode = false

    val latch = new CountDownLatch(1)
    val context = ActorContext.createDefault()
    context.register("netsrv")(NetworkService(context))
    context.start()

    val srv = context.netsrv[NetworkService].locate[Message](
                s"cnt://chatservice@localhost:${args(1)}").get

    val endpoint = context.netsrv[NetworkService].locate[Message](
                s"cnt://client@localhost:${args(0)}").get

    val client = context.spawn(new ClientOffline(endpoint)(text =>
      println(s"Receive from ${text.sender}: ${text.payload}")
    ))
    context.netsrv[NetworkService].register("client", client)

    context.eventBus.subscribe(context.spawn(
      (_: DisconnectEvent) => latch.countDown()
    ))

    var line = ""
    while ({line = StdIn.readLine(); line.nonEmpty}) {
      line match {
        case "login" =>
          val fut: Future[LoginResult] = context.ask(client, DoLogin(srv, _))
          try {
            Await.result(fut, Duration("1s")) match {
              case LoginSuccess(ssid) => println(s"Logged in with session: $ssid")
            }
          } catch {
            case _: java.util.concurrent.TimeoutException =>
              println("Failed to complete login within allowed time")
          }
        case "logout" => client ! DoLogout
        case a: String if a.nonEmpty => client ! SendMessage(a)
        case _ => ()
      }
    }

    context.netsrv[NetworkService].disconnect(
      new InetSocketAddress("localhost", args(1).toInt))
    latch.await()
    context.stop()
  }
}
