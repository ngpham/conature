
package np.conature.nbnet;

public class ContextualRawMessage {
  public byte[] rawBytes;
  public SocketContext context;

  public ContextualRawMessage(byte[] bytes, SocketContext ctx) {
    rawBytes = bytes;
    context = ctx;
  }
}
