
package np.conature.nbnet;

import np.conature.actor.ConQueue;
import np.conature.actor.MpscQueue;

import java.util.function.Consumer;

public class CombinedTask implements Runnable {
  private ConQueue<Runnable> tasks = new MpscQueue<>();

  @Override
  public void run() {
    tasks.batchConsume((x) -> { x.run(); });
  }

  public void offer(Runnable t) { tasks.offer(t); }
  public boolean isEmpty() { return tasks.isEmpty(); }
}
