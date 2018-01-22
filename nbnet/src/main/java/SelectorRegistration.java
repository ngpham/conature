
package np.conature.nbnet;

import np.conature.actor.ConQueue;
import np.conature.actor.MpscQueue;

import java.util.function.Consumer;

public class SelectorRegistration implements Runnable {
  protected ConQueue<Consumer<Void>> tasks = new MpscQueue<>();

  @Override
  public void run() {
    tasks.batchConsume((x) -> { x.accept(null); });
  }

  protected void offer(Consumer<Void> t) { tasks.offer(t); }
}
