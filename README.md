# conature

Concurrent Miniature: a non-trivial name for a rather trivial actor lib in Scala.

Justification of existence:
1. It is difficult to see the core ideas from big and heavily engineered projects.
2. Avoid bloating dependencies (almost a must in any good projects).
3. D.I.Y.

## Features

Typed, contravariant, asynchronous networked actors.

Does not have fancy features: supervision, recovery. Configuration is limited.

Does not have documentation yet.

## Design at a Glance

Strive for zero dependencies, standard lib only.

An actor is a unique object with internal state, which
acts on typed messages. Remote actor is also typed, and having the same interface with local actor.
The underlying execution is backed by a thread pool, and includes a single-threaded scheduler for
timeout and scheduled tasks. More on typing below.

The networking service is based on Java non-blocking TCP: a single-threaded event loop with NIO
selector. Messages and network events processing are same-thread callbacks. Messages have dynamic
size, and is limited only by Java array size. By providing asynchronous processing in the callbacks,
which is the case with remote actors, we achieve asynchronous and concurrency. There are also
API for idle timeout behaviors setting, a sane default is used (see remote actors).

Serialization is simply Java based (performance is not yet a priority).

Remote actors are p2p network, i.e. every nodes have listening address.
A peer can work in "client mode" by not configuring a listening port. In this case, client-initiated
connection will be full-duplex. In fact, by default, conature tries to setup full-duplex channels
after establishes a connection. Conature does not close connection on
write error, but does so on read error or read idle timeout.

## Strongly Typed Actor

Unlike Akka (as of version 2.5.19), conature actor are typed on both asynchronous and synchronous
message (i.e the ask-pattern). Please consult Akka documentation for more details. Some problems:
typed actor ask-pattern is unnatural, there is no guarantee that the ask will have a reply (user
forgot to write: `askBroker ! theReply` in handling the asked message, or actor terminates)
which forces sender to wait until timeout.

Conature solution: Actor is typed on both receive and reply.
```scala
trait Actor[-A, +R] {
  def !(message: A): Unit = send(message, Actor.empty)
  private[conature] def send(message: A, repTo: Actor[Option[R], Any]): Unit
  def ?(message: A, timeout: Duration = Duration.Inf): Future[R]
}
```
The sender is taken care by the framework: in asynchronous `!`, there is a dummy sender, in
synchronous `?` the proper broker will be setup. This design requires some extension of the actor
state transition. The transition will require explicit `Option` reply. Some type manipulation,
thanks to Scala bottom type, allows the implementation to be type safe and cast-free. Here is
an excerpt from the conature Actor.
```scala
case class State[-A, +R](behavior: Behavior[A, R], reply: Option[R])
object State {
  val trivial = State[Any, Nothing](Behavior.same, None)
}
trait Behavior[-A, +R] {
  def receive(message: A): State[A, R]
}
object Behavior {
  val empty = new Behavior[Any, Nothing] {
    def receive(x: Any): State[Any, Nothing] = State.trivial
  }
  val same: Behavior[Any, Nothing] = new Behavior[Any, Nothing] {
    def receive(x: Any): State[Any, Nothing] = State(same, None)
  }
}
```

An example from the test suit:
```scala
class RequestHandler extends Behavior[IRequest, IReply] {
  def receive(msg: IRequest) = msg match {
    case Request(x) =>
      State(Behavior.same, Some(Reply(x * 2)))
    case _ =>
      State.trivial
  }
}

val context = ActorContext.createDefault()
val actor = context.spawn(new RequestHandler)

val fut: Future[IReply] = actor ? Request(3)

assert((Await.result(fut, Duration.Inf) match {
  case Reply(x) => x
  case _ => 0
}) == 6)
context.stop()
```
The behavior is simple: There is only one state, which receives a request, computes the response,
finally transits into a new state (which is the same) and emits the response. `State.trivial` is
a shortcut for `State(Behavior.same, None)` which indicate no transition, and an empty response.
This empty response help `ask()` to return quickly with a `Future.failed`.

The empty response is also the bottom behavior, i.e. `Actor.empty`, which is useful when actors
crash or are terminated. You will notice that conature do not have builtin error recovery, i.e.
watch/supervision, at least for now. However, users can implement a limited recovery strategy:
provide a state to restart the actor. Full example is presented in networked ask under `systest`.

