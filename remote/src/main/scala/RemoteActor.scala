
package np.conature.remote

import java.net.URI
import java.net.InetSocketAddress
import java.io.ObjectStreamException
import scala.util.Try
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.concurrent.{ Future, Promise }
import java.util.concurrent.TimeoutException
import np.conature.actor.{ Actor, Behavior, State, AskFailureException }

@SerialVersionUID(1L)
final class RemoteActor[-A <: Serializable, +R](
    @transient val node: InetSocketAddress,
    @transient val name: String,
    val address: String,
    @transient val netSrv: NetworkService
) extends Actor[A, R] with Serializable {

  private[conature] def send(message: A, repTo: Actor[Option[R], Any]): Unit =
    netSrv.send(node, message, Some(address))

  override def terminate(): Unit = ()

  @inline override def isTerminated: Boolean = false
  @inline override def isActive: Boolean = true

  // TODO: Cache the instance, i.e. implement a concurrent bounded weakreference map.
  @throws(classOf[ObjectStreamException])
  private def readResolve(): AnyRef =
    RemoteActor(address, NetworkService.instance).get

  override def ?(message: A, timeout: Duration = Duration.Inf): Future[R] = {
    val prm = Promise[R]()
    val askor = netSrv.actorCtx.spawn(new Behavior[Option[R], Nothing] {
      def receive(msg: Option[R]) = {
        msg match {
          case Some(m) => prm trySuccess m
          case None =>
            prm tryFailure (
              new AskFailureException(
                "ask() failed, askee was terminated or did not handle ask()."))
        }
        selfref.terminate()
        State.trivial
      }

      setTimeout(timeout) {
        prm tryFailure (
          new TimeoutException(s"ask() failed to received a reply within $timeout"))
        selfref.terminate()
      }
    })

    netSrv.askBroker ! AskRequest(askor, this, message)
    prm.future
  }

  // No type check on the type parameter, unfortunately
  override def equals(that: Any): Boolean = that match {
    case rma: RemoteActor[_, _] => rma.address == this.address
    case _ => false
  }

  override def hashCode(): Int = java.util.Objects.hash(address)

  override def toString(): String = s"RemoteActor($address)"
}

private[remote] object RemoteActor {
  val defaulScheme = "cnt"
  // cnt://actorName@host:port
  def apply[A <: Serializable, B](uri: URI, netSrv: NetworkService): Try[RemoteActor[A, B]] =
    Try {
      if (uri.getScheme != defaulScheme) throw new IllegalArgumentException()
      val ra = new RemoteActor[A, B](
        new InetSocketAddress(uri.getHost(), uri.getPort().toInt),
        uri.getUserInfo.split(":")(0),
        uri.toString,
        netSrv)
      ra
    }

  def apply[A <: Serializable, B](
      address: String,
      netSrv: NetworkService): Try[RemoteActor[A, B]] =
    apply(new URI(address), netSrv)

  def apply[A <: Serializable, B](
      name: String,
      host: String,
      port: Int,
      netSrv: NetworkService): Try[RemoteActor[A, B]] =
    Try {
      // cnt://actorName@host:port
      val ra = new RemoteActor[A, B](
        new InetSocketAddress(host, port),
        name,
        (new URI(defaulScheme, name, host, port, null, null, null)).toString,
        netSrv)
      ra
    }

  // extract into (name, host, port)
  def unapply(ra: RemoteActor[_,_]): Option[(String, String, Int)] = {
    Some((ra.name, ra.node.getHostString, ra.node.getPort))
  }
}
