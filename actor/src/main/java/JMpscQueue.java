package np.conature.actor;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class JMpscQueue<T> {
  protected static final AtomicReferenceFieldUpdater __head =
    AtomicReferenceFieldUpdater.newUpdater(JMpscQueue.class, JMpscQueue.Node.class, "head");

  protected static final AtomicReferenceFieldUpdater __tail =
    AtomicReferenceFieldUpdater.newUpdater(JMpscQueue.class, JMpscQueue.Node.class, "tail");

  private volatile Node<T> head;
  private volatile Node<T> tail;

  protected JMpscQueue() {
    final Node<T> n = new Node<T>();
    __head.set(this, n);
    __tail.set(this, n);
  }

  public void add(T value) {
    Node<T> n = new Node(value);
    Node.__next.lazySet(__head.getAndSet(this, n), n);
  }

  public T take() {
    Node<T> n = tail.next;  T ret = null;
    if (n != null) {
      ret = n.value;  n.value = null;
      __tail.lazySet(this, n);
    }
    return ret;
  }

  public boolean isEmpty() { return tail.next == null; }
  public boolean isLoaded() { return !isEmpty(); }

  public void batchConsume(int i, Consumer<T> func) {
    Node<T> n = tail;
    while (n.next != null && i > 0) {
      n = n.next; i--;
      func.accept(n.value);  n.value = null;
    }
    if (n != tail) __tail.lazySet(this, n);
  }

  private static final class Node<T> {
    protected static final AtomicReferenceFieldUpdater __next =
      AtomicReferenceFieldUpdater.newUpdater(JMpscQueue.Node.class, JMpscQueue.Node.class, "next");

    protected T value = null;
    private volatile Node<T> next = null;
    protected Node(T v) { value = v; }
    protected Node() { }
  }
}
