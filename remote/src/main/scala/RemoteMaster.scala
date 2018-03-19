
package np.conature.remote

import java.net.InetSocketAddress

import scala.collection.mutable.Map

import np.conature.actor.{ Behavior, Actor }
import messages.{ CommandEventProtocol, LinkTo, UnLinkTo, SendMessage, Flush,
  ConnectionAcceptance, ConnectionClosure, InboundMessage, RemoveAllProxies }

class RemoteMaster(val netSrv: NetworkService)
extends Behavior[CommandEventProtocol] {
  val isaToProxy = Map[InetSocketAddress, Actor[CommandEventProtocol]]()

  def apply(cep: CommandEventProtocol): Behavior[CommandEventProtocol] = cep match {
    case LinkTo(node) =>
      getOrElseCreateLinkTo(node)
      this
    case UnLinkTo(_) => this
    case SendMessage(_, node) =>
      val proxy = getOrElseCreateLinkTo(node)
      proxy ! cep
      this
    case RemoveAllProxies =>
      isaToProxy.foreach(kv => kv._2 ! ConnectionClosure(kv._1))
      isaToProxy.clear()
      this
    case Flush => this

    case ConnectionAcceptance(sctx) =>
      val proxy = isaToProxy.getOrElse(sctx.remoteIdentity, null)
      if (proxy ne null) proxy ! cep
      else isaToProxy += (sctx.remoteAddress ->
          netSrv.actorCtx.spawn(new ActiveProxy(netSrv, sctx)))
      this
    case ConnectionClosure(addressOrId) =>
      val proxy = isaToProxy.getOrElse(addressOrId, null)
      if (proxy ne null) {
        isaToProxy -= addressOrId
        proxy ! cep
      }
      this
    case InboundMessage(sctx, _) =>
      val proxy = isaToProxy.getOrElse(sctx.remoteAddress, null)
      if (proxy ne null) proxy ! cep
      this
  }

  private def getOrElseCreateLinkTo(node: InetSocketAddress): Actor[CommandEventProtocol] =
    isaToProxy.getOrElseUpdate(node, {
      netSrv.nbTcp.connect(node)
      netSrv.actorCtx.spawn(new PendingProxy(netSrv, Some(node)))
    })
}
