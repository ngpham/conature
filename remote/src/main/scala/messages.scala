
package np.conature.remote
package messages

import np.conature.nbnet.SocketContext

import java.net.InetSocketAddress

trait ConatureMessage

@SerialVersionUID(1L)
case class DataMessage(val recipient: String, val data: Serializable) extends ConatureMessage

trait CommandEventProtocol extends ConatureMessage

sealed trait Command extends CommandEventProtocol
sealed trait Protocol extends CommandEventProtocol
sealed trait Event extends CommandEventProtocol

case class LinkTo(node: InetSocketAddress) extends Command
case class UnLinkTo(node: InetSocketAddress) extends Command
case class SendMessage(dMsg: DataMessage, node: InetSocketAddress) extends Command
case object Flush extends Command
case object RemoveAllProxies extends Command

case class ConnectionAcceptance(sctx: SocketContext) extends Event
case class ConnectionClosure(addressOrId: InetSocketAddress) extends Event
case class InboundMessage(sctx: SocketContext, rawMsg: Array[Byte]) extends Event

@SerialVersionUID(1L)
case class HandshakeFrom(nodeUri: String) extends Protocol

@SerialVersionUID(1L)
case class GoodbyeFrom(nodeUri: String) extends Protocol
