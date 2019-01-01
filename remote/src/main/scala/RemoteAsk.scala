
package np.conature.remote

import scala.collection.{ mutable => mc }
import np.conature.actor.{ Actor, State, Behavior }
import Actor.{ AnyActor, ActorAny }

trait RemoteAskProto extends Serializable

case class AskRequest(askor: AnyActor, askee: AnyActor, payload: Any)
extends RemoteAskProto

case class AskRemote(
  requestId: Int, broker: RemoteActor[RemoteAskProto, Nothing],
  askee: AnyActor, payload: Any)
extends RemoteAskProto

case class ReplyRemote(requestId: Int, payload: Any)
extends RemoteAskProto

private[remote] class RemoteAskBroker(val netSrv: NetworkService)
extends Behavior[RemoteAskProto, Nothing] {
  val reqsActors = mc.Map.empty[Int, AnyActor]
  var reqId: Int = 0

  def receive(rap: RemoteAskProto) = rap match {
    case AskRequest(askor, askee, payload) =>
      reqsActors.put(reqId, askor)
      val remBrk = getRemoteRemoteBrokerFor(askee)
      remBrk ! AskRemote(reqId, netSrv.askBrokerRemote, askee, payload)
      reqId += 1
      State.trivial
    case AskRemote(msgId, remRemBroker, askee, payload) =>
      val localAskee = getLocalAskee(askee)
      val forwarder = context.spawn(new ReplyForwarder(msgId, remRemBroker))
      localAskee.send(payload, forwarder)
      State.trivial
    case ReplyRemote(replyId: Int, payload: Any) =>
      val a = reqsActors.getOrElse(replyId, Actor.empty).asInstanceOf[ActorAny]
      a ! payload
      reqsActors -= replyId
      State.trivial
  }

  // Since askee is valid, this method will never throw
  protected def getRemoteRemoteBrokerFor(askee: AnyActor): Actor[RemoteAskProto, Nothing] =
    askee match {
      case ra @ RemoteActor(_, h, p) =>
        RemoteActor[RemoteAskProto, Nothing](NetworkService.brokerName, h, p, ra.netSrv).get
      case _ => Actor.empty
    }

  protected def getLocalAskee(askee: AnyActor): ActorAny = askee match {
    case ra: RemoteActor[_, _] =>
      netSrv.lookupLocal(ra.address) match {
        case Some(x) => x
        case None => Actor.empty
      }
    case _ => Actor.empty
  }

}

private[remote]
class ReplyForwarder(val msgId: Int, val remRemBroker: Actor[RemoteAskProto, Nothing])
extends Behavior[Option[Any], Any] {
  def receive(msg: Option[Any]) = {
    remRemBroker ! ReplyRemote(msgId, msg)
    State.trivial
  }
}
