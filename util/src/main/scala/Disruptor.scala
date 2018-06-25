
package np.conature.util

import java.util.concurrent.locks.{ Lock, ReentrantLock, Condition, LockSupport }
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }
import scala.collection.{ mutable => mc }
import scala.util.control.NonFatal
import scala.reflect.ClassTag

trait Disruptor[T] {
  def start(): Unit

  // stop granting claim(), finish pending publication,
  // readers read all current items then return
  def orderlyStop(): Unit

  // evasive stop
  def stop(): Unit

  def registerHandler(handler: Disruptor.Handler[T], name: Option[String] = None): Unit
  def claimNext(n: Int = 1): (Long, Long)
  def write(i: Long, x: T): Unit
  def publish(inclusive: (Long, Long)): Unit
  def slowReader: Long
  def writePosition: Long
}

object Disruptor {
  trait Handler[T] {
    // process items, until disruptor stops.
    // early opt out by throwing InterruptedException.
    def apply(x: T): Unit
    def postStop(): Unit = ()
  }

  def apply[T : ClassTag](sizeInBits: Int = 10)(f: => T) = new DisruptorImpl(sizeInBits)(f)
  val logger = Log.logger(classOf[Disruptor[_]].getName)
}

private object DisruptorImpl {
  private type Cursor = AtomicLong

  private val Stopped = 0
  private val Running = 1
  private val Stopping = 2

  private class PublishTrack {  // ensure memory barrier
    val segments: AtomicReference[List[(Long, Long)]] =
      new AtomicReference(List(-1L -> -1L))

    def available: Long = segments.get.head._2

    def add(seg: (Long, Long)): Unit = {
      var old = segments.getAcquire
      var update = merge((seg +: old).sortWith(_._2 <= _._1))
      while (!segments.compareAndSet(old, update)) {
        old = segments.getAcquire
        update = merge((seg +: old).sortWith(_._2 <= _._1))
      }
    }

    private def merge(ss: List[(Long, Long)]): List[(Long, Long)] = {
      ss.foldRight(List.empty[(Long, Long)])((x, acc) => {
        acc match {
          case Nil => x :: Nil
          case h :: t =>
            if (h._1 == x._2 + 1) (x._1, h._2) :: t
            else x :: acc
        }
      })
    }
  }

  private class RingBuffer[T : ClassTag](sizeInBits: Int, f: => T) {
    private val size = 1 << sizeInBits
    private val mask = size - 1
    private val buffer = Array.fill[T](size)(f)

    def elem(i: Long): T = buffer((i & mask).asInstanceOf[Int])

    def write(i: Long, x: T): Unit = buffer((i & mask).asInstanceOf[Int]) = x
  }

  private class BatchHandler[T](
      val handler: Disruptor.Handler[T],
      val name: Option[String],
      val cursor: Cursor,
      val disruptor: DisruptorImpl[T]) extends Runnable {

    def run(): Unit = {
      var shouldRun = true
      var cur = cursor.get

      while (shouldRun) {
        val oldSlowReader = disruptor.slowReader

        try {
          val readInclusive = disruptor.waitForPublication(cur + 1)
          applyHandler(cur, readInclusive)
          cur = readInclusive
          if (disruptor.state.getAcquire == Stopped ||
              (disruptor.state.getAcquire == Stopping &&
              cur == disruptor.writePosition))
            shouldRun = false
        } catch {
          case _: InterruptedException =>
            shouldRun = false
            disruptor.readCursors.remove(cursor)
          case e: Throwable => // Fatal Exceptions only
            disruptor.readCursors.remove(cursor)
            throw e
        } finally {
          signalWritersTentatively(oldSlowReader)
        }
      }

      handler.postStop()
    }

    private def applyHandler(exclusive: Long, inclusive: Long): Unit = {
      ((exclusive + 1) to inclusive) foreach { i =>
        try {
          handler(disruptor.ringBuffer.elem(i))
        } catch {
          case NonFatal(e) =>
            println(s"Swallow NonFatal Exception from user-defined handler $e")
          case e: InterruptedException => throw e
          case e: Throwable => throw e
        } finally {
          cursor.setRelease(i)
        }
      }
    }

    private def signalWritersTentatively(oldSlowReader: Long): Unit =
      if (disruptor.slowReader > oldSlowReader) {
        disruptor.lock.lock()
        try { disruptor.notifyWriter.signalAll()
        } finally { disruptor.lock.unlock() }
      }
  }

  private class CursorGroup(guard: Cursor) {
    private val members: AtomicReference[Set[Cursor]] =
      new AtomicReference(Set.empty[Cursor])

    def staleMin: Long = {
      val grp = members.getAcquire
      if (grp.isEmpty) guard.getAcquire else grp.map(_.getAcquire).min
    }

    def staleMax: Long = {
      val grp = members.getAcquire
      if (grp.isEmpty) guard.getAcquire else grp.map(_.getAcquire).max
    }

    def setAll(v: Long) = members.getAcquire.foreach(_.lazySet(v))

