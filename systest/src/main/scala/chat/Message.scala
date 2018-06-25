package np.conature.systest.chat

import np.conature.actor.Actor

// server published protocol
sealed trait Message extends Serializable

@SerialVersionUID(1L)
case class Login(user: Actor[Message]) extends Message

@SerialVersionUID(1L)
case class LoginGranted(sessionId: String) extends Message

@SerialVersionUID(1L)
case class Logout(sessionId: String) extends Message

@SerialVersionUID(1L)
case class BroadCast(sessionId: String, payload: String) extends Message

@SerialVersionUID(1L)
case class Text(sender: Actor[Message], payload: String) extends Message

private[chat] case class SessionEnd(cb: () => Unit) extends Message

// client private protocol
trait ClientCommand
case class DoLogin(server: Actor[Message], repTo: Actor[LoginResult]) extends ClientCommand
case object DoLogout extends ClientCommand
case class SendMessage(payload: String) extends ClientCommand

trait LoginResult extends ClientCommand
case class LoginSuccess(ssid: String) extends LoginResult
case object LoginFailure extends LoginResult
