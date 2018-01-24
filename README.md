# conature

Concurrent Miniature: a non-trivial name for a simple actor lib in Scala.

Justification of existence: (1) akka is way too big, scalaz/lift actor is a little too small,
Netty is doing much more than just non-blocking TCP.
(2) D.I.Y.

## Features

Conature actor is typed and contravariant.

Unlike scalaz actor where actor (well, everything) is promoted to be functional,
conature actor is stateful: explicitly updates its behavior after processing each message.

Support for receive timeout: Unlike akka, where timeout is delivered as a message, conature timeout
is a callback executed within the actor. One advantage of conature's choice: timeout callback is
cancelled if it lost the race with an incoming message. If users want async timeout, they
can set the callback action to self addressing message.

Support non-blocking networking. You may take a look at systest/Peer.scala for the simplistic and
the only one example :-). Also, you can take a look at remote implementation for an example
of using conature actors.

Does not have fancy features: supervision, recovery.
Does not have configuration and documentation, yet.

## Design at a Glance

Strive for zero dependencies, standard lib only.

An actor is a unique object with internal state, which
acts on typed messages. Remote actor is also typed, and having the same interface with local actor.
The underlying execution is backed by a thread pool, and includes a single-threaded scheduler for
timeout and scheduled tasks.

The networking service (still in its primitive form) is based on Java non-blocking TCP.
This TCP layer is thin, as we can reuse the actor system
to have concurrent handling of networking events. The networking is hardcoded with message of
64KB maximum. Large message transfer must be handled by client code.

Serialization is simply Java based. I am considering an alternative dynamic serialization approach,
i.e. no code generation.

## Acknowledgment

akka, scalaz, lift, fpinscala, 1024cores,...

## Todo

- Investigate dynamic serialization.
- Keep-alive for network links, remote loading, improve error recovery.
- "println" began to hurt the eyes, I need a simple logger (just use the actors, maybe).
- Performance test? Extensive networking test?
- Examples of distributed algorithms, protocol simulations.
