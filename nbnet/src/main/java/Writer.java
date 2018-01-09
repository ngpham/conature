
package np.conature.nbnet;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.io.IOException;

public class Writer {
  static final int SendingNotInitialized = 1;
  static final int SendingInProgress = 2;

  private Queue<ByteBuffer> pendingMessages;
  private ByteBuffer header;
  private int state;
  private ByteBuffer[] toWrite;
  protected SocketContext context;

  public Writer() {
    pendingMessages = new ConcurrentLinkedQueue<ByteBuffer>(); // to be replaced by MPSC queue
    header = ByteBuffer.allocate(2);
    toWrite = new ByteBuffer[2];
    toWrite[0] = header;
    state = SendingNotInitialized;
  }

  public void enqueue(byte[] bytes) { pendingMessages.offer(ByteBuffer.wrap(bytes)); }

  public boolean hasNothingToWrite() {
    return (pendingMessages.isEmpty() && (state == SendingNotInitialized));
  }

  public boolean hasSomethingToWrite() { return !hasNothingToWrite(); }

  public int write(SocketChannel channel, SelectionKey key) {
    int r = 0;
    try {
      while (true) {
        if (hasNothingToWrite()) { key.interestOps(0); break; }

        if (state == SendingNotInitialized) {
          ByteBuffer sendBuffer = pendingMessages.poll();
          if (sendBuffer != null) {
            toWrite[1] = sendBuffer;
            int size = sendBuffer.limit();
            header.clear();
            header.put((byte)((size >> 8) & 0xff)); header.put((byte)(size & 0xff));
            header.flip();
            state = SendingInProgress;
          }
        }
        if (state == SendingInProgress) {
          r = (int) channel.write(toWrite); // safe
          if (r <= 0) break;
          if (toWrite[1].limit() == toWrite[1].position()) state = SendingNotInitialized;
        }
      }  // end while
    } catch (IOException e) {
      System.out.println("IOException in write() socket channel.");
      e.printStackTrace();
    } catch (Exception ae) {
      ae.printStackTrace();
      r = -1;
    }

    return r;
  }

}
