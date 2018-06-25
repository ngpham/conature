
package np.conature.remote

import java.net.{ InetSocketAddress, URI }
import scala.util.{ Try, Success, Failure }
import scala.concurrent.Promise
import scala.collection.concurrent.{ Map => CMap, TrieMap }
import np.conature.actor.{ ActorContext, Actor, Extension }
import np.conature.nbnet.NbTransport
import np.conature.util.Log
import messages.{ DataMessage, SendMessage, InboundMessage, ConnectionAcceptance,
  ConnectionAttemptFailure, ConnectionClosure, RemoveAllProxies, CommandEventProtocol,
  ContextualData, Disconnect }

trait NetworkService extends Extension { netSrv =>
  private[remote] def actorCtx: ActorContext
  private[remote] def nbTcp: NbTransport
  private[remote] def serializer: Serializer
  private[remote] def remoteMaster: Actor[CommandEventProtocol]

  def localIsa: InetSocketAddress

  def send[A <: Serializable](
    node: InetSocketAddress,
    msg: A,
    optActorName: Option[String] = None,
    optPrms: Option[Promise[Unit]] = None): Unit

  def disconnect(node: InetSocketAddress): Unit

  def register(name: String, actor: Actor[_]): Boolean
  def lookupLocal(name: String): Option[Actor[Any]]

  def clientSeverModeMessageHandler: ContextualData => Unit

  def locate[A <: Serializable](actorAdr: String): Option[RemoteActor[A]] =
    RemoteActor(actorAdr, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }

  def locate[A <: Serializable](uri: URI): Option[RemoteActor[A]] =
    RemoteActor(uri, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }

  def locate[A <: Serializable](
      name: String,
      host: String,
      port: Int): Option[RemoteActor[A]] =
    RemoteActor(name, host, port, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(_) => None
    }
}

private[remote] class NetworkServiceImpl (
    private[remote] val actorCtx: ActorContext,
    private[remote] val serializer: Serializer,
    localAdr: String,
    serverMode: Boolean,
    val clientSeverModeMessageHandler: ContextualData => Unit)
extends NetworkService {

  val localIsa = Try {
    val uri = new URI(localAdr)
    if (uri.getScheme != "cnt") throw new IllegalArgumentException()
    new InetSocketAddress(uri.getHost(), uri.getPort().toInt)
  } match {
    case Success(x) => x
    case Failure(_) =>
      Log.warn(NetworkService.logger, "NetworkService using default address: cnt://localhost:9999.")
      new InetSocketAddress("localhost", 9999)
  }

  val localUri: String = (new URI(s"cnt://${localIsa.getHostName}:${localIsa.getPort}")).toString

  private val localActors: CMap[String, Actor[Any]] = TrieMap.empty[String, Actor[Any]]

  private[remote] val remoteMaster: Actor[CommandEventProtocol] =
    actorCtx.spawn(new RemoteMaster(this))

  private[remote] var nbTcp: NbTransport = new NbTransport(localIsa.getPort, actorCtx.scheduler)

  def register(name: String, actor: Actor[_]): Boolean =
    localActors.putIfAbsent(name, actor.asInstanceOf[Actor[Any]]) match {
      case Some(_) => false
      case None => true
    }

  def lookupLocal(name: String): Option[Actor[Any]] = localActors.get(name)

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

object NetworkService {
  var enableDuplexConnection = true

  private[remote] var instance: NetworkService = null

  def apply(actorCtx: ActorContext, localAdr: String, serverMode: Boolean): NetworkService = {
    // cnt://host:port
    apply(
      actorCtx,
      localAdr,
      serverMode,
      (_: ContextualData) => ())
  }

  def apply(actorCtx: ActorContext, localAdr: String, serverMode: Boolean,
      handler: ContextualData => Unit): NetworkService = {
    if (!serverMode) { enableDuplexConnection = true }
    instance = new NetworkServiceImpl(
      actorCtx,
      new Serializer(),
      localAdr,
      serverMode,
      handler)
    instance
  }

  val logger = Log.logger(classOf[NetworkService].getName)
}
