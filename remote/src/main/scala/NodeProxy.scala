
package np.conature.remote

import java.net.InetSocketAddress
import java.util.function.Consumer
import scala.collection.mutable.{ Buffer }
import scala.concurrent.Promise
import scala.util.{ Success, Failure }
import np.conature.actor.{ Behavior }
import np.conature.nbnet.{ SocketContext, WriteComplete }
import np.conature.util.Log
import messages._
import events.{ DisconnectEvent }

private[remote] sealed trait NodeProxy extends Behavior[CommandEventProtocol] {
  def netSrv: NetworkService
  def remoteIdentity: Option[InetSocketAddress]
  def buffer: Buffer[(DataMessage, Option[Promise[Unit]])]
}

private[remote] class PendingProxy(
    val netSrv: NetworkService,
    val remoteIdentity: Option[InetSocketAddress],
    val buffer: Buffer[(DataMessage, Option[Promise[Unit]])] = Buffer.empty)
extends NodeProxy {
  require(remoteIdentity.nonEmpty)

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case SendMessage(dMsg, _, optPromise) =>
      buffer += (dMsg -> optPromise)
      Behavior.same
    case Disconnect(node) =>
      Log.warn(
        NetworkService.logger,
        "{0} is not connected, ignore Disconnect command. This must not happen.",
        node)
      Behavior.same
    case ConnectionAcceptance(sctx) =>
      val ap = new ActiveProxy(netSrv, sctx, buffer, remoteIdentity)
      if (NetworkService.Config.enableDuplexConnection) selfref ! AdviceReuseConnection
      if (buffer.nonEmpty) selfref ! Flush
      ap
    case ConnectionAttemptFailure(_) =>
      Log.warn(
        NetworkService.logger,
        "Failed to connect to {0}. {1} messages are not sent",
        remoteIdentity, buffer.size)
      buffer.foreach { case (_, optPromise) =>
        optPromise map (p => p failure new Exception())
      }
      terminate()
      Behavior.same
    case _ => Behavior.same
  }
}

private[remote] class ActiveProxy(
    val netSrv: NetworkService,
    val sockCtx: SocketContext,
    val buffer: Buffer[(DataMessage, Option[Promise[Unit]])] = Buffer.empty,
    val remoteIdentity: Option[InetSocketAddress] = None)
extends NodeProxy {

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case SendMessage(dMsg, _, optPromise) =>
      serializeThenSend(dMsg, optPromise)
      Behavior.same
    case Flush =>
      buffer.foreach(t => serializeThenSend(t._1, t._2))
      buffer.clear()
      Behavior.same
    case Disconnect(_) =>
      sendNotificationThenDisconnect()
      Behavior.same
    case AdviceReuseConnection =>
      serializeThenSend(ReuseConnectionMessage(netSrv.uniqIsa))
      Behavior.same
    case ConnectionClosure(_) =>
      if (buffer.nonEmpty)
        Log.warn(
          NetworkService.logger,
          "Connection to {0} closed. {1} messages are not sent",
          remoteIdentity, buffer.size)
      buffer.foreach { case (_, optPromise) =>
        optPromise map (p => p failure new Exception())
      }
      context.eventBus.publish(DisconnectEvent(sockCtx.remoteAddress(), remoteIdentity))
      terminate()
      Behavior.same
    case InboundMessage(_, rawMsg) =>
      deserializeThenDeliver(rawMsg)
      Behavior.same
    case _ => Behavior.same
  }

  def serializeThenSend(msg: Serializable, optPromise: Option[Promise[Unit]] = None): Unit =
    netSrv.serializer.toBinary(msg) match {
      case Success(x) =>
        optPromise match {
          case Some(p) =>
            sockCtx.send(x, new Consumer[WriteComplete] {
              def accept(sc: WriteComplete): Unit = {
                if (sc.isSuccess()) p.success(())
                else p failure sc.cause()
              }
            })
          case None =>
            sockCtx.send(x)
        }
      case Failure(e) => Log.error(NetworkService.logger, s"Error in serializing ${msg}", e)
    }

  def sendNotificationThenDisconnect(): Unit = {
    sockCtx.send(
      netSrv.serializer.toBinary(DisconnectNotificationMessage).get,
      new Consumer[WriteComplete] {
        def accept(sc: WriteComplete): Unit = sockCtx.destroy()
      })
  }

  def deserializeThenDeliver(rawMsg: Array[Byte]): Unit =
    netSrv.serializer.fromBinary(rawMsg) match {
      case Success(dMsg: DataMessage) =>
        dMsg.recipient.fold(
          netSrv.clientSeverModeMessageHandler(ContextualData(sockCtx, dMsg.data))
        )(actorName => {
          netSrv.lookupLocal(actorName).foreach(_ ! dMsg.data)
        })
      case Success(ReuseConnectionMessage(identity)) =>
        sockCtx.remoteIdentity(identity)
        netSrv.remoteMaster ! UpdateRemoteIdentity(identity, sockCtx.remoteAddress)
      case Success(DisconnectNotificationMessage) =>
        sockCtx.destroy()
      case _ =>
        Log.error(
          NetworkService.logger, "Unable to deserialize received bytes.")
  }
}
