# conature

Concurrent Miniature: a non-trivial name for a simple actor lib in Scala.

Justification of existence: (1) akka is way too big, scalaz/lift actor is a little too small.
(2) D.I.Y.

## Features

Conature actor is typed and contravariant.

Unlike scalaz actor where actor (well, everything) is promoted to be functional (to encourage
the workaround of closing over the outer context from within the actor), conature actor is stateful:
explicitly updates its behavior after processing each message.

Support for receive timeout: Unlike akka, where timeout is delivered as a message, conature timeout
is a callback executed within the actor. One advantage of conature's choice: timeout callback is
cancelled if it lost the race with an incoming message. If users want async timeout, they
can just set the callback action as sending message.


## Acknowledgment

akka, scalaz, lift, fpinscala, 1024cores.

## Todo

Support for networking.
Examples of distributed algorithm/protocol simulations.
