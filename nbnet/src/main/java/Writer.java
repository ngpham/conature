
package np.conature.nbnet;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.io.IOException;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.logging.Logger;
import java.util.logging.Level;

class Writer {
  private static final int MaxRetries = 4; // not much of critical for now
  private static final int SendingNotInitialized = 1;
  private static final int SendingInProgress = 2;

  private static final Logger logger = Logger.getLogger(Writer.class.getName());

  // Dynamic payload with dynamic header length, limited by jvm array size
  // byte0: length --- value in little-endian. For example:
  // 3         b0 b1 b2
  private static ByteBuffer computeHeader(byte[] bytes) {
    int size = bytes.length;
    int numBytesForSize = Integer.numberOfTrailingZeros(Integer.highestOneBit(size)) / 8 + 1;

    ByteBuffer bb = ByteBuffer.allocate(numBytesForSize + 1);
    bb.put((byte)(numBytesForSize & 0xff));
    while (size > 0) {
      bb.put((byte)(size & 0xff));
      size = size >>> 8;
    }
    bb.flip();
    return bb;
  }

  private Queue<OutboundEntry> pendingMessages;
  private ByteBuffer header;
  private int state;
  private OutboundEntry currentEntry;
  private ByteBuffer[] toWrite;
  protected SocketContext context;

  public Writer() {
    pendingMessages = new ArrayDeque<OutboundEntry>();
    toWrite = new ByteBuffer[2];
    state = SendingNotInitialized;
  }

  protected void enqueue(OutboundEntry entry) {
    pendingMessages.offer(entry);
  }

  protected boolean hasNothingToWrite() {
    return (pendingMessages.isEmpty() && (state == SendingNotInitialized));
  }

  protected boolean hasSomethingToWrite() { return !hasNothingToWrite(); }

  protected int write(SocketChannel channel) {
    int r = 0;
    int retries = MaxRetries;

    try {
      while (retries > 0 && hasSomethingToWrite()) {
        if (state == SendingNotInitialized) {
          currentEntry = pendingMessages.poll();

          if (currentEntry != null) {
            ByteBuffer sendBuffer = ByteBuffer.wrap(currentEntry.toSend);
            toWrite[0] = computeHeader(sendBuffer.array());
            toWrite[1] = sendBuffer;
            state = SendingInProgress;
          }
        }
        if (state == SendingInProgress) {
          r = (int) channel.write(toWrite);
          if (r == 0) retries -= 1;
          if (r < 0) break;

          if (toWrite[1].limit() == toWrite[1].position()) {
            toWrite[0] = null;
            toWrite[1] = null;
            state = SendingNotInitialized;

            if (context.timeoutTask != null) {
              context.timeoutTask.reScheduleWriteIdleTimeout();
              context.timeoutTask.reScheduleAllIdleTimeout();
            }
            currentEntry.onComplete.accept(new WriteComplete(context));
            currentEntry = null;
          }
        }
      }  // end while
    } catch (Exception e) {
      logger.log(Level.INFO, "Error writing to SocketChannel.", e);

      if (currentEntry != null)
        currentEntry.onComplete.accept(new WriteComplete(context, e));

      pendingMessages.clear();
      state = SendingNotInitialized;
      r = -1;
    }

    return r;
  }
}
