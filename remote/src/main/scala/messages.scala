
package np.conature.remote
package messages

import java.net.{ InetSocketAddress }
import scala.concurrent.Promise
import np.conature.nbnet.{ SocketContext }

private[remote] sealed trait ConatureMessage

@SerialVersionUID(1L)
private[remote]
case class DataMessage(recipient: Option[String], data: Serializable) extends ConatureMessage

@SerialVersionUID(1L)
private[remote]
case class ReuseConnectionMessage(identity: InetSocketAddress) extends ConatureMessage

@SerialVersionUID(1L)
private[remote]
case object DisconnectNotificationMessage extends ConatureMessage

private[remote]
case class ContextualData(sockCtx: SocketContext, data: Serializable) extends ConatureMessage

private[remote] sealed trait CommandEventProtocol extends ConatureMessage

private[remote] trait Command extends CommandEventProtocol
private[remote] trait Protocol extends CommandEventProtocol
private[remote] trait Event extends CommandEventProtocol

private[remote] case class SendMessage(
  dMsg: DataMessage, node: InetSocketAddress, optPrms: Option[Promise[Unit]])
extends Command

private[remote] case class UpdateRemoteIdentity(
  remoteIdentity: InetSocketAddress, remoteAddress: InetSocketAddress) extends Command
private[remote] case object Flush extends Command
private[remote] case object RemoveAllProxies extends Command
private[remote] case object AdviceReuseConnection extends Command
private[remote] case class Disconnect(node: InetSocketAddress) extends Command

private[remote] case class ConnectionAcceptance(sctx: SocketContext) extends Event
private[remote] case class ConnectionAttemptFailure(isa: InetSocketAddress) extends Event
private[remote] case class ConnectionClosure(sctx: SocketContext) extends Event

private[remote] case class InboundMessage(sctx: SocketContext, rawMsg: Array[Byte])
extends Event
