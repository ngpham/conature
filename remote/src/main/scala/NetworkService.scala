
package np.conature.remote

import np.conature.actor.{ ActorContext, Actor }
import np.conature.nbnet.{ NbTransport }
import messages.{ DataMessage, SendMessage, InboundMessage, ConnectionAcceptance,
  ConnectionClosure, RemoveAllProxies, CommandEventProtocol }

import java.net.{ InetSocketAddress, URI }

import scala.util.{ Try, Success, Failure }
import scala.collection.concurrent.{ Map => CMap, TrieMap }

trait NetworkService { netSrv =>
  private[remote] def actorCtx: ActorContext
  private[remote] def nbTcp: NbTransport
  private[remote] def serializer: Serializer

  def register(name: String, actor: Actor[_]): Boolean
  def lookupLocal(name: String): Option[Actor[Any]]

  private[remote] def send[A <: Serializable](
    node: InetSocketAddress, actorName: String, msg: A): Unit

  // def connect(nodeAdr: String): Unit
  // def disConnect(nodeAdr: String): Unit
  def start(): Try[Unit]
  def stop(): Unit

  def locate[A <: Serializable](actorAdr: String): Option[RemoteActor[A]] =
    RemoteActor(actorAdr, netSrv) match {
      case Success(ra) => Some(ra)
      case Failure(e) =>
        println(s"Error looking up remote actor. $e")
        None
    }
}

private[remote] class NetworkServiceImpl (
    private[remote] val actorCtx: ActorContext,
    private[remote] val serializer: Serializer,
    localAdr: String)
extends NetworkService {

  val localIsa = Try {
    val uri = new URI(localAdr)
    if (uri.getScheme != "cnt") throw new IllegalArgumentException()
    new InetSocketAddress(uri.getHost(), uri.getPort().toInt)
  } match {
    case Success(x) => x
    case Failure(_) =>
      println("NetworkService using default address: cnt://localhost:9999")
      new InetSocketAddress("localhost", 9999)
  }

  val localUri: String = (new URI(s"cnt://${localIsa.getHostName}:${localIsa.getPort}")).toString
  private[remote] var nbTcp: NbTransport = null

  private var localActors: CMap[String, Actor[Any]] = null

  private var remoteMaster: Actor[CommandEventProtocol] = null

  def register(name: String, actor: Actor[_]): Boolean =
    localActors.putIfAbsent(name, actor.asInstanceOf[Actor[Any]]) match {
      case Some(_) => false
      case None => true
    }

  def lookupLocal(name: String): Option[Actor[Any]] = localActors.get(name)

  def start(): Try[Unit] = Try {
    localActors = TrieMap.empty[String, Actor[Any]]
    remoteMaster = actorCtx.create(new RemoteMaster(this))

    nbTcp = new NbTransport(localIsa.getPort)
    (nbTcp
      .setInboundMessageHandler(crm =>
          remoteMaster ! InboundMessage(crm.context, crm.rawBytes))
      .setOnConnectionEstablishedHandler(sockCtx =>
          remoteMaster ! ConnectionAcceptance(sockCtx))
      .setOnConnectionAttemptFailureHandler(isa =>
          remoteMaster ! ConnectionClosure(isa))
      .setOnConnectionCloseHandler(isa =>
          remoteMaster ! ConnectionClosure(isa))
      .start())
  } match {
    case Success(_) => Success(())
    case x @ Failure(_) => stop(); x
  }

  // def connect(nodeAdr: String): Unit = ()
  // def disConnect(nodeAdr: String): Unit = ()

  private[remote] def send[A <: Serializable](
      node: InetSocketAddress, actorName: String, msg: A): Unit =
    remoteMaster ! SendMessage(DataMessage(actorName, msg), node)


  def stop(): Unit = {
    nbTcp.shutdown()
    remoteMaster ! RemoveAllProxies
    localActors.clear()
    remoteMaster.terminate()
  }
}

object NetworkService {
  def apply(actorCtx: ActorContext, localAdr: String): NetworkService = {
    // cnt://host:port
    new NetworkServiceImpl(
      actorCtx,
      new Serializer(),
      localAdr)
  }
}
