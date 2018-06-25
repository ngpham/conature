
package np.conature.remote

import java.io.{ InputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream,
  ObjectOutputStream, ObjectStreamClass }
import scala.util.Try

class Serializer(val clzLoader: Option[ClassLoader] = None) {
  def toBinary(o: Serializable): Try[Array[Byte]] = {
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    val res = Try {
      oos.writeObject(o)
      oos.flush()
      baos.toByteArray()
    }
    oos.close()
    res
  }

  def fromBinary(bytes: Array[Byte]): Try[AnyRef] = {
    val bais = new ByteArrayInputStream(bytes)
    val ois = new ObjectInputStreamWithClassLoader(bais, clzLoader)
    val res = Try { ois.readObject() }
    ois.close()
    res
  }
}

private[remote]
class ObjectInputStreamWithClassLoader(in: InputStream, clzLoader: Option[ClassLoader])
extends ObjectInputStream(in) {
  val loader = clzLoader.getOrElse(getClass.getClassLoader)

  override def resolveClass(desc: ObjectStreamClass): Class[_] =
    try {
      loader.loadClass(desc.getName)
    } catch {
      case _: ClassNotFoundException => super.resolveClass(desc)
    }
}
