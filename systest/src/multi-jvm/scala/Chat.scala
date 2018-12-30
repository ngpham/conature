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
    Server.main(Array("9999", "3"))
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
    var countFromOthers = 0
    val context = ActorContext.createDefault()
    val nsr = NetworkService(context)
    context.register("netsrv")(nsr)
    context.start()

    val srv = nsr.locate[Message](
        s"cnt://chatservice@localhost:9999").get

    val endpoint = nsr.locate[Message](
        s"cnt://client@localhost:$port").get

    val client = context.spawn(new ClientOffline(endpoint)((msg: Text) => {
      if (msg.sender != endpoint) countFromOthers += 1
      count += 1
      if (count == numMsg) latchMsg.countDown()
    }))

    context.eventBus.subscribe(context.spawn(
      (_: DisconnectEvent) => latchSys.countDown()
    ))

    val lifted: Actor[Message, Future[LoginResult]] =
      context.liftextend(client)((m: Message) => ServerMsg(m))(identity)

    nsr.bind(endpoint, lifted)

    @volatile var loggedin = false

    while (!loggedin)
      try {
        val fut: Future[LoginResult] = (client ? ClientCmd(DoLogin(srv))).flatten
        Await.result(fut, Duration("1s")) match {
          case LoginSuccess(_) => loggedin = true
        }
      } catch {
        case _: java.util.concurrent.TimeoutException => ()
      }

    for (_ <- 1 to numMsg) client ! ClientCmd(SendMessage("something"))

    latchMsg.await()
    nsr.disconnect(new InetSocketAddress("localhost", 9999))
    println(s"**** Chat client completed $count messages, $countFromOthers are from friends.")
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

object ChatMultiJvmNode4 {
  def main(args: Array[String]): Unit =  {
    val cc = new ClientController(10000, 1024)
    cc.run()
  }
}
