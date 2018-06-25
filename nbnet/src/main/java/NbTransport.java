
package np.conature.nbnet;

import java.util.Collection;
import java.util.Iterator;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.InetSocketAddress;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.IOException;

import np.conature.util.ConQueue;
import np.conature.util.MpscQueue;
import np.conature.util.Scheduler;
import np.conature.util.HashedWheelScheduler;

public class NbTransport {
  private static final int IsActive = 0;
  private static final int IsShutdown = 1;
  private static final int IsStopped = 2;

  protected static Consumer<? extends Object> EmptyHandler = (x) -> {};

  private static final Logger logger = Logger.getLogger(NbTransport.class.getName());

  private ServerSocketChannel acceptor;

  private Thread asyncIo;
  private CombinedTask combinedTask;
  private ConQueue<Runnable> asyncIoQueue;

  protected Selector selector;
  protected Scheduler scheduler;

  private int port;
  private AtomicInteger state;

  @SuppressWarnings("unchecked")
  protected Consumer<ContextualRawMessage> inboundMessageHandler =
    (Consumer<ContextualRawMessage>) EmptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<SocketContext> onConnectionEstablishedHandler =
    (Consumer<SocketContext>) EmptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<InetSocketAddress> onConnectionAttemptFailureHandler =
    (Consumer<InetSocketAddress>) EmptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<SocketContext> onConnectionCloseHandler =
    (Consumer<SocketContext>) EmptyHandler;

  public NbTransport(int port) {
    this(port, HashedWheelScheduler.apply());
  }

  public NbTransport(int port, Scheduler scheduler) {
    this.port = port;
    this.scheduler = scheduler;
    this.state = new AtomicInteger(-1);
  }

  public NbTransport setInboundMessageHandler(Consumer<ContextualRawMessage> handler) {
    this.inboundMessageHandler = handler;
    return this;
  }

  public NbTransport setOnConnectionEstablishedHandler(Consumer<SocketContext> handler) {
    this.onConnectionEstablishedHandler = handler;
    return this;
  }

  public NbTransport setOnConnectionAttemptFailureHandler(Consumer<InetSocketAddress> handler) {
    this.onConnectionAttemptFailureHandler = handler;
    return this;
  }

  public NbTransport setOnConnectionCloseHandler(Consumer<SocketContext> handler) {
    this.onConnectionCloseHandler = handler;
    return this;
  }

  public void start(boolean serverMode) throws IOException {
    state.set(IsActive);
    selector = Selector.open();

    if (serverMode) {
      acceptor = ServerSocketChannel.open();
      acceptor.configureBlocking(false);
      acceptor.bind(new InetSocketAddress(port));
      acceptor.register(selector, SelectionKey.OP_ACCEPT);
    }

    combinedTask = new CombinedTask();

    asyncIoQueue = new MpscQueue<Runnable>();
    asyncIoQueue.offer(new SelectRecurrence());

    asyncIo = new Thread(new Runnable() {
      @Override public void run() {
        Runnable task = asyncIoQueue.poll();
        while (task != null) {
          task.run();
          task = asyncIoQueue.poll();
        }
      }
    });

    asyncIo.setDaemon(true);
    asyncIo.start();
  }

  // note that we donot shutdown the scheduler
  public void shutdown() {
    if (!state.compareAndSet(IsActive, IsShutdown)) return;
    try {
      if (acceptor != null) acceptor.close();
    } catch (IOException e) {
      logger.log(Level.INFO, "Ignore error during socket acceptor shutdown:", e);
    }

    logger.log(Level.INFO, "NbTransport is shuting down.");

    scheduleTask(new Runnable() {
      public void run() {
        for (SelectionKey key: selector.keys()) {
          if (key.isValid() && (key.attachment() instanceof SocketContext)) {
            SocketContext ctx = (SocketContext) key.attachment();
            ctx.destroy();
          }
        }
        state.set(IsStopped);
        try {
          selector.close();
        } catch (IOException e) {
          logger.log(Level.INFO, "Ignore error during NbTransport shutdown:", e);
        }
      }
    });
  }

