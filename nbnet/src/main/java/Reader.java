
package np.conature.nbnet;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.io.IOException;

public class Reader {
  static final int ReadingHeader = 1;
  static final int ReadingPayload = 2;

  private int state;
  private ByteBuffer header;
  private ByteBuffer payload;

  protected SocketContext context;

  public Reader() { header = ByteBuffer.allocate(2); reset(); }

  private void reset() {
    state = ReadingHeader;
    header.clear();
    payload = null;
  }

  protected int read(SocketChannel channel) {
    int r = 0;
    try {
      while(true) {
        if (state == ReadingHeader) {
          r = channel.read(header);
          if (r <= 0) break;
          if (header.limit() == header.position()) {
            header.flip();
            int size = header.getShort() & 0xFFFF;
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
      System.out.println("Exception read() at SocketChannel. " + e);
      r = -1;
    }

    return r;
  }

}
