
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

  def uniqIsa: InetSocketAddress

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

  // val localUri: String = (new URI(s"cnt://${uniqIsa.getHostName}:${uniqIsa.getPort}")).toString

  private val localActors: CMap[String, Actor[Any]] = TrieMap.empty[String, Actor[Any]]

  private[remote] val remoteMaster: Actor[CommandEventProtocol] =
    actorCtx.spawn(new RemoteMaster(this))

  private[remote] var nbTcp: NbTransport =
    new NbTransport(bindHost, bindPort, actorCtx.scheduler)

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
  object Config {
    var enableDuplexConnection = true
    var uriStr = "cnt://localhost:9999"
    var bindHost = "127.0.0.1"
    var bindPort = 9999
    var serverMode = true
    var classicMsgHandler: ContextualData => Unit = (_:ContextualData) => ()
  }

  private[remote] var instance: NetworkService = null

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
}
