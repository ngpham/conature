package np.conature

import java.util.concurrent.atomic.{ AtomicReference }
import java.util.function.Consumer

class MpscQueue[A] extends JMpscQueue[A] {
  def batchConsume(i: Int)(f: A => Unit): Unit = {
    super.batchConsume(i, new Consumer[A] { def accept(a: A) = f(a) })
  }
}