With typed actor, it is natural to consider lift/contramap (on the receive type) and extend (on
the response type). One important use case is to compose actor, via ADT messages. In Scala 3, we
will have Union type, which is much better. Anyway, here is the API, the usage can be found in the
networked chat in `systest`.
```scala
// A -----> X
// ↑        |
// |        ↓
// B -----> Y
def liftextend[A, X, B, Y](a: Actor[A, X])(f: B => A)(g: X => Y): Actor[B, Y]
```

## More on `ask()`

Notice that actor always reply to `ask()` when it finishes processing the message, event if user
implementation did not provide a reply. In case the actor need to do other thing, before it can
answer, we can use a `Future` reply. Again, the example is in network chat, where client needs
to login and waits/retries. until the server is online. Here is an excerpt:
```scala
val client: Actor[Message, Future[LoginResult]] = ???
@volatile var loggedin = false
while (!loggedin)
  try {
    val fut: Future[LoginResult] = (client ? ClientCmd(DoLogin(srv))).flatten
    Await.result(fut, Duration("1s")) match {
      case LoginSuccess(_) => loggedin = true
    }
  } catch { case _: java.util.concurrent.TimeoutException => () }
```
The client is an actor that knows how to process `Message` defined by the server. Client is also
capable of processing extra control/commands (using `liftextend`). The code shows how the main
thread asks the client to perform login, the client return a nested `Future` and works on the login
by asynchronous message to the server. Client does not block on the local login command nor the
remote login request. Once server grants the session, client actor would complete
the future.

Notice that `Future` is not serializable, rather obviously, thus this scenario will not work
accross the network. But there is another solution, `delegate()` or forwarding. We setup a front
actor to receive the request, and forward to other worker-actors to complete the task and reply.
Conature takes care of correct logic and type safety:
```scala
trait Behavior[-A, +R] {
  def receive(message: A): State[A, R]
  protected def delegate[B, C](msg: B, a: Actor[C, R @uncheckedVariance])(implicit ev: B <:< C): Unit
}
```
The delegation is more general than forwaring. It is not limited to messages of the same type `A`,
but can be any other types that match the delgatee(s), given that delegatees have the correct
reply type.

## Networking

The networking is almost transparent, in most of the cases, we only work with `Actor`
interface. We do need `RemoteActor` interface during the binding, that is: to bind a local actor
with a stub that has a unique URI. This binding steps force users to supply correct types, thus,
the system will not face with runtime type error. Ask() is transparent over the network,
orchestrated by an actor named `"cnt://cnt.broker@<host>:<port>"`.
And finally, we can enjoy type safe RPC, either synchronous or asynchronous on top of
asynchronous actors.

## Project Structure

`util`: Simple implementation of useful utilities: multiple producer single consumer queue,
generic event bus, macro-based wrapper for Java Logging, hashed wheel scheduler, disruptor (idea
from LMax disruptor, tested, but not used in the project).

`actor`: Local typed actor implementation, actor-based event bus.

`nbnet`: Asynchronous non-blocking TCP service.

`remote`: Remote actor implementation.

`systest`: Integrated tests, multiple-JVM tests (using sbt-multi-jvm plugin).

## Examples

Examples can be found in `systest` and test cases.

From sbt, run:
```
test
systest/multi-jvm:run np.conature.systest.multijvm.Chat
systest/multi-jvm:run np.conature.systest.multijvm.NetworkAsk
```
For convenience, let's have a walk through with `NetworkAsk`, which will demonstrate: error
recovery, delegation to workers, networking, and ask pattern.

NetworkAsk includes a server that takes request to compute a boring Fibonacci number. The man
actor receives the request and delegates
the task to worker-actors, and set them to auto restart should them fail. The clients does not
use any actor, but employs `ask` as a form of RPC.

