
package np.conature.nbnet;

import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.List;
import java.io.IOException;

public class SocketContext {
  static final int IsOpened = 1;
  static final int IsClosing = 2;
  static final int IsClosed = 3;

  protected Reader reader;
  protected Writer writer;
  protected Consumer<ContextualRawMessage> readHandler;
  protected SocketChannel channel;
  protected NbTransport nbTrans;
  private InetSocketAddress remoteAddress;
  private InetSocketAddress remoteIdentity = null;
  private RegisterWriteOp registerWriteOp = null;

  private volatile int state;

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

    state = IsOpened;
    registerWriteOp = new RegisterWriteOp(this);
  }

  public int send(byte[] message) {
    if (state != IsOpened) return -1;

    writer.enqueue(message);
    nbTrans.scheduleRegistration(registerWriteOp);

    return 0;
  }

  protected void handle(byte[] bytes) {
    nbTrans.inboundMessageHandler.accept(new ContextualRawMessage(bytes, this));
  }

  protected void destroy() {
    if (state == IsClosed) return;

    state = IsClosed;
    reader = null;
    writer = null;

    try {
      channel.close();
    } catch (Exception e) {
      System.out.println("Ignore error at SocketChannel close(). " + e);
    } finally {
      channel = null;
    }

    nbTrans.onConnectionCloseHandler.accept(remoteAddress);
  }

  public void flushThenDestroy() {
    if (state == IsOpened) {
      state = IsClosing;
      if (writer.hasNothingToWrite()) destroy();
    }
  }

  protected void read() {
    if (state == IsClosed) {
      System.out.println("SocketContext was destroyed. Ignore read().");
      return;
    }
    int r = reader.read(channel);
    if (r < 0) destroy();
  }

  protected int write() {
    if (state == IsClosed) {
      System.out.println("SocketContext was destroyed. Ignore write().");
      return -1;
    }
    int r = writer.write(channel);
    if (r < 0 || state == IsClosing) destroy();
    return r;
  }

  public InetSocketAddress remoteAddress() { return remoteAddress; }
  public InetSocketAddress remoteIdentity() { return remoteIdentity; }
  public void remoteIdentity(InetSocketAddress isa) { remoteIdentity = isa; }

  private class RegisterWriteOp implements Runnable {
    private SocketContext context;
    protected RegisterWriteOp(SocketContext sc) { context = sc; }

    public void run() {
      try {
        if (context.writer.hasNothingToWrite()) return;

        SelectionKey key = context.channel.keyFor(nbTrans.selector);
        if (key == null)
          key = context.channel.register(nbTrans.selector, SelectionKey.OP_WRITE);
        else {
          int intOps = key.interestOps();
          if ((intOps & SelectionKey.OP_WRITE) == 0)
            key.interestOps(intOps | SelectionKey.OP_WRITE);
        }
      } catch (Exception e) {
        System.out.println("Some error, SocketContext must have been destroyed, "
          + "or will be destroyed by select read(). " + e);
      }
    }
  }

}
