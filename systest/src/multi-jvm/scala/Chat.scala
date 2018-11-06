package np.conature.systest.multijvm

import java.util.concurrent.CountDownLatch
import java.net.InetSocketAddress
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import np.conature.actor.{ ActorContext, Actor }
import np.conature.remote.{ NetworkService }
import np.conature.remote.events.DisconnectEvent
import np.conature.systest.chat._

// systest/multi-jvm:run np.conature.systest.multijvm.Chat

object ChatMultiJvmNode1 {
  def main(args: Array[String]): Unit =  {
    np.conature.nbnet.Config.shortReadIdle = 1
    Server.main(Array("9999", "2"))
  }
}

class ClientController(port: Int, numMsg: Int) {
  def run(): Unit = {
    NetworkService.Config.uriStr = s"cnt://localhost:$port"
    NetworkService.Config.bindPort = port
    NetworkService.Config.serverMode = false

    val latchMsg = new CountDownLatch(1)
    val latchSys = new CountDownLatch(1)
    var count = 0
    val context = ActorContext.createDefault()
    context.register("netsrv")(NetworkService(context))
    context.start()

    val srv = context.netsrv[NetworkService].locate[Message](
      s"cnt://chatservice@localhost:9999").get

    val endpoint = context.netsrv[NetworkService].locate[Message](
      s"cnt://client@localhost:$port").get

    val client: Actor[Any] = context.spawn(new ClientOffline(endpoint)(_ => {
      count += 1
      if (count == numMsg) latchMsg.countDown()
    }))

    context.eventBus.subscribe(context.spawn(
      (e: DisconnectEvent) => latchSys.countDown()
    ))

    context.netsrv[NetworkService].register("client", client)

    var loggedin = false

    while (!loggedin)
      try {
        val fut: Future[LoginResult] = context.ask(client, DoLogin(srv, _))
        Await.result(fut, Duration("1s")) match {
          case LoginSuccess(_) => loggedin = true
        }
      } catch {
        case _: java.util.concurrent.TimeoutException => ()
      }

    for (i <- 1 to numMsg) client ! SendMessage("something")

    latchMsg.await()
    context.netsrv[NetworkService].disconnect(new InetSocketAddress("localhost", 9999))
    println(s"Chat client completed $count messages.")
    latchSys.await()
    context.stop()
  }
}

object ChatMultiJvmNode2 {
  def main(args: Array[String]): Unit =  {
    val cc = new ClientController(7777, 1024)
    cc.run()
  }
}

object ChatMultiJvmNode3 {
  def main(args: Array[String]): Unit =  {
    val cc = new ClientController(8888, 1024)
    cc.run()
  }
}
