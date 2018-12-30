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

  // Safety: if actor is terminated, Future should fail fast
  def ?(message: A, timeout: Duration = Duration.Inf): Future[R]
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
finally transits into a new state (which is the same) and emits the response.

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

## Project Structure

`util`: Simple implementation of useful utilities: multiple producer single consumer queue,
generic event bus, macro-based wrapper for Java Logging, hashed wheel scheduler, disruptor (idea
from LMax disruptor, tested, but not used in the project).

`actor`: Local typed actor implementation, actor-based event bus.

`nbnet`: Asynchronous non-blocking TCP service.

`remote`: Remote actor implementation.

`systest`: Integrated tests, multiple-JVM tests (using sbt-multi-jvm plugin).

## Examples

Examples can be found in `systest` and test cases. Here is a demo for the chat test:

From sbt, run:
```
systest/multi-jvm:run np.conature.systest.multijvm.Chat
```
sbt will fork four JVMs: one server, and three clients. Clients will try to connect, with retries
and timeout, until the server JVM is ready. Hence we may see some report of failed to connect.
The test asserts a number of messages broadcast then disconnect the clients. For the sever, it
listens on the disconnect events to properly shutdown.

More details to come.

## Acknowledgment

akka, scalaz, netty, fpinscala, 1024cores,...
