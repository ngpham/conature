package np.conature;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class JActor {
  protected static final AtomicIntegerFieldUpdater<JActor> __state =
    AtomicIntegerFieldUpdater.newUpdater(JActor.class, "state");

  @SuppressWarnings({"unused"})
  protected volatile int state = 0;  // 0 suspended, 1 running

  protected void suspend() { __state.set(this, 0); }
  protected boolean unblock() { return __state.compareAndSet(this, 0, 1); }
}
