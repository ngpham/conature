# conature

Concurrent Miniature: a non-trivial name for a simple actor lib in Scala.

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
timeout and scheduled tasks.

The networking service is based on Java non-blocking TCP: a single-threaded event loop with NIO
selector. Messages and network events processing are same-thread callbacks. Messages have dynamic
size, and is limited only by Java array size. By providing asynchronous processing in the callbacks,
which is the case with remote actors, we have an asynchronous network application. There are also
API for idle timeout behaviors setting, a sane default is used (see remote actors).

Serialization is simply Java based (performance is not yet a priority).

Remote actors are p2p network, i.e. every nodes have listening address.
A peer can work in "client mode" by not configuring a listening port. In this case, client-initiated
connection will be full-duplex. In fact, by default, conature tries to setup full-duplex channels
after establishes a connection. [Skipping details] conature does not close connection on
write error, but does so on read error or read idle timeout.

## Project Structure

`util`: Simple implementation of useful utilities: multiple producer single consumer queue,
generic event bus, macro-based wrapper for Java Logging, hashed wheel scheduler, disruptor (idea
from LMax disruptor, tested, but not used in the project).

`actor`: Local typed actor implementation, actor-based event bus.

`nbnet`: Asynchronous non-blocking TCP services.

`remote`: Remote actor implementation.

`systest`: Integrated tests, multiple-JVM tests (using sbt-multi-jvm plugin).

## Examples

Examples can be found in `systest` and test cases. Here is a demo for the chat test:

From sbt, run:
```
systest/multi-jvm:run np.conature.systest.multijvm.Chat
```
sbt will fork three JVMs: one server, and two clients. Clients will try to connect, with retries
and timeout, until the server JVM is ready. Hence we may see some report of failed to connect.
The test asserts a number of messages broadcast then disconnect the clients. For the sever, it
listens on the disconnect events to properly shutdown.

More details to come.

## Acknowledgment

akka, scalaz, netty, fpinscala, 1024cores,...