    def add(cursor: Cursor): Unit = {
      cursor.set(staleMax)
      var old = members.get
      var update = old + cursor
      while (!members.weakCompareAndSetRelease(old, update)) {
        cursor.set(staleMax)
        old = members.get
        update = old + cursor
      }
    }

    def remove(s: Cursor): Unit = {
      var old = members.get
      var update = old.filter(_ ne s)
      while (!members.weakCompareAndSetRelease(old, update)) {
        old = members.get
        update = old.filter(_ ne s)
      }
    }
  }
}

class DisruptorImpl[T : ClassTag] private[util] (sizeInBits: Int = 10)(f: => T)
extends Disruptor[T] {
  import DisruptorImpl._

  private val ringBuffer = new RingBuffer[T](sizeInBits, f)
  private val size: Int = 1 << sizeInBits

  private val writeCursor: Cursor = new AtomicLong(-1)
  private val publishTrack = new PublishTrack()
  private val readCursors = new CursorGroup(writeCursor)

  private val handlers = mc.ListBuffer.empty[BatchHandler[T]]

  private val state = new AtomicInteger(0)

  private val lock: Lock = new ReentrantLock()
  private val notifyReader: Condition = lock.newCondition()
  private val notifyWriter: Condition = lock.newCondition()

  override def start(): Unit = {
    if (state.compareAndSet(Stopped, Running))
      handlers.foreach(spawnHandlerThread(_))
    else ()
  }

  override def orderlyStop(): Unit = {
    if (state.getAcquire == Stopped) ()
    else {
      state.setRelease(Stopping)
      lock.lock()
      try {
        notifyReader.signalAll()
      } finally {
        lock.unlock()
      }
    }
  }

  override def stop(): Unit = {
    state.setRelease(Stopped)
    lock.lock()
    try {
      notifyReader.signalAll()
      notifyWriter.signalAll()
    } finally {
      lock.unlock()
    }
  }

  override def registerHandler(
      handler: Disruptor.Handler[T],
      name: Option[String] = None): Unit = {
    val batchHandler = new BatchHandler(handler, name, new AtomicLong(-1), this)
    handlers append batchHandler
    readCursors.add(batchHandler.cursor)
    // start rightaway if disruptor is running. Race is ok...
    if (state.getAcquire == Running)
      spawnHandlerThread(batchHandler)
  }

  @throws(classOf[InterruptedException])
  override def claimNext(n: Int = 1): (Long, Long) = recClaim(n)

  override def write(i: Long, x: T): Unit = { ringBuffer.write(i, x) }

  override def publish(inclusive: (Long, Long)): Unit = {
    publishTrack.add(inclusive)
    lock.lock()
    try {
      notifyReader.signalAll()
    } finally { lock.unlock() }
  }

  override def slowReader: Long = readCursors.staleMin
  override def writePosition: Long = writeCursor.getAcquire

  @throws(classOf[InterruptedException])
  private def waitForPublication(value: Long): Long = {
    def readerMustWaitWhileServiceRunning: Boolean = {
      state.getAcquire == Running && value > publishTrack.available
    }
    def readerMustWaitWhileServiceStopping: Boolean = {
      state.getAcquire == Stopping && value > publishTrack.available && value <= writePosition
    }

    if (value <= publishTrack.available) publishTrack.available
    else {
      lock.lock()
      try {
        while (readerMustWaitWhileServiceRunning || readerMustWaitWhileServiceStopping)
          notifyReader.await()
        publishTrack.available
      } finally {
        lock.unlock()
      }
    }
  }

  private def spawnHandlerThread(bh: BatchHandler[T]): Unit = {
    val t = new Thread(bh)
    t.setDaemon(true)
    bh.name.map(x => t.setName(x))
    t.start()
  }

  @throws(classOf[InterruptedException])
  @annotation.tailrec
  private def recClaim(n: Int): (Long, Long) = {
    if (state.getAcquire != 1) (-1L, -1L)
    else {
      spinClaim(n) match {
        case Some(lohi) => lohi
        case None =>
          waitForReaders(n)
          recClaim(n)
      }
    }
  }

  private def spinClaim(n: Int): Option[(Long, Long)] = {
    def cas(): Option[(Long, Long)] = {
      val old = writePosition
      val claim = old + n
      if (claim <= readCursors.staleMin + size)
        if (writeCursor.weakCompareAndSetRelease(old, claim)) Some((old + 1, claim))
        else None
      else None
    }
    def shouldCompete: Boolean =
      (writePosition + n <= readCursors.staleMin + size)

    var ret: Option[(Long, Long)] = None
    var done = false
    while (shouldCompete && !done) {
      ret = cas()
      if (ret.nonEmpty) done = true
      else LockSupport.parkNanos(1)
    }
    ret
  }

  @throws(classOf[InterruptedException])
  private def waitForReaders(n: Int): Unit = {
    lock.lock()
    try {
      while (readCursors.staleMin + size < writePosition + n)
        notifyWriter.await()
    } finally {
      lock.unlock()
    }
  }
}
