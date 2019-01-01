
package np.conature.systest.networkask

import java.util.concurrent.CountDownLatch
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

import np.conature.actor.{ ActorContext, Behavior, State, Actor, AskFailureException }
import np.conature.remote.NetworkService
import np.conature.remote.events.DisconnectEvent

sealed trait FiboMessage extends Serializable
case class FiboRequest(n: Int) extends FiboMessage
case class FiboResponse(fn: Int) extends FiboMessage

class FiboBehavior(val workerId: Int) extends Behavior[FiboMessage, FiboMessage] {
  def receive(msg: FiboMessage) = msg match {
    case FiboRequest(n) =>
      // println(s"Request is served by: worker $workerId")
      if (n == 5 && scala.util.Random.nextFloat > 0.5)
        throw new Exception("I Crashed for fun.")
      State(Behavior.same, Some(FiboResponse(n * 100))) // The best Fibonacci computation!
    case FiboResponse(_) =>
      State.trivial
  }
}

class FiboLoadBalancer(val conLev: Int = 4) extends Behavior[FiboMessage, FiboMessage] {
  var workers: Seq[Actor[FiboMessage, FiboMessage]] = Seq.empty
  var token = 0

  def receive(msg: FiboMessage) = msg match {
    case FiboRequest(_) =>
      delegate(msg, workers(token))
      token = (token + 1) % conLev
      State.trivial
    case FiboResponse(_) =>
      State.trivial
  }

  override def postInit(): Unit = {
    workers = Seq.tabulate(conLev) { i =>
      context.spawn(
        new FiboBehavior(i),
        (er: Throwable) => {
          println(s"Worker $i encountered $er. Recreating initial behavior.")
          new FiboBehavior(i)
        })
    }
  }
}

// Server which will stop after 2 disconnects from clients
// np.conature.systest.networkask.Server 9999 2
object Server {
  def main(args: Array[String]): Unit = {
    NetworkService.Config.uriStr = s"cnt://localhost:${args(0)}"
    NetworkService.Config.bindPort = args(0).toInt
    np.conature.nbnet.Config.shortReadIdle = 1

    val latch = new CountDownLatch(args(1).toInt)

    val context = ActorContext.createDefault()
    val netsrv = NetworkService(context)

    context.register("netsrv")(netsrv)
    context.start()

    val address = netsrv.locate[FiboMessage, FiboMessage](
      s"cnt://fibocomp@localhost:${args(0)}").get

    val srv = context.spawn(new FiboLoadBalancer)
    netsrv.bind(address, srv)

    println("Server is ready with fibocomp service")

    context.eventBus.subscribe(
      context spawn { _: DisconnectEvent => latch.countDown() }
    )

    latch.await()
    context.stop()
  }
}

// Client thats talk to server-9999
// np.conature.systest.networkask.Client 8888 9999
object Client {
  def main(args: Array[String]): Unit = {
    NetworkService.Config.uriStr = s"cnt://localhost:${args(0)}"
    NetworkService.Config.bindPort = args(0).toInt
    NetworkService.Config.serverMode = false

    var timeouts = 0

    val context = ActorContext.createDefault()
    val nsr = NetworkService(context)

    context.register("netsrv")(nsr)
    context.start()

    val fiboServer = nsr.locate[FiboRequest, FiboResponse](
        s"cnt://fibocomp@localhost:${args(1)}").get

    var done = false
    while (!done) {
      val fut = fiboServer ? FiboRequest(3)
      try {
        val x = Await.result(fut, Duration("500ms"))
        println(s"Client first ask was replied with $x")
        done = true
      } catch {
        case e @ (_: java.util.concurrent.TimeoutException | _: AskFailureException) =>
          timeouts += 1
          if (timeouts == 3) done = true
          println(e)
      }
    }

    if (timeouts < 3) {
      val reqs = List(1,2,3,4,5,6,7,8,9,10)
      val futs = reqs.map(e => fiboServer ? FiboRequest(e))
      implicit val ec = context.strategy
      val fut = Future.sequence(futs)

      try {
        val xs = Await.result(fut, Duration("500ms"))
        assert(xs.map(_.fn) == reqs.map(_ * 100))
      } catch {
        case _: java.util.concurrent.TimeoutException =>
          println("Client sequence of asks was timeout.")
        case e: AskFailureException =>
          println(e)
      }
    } else {
      println("Too many timeouts, I will stop asking!")
    }

    // Just let the connection timeout to test auto cleanup on server
    // nsr.disconnect(new InetSocketAddress("localhost", args(1).toInt))
    context.stop()
  }
}
