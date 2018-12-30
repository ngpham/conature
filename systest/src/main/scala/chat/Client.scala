package np.conature.systest.chat

import java.util.concurrent.CountDownLatch
import java.net.InetSocketAddress
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration.{ Duration }
import scala.io.StdIn
import np.conature.actor.{ ActorContext, Behavior, Actor, State, NonAskableActor }
import np.conature.remote.{ NetworkService }
import np.conature.remote.events.DisconnectEvent

sealed trait UnionMsg
case class ServerMsg(msg: Message) extends UnionMsg
case class ClientCmd(msg: ClientCommand) extends UnionMsg

trait ClientBehavior extends Behavior[UnionMsg, Future[LoginResult]]

class ClientOffline(val myRemoteRef: NonAskableActor[Message])(val hook: Text => Unit)
extends ClientBehavior {
  var retries = 0
  var server: Option[NonAskableActor[Message]] = None
  var prm: Promise[LoginResult] = Promise.successful(LoginFailure)

  def receive(msg: UnionMsg) = msg match {
    case ClientCmd(DoLogin(srv)) =>
      srv ! Login(myRemoteRef)
      server = Some(srv)
      retries += 1
      prm = Promise[LoginResult]()
      State(Behavior.same, Some(prm.future))
    case ServerMsg(LoginGranted(ssid)) =>
      prm.trySuccess(LoginSuccess(ssid))
      State(new ClientActive(myRemoteRef, server.get, ssid)(hook), None)
    case _ =>
      State.trivial
  }
}

class ClientActive
  (val myRemoteRef: NonAskableActor[Message],
   val server: NonAskableActor[Message],
   val ssid: String)
  (val hook: Text => Unit)
extends ClientBehavior {
  def receive(msg: UnionMsg) = msg match {
    case ClientCmd(SendMessage(payload)) =>
      server ! BroadCast(ssid, payload)
      State.trivial
    case ClientCmd(DoLogout) =>
      server ! Logout(ssid)
      State(new ClientOffline(myRemoteRef: NonAskableActor[Message])(hook), None)
    case ServerMsg(m @ Text(_, _)) =>
      hook(m)
      State.trivial
    case _ =>
      State.trivial
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
    val nsr = NetworkService(context)
    context.register("netsrv")(nsr)
    context.start()

    val srv = nsr.locate[Message](
        s"cnt://chatservice@localhost:${args(1)}").get

    val endpoint = nsr.locate[Message](
        s"cnt://client@localhost:${args(0)}").get

    val client = context.spawn(new ClientOffline(endpoint)(text =>
      println(s"Receive from ${text.sender}: ${text.payload}")
    ))

    val lifted: Actor[Message, Future[LoginResult]] =
      context.liftextend(client)((m: Message) => ServerMsg(m))(identity)

    nsr.bind(endpoint, lifted)

    context.eventBus.subscribe(context.spawn(
      (_: DisconnectEvent) => latch.countDown()
    ))

    var line = ""
    while ({line = StdIn.readLine(); line.nonEmpty}) {
      line match {
        case "login" =>
          val fut: Future[LoginResult] = (client ? ClientCmd(DoLogin(srv))).flatten
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
