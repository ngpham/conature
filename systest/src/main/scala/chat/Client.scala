package np.conature.systest.chat;

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.Duration
import scala.io.StdIn

import np.conature.actor.{ ActorContext, Behavior, Actor }
import np.conature.remote.{ NetworkService }

trait ClientCommand extends Message

@SerialVersionUID(1L)
case class DoLogin() extends ClientCommand

@SerialVersionUID(1L)
case class SaySomething(payload: String) extends ClientCommand

@SerialVersionUID(1L)
case class DoLogout() extends ClientCommand

class ClientOffline(val server: Actor[Message], val endpoint: Actor[Message])
extends Behavior[Message] {
  var timeoutCount = 0

  def apply(msg: Message): Behavior[Message] = {
    timeoutCount = 0
    msg match {
      case DoLogin() =>
        server ! Login(endpoint)
        new ClientOnline(server, endpoint)
      case _ => this
    }
  }

  setTimeout(Duration("5s"))({
    println("Client was idle for 5 secs.")
    timeoutCount += 1
    // if (timeoutCount == 5) {
    //   println("Client is shuting down")
    //   selfref.terminate()
    //   selfref.context.stop()
    // }
  })
}


class ClientOnline(server: Actor[Message], endpoint: Actor[Message])
extends ClientOffline(server, endpoint) {

  override def apply(msg: Message): Behavior[Message] = {
    timeoutCount = 0
    msg match {
      case DoLogin() => this
      case DoLogout() =>
        server ! Logout(endpoint)
        this
      case SaySomething(payload) =>
        server ! BroadCast(endpoint, payload)
        this
      case UniCast(snd, snto, payload) =>
        println(s"Received $payload from: $snd, sent-to: $snto")
        this
      case _ => this
    }
  }
}


// Sample run on localhost:
// jvm1: np.conature.systest.chat.Server 9999
// jvm2: np.conature.systest.chat.Client 8888 9999
// jvm2: np.conature.systest.chat.Client 7777 9999

object Client {
  def main(args: Array[String]): Unit = {
    val localAdr = s"cnt://locahost:${args(0)}"
    val latch = new CountDownLatch(1)
    val context = ActorContext.createDefault({
      latch.countDown()
    })

    context.register("netsrv")(NetworkService(context, localAdr))
    context.start()

    val srv = context.netsrv[NetworkService].locate[Message](
                s"cnt://chatservice@localhost:${args(1)}").get

    val endpoint = context.netsrv[NetworkService].locate[Message](
                s"cnt://client@localhost:${args(0)}").get

    val client = context.spawn(new ClientOffline(srv, endpoint))
    context.netsrv[NetworkService].register("client", client)

    var line = ""
    while ({line = StdIn.readLine(); line.nonEmpty}) {
      line match {
        case "login" => client ! DoLogin()
        case "logout" => client ! DoLogout()
        case "exit" => ()
        case a: String if a.nonEmpty => client ! SaySomething(a)
        case _ =>
      }
    }

    context.stop()
  }
}
