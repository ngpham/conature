
package np.conature.nbnet;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Set;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;

public class OutboundProxy implements Runnable {
  private Server server;
  private boolean isScheduledForSelect;

  public OutboundProxy(Server server) { this.server = server; }

  public void writeWithContext(SocketContext context) {
    int r = context.write();
    if ((r >= 0) && context.writer.hasSomethingToWrite()) {
      try {
        SelectionKey key = context.channel.keyFor(server.outboundSelector);
        if (key == null)
          key = context.channel.register(server.outboundSelector, SelectionKey.OP_WRITE);
        else
          key.interestOps(SelectionKey.OP_WRITE);

        if (!isScheduledForSelect) {
          isScheduledForSelect = true;
          server.asyncOutbound.submit(this);
        }
      } catch (ClosedChannelException e) {
        System.out.println("Channel close during write select register." + e);
        context.destroy();
      }
    }
  }

  @Override
  public void run() {
    int readyChannels = 0;
    int r = 0;
    boolean hasPendingWrite = true;
    int retries = 4;

    while (retries > 0 && hasPendingWrite) {
      retries -= 1;
      hasPendingWrite = false;

      try {
        readyChannels = server.outboundSelector.select();
      } catch (IOException e) {
        System.out.println("Outbound select() error. Skip writting task." + e);
        // sleep for a while
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
            hasPendingWrite = true;
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
    } // while

    isScheduledForSelect = hasPendingWrite;
  }

}
