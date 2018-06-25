
package np.conature.nbnet;

import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.List;
import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.util.logging.Logger;
import java.util.logging.Level;
import np.conature.util.Scheduler;
import np.conature.util.Cancellable;

public class SocketContext {
  private static final int IsOpen = 0;
  private static final int IsClosing = 1;
  private static final int IsClosed = 2;

  private static final Logger logger = Logger.getLogger(SocketContext.class.getName());

  protected Reader reader;
  protected Writer writer;
  protected SocketChannel channel;
  protected NbTransport nbTrans;

  private InetSocketAddress remoteAddress;
  private InetSocketAddress remoteIdentity = null;

  private int state;
  private boolean isWritable;
  protected ChannelIdleTimeout timeoutTask = null;

  public SocketContext(
      NbTransport nbTrans,
      SocketChannel channel,
      Reader reader, Writer writer,
      InetSocketAddress remoteAddress) {
    this.nbTrans = nbTrans;
    this.channel = channel;
    this.reader = reader; reader.context = this;
    this.writer = writer; writer.context = this;
    this.remoteAddress = remoteAddress;

    state = IsOpen;
    isWritable = true;
  }

  public void send(byte[] message) {
    send(message, OutboundEntry.EmptyOnComplete);
  }

  public void send(byte[] message, Consumer<WriteComplete> onComplete) {
    if (nbTrans.inAsyncIo(Thread.currentThread()))
      runSend(message, onComplete);
    else
      nbTrans.scheduleTask(new Runnable() {
        public void run() { runSend(message, onComplete); }
      });
  }

  public void destroyWithoutCallback() {
    if (nbTrans.inAsyncIo(Thread.currentThread()))
      runDestroyWithoutCallback();
    else
      nbTrans.scheduleTask(new Runnable() {
        public void run() { runDestroyWithoutCallback(); }
      });
  }

  public void destroy() {
    if (nbTrans.inAsyncIo(Thread.currentThread()))
      runDestroy();
    else
      nbTrans.scheduleTask(new Runnable() {
        public void run() { runDestroy(); }
      });
  }

  // time unit is second.
  public void enableTimeout(
      int readIdle, int writeIdle, int allIdle, Consumer<ChannelIdleEvent> callback) {
    if (nbTrans.inAsyncIo(Thread.currentThread()))
      runEnableTimeout(readIdle, writeIdle, allIdle, callback);
    else
      nbTrans.scheduleTask(new Runnable() {
        public void run() { runEnableTimeout(readIdle, writeIdle, allIdle, callback); }
      });
  }

  private void runEnableTimeout(
      int readIdle, int writeIdle, int allIdle, Consumer<ChannelIdleEvent> callback) {
    timeoutTask = new ChannelIdleTimeout(this, readIdle, writeIdle, allIdle, callback);
  }

  private void runSend(byte[] message, Consumer<WriteComplete> onComplete) {
    if (state != IsOpen || !isWritable) return;

    writer.enqueue(new OutboundEntry(message, onComplete));

    try {
      SelectionKey key = channel.keyFor(nbTrans.selector);
      if (key == null)
        key = channel.register(nbTrans.selector, SelectionKey.OP_WRITE);
      else {
        int intOps = key.interestOps();
        if ((intOps & SelectionKey.OP_WRITE) == 0)
          key.interestOps(intOps | SelectionKey.OP_WRITE);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error in registering SelectionKey.OP_WRITE.", e);
    }
  }

  protected void handle(byte[] bytes) {
    if (timeoutTask != null) {
      timeoutTask.reScheduleReadIdleTimeout();
      timeoutTask.reScheduleAllIdleTimeout();
    }
    nbTrans.inboundMessageHandler.accept(new ContextualRawMessage(bytes, this));
  }

  private void runDestroyWithoutCallback() {
    if (state == IsClosed) return;
    state = IsClosed;
    isWritable = false;
    if (timeoutTask != null) timeoutTask.destroy();
    try {
      channel.close();
    } catch (Exception e) {
      logger.log(Level.INFO, "Ignore error at SocketChannel.close()", e);
    } finally {
      reader = null;
      writer = null;
      channel = null;
    }
  }

  private void runDestroy() {
    runDestroyWithoutCallback();
    nbTrans.onConnectionCloseHandler.accept(this);
  }

  public void flushThenDestroy() {
    if (nbTrans.inAsyncIo(Thread.currentThread()))
      runFlushThenDestroy();
    else
      nbTrans.scheduleTask(new Runnable() {
        public void run() { runFlushThenDestroy(); }
      });
  }

  private void runFlushThenDestroy() {
    if (state == IsOpen) {
      state = IsClosing;
      if (writer.hasNothingToWrite()) destroy();
    }
  }

  protected void read() {
    if (state == IsClosed) {
      logger.log(Level.INFO, "SocketContext was destroyed, skipped read().");
      return;
    }
    reader.read(channel);
  }

  protected void write(SelectionKey key) {
    if (!isWritable || state == IsClosed) {
      logger.log(Level.INFO,
        "SocketContext is not writable ({0}) or was destroyed ({1}), skipped read().",
        new Object[] {isWritable, (state == IsClosed)});
      return;
    }

    int r = writer.write(channel);
    if (r < 0) {
      isWritable = false;
      if (Config.onWriteErrorCloseByShortReadTimeout) {
        if (timeoutTask != null) timeoutTask.destroy();
        runEnableTimeout(Config.shortReadIdle, 0, 0, new Consumer<ChannelIdleEvent>() {
          public void accept(ChannelIdleEvent ev) {
            if (ev.isReadIdle()) ev.sockCtx().destroy();
          }
        });
      }
    }
    try {
      if (!isWritable || writer.hasNothingToWrite())
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    } catch (CancelledKeyException | NullPointerException e) {
      logger.log(Level.WARNING, "Error in SelectionKey deregistering OP_WRITE", e);
    }
  }

  public InetSocketAddress remoteAddress() { return remoteAddress; }
  public InetSocketAddress remoteIdentity() { return remoteIdentity; }
  public void remoteIdentity(InetSocketAddress isa) { remoteIdentity = isa; }
}
