
package np.conature.nbnet;

import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.util.function.Consumer;
import java.util.List;
import java.io.IOException;

public class SocketContext {
  protected Reader reader;
  protected Writer writer;
  protected Consumer<ContextualRawMessage> readHandler;
  protected SocketChannel channel;
  protected Server server;

  private volatile boolean isValid = true;

  public SocketContext(
      Server server,
      SocketChannel channel,
      Reader reader, Writer writer,
      Consumer<ContextualRawMessage> readHandler) {
    this.server = server;
    this.channel = channel;
    this.readHandler = readHandler;
    this.reader = reader; reader.context = this;
    this.writer = writer; writer.context = this;
  }

  public int send(byte[] message) {
    if (!isValid) return -1; // best effort
    try {
      writer.enqueue(message);
    } catch (NullPointerException e) {
      System.out.println("Racing with SocketContext.destroy(): " + e);
      return -1;
    }
    server.asyncThread.submit(new Runnable() {
      public void run() { server.outboundProxy.writeWithContext(SocketContext.this); }
    });
    return 0;
  }

  protected void handle(byte[] bytes) {
    readHandler.accept(new ContextualRawMessage(bytes, this));
  }

  protected void destroy() {
    System.out.println("Closing down SocketChannel " + this);
    isValid = false;
    reader = null;
    writer = null;
    readHandler = null;

    try { channel.close(); } catch (Exception e) {
      System.out.println("Ignore error at SocketChannel close() " + e);
    }
  }

  protected void read() {
    if (!isValid) {
      System.out.println("SocketContext was destroyed. Ignore read().");
      return;
    }
    int r = reader.read(channel);
    if (r < 0) destroy();
  }

  protected int write() {
    if (!isValid) {
      System.out.println("SocketContext was destroyed. Ignore write().");
      return -1;
    }
    int r = writer.write(channel);
    if (r < 0) destroy();
    return r;
  }
}
