
package np.conature.remote

import java.net.InetSocketAddress
import java.util.NoSuchElementException
import scala.collection.mutable.Map
import np.conature.actor.{ Behavior, Actor }
import np.conature.util.Log
import messages._

private[remote] class RemoteMaster(val netSrv: NetworkService)
extends Behavior[CommandEventProtocol] {
  val identityToRemoteAddress = Map[InetSocketAddress, InetSocketAddress]()
  val isaToProxy = Map[InetSocketAddress, Actor[CommandEventProtocol]]()

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case SendMessage(_, node, _) =>
      try {
        val proxy = getOrElseCreateLinkTo(node)
        proxy ! cep
      } catch {
        case _: NoSuchElementException =>
          Log.error(
            NetworkService.logger,
            "Unexpected severe error. {0}: Failed to find/create connection.")
      }
      this
    case RemoveAllProxies =>
      isaToProxy.foreach(kv => kv._2 ! ConnectionClosure(null)) // ugly null, but ok
      isaToProxy.clear()
      this
    case Disconnect(node) =>
      identityToRemoteAddress.get(node) map { isa: InetSocketAddress =>
        isaToProxy.get(isa) map (proxy => proxy ! Disconnect(node))
      }
      this
    case UpdateRemoteIdentity(identity, address) =>
      identityToRemoteAddress.get(identity) map { oldRemoteAddress =>
        // identity -> oldRemoteAddress -> proxy
        // We are to remap identity -> newRemoteAddress.
        // oldRemoteAddress -> proxy is still kept until the old proxy is closed on
        // read idle timeout, since without identity, it will not be used for outbound.
        isaToProxy.get(oldRemoteAddress) map { proxy =>
          proxy ! ConnectionClosure(null) // to change this to quick read-timeout close
        }
      }
      identityToRemoteAddress += (identity -> address)
      this
    case ConnectionAcceptance(sctx) =>
      if (sctx.remoteIdentity ne null) { // complete a pending connection initiated by us
        try {
          val proxy = isaToProxy.get(sctx.remoteIdentity).get
          identityToRemoteAddress += (sctx.remoteIdentity -> sctx.remoteAddress)
          isaToProxy += (sctx.remoteAddress -> proxy)
          proxy ! cep
        } catch {
          case _: NoSuchElementException =>
            Log.error(
              NetworkService.logger,
              "Unexpected severe error. {0}: Failed to match an outbound connection.")
        }
      } else { // incomming connection
        isaToProxy +=
          (sctx.remoteAddress -> netSrv.actorCtx.spawn(new ActiveProxy(netSrv, sctx)))
      }
      this
    case ConnectionAttemptFailure(address) =>
      // identity -> pendingProxy. This is outgoing connection, not yet established.
      isaToProxy.get(address) map (_ ! cep)
      isaToProxy -= address
      this
    case ConnectionClosure(sctx) =>
      // Clean up the mapping and the proxy: sctx.remoteAddress -> proxy
      // Remove mapping: sctx.identity -> sctx.remoteAddress, only if any, as identity may be
      // mapping to a new connection.
      isaToProxy.get(sctx.remoteAddress) map { proxy =>
        proxy ! cep
        isaToProxy -= sctx.remoteAddress
      }
      val remoteAddress = identityToRemoteAddress.getOrElse(sctx.remoteIdentity, null)
      if (remoteAddress == sctx.remoteAddress) {
        identityToRemoteAddress -= sctx.remoteIdentity
      }
      this
    case InboundMessage(sctx, _) =>
      val proxy = isaToProxy.getOrElse(
        sctx.remoteIdentity,
        isaToProxy.getOrElse(sctx.remoteAddress, null))
      if (proxy ne null) proxy ! cep
      this
    case _ =>
      this
  }

  // If connection is established, we have:
  // identity -> remoteAddress -> proxy   OR   remoteAddress -> proxy (which is not for outbound)
  // If the connection is pending, we have: identity -> proxy
  @throws[NoSuchElementException]
  private def getOrElseCreateLinkTo(node: InetSocketAddress): Actor[CommandEventProtocol] = {
    val remoteAddress = identityToRemoteAddress.getOrElse(node, null)
    if (remoteAddress eq null) {
      isaToProxy.getOrElseUpdate(node, {
        netSrv.nbTcp.connect(node)
        netSrv.actorCtx.spawn(new PendingProxy(netSrv, Some(node)))
      })
    } else {
      isaToProxy.get(remoteAddress).get
    }
  }
}
