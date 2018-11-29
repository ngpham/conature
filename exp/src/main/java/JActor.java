package np.conature.exp;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class JActor {
  protected static final AtomicIntegerFieldUpdater<JActor> __state =
    AtomicIntegerFieldUpdater.newUpdater(JActor.class, "state");

  protected static final AtomicIntegerFieldUpdater<JActor> __terminated =
    AtomicIntegerFieldUpdater.newUpdater(JActor.class, "terminated");

  @SuppressWarnings({"unused"})
  protected volatile int state = 0;  // 0 suspended, 1 (scheduled for) running

  protected volatile int terminated = 0; // 0 alive, 1 dead

  protected void suspend() { __state.set(this, 0); }

  // mutual exclusive and memory barrier
  protected boolean unblock() { return __state.compareAndSet(this, 0, 1); }

  protected boolean tryTerminate() { return __terminated.compareAndSet(this, 0, 1); }
}
