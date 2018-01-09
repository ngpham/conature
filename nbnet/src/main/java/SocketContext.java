
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

  public void send(byte[] message) {
    writer.enqueue(message);
    // set key here, if OutboundProxy is running, it will write asa message is visible to Writer.
    SelectionKey key = channel.keyFor(server.outboundSelector);
    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    // memory visibility can be delayed, running OutboundProxy-Writer might have
    // decided that there is nothing to write.
    // Thus scheduled OutboundProxy must set the key. For the scheduled task,
    // memory visibility is ensured by jvm specs.
    server.asyncThread.submit(server.outboundProxy.setWriteChannel(channel));
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
      e.printStackTrace();
    }
  }

  protected void read() {
    int r = reader.read(channel);
    if (r < 0) destroy();
  }

  protected void writeWithKey(SelectionKey key) {
    int r = writer.write(channel, key);
    if (r < 0) destroy();
  }
}
