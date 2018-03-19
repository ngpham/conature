
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
import java.io.IOException;
import java.nio.channels.CancelledKeyException;

import np.conature.util.ConQueue;
import np.conature.util.MpscQueue;

public class NbTransport {
  private static Consumer<? extends Object> emptyHandler = (x) -> { };

  private ServerSocketChannel acceptor;

  private Thread asyncIo;
  private CombinedTask combinedTask;
  private ConQueue<Runnable> asyncIoQueue;

  protected Selector selector;

  private int port;
  private volatile boolean isActive;

  @SuppressWarnings("unchecked")
  protected Consumer<ContextualRawMessage> inboundMessageHandler =
    (Consumer<ContextualRawMessage>) emptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<SocketContext> onConnectionEstablishedHandler =
    (Consumer<SocketContext>) emptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<InetSocketAddress> onConnectionAttemptFailureHandler =
    (Consumer<InetSocketAddress>) emptyHandler;

  @SuppressWarnings("unchecked")
  protected Consumer<InetSocketAddress> onConnectionCloseHandler =
    (Consumer<InetSocketAddress>) emptyHandler;

  public NbTransport(int port) { this.port = port; }

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

  public NbTransport setOnConnectionCloseHandler(Consumer<InetSocketAddress> handler) {
    this.onConnectionCloseHandler = handler;
    return this;
  }

  public void start() throws IOException {
    isActive = true;
    selector = Selector.open();

    acceptor = ServerSocketChannel.open();
    acceptor.configureBlocking(false);
    acceptor.bind(new InetSocketAddress(port));

    acceptor.register(selector, SelectionKey.OP_ACCEPT);

    combinedTask = new CombinedTask();

    asyncIoQueue = new MpscQueue<Runnable>();
    asyncIoQueue.offer(new SelectRecurrence());

    asyncIo = new Thread(new Runnable() {
      @Override public void run() {
        Runnable task = asyncIoQueue.poll();
        while (task != null && isActive) {
          task.run();
          task = asyncIoQueue.poll();
        }
      }
    });

    asyncIo.setDaemon(true);
    asyncIo.start();
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
      e.printStackTrace();
    }
  }

  private void finishAndSetupSocketContext(InetSocketAddress orgAttempt, SocketChannel channel) {
    try {
      boolean connected = channel.finishConnect();
      if (connected) setupSocketContext(channel, orgAttempt);
    } catch (IOException e) {
      System.out.println("Cannot complete an outbound connection attempt. " + e);
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
      System.out.println("Cannot accept a connection attempt. " + e);
    }
  }

  public void connect(InetSocketAddress address) {
    try {
      SocketChannel socket = SocketChannel.open();
      socket.configureBlocking(false);
      socket.connect(address);

      scheduleRegistration(new Runnable() {
        public void run() {
          try {
            socket.register(selector, SelectionKey.OP_CONNECT, address);
          } catch (Exception e) {
            System.out.println("Cannot register select OP_CONNECT." + e);
            onConnectionAttemptFailureHandler.accept(address);
          }
        }
      });
    } catch (Exception e) {
      System.out.println("Exception NbTransport.connect(). " + e);
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
        int r = context.write();
        if ((r >= 0) && context.writer.hasNothingToWrite()) {
          try {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
          } catch (CancelledKeyException e) {
            System.out.println("Ignore CancelledKeyException: " + e);
          }
        }
      }

      it.remove();
    }
  }

  public void shutdown() {
    try {
      isActive = false;
      acceptor.close();
      selector.wakeup();

      System.out.println("NbTransport is shutting down.");

      for (SelectionKey key: selector.keys()) {
        if (key.isValid() && (key.attachment() instanceof SocketContext)) {
          SocketContext ctx = (SocketContext) key.attachment();
          ctx.destroy();
        }
      }
      selector.close();
    } catch (Exception e) {
      System.out.println("Ignore error during nbTrans shutdown: " + e);
    }
    System.out.println("NbTransport was shutdown.");
  }

  protected void scheduleRegistration(Runnable task) {
    combinedTask.offer(task);
    asyncIoQueue.offer(combinedTask);
    selector.wakeup();
  }

  private class SelectRecurrence implements Runnable {
    public void run() {
      try {
        int readyChannels = 0;
        readyChannels = NbTransport.this.selector.select();

        if (readyChannels == 0) {
          try {
            if (NbTransport.this.combinedTask.isEmpty()) Thread.sleep(1000);
          } catch (InterruptedException ie) { }
        }
        if (readyChannels > 0) {
          NbTransport.this.consumeKeys(NbTransport.this.selector.selectedKeys());
        }
      } catch (Exception e) {
        System.out.println(e);
        try { Thread.sleep(1000); } catch (InterruptedException ie) { }
      } finally {
        if (NbTransport.this.isActive) NbTransport.this.asyncIoQueue.offer(this);
      }
    }
  }

}
