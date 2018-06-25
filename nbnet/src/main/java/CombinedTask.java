
package np.conature.nbnet;

import java.util.function.Consumer;

import np.conature.util.ConQueue;
import np.conature.util.MpscQueue;

class CombinedTask implements Runnable {
  private ConQueue<Runnable> tasks = new MpscQueue<>();

  @Override
  public void run() {
    tasks.batchConsume((x) -> { x.run(); });
  }

  public void offer(Runnable t) { tasks.offer(t); }
  public boolean isEmpty() { return tasks.isEmpty(); }
}
