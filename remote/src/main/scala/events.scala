
package np.conature.remote
package events

import java.net.InetSocketAddress

case class DisconnectEvent(
  remoteAddress: InetSocketAddress,
  remoteIdentity: Option[InetSocketAddress])
