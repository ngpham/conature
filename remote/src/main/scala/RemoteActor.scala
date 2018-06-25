
package np.conature.remote

import np.conature.actor.Actor

import java.net.URI
import java.net.InetSocketAddress
import java.io.ObjectStreamException
import scala.util.Try
import scala.concurrent.{ Promise, Future }

@SerialVersionUID(1L)
private[remote] final class RemoteActor[-A <: Serializable] (
    @transient val node: InetSocketAddress,
    @transient val name: String,
    val address: String,
    @transient val netSrv: NetworkService)
extends Actor[A] with Serializable {

  override def !(message: A): Unit =
    netSrv.send(node, message, Some(name))

  override def send(message: A): Future[Unit] = {
    val promise = Promise[Unit]()
    netSrv.send(node, message, Some(name), Some(promise))
    promise.future
  }

  override def terminate(): Unit = ()
  override def isTerminated = false
  override def isActive = true
  override def toString(): String = address

  @throws(classOf[ObjectStreamException])
  private def readResolve(): AnyRef = RemoteActor(address, NetworkService.instance).get

  override def equals(that: Any): Boolean = that match {
    case rma: RemoteActor[_] => rma.address == this.address
    case _ => false
  }

  override def hashCode(): Int = java.util.Objects.hash(address)
}

private[remote] object RemoteActor {
  // cnt://actorName@host:port
  def apply[A <: Serializable](uri: URI, netSrv: NetworkService): Try[RemoteActor[A]] =
    Try {
      if (uri.getScheme != "cnt") throw new IllegalArgumentException()
      val ra = new RemoteActor[A](
        new InetSocketAddress(uri.getHost(), uri.getPort().toInt),
        uri.getUserInfo.split(":")(0),
        uri.toString,
        netSrv)
      ra
    }

  def apply[A <: Serializable](address: String, netSrv: NetworkService): Try[RemoteActor[A]] =
    apply(new URI(address), netSrv)

  def apply[A <: Serializable](
      name: String,
      host: String,
      port: Int,
      netSrv: NetworkService): Try[RemoteActor[A]] =
    Try {
      // cnt://actorName@host:port
      val ra = new RemoteActor[A](
        new InetSocketAddress(host, port),
        name,
        (new URI("cnt", name, host, port, null, null, null)).toString,
        netSrv)
      ra
    }
}
