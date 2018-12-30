
package np.conature.remote

import np.conature.actor.{ Actor, NonAskableActor }

import java.net.URI
import java.net.InetSocketAddress
import java.io.ObjectStreamException
import scala.util.Try

@SerialVersionUID(1L)
final class RemoteActor[-A <: Serializable](
    @transient val node: InetSocketAddress,
    @transient val name: String,
    val address: String,
    @transient val netSrv: NetworkService
) extends NonAskableActor[A] with Serializable {

  private[conature] def send(message: A, repTo: Actor[Option[Nothing], Any]): Unit =
    netSrv.send(node, message, Some(address))

  override def terminate(): Unit = ()

  @inline override def isTerminated: Boolean = false
  @inline override def isActive: Boolean = true

  // TODO: Cache the instance, i.e. implement a concurrent bounded weakreference map.
  @throws(classOf[ObjectStreamException])
  private def readResolve(): AnyRef =
    RemoteActor(address, NetworkService.instance).get

  // No type check on the type parameter, unfortunately
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

  def apply[A <: Serializable](
      address: String,
      netSrv: NetworkService): Try[RemoteActor[A]] =
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
