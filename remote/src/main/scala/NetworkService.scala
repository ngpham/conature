
package np.conature.remote

import java.net.{ InetSocketAddress, URI }
import scala.util.{ Try, Success, Failure }
import scala.concurrent.Promise
import scala.collection.concurrent.{ Map => CMap, TrieMap }
import np.conature.actor.{ ActorContext, Actor, Extension }
import Actor.{ AnyActor, ActorAny }
import np.conature.nbnet.NbTransport
import np.conature.util.Log
import messages.{ DataMessage, SendMessage, InboundMessage, ConnectionAcceptance,
  ConnectionAttemptFailure, ConnectionClosure, RemoveAllProxies, CommandEventProtocol,
  ContextualData, Disconnect }

trait NetworkService extends Extension { netSrv =>
  private[remote] def actorCtx: ActorContext
  private[remote] def nbTcp: NbTransport
  private[remote] def serializer: Serializer
  private[remote] def remoteMaster: Actor[CommandEventProtocol, Nothing]
  private[remote] def askBroker: Actor[RemoteAskProto, Nothing]
  private[remote] def askBrokerRemote: RemoteActor[RemoteAskProto, Nothing]

  def uniqIsa: InetSocketAddress

  def send[A <: Serializable](
    node: InetSocketAddress,
    msg: A,
    optActorName: Option[String] = None,
    optPrms: Option[Promise[Unit]] = None): Unit

  def disconnect(node: InetSocketAddress): Unit

  def lookupLocal(name: String): Option[ActorAny]

  def bind[A <: Serializable, X, B, Y]
      (ra: RemoteActor[A, X], a: Actor[B, Y])
      (implicit ev1: A <:< B, ev2: Y <:< X): Option[Actor[Any, Any]]

  def unbind[A <:Serializable, X](ra: RemoteActor[A, X]): Option[Actor[A, X]]

  def clientSeverModeMessageHandler: ContextualData => Unit

  def locate[A <: Serializable, X](actorAdr: String): Option[RemoteActor[A, X]] =
    RemoteActor(actorAdr, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }

  def locate[A <: Serializable, X](uri: URI): Option[RemoteActor[A, X]] =
    RemoteActor(uri, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }

  def locate[A <: Serializable, X](
      name: String,
      host: String,
      port: Int): Option[RemoteActor[A, X]] =
    RemoteActor(name, host, port, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }

  def locate[A <: Serializable, X](
      name: String,
      isa: InetSocketAddress): Option[RemoteActor[A, X]] =
    RemoteActor(name, isa.getHostName, isa.getPort, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }
}

object NetworkService {
  object Config {
    var enableDuplexConnection = true
    var uriStr = "cnt://localhost:9999"
    var bindHost = "127.0.0.1"
    var bindPort = 9999
    var serverMode = true
    var classicMsgHandler: ContextualData => Unit = (_:ContextualData) => ()
  }

  val brokerName = "cnt.broker"

  def apply(actorCtx: ActorContext): NetworkService = {
    instance = new NetworkServiceImpl(
      actorCtx,
      new Serializer(),
      Config.uriStr,
      Config.bindHost,
      Config.bindPort,
      Config.serverMode,
      Config.classicMsgHandler)
    instance
  }

  val logger = Log.logger(classOf[NetworkService].getName)

  private[remote] var instance: NetworkService = null
}

private[remote] class NetworkServiceImpl (
    private[remote] val actorCtx: ActorContext,
    private[remote] val serializer: Serializer,
    uriStr: String,
    bindHost: String,
    bindPort: Int,
    serverMode: Boolean,
    val clientSeverModeMessageHandler: ContextualData => Unit)
extends NetworkService {

  val uniqIsa = Try {
    val uri = new URI(uriStr)
    if (uri.getScheme != "cnt") throw new IllegalArgumentException()
    new InetSocketAddress(uri.getHost(), uri.getPort().toInt)
  } match {
    case Success(x) => x
    case Failure(_) =>
      Log.warn(
        NetworkService.logger,
        "NetworkService using default address: cnt://localhost:9999 which is not Internet-reachable.")
      new InetSocketAddress("localhost", 9999)
  }

  private val localActors: CMap[String, AnyActor] =
    TrieMap.empty[String, AnyActor]

  private[remote] val remoteMaster: Actor[CommandEventProtocol, Nothing] =
    actorCtx.spawn(new RemoteMaster(this))

  // Is this guarantee that jvm executes val field initialization in order?
  // If not bind(askBrokerRemote, a) will fail.
  // Using lazy val to ensure initialization.
  private[remote] lazy val askBrokerRemote =
    locate[RemoteAskProto, Nothing](NetworkService.brokerName, uniqIsa).get

  private[remote] val askBroker: Actor[RemoteAskProto, Nothing] = {
    val a = actorCtx.spawn(new RemoteAskBroker(this))
    bind(askBrokerRemote, a)
    a
  }

  private[remote] var nbTcp: NbTransport =
    new NbTransport(bindHost, bindPort, actorCtx.scheduler)

  def bind[A <: Serializable, X, B, Y]
      (ra: RemoteActor[A, X], a: Actor[B, Y])
      (implicit ev1: A <:< B, ev2: Y <:< X): Option[ActorAny] =
    localActors.put(ra.address, a).asInstanceOf[Option[ActorAny]]

  def unbind[A <:Serializable, X](ra: RemoteActor[A, X]): Option[Actor[A, X]] =
    localActors.remove(ra.address) map (_.asInstanceOf[Actor[A, X]])

  def lookupLocal(name: String): Option[ActorAny] =
    localActors.get(name).asInstanceOf[Option[ActorAny]]

  override def start(): Unit = {
    nbTcp
      .setInboundMessageHandler(crm =>
          remoteMaster ! InboundMessage(crm.context, crm.rawBytes))
      .setOnConnectionEstablishedHandler(sockCtx =>
          remoteMaster ! ConnectionAcceptance(sockCtx))
      .setOnConnectionAttemptFailureHandler(isa =>
          remoteMaster ! ConnectionAttemptFailure(isa))
      .setOnConnectionCloseHandler(sockCtx =>
          remoteMaster ! ConnectionClosure(sockCtx))
      .start(serverMode)
  }

  def send[A <: Serializable](
      node: InetSocketAddress,
      msg: A,
      optActorName: Option[String] = None,
      optPrms: Option[Promise[Unit]] = None): Unit =
    remoteMaster ! SendMessage(DataMessage(optActorName, msg), node, optPrms)

  def disconnect(node: InetSocketAddress): Unit =
    remoteMaster ! Disconnect(node)

  override def stop(): Unit = {
    nbTcp.shutdown()
    remoteMaster ! RemoveAllProxies
    localActors.clear()
    remoteMaster.terminate()
  }
}
