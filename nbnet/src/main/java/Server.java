
package np.conature.nbnet;

import java.util.Set;
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
import java.nio.channels.ClosedChannelException;

public class Server {
  private static Consumer<? extends Object> emptyHandler = (x) -> { };

  private ServerSocketChannel acceptor;

  protected ExecutorService asyncInbound;
  protected ExecutorService asyncOutbound;

  protected OutboundProxy outboundProxy;
  private Runnable inboundSelectLoop;
  private SelectorRegistration registrationTask;

  protected Selector inboundSelector;
  protected Selector outboundSelector;

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

  public Server(int port) { this.port = port; }

  public Server setInboundMessageHandler(Consumer<ContextualRawMessage> handler) {
    this.inboundMessageHandler = handler;
    return this;
  }

  public Server setOnConnectionEstablishedHandler(Consumer<SocketContext> handler) {
    this.onConnectionEstablishedHandler = handler;
    return this;
  }

  public Server setOnConnectionAttemptFailureHandler(Consumer<InetSocketAddress> handler) {
    this.onConnectionAttemptFailureHandler = handler;
    return this;
  }

  public Server setOnConnectionCloseHandler(Consumer<InetSocketAddress> handler) {
    this.onConnectionCloseHandler = handler;
    return this;
  }

  public void start() throws IOException {
    isActive = true;
    inboundSelector = Selector.open();
    outboundSelector = Selector.open();

    acceptor = ServerSocketChannel.open();
    acceptor.configureBlocking(false);
    acceptor.bind(new InetSocketAddress(port));

    acceptor.register(inboundSelector, SelectionKey.OP_ACCEPT);

    registrationTask = new SelectorRegistration();

    inboundSelectLoop = new Runnable() {
      public void run() {
        int readyChannels = 0;
        try {
          readyChannels = inboundSelector.select();
        } catch (IOException ioe) {
          System.out.println("Inbound select() error. Sleep and retry" + ioe);
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        }

        if (!isActive) { System.out.println("inboundSelectLoop stopping."); return; }

        if (readyChannels == 0) {
          try {
            if (registrationTask.tasks.isEmpty()) Thread.sleep(1000);
          } catch (InterruptedException ie) { }
        }

        if (readyChannels > 0) {
          Set<SelectionKey> selectedKeys = inboundSelector.selectedKeys();
          Iterator<SelectionKey> it = selectedKeys.iterator();
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
            }
            it.remove();
          }
        }

        asyncInbound.submit(inboundSelectLoop);
        return;
      }
    };

    asyncInbound = Executors.newSingleThreadExecutor();
    asyncInbound.submit(inboundSelectLoop);

    asyncOutbound = Executors.newSingleThreadExecutor();
    outboundProxy = new OutboundProxy(this);
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
      channel.register(inboundSelector, SelectionKey.OP_READ, context);

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

      registrationTask.offer(
        (Void x) -> {
          try {
            socket.register(inboundSelector, SelectionKey.OP_CONNECT, address);
          } catch (ClosedChannelException e) {
            System.out.println("Ignore failed connection." + e);
          }
        }
      );

      asyncInbound.submit(registrationTask);
      inboundSelector.wakeup();
    } catch (IOException e) {
      System.out.println("Cannot complete an outbound connection attempt. " + e);
    } catch (Exception e) {
      System.out.println("Exception Server.connect(). " + e);
    }
  }

  public void shutdown() {
    try {
      isActive = false;
      asyncOutbound.shutdown();
      asyncInbound.shutdownNow();
      inboundSelector.wakeup();
      outboundSelector.wakeup();
      acceptor.close();

      System.out.println("Server is shutting down.");

      for (SelectionKey key: inboundSelector.keys()) {
        if (key.isValid() && (key.attachment() instanceof SocketContext)) {
          SocketContext ctx = (SocketContext) key.attachment();
          if (ctx != null) ctx.flushThenDestroy();
        }
      }
    } catch (Exception e) {
      System.out.println("Ignore error during server shutdown: " + e);
    }
    System.out.println("Server was shutdown.");
  }
}
