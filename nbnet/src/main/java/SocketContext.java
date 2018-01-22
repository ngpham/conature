
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
  protected Server server;
  private InetSocketAddress remoteAddress;
  private InetSocketAddress remoteIdentity = null;

  private volatile int state;

  public SocketContext(
      Server server,
      SocketChannel channel,
      Reader reader, Writer writer,
      InetSocketAddress remoteAddress) {
    this.server = server;
    this.channel = channel;
    this.reader = reader; reader.context = this;
    this.writer = writer; writer.context = this;
    this.remoteAddress = remoteAddress;

    state = IsOpened;
  }

  public int send(byte[] message) {
    if (state != IsOpened) return -1;
    try {
      writer.enqueue(message);
    } catch (NullPointerException e) {
      System.out.println("Racing with SocketContext.destroy(). " + e);
      return -1;
    }
    server.asyncOutbound.submit(new Runnable() {
      public void run() { server.outboundProxy.writeWithContext(SocketContext.this); }
    });
    return 0;
  }

  protected void handle(byte[] bytes) {
    server.inboundMessageHandler.accept(new ContextualRawMessage(bytes, this));
  }

  protected void destroy() {
    if (state == IsClosed) return;

    state = IsClosed;
    reader = null;
    writer = null;

    try {
      channel.close();
    } catch (Exception e) {
      System.out.println("Ignore error at SocketChannel close() " + e);
    } finally {
      channel = null;
    }

    server.onConnectionCloseHandler.accept(remoteAddress);
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
}