  public void connect(InetSocketAddress address) {
    try {
      SocketChannel socket = SocketChannel.open();
      socket.configureBlocking(false);
      socket.connect(address);

      boolean ok = scheduleTask(new Runnable() {
        public void run() {
          try {
            socket.register(selector, SelectionKey.OP_CONNECT, address);
          } catch (IOException e) {
            logger.log(Level.WARNING, "Cannot register select OP_CONNECT.", e);
            try {
              socket.close();
            } catch (IOException ex) {
              logger.log(Level.INFO, "Ignore error during closing newly accepted socket.", e);
            }
            onConnectionAttemptFailureHandler.accept(address);
          }
        }
      });
      if (!ok) socket.close();
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error in NbTransport.connect().", e);
    }
  }

  protected boolean inAsyncIo(Thread thread) {
    return thread == asyncIo;
  }

  private void setupSocketContext(SocketChannel channel, InetSocketAddress id)
  throws IOException {
    try {
      SocketContext context = new SocketContext(
        this,
        channel,
        new Reader(), new Writer(),
        (InetSocketAddress) channel.getRemoteAddress());

      if (id != null) context.remoteIdentity(id);
      channel.register(selector, SelectionKey.OP_READ, context);

      onConnectionEstablishedHandler.accept(context);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to complete setup SocketContext.", e);
    }
  }

  private void finishAndSetupSocketContext(InetSocketAddress orgAttempt, SocketChannel channel) {
    try {
      boolean connected = channel.finishConnect();
      if (connected) setupSocketContext(channel, orgAttempt);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to complete an outbound connection attempt.", e);
      onConnectionAttemptFailureHandler.accept(orgAttempt);
    }
  }

  private void accept(SelectionKey key) {
    ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
    try {
      SocketChannel socket = ssc.accept();
      if (socket == null) throw new IOException();
      socket.configureBlocking(false);
      setupSocketContext(socket, null);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to accept an incomming connection", e);
    }
  }

  private void consumeKeys(Collection<SelectionKey> keys) {
    Iterator<SelectionKey> it = keys.iterator();
    while (it.hasNext()) {
      SelectionKey key = it.next();

      if (key.isValid() && key.isAcceptable()) {
        accept(key);
      } else if (key.isValid() && key.isReadable()) {
        SocketContext context = (SocketContext) key.attachment();
        context.read();
      } else if (key.isValid() && key.isConnectable()) {
        InetSocketAddress orgAttempt = (InetSocketAddress) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        finishAndSetupSocketContext(orgAttempt, channel);
      } else if (key.isValid() && key.isWritable()) {
        SocketContext context = (SocketContext) key.attachment();
        context.write(key);
      }

      it.remove();
    }
  }

  protected boolean scheduleTask(Runnable task) {
    if (state.get() == IsStopped)
      return false;

    combinedTask.offer(task);
    asyncIoQueue.offer(combinedTask);
    selector.wakeup();
    return true;
  }

  private class SelectRecurrence implements Runnable {
    public void run() {
      try {
        int readyChannels = 0;
        readyChannels = NbTransport.this.selector.select();

        if (readyChannels == 0) {
          try {
            if (NbTransport.this.combinedTask.isEmpty()) Thread.sleep(1000);
          } catch (InterruptedException ie) {}
        }
        if (readyChannels > 0) {
          NbTransport.this.consumeKeys(NbTransport.this.selector.selectedKeys());
        }
      } catch (Exception e) {
        logger.log(Level.INFO, "Error in Selector.select(), to retry later.", e);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {}
      } finally {
        if (NbTransport.this.state.get() == IsActive)
          NbTransport.this.asyncIoQueue.offer(this);
      }
    }
  }

}
