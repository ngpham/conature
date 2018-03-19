
package np.conature.remote

import np.conature.actor.Behavior
import np.conature.nbnet.SocketContext
import messages.{ CommandEventProtocol, DataMessage, SendMessage, Flush, ConnectionAcceptance,
  ConnectionClosure, InboundMessage }

import java.net.InetSocketAddress
import scala.collection.mutable.{ Buffer }
import scala.util.{ Success, Failure }


private[remote] sealed trait NodeProxy extends Behavior[CommandEventProtocol] {
  def netSrv: NetworkService
  def remoteIdentity: Option[InetSocketAddress]
  def buffer: Buffer[DataMessage]
}

private[remote] class PendingProxy(
    val netSrv: NetworkService,
    val remoteIdentity: Option[InetSocketAddress],
    val buffer: Buffer[DataMessage] = Buffer.empty)
extends NodeProxy {
  require(remoteIdentity.nonEmpty)

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case SendMessage(dMsg, _) =>
      buffer += dMsg
      this
    case ConnectionAcceptance(sctx) =>
      val ap = new ActiveProxy(netSrv, sctx, buffer, remoteIdentity)
      if (buffer.nonEmpty) selfref ! Flush
      ap
    case ConnectionClosure(_) =>
      if (buffer.nonEmpty)
        println(s"Link is not established. Some messages are not sent to $remoteIdentity")
      // should have a hook for user-code to take action... event bus.
      terminate()
      this
    case _ => this
  }
}

private[remote] class ActiveProxy(
    val netSrv: NetworkService,
    val sockCtx: SocketContext,
    val buffer: Buffer[DataMessage] = Buffer.empty,
    val remoteIdentity: Option[InetSocketAddress] = None)
extends NodeProxy {

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case SendMessage(dMsg, _) =>
      serializeThenSend(dMsg)
      this
    case Flush =>
      buffer.foreach(serializeThenSend(_))
      buffer.clear()
      this
    case ConnectionClosure(_) =>
      if (buffer.nonEmpty)
        println(s"Link failed/unestablished. Some messages are not sent.")
      terminate()
      this
    case InboundMessage(_, rawMsg) =>
      deserializeThenDeliver(rawMsg)
      this

    case _ => this
  }

  def serializeThenSend(dMsg: DataMessage): Unit = netSrv.serializer.toBinary(dMsg) match {
    case Success(x) => sockCtx.send(x); ()
    case Failure(e) => println(s"Error serializing ${dMsg.data}. $e")
  }

  def deserializeThenDeliver(rawMsg: Array[Byte]): Unit =
    netSrv.serializer.fromBinary(rawMsg) match {
      case Success(dMsg: DataMessage) =>
        netSrv.lookupLocal(dMsg.recipient) map (_ ! dMsg.data)
        ()
      case _ => println("Failed to deserialize")
  }
}