First, we define the message for our application:
```scala
sealed trait FiboMessage extends Serializable
case class FiboRequest(n: Int) extends FiboMessage
case class FiboResponse(fn: Int) extends FiboMessage
```
The worker actor is implemented as a single behavior, with a random crash for demonstration.
```scala
class FiboBehavior(val workerId: Int) extends Behavior[FiboMessage, FiboMessage] {
  def receive(msg: FiboMessage) = msg match {
    case FiboRequest(n) =>
      if (n == 5 && scala.util.Random.nextFloat > 0.5)
        throw new Exception("I Crashed for fun.")
      State(Behavior.same, Some(FiboResponse(n * 100))) // The best Fibonacci computation!
    case FiboResponse(_) =>
      State.trivial
  }
}
```
The server actor is also a single behavior, which overrides the `postInit()` to create its workers.
```scala
class FiboLoadBalancer(val conLev: Int = 4) extends Behavior[FiboMessage, FiboMessage] {
  var workers: Seq[Actor[FiboMessage, FiboMessage]] = Seq.empty
  var token = 0

  def receive(msg: FiboMessage) = msg match {
    case FiboRequest(_) =>
      delegate(msg, workers(token))  // Round-robin delegation
      token = (token + 1) % conLev
      State.trivial
    case FiboResponse(_) =>
      State.trivial
  }

  override def postInit(): Unit = {
    workers = Seq.tabulate(conLev) { i =>
      context.spawn(    // access the ActorContext to create actors
        new FiboBehavior(i),
        (er: Throwable) => { new FiboBehavior(i)})
        // the worker failed behavior is replaced by a fresh behavior
    }
  }
}
```
We setup the server, that will serves on port 9999, until it reaches a limit of 2 client
disconnection events, detected by channel read idle.
```scala
NetworkService.Config.uriStr = "cnt://localhost:9999"
NetworkService.Config.bindPort = 9999
np.conature.nbnet.Config.shortReadIdle = 1  // 1sec, for testing

val latch = new CountDownLatch(2)  // limits of disconnection event count
val context = ActorContext.createDefault()     // create the ActorContext
val netsrv = NetworkService(context)           // create the network service
context.register("netsrv")(netsrv)   // register network service to start/stop by ActorContext
context.start()                      // start the system

// Create the remote stub, as a RemoteActor with a network-reachable URI
val address = netsrv.locate[FiboMessage, FiboMessage](s"cnt://fibocomp@localhost:9999").get
// start our server main actor, which will start its workers.
val srv = context.spawn(new FiboLoadBalancer)
netsrv.bind(address, srv)    // assign the server actor with the RemoteActor stub.
// Listen to the network event. The event bus expects Actor as the handler
// Since we do not need to ask the handler, we can create and Actor[SomeEvent, Nothing],
// which has a convenient API in the ActorContext.
context.eventBus.subscribe(
  context spawn { _: DisconnectEvent => latch.countDown() }
)
latch.await()
context.stop()
```
The client does not need any actors, but it needs the actor system to handle ask/reply.
```scala
NetworkService.Config.uriStr = s"cnt://localhost:8888" // unique uri, but will not listen
NetworkService.Config.bindPort = 8888
NetworkService.Config.serverMode = false   // client will not listen on incoming connection

// Create and start the actor system, with networking
val context = ActorContext.createDefault()
val netsrv = NetworkService(context)
context.register("netsrv")(netsrv)
context.start()
// get the RemoteActor stub for the server
val fiboServer =
  nsr.locate[FiboRequest, FiboResponse](s"cnt://fibocomp@localhost:${args(1)}").get
// fire the first ask, and wait for response, RPC-like. Keep trying until the server starts.
var done = false
while (!done) {
  val fut = fiboServer ? FiboRequest(3)
  try {
    val x = Await.result(fut, Duration("500ms"))
    done = true
  } catch {
    case e @ (_: java.util.concurrent.TimeoutException | _: AskFailureException) => println(e)
  }
}
// Make more RPC calls, notice that asked Futures can complete in any order, which is a good thing.
// If we want ordering, the code must Await after each ask.
val reqs = List(1,2,3,4,5,6,7,8,9,10)
val futs = reqs.map(e => fiboServer ? FiboRequest(e))
implicit val ec = context.strategy
val fut = Future.sequence(futs)

try {
  val xs = Await.result(fut, Duration("500ms"))
  assert(xs.map(_.fn) == reqs.map(_ * 100))
} catch {
  case e @ (_: java.util.concurrent.TimeoutException | _: AskFailureException) => println(e)
}

// proper disconnect: netsrv.disconnect(new InetSocketAddress("localhost", 9999))
// shutdown
context.stop()
```

More details to come...

## Acknowledgment

akka, scalaz, netty, fpinscala, 1024cores,...
