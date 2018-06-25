
package np.conature.nbnet;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.IOException;

class Reader {
  private static final int ReadingPreHeader = 1;
  private static final int ReadingHeader = 2;
  private static final int ReadingPayload = 3;

  private static final Logger logger = Logger.getLogger(Reader.class.getName());

  private int state;
  private ByteBuffer preHeader;
  private ByteBuffer header;
  private ByteBuffer payload;

  protected SocketContext context;

  public Reader() {
    preHeader = ByteBuffer.allocate(1);
    reset();
  }

  private void reset() {
    state = ReadingPreHeader;
    preHeader.clear();
    header = null;
    payload = null;
  }

  protected void read(SocketChannel channel) {
    int r = 0;
    try {
      while(true) {
        if (state == ReadingPreHeader) {
          r = channel.read(preHeader);
          if (r <= 0) break;
          if (preHeader.limit() == preHeader.position()) {
            preHeader.flip();
            int size = preHeader.get() & 0xff;
            header = ByteBuffer.wrap(new byte[size]);
            state = ReadingHeader;
          }
        }

        if (state == ReadingHeader) {
          r = channel.read(header);
          if (r <= 0) break;
          if (header.limit() == header.position()) {
            header.flip();
            int size = 0;
            int factor = 1;
            while (header.position() < header.limit()) {
              size = size + (header.get() & 0xff) * factor;
              factor = factor << 8;
            }
            payload = ByteBuffer.wrap(new byte[size]);
            state = ReadingPayload;
          }
        }

        if (state == ReadingPayload) {
          r = channel.read(payload);
          if (r <= 0) break;
          if (payload.limit() == payload.position()) {
            context.handle(payload.array());
            reset();
          }
        }
      }
    } catch (IOException e) {
      logger.log(Level.WARNING, "Error in Reader.read()", e);
      r = -1;
    }

    if (r < 0) context.destroy();
    return;
  }

}
