
package np.conature.nbnet;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.io.IOException;

public class OutboundProxy implements Runnable {
  private Server server;
  private boolean isScheduledForSelect;

  public OutboundProxy(Server server) { this.server = server; }

  public void writeWithContext(SocketContext context) {
    int r = context.write();
    if ((r >= 0) && context.writer.hasSomethingToWrite()) {
      SelectionKey key = context.channel.keyFor(server.outboundSelector);
      key.interestOps(SelectionKey.OP_WRITE);

      if (!isScheduledForSelect) {
        isScheduledForSelect = true;
        server.asyncThread.submit(this);
      }
    }
  }

  @Override
  public void run() {
    int readyChannels = 0;
    int r = 0;
    boolean shouldLoop = false;

    while (shouldLoop) {
      try {
        readyChannels = server.outboundSelector.select();
      } catch (IOException e) {
        System.out.println("Outbound select() error. Skip writting task.");
        e.printStackTrace();
        isScheduledForSelect = false;
        return;
      }

      Set<SelectionKey> selectedKeys = server.outboundSelector.selectedKeys();
      Iterator<SelectionKey> it = selectedKeys.iterator();
      while (it.hasNext()) {
        SelectionKey key = it.next();
        if (key.isValid() && key.isWritable()) {
          SocketContext context = (SocketContext) key.attachment();
          r = context.write();
          if ((r >= 0) && context.writer.hasSomethingToWrite()) {
            shouldLoop = true;
          } else if (r >= 0) {
            try {
              key.interestOps(0);
            } catch (Exception e) {
              System.out.println("Ignore CancelledKeyException: " + e);
            }
          }
        }
        it.remove();
      }
    } // while (shouldLoop)

    isScheduledForSelect = false;
  }

}
