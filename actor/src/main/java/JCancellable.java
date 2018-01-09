package np.conature.actor;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class JCancellable {
  protected static final AtomicIntegerFieldUpdater<JCancellable> __state =
    AtomicIntegerFieldUpdater.newUpdater(JCancellable.class, "state");

  @SuppressWarnings({"unused"})
  protected volatile int state = 1;

  public void cancel() { __state.compareAndSet(this, 1, 0); }
}
