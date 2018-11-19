package np.conature.systest.multijvm

import java.util.concurrent.CountDownLatch
import java.net.InetSocketAddress
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ Duration }
import np.conature.actor.{ ActorContext, Actor }
import np.conature.remote.{ NetworkService }
import np.conature.remote.events.DisconnectEvent
import np.conature.systest.chat._

// systest/multi-jvm:run np.conature.systest.multijvm.TypedChat

object TypedChatMultiJvmNode1 {
  def main(args: Array[String]): Unit =  {
    np.conature.nbnet.Config.shortReadIdle = 1
    Server.main(Array("9999", "2"))
  }
}

class TypedClientController(port: Int, numMsg: Int) {
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

    val client = context.spawn(new TypedClientOffline(endpoint)(_ => {
      count += 1
      if (count == numMsg) latchMsg.countDown()
    }))

    context.eventBus.subscribe(context.spawn(
      (_: DisconnectEvent) => latchSys.countDown()
    ))

    context.netsrv[NetworkService].register("client", client)

    @volatile var loggedin = false

    while (!loggedin)
      try {
        val fut: Future[LoginResult] =
          context.ask[UnionMsg, LoginResult](client, (x: Actor[LoginResult]) => {
              ClientCmd(DoLogin(srv, x))
          } : UnionMsg)
        Await.result(fut, Duration("1s")) match {
          case LoginSuccess(_) => loggedin = true
        }
      } catch {
        case _: java.util.concurrent.TimeoutException => ()
      }

    for (_ <- 1 to numMsg) client ! ClientCmd(SendMessage("something"))

    latchMsg.await()
    context.netsrv[NetworkService].disconnect(new InetSocketAddress("localhost", 9999))
    println(s"Chat client completed $count messages.")
    latchSys.await()
    context.stop()
  }
}

object TypedChatMultiJvmNode2 {
  def main(args: Array[String]): Unit =  {
    val cc = new ClientController(7777, 1024)
    cc.run()
  }
}

object TypedChatMultiJvmNode3 {
  def main(args: Array[String]): Unit =  {
    val cc = new ClientController(8888, 1024)
    cc.run()
  }
}
