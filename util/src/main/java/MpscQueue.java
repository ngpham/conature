package np.conature.util;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "rawtypes"})
public class MpscQueue<T> implements ConQueue<T> {
  protected static final AtomicReferenceFieldUpdater __head =
    AtomicReferenceFieldUpdater.newUpdater(MpscQueue.class, MpscQueue.Node.class, "head");

  protected static final AtomicReferenceFieldUpdater __tail =
    AtomicReferenceFieldUpdater.newUpdater(MpscQueue.class, MpscQueue.Node.class, "tail");

  private volatile Node<T> head;
  private volatile Node<T> tail;

  public MpscQueue() {
    final Node<T> n = new Node<T>();
    __head.set(this, n);
    __tail.set(this, n);
  }

  @Override
  public void offer(T value) {
    Node<T> n = new Node(value);
    Node.__next.lazySet(__head.getAndSet(this, n), n);
  }

  @Override
  public T poll() {
    Node<T> n = tail.next;  T ret = null;
    if (n != null) {
      ret = n.value;  n.value = null;
      __tail.lazySet(this, n);
    }
    return ret;
  }

  @Override
  public boolean isEmpty() { return tail.next == null; }
  @Override
  public boolean isLoaded() { return !isEmpty(); }

  @Override
  public void batchConsume(int i, Consumer<T> func) {
    Node<T> n = tail;
    while (n.next != null && i > 0) {
      n = n.next; i--;
      func.accept(n.value);  n.value = null;
    }
    if (n != tail) __tail.lazySet(this, n);
  }

  @Override
  public void batchConsume(Consumer<T> func) {
    Node<T> n = tail;
    while (n.next != null) {
      n = n.next;
      func.accept(n.value);  n.value = null;
    }
    if (n != tail) __tail.lazySet(this, n);
  }

  private static final class Node<T> {
    protected static final AtomicReferenceFieldUpdater __next =
      AtomicReferenceFieldUpdater.newUpdater(MpscQueue.Node.class, MpscQueue.Node.class, "next");

    protected T value = null;
    private volatile Node<T> next = null;
    protected Node(T v) { value = v; }
    protected Node() { }
  }
}
