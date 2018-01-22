
package np.conature.remote

import np.conature.actor.Actor

import java.net.URI
import java.net.InetSocketAddress

import scala.util.Try

private[remote] final class RemoteActor[-A <: Serializable] (
    val node: InetSocketAddress,
    val name: String,
    val netSrv: NetworkService)
extends Actor[A] {
  def !(message: A): Unit = { netSrv.send(node, name, message) }

  def terminate(): Unit = ()
  override def contramap[B](f: B => A): Actor[B] = null
}

object RemoteActor {
  def apply[A <: Serializable](
      actorAdr: String,
      netSrv: NetworkService): Try[RemoteActor[A]] =
    Try {
      // cnt://actorName@host:port
      val uri = new URI(actorAdr)
      if (uri.getScheme != "cnt") throw new IllegalArgumentException()
      val ra = new RemoteActor[A](
        new InetSocketAddress(uri.getHost(), uri.getPort().toInt),
        uri.getUserInfo.split(":")(0),
        netSrv)
      ra
    }
}
