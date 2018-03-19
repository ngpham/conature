
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
    val ois = clzLoader.map(new ObjectInputStreamWithClassLoader(bais, _)).
      getOrElse(new ObjectInputStream(bais))
    val res = Try { ois.readObject() }
    ois.close()
    res
  }
}

private[remote] class ObjectInputStreamWithClassLoader(in: InputStream, clzLoader: ClassLoader)
extends ObjectInputStream(in) {
  override def resolveClass(desc: ObjectStreamClass): Class[_] =
    try {
      clzLoader.loadClass(desc.getName)
    } catch {
      case _: ClassNotFoundException => super.resolveClass(desc)
    }
}
