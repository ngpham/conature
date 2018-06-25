
package np.conature.util

import java.util.logging.{ Logger => JLogger }
import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import scala.reflect.{ ClassTag, classTag }

object Log {
  @inline def logger(name: String): JLogger = JLogger.getLogger(name)
  @inline def logger(clazz: Class[_]): JLogger = logger(clazz.getName)

  @inline def logger[T : ClassTag]: JLogger =
    logger(classTag[T].runtimeClass.getName.stripSuffix("$"))

  def error(wrappee: JLogger, msg: String): Unit = macro MacroImplLog.errorMsg

  def error(wrappee: JLogger, msg: String, thrown: Throwable): Unit =
    macro MacroImplLog.errorMsgThrown

  def error(wrappee: JLogger, msg: String, params: Any*): Unit =
    macro MacroImplLog.errorMsgParams

  def warn(wrappee: JLogger, msg: String): Unit = macro MacroImplLog.warnMsg

  def warn(wrappee: JLogger, msg: String, thrown: Throwable): Unit =
    macro MacroImplLog.warnMsgThrown

  def warn(wrappee: JLogger, msg: String, params: Any*): Unit =
    macro MacroImplLog.warnMsgParams

  def info(wrappee: JLogger, msg: String): Unit = macro MacroImplLog.infoMsg

  def info(wrappee: JLogger, msg: String, thrown: Throwable): Unit =
    macro MacroImplLog.infoMsgThrown

  def info(wrappee: JLogger, msg: String, params: Any*): Unit =
    macro MacroImplLog.infoMsgParams

  def debug(wrappee: JLogger, msg: String): Unit = macro MacroImplLog.debugMsg

  def debug(wrappee: JLogger, msg: String, thrown: Throwable): Unit =
    macro MacroImplLog.debugMsgThrown

  def debug(wrappee: JLogger, msg: String, params: Any*): Unit =
    macro MacroImplLog.debugMsgParams

  def trace(wrappee: JLogger, msg: String): Unit = macro MacroImplLog.traceMsg

  def trace(wrappee: JLogger, msg: String, thrown: Throwable): Unit =
    macro MacroImplLog.traceMsgThrown

  def trace(wrappee: JLogger, msg: String, params: Any*): Unit =
    macro MacroImplLog.traceMsgParams
}

private object MacroImplLog {
  private def castToSeqAnyRef(c: blackbox.Context)
      (anies: c.Expr[Any]*): Seq[c.Expr[AnyRef]] = {
    import c.universe._
    anies.map { x =>
      c.Expr[AnyRef](
        if (x.tree.tpe <:< weakTypeOf[AnyRef]) x.tree
        else q"$x.asInstanceOf[AnyRef]")
    }
  }

  def errorMsg(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.SEVERE))
        $wrappee.log(java.util.logging.Level.SEVERE, $msg)
    """
  }

  def errorMsgThrown(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], thrown: c.Expr[Throwable]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.SEVERE))
        $wrappee.log(java.util.logging.Level.SEVERE, $msg, $thrown)
    """
  }

  def errorMsgParams(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], params: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    val seqAnyRef = castToSeqAnyRef(c)(params: _*)
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.SEVERE)) {
        $wrappee.log(java.util.logging.Level.SEVERE, $msg, Array(..$seqAnyRef))
      }
    """
  }

  def warnMsg(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.WARNING))
        $wrappee.log(java.util.logging.Level.WARNING, $msg)
    """
  }

  def warnMsgThrown(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], thrown: c.Expr[Throwable]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.WARNING))
        $wrappee.log(java.util.logging.Level.WARNING, $msg, $thrown)
    """
  }

  def warnMsgParams(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], params: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    val seqAnyRef = castToSeqAnyRef(c)(params: _*)
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.WARNING)) {
        $wrappee.log(java.util.logging.Level.WARNING, $msg, Array(..$seqAnyRef))
      }
    """
  }

  def infoMsg(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.INFO))
        $wrappee.log(java.util.logging.Level.INFO, $msg)
    """
  }

  def infoMsgThrown(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], thrown: c.Expr[Throwable]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.INFO))
        $wrappee.log(java.util.logging.Level.INFO, $msg, $thrown)
    """
  }

  def infoMsgParams(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], params: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    val seqAnyRef = castToSeqAnyRef(c)(params: _*)
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.INFO)) {
        $wrappee.log(java.util.logging.Level.INFO, $msg, Array(..$seqAnyRef))
      }
    """
  }

  def debugMsg(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINE))
        $wrappee.log(java.util.logging.Level.FINE, $msg)
    """
  }

  def debugMsgThrown(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], thrown: c.Expr[Throwable]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINE))
        $wrappee.log(java.util.logging.Level.FINE, $msg, $thrown)
    """
  }

  def debugMsgParams(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], params: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    val seqAnyRef = castToSeqAnyRef(c)(params: _*)
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINE)) {
        $wrappee.log(java.util.logging.Level.FINE, $msg, Array(..$seqAnyRef))
      }
    """
  }

  def traceMsg(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINEST))
        $wrappee.log(java.util.logging.Level.FINEST, $msg)
    """
  }

  def traceMsgThrown(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], thrown: c.Expr[Throwable]): c.universe.Tree = {
    import c.universe._
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINEST))
        $wrappee.log(java.util.logging.Level.FINEST, $msg, $thrown)
    """
  }

  def traceMsgParams(c: blackbox.Context)(
      wrappee: c.Expr[JLogger],
      msg: c.Expr[String], params: c.Expr[Any]*): c.universe.Tree = {
    import c.universe._
    val seqAnyRef = castToSeqAnyRef(c)(params: _*)
    q"""
      if ($wrappee.isLoggable(java.util.logging.Level.FINEST)) {
        $wrappee.log(java.util.logging.Level.FINEST, $msg, Array(..$seqAnyRef))
      }
    """
  }
}
