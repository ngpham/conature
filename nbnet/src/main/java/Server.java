
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
  private ServerSocketChannel acceptor;

  private Thread inboundThread;
  protected ExecutorService asyncThread;
  protected OutboundProxy outboundProxy;

  protected Selector inboundSelector;
  protected Selector outboundSelector;

  private int port;
  protected Consumer<ContextualRawMessage> handler;
  private volatile boolean isActive;

  public Server(int port) {
    this(port, new Consumer<ContextualRawMessage>() {
      public void accept(ContextualRawMessage msg) {
        System.out.print("0x");
        for (byte b : msg.rawBytes) System.out.format("%02x", b);
        System.out.println("");
      }
    });
  }

  public Server(int port, Consumer<ContextualRawMessage> handler) {
    this.port = port;
    this.handler = handler;
  }

  public Server setHandler(Consumer<ContextualRawMessage> handler) {
    this.handler = handler;
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

    inboundThread = new Thread(new Runnable() { public void run() {
      while (isActive) {
        int readyChannels = 0;
        try {
          readyChannels = inboundSelector.select();
        } catch (IOException ioe) {
          System.out.println("Inbound select() error. Sleep and retry");
          ioe.printStackTrace();
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            continue;
          }
        }

        if (!isActive) { System.out.println("Inbound Thread stopping."); return; }

        if (readyChannels == 0) {
          System.out.println(
            "select() seems to run into the infamous jdk epoll bug on Linux.\n" +
            "Sleep and retry.");
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            continue;
          }
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
            }
            it.remove();
          }
        }
      } // while (isActive)
    }});

    inboundThread.setDaemon(true);
    inboundThread.start();

    asyncThread = Executors.newSingleThreadExecutor();
    outboundProxy = new OutboundProxy(this);
  }

  private void accept(SelectionKey key) {
    ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
    try {
      SocketChannel socket = ssc.accept();
      if (socket == null) throw new IOException();
      socket.configureBlocking(false);

      SocketContext context = new SocketContext(
        this, socket,
        new Reader(), new Writer(),
        handler);

      socket.register(inboundSelector, SelectionKey.OP_READ).attach(context);
      socket.register(outboundSelector, 0).attach(context);
    } catch (IOException e) {
      System.out.println("Cannot accept a connection attempt.");
      e.printStackTrace();
    } catch (Exception ae) {
      ae.printStackTrace();
    }
  }

  public void shutdown() {
    try {
      System.out.println("Server is shutting down.");
      acceptor.close();
    } catch (IOException e) {
      System.out.println("Ignore error during server shutdown: " + e);
    }
    isActive = false;
    asyncThread.shutdownNow();
  }

}
