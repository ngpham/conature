
package np.conature.nbnet;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.io.IOException;

public class OutboundProxy implements Runnable {
  private Server server;
  private SocketChannel channelHint;

  public OutboundProxy(Server server) { this.server = server; }

  @Override
  public void run() {
    // check if the suggested channel still needs to write.
    // memory visibilty is ensured for the corresponding SocketContext.send()
    SelectionKey key = channelHint.keyFor(server.outboundSelector);
    SocketContext ctx = (SocketContext) key.attachment();
    if (ctx.writer.hasSomethingToWrite()) {
      key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }
    writeSelect();
  }

  public OutboundProxy setWriteChannel(SocketChannel channel) {
    channelHint = channel;
    return this;
  }

  private void writeSelect() {
    int readyChannels = 0;
    try {
      readyChannels = server.outboundSelector.select();
    } catch (IOException e) {
      System.out.println("Outbound select() error. Skip writting task.");
      e.printStackTrace();
      return;
    }

    if (readyChannels > 0) {
      Set<SelectionKey> selectedKeys = server.outboundSelector.selectedKeys();
      Iterator<SelectionKey> it = selectedKeys.iterator();
      while (it.hasNext()) {
        SelectionKey key = it.next();
        if (key.isValid() && key.isWritable()) {
          SocketContext context = (SocketContext) key.attachment();
          context.writeWithKey(key);
        }
        it.remove();
      }
    }
  }

}
