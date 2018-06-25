
package np.conature.nbnet;

import java.util.function.Consumer;

class OutboundEntry {
  static Consumer<WriteComplete> EmptyOnComplete = (x) -> {};

  protected byte[] toSend;
  protected Consumer<WriteComplete> onComplete;

  public OutboundEntry(byte[] toSend, Consumer<WriteComplete> onComplete) {
    this.toSend = toSend;
    this.onComplete = onComplete;
  }
}
