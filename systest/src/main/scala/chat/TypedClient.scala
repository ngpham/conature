package np.conature.systest.chat

import java.util.concurrent.CountDownLatch
import java.net.InetSocketAddress
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import scala.io.StdIn
import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.{ NetworkService }
import np.conature.remote.events.DisconnectEvent

sealed trait UnionMsg
case class ServerMsg(msg: Message) extends UnionMsg
case class ClientCmd(msg: ClientCommand) extends UnionMsg

trait TypedClientBehavior extends Behavior[UnionMsg]

class TypedClientOffline(val myRemoteRef: Actor[Message])(val hook: Text => Unit)
extends TypedClientBehavior {
  var retries = 0
  var server: Option[Actor[Message]] = None
  var notifyee: Option[Actor[LoginResult]] = None

  def apply(msg: UnionMsg): TypedClientBehavior = msg match {
    case ClientCmd(DoLogin(srv, repTo)) =>
      srv ! Login(myRemoteRef)
      server = Some(srv)
      retries += 1
      notifyee = Some(repTo)
      this
    case ServerMsg(LoginGranted(ssid)) =>
      notifyee map (_ ! LoginSuccess(ssid))
      new TypedClientActive(myRemoteRef, server.get, ssid)(hook)
    case _ => this
  }
}

class TypedClientActive
  (val myRemoteRef: Actor[Message], val server: Actor[Message], val ssid: String)
  (val hook: Text => Unit)
extends TypedClientBehavior {
  def apply(msg: UnionMsg): TypedClientBehavior = msg match {
    case ClientCmd(SendMessage(payload)) =>
      server ! BroadCast(ssid, payload)
      this
    case ClientCmd(DoLogout) =>
      server ! Logout(ssid)
      new TypedClientOffline(myRemoteRef: Actor[Message])(hook)
    case ServerMsg(m @ Text(_, _)) =>
      hook(m)
      this
    case _ => this
  }
}

object TypedClient {
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

    val client = context.spawn(new TypedClientOffline(endpoint)(text =>
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
          val fut: Future[LoginResult] =
            context.ask[UnionMsg, LoginResult](client, (x: Actor[LoginResult]) => {
              ClientCmd(DoLogin(srv, x))
            } : UnionMsg)
          try {
            Await.result(fut, Duration("1s")) match {
              case LoginSuccess(ssid) => println(s"Logged in with session: $ssid")
            }
          } catch {
            case _: java.util.concurrent.TimeoutException =>
              println("Failed to complete login within allowed time")
          }
        case "logout" => client ! ClientCmd(DoLogout)
        case a: String if a.nonEmpty => client ! ClientCmd(SendMessage(a))
        case _ => ()
      }
    }

    context.netsrv[NetworkService].disconnect(
      new InetSocketAddress("localhost", args(1).toInt))
    latch.await()
    context.stop()
  }
}
