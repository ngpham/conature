
package np.conature.utiltesting.disruptor

import org.scalatest.FlatSpec
import scala.collection.{ mutable => mc }
import scala.util.Random

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import np.conature.util.Disruptor

class IntGenerator(start: Int, step: Int) {
  var v = start
  def next(): Int = { val ret = v; v += step; ret }
}

class Reader(
    verify: Set[Int],
    latch: CountDownLatch) extends Disruptor.Handler[Int] {
  val acc = mc.ListBuffer.empty[Int]
  val nums = verify.size

  def apply(x: Int): Unit = acc.append(x)

  override def postStop(): Unit = {
    try {
      assert(acc.size == nums, s"received more/less than expected: ${acc.size - nums}")
      val mismatch = verify diff acc.toSet
      assert(mismatch.isEmpty, s"send and receive mismatched by: ${mismatch.size}")
    } finally { latch.countDown() }
  }
}

class ReaderWithTally(
    verify: Set[Int],
    latch: CountDownLatch,
    tally: AtomicInteger) extends Reader(verify, latch) {

  override def postStop(): Unit = {
    var old = tally.getAcquire
    var update = old + acc.size
    while (!tally.compareAndSet(old, update)) {
      old = tally.getAcquire
      update = old + acc.size
    }
    latch.countDown()
  }
}

class ReaderWithOptOut(
    latch: CountDownLatch,
    maxRead: Int) extends Disruptor.Handler[Int] {

  var count = 0
  def apply(x: Int): Unit =  {
    count += 1
    if (count >= maxRead) throw new InterruptedException()
  }

  override def postStop() = latch.countDown
}


class Producer(
    disruptor: Disruptor[Int],
    total: Int,
    gen: IntGenerator,
    latch: CountDownLatch,
    allocSize: Int) extends Runnable {
  var count = 0
  def postStop(): Unit = latch.countDown()

  override def run(): Unit = {
    val rand = new Random()
    var shouldRun = true

    while (shouldRun) {
      val toClaim = math.min(rand.nextInt(allocSize) + 1, total - count)
      val lohi = disruptor.claimNext(toClaim)

      if (lohi._1 != -1) {
        for (j <- lohi._1 to lohi._2) disruptor.write(j, gen.next())
        disruptor.publish(lohi)
        count += toClaim
      }

      shouldRun = (count < total) && (lohi._1 != -1)
    }

    postStop()
  }
}

class ProducerWithTally(
    disruptor: Disruptor[Int],
    total: Int,
    gen: IntGenerator,
    latch: CountDownLatch,
    allocSize: Int,
    tally: AtomicInteger) extends Producer(disruptor, total, gen, latch, allocSize) {

  override def postStop(): Unit = {
    var old = tally.getAcquire
    var update = old + count
    while (!tally.compareAndSet(old, update)) {
      old = tally.getAcquire
      update = old + count
    }
    super.postStop()
  }
}

object DefaultDisruptor {
  val sizeInBits = 8
  val nums = 1 << 16
  val maxWriteChunkSize = 128
  def apply() = Disruptor[Int](sizeInBits)(0)
}

class DisruptorTest extends FlatSpec {
  import DefaultDisruptor._

  "A Disruptor" should s"manage 4 writers, 6 readers, broadcast of ${nums} integers" in {
    val d = DefaultDisruptor()
    val test = (1 to nums).toSet
    val writeLatch = new CountDownLatch(4)
    val readLatch = new CountDownLatch(6)

    for (i <- 1 to 6) {
      d.registerHandler(new Reader(test, readLatch), Some(s"r${i.toString}"))
    }
    d.start()
    for (i <- 1 to 4) {
      val p = new Thread(
        new Producer(d, nums/4, new IntGenerator(i, 4), writeLatch, maxWriteChunkSize))
      p.setName(s"w${i.toString}")
      p.start()
    }

    writeLatch.await()
    d.orderlyStop()
    readLatch.await()
  }

  it should s"send/receive all messages during orderlyStop()" in {
    val d = DefaultDisruptor()
    val test = (1 to nums).toSet
    val writeLatch = new CountDownLatch(4)
    val readLatch = new CountDownLatch(6)

    val totalSent = new AtomicInteger(0)
    val totalReceived = new AtomicInteger(0)

    for (i <- 1 to 6) {
      d.registerHandler(
        new ReaderWithTally(test, readLatch, totalReceived),
        Some(s"r${i.toString}"))
    }
    d.start()
    for (i <- 1 to 4) {
      val p = new Thread(
        new ProducerWithTally(
          d, nums/4, new IntGenerator(i, 4), writeLatch, maxWriteChunkSize, totalSent))
      p.setName(s"w${i.toString}")
      p.start()
    }
    Thread.sleep(10)
    d.orderlyStop()
    writeLatch.await()
    readLatch.await()
    assert(6 * totalSent.get == totalReceived.get)
  }

  it should "not deadlock/livelock under compelled shutdown" in {
    val d = DefaultDisruptor()
    val test = (1 to nums).toSet
    val writeLatch = new CountDownLatch(4)
    val readLatch = new CountDownLatch(6)

    val totalSent = new AtomicInteger(0)
    val totalReceived = new AtomicInteger(0)

    for (i <- 1 to 6) {
      d.registerHandler(
        new ReaderWithTally(test, readLatch, totalReceived),
        Some(s"r${i.toString}"))
    }
    d.start()
    for (i <- 1 to 4) {
      val p = new Thread(
        new ProducerWithTally(
          d, nums/4, new IntGenerator(i, 4), writeLatch, maxWriteChunkSize, totalSent))
      p.setName(s"w${i.toString}")
      p.start()
    }
    Thread.sleep(100)
    d.stop()
    writeLatch.await()
    readLatch.await()
  }

  it should "progress with readers join/leave as will" in {
    val d = DefaultDisruptor()
    val writeLatch = new CountDownLatch(4)
    val readLatch = new CountDownLatch(6)

    d.start()
    for (i <- 1 to 4) {
      val p = new Thread(
        new Producer(
          d, nums/4, new IntGenerator(i, 4), writeLatch, maxWriteChunkSize))
      p.setName(s"w${i.toString}")
      p.start()
    }

    for (i <- 1 to 3) {
      d.registerHandler(
        new ReaderWithOptOut(readLatch, nums / 8),
        Some(s"r${i.toString}"))
    }

    writeLatch.await()
    for (i <- 1 to 3) {
      d.registerHandler(
        new ReaderWithOptOut(readLatch, nums / 2),
        Some(s"r${i.toString}"))
    }
    d.stop()
    readLatch.await()
  }

}
