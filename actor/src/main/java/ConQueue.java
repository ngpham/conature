
package np.conature.actor;

import java.util.function.Consumer;

public interface ConQueue<T> {
  void offer(T value);
  T poll();
  void batchConsume(int i, Consumer<T> func);
  void batchConsume(Consumer<T> func);
  boolean isEmpty();
  boolean isLoaded();
}
