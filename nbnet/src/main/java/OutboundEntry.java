
package np.conature.nbnet;

import java.util.function.Consumer;
import np.conature.util.JMisc;

class OutboundEntry {
  @SuppressWarnings("unchecked")
  static Consumer<WriteComplete> EmptyOnComplete = (Consumer<WriteComplete>) JMisc.EmptyFunc;

  protected byte[] toSend;
  protected Consumer<WriteComplete> onComplete;

  public OutboundEntry(byte[] toSend, Consumer<WriteComplete> onComplete) {
    this.toSend = toSend;
    this.onComplete = onComplete;
  }
}
