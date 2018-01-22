
package np.conature.systest;

import np.conature.nbnet.Server;
import np.conature.nbnet.ContextualRawMessage;

import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.Scanner;

public class EchoServer {
  private Server netSrv;

  public static void main(String[] args) {
    int port = 9999;
    if (args.length == 1) port = Integer.parseInt(args[0]);
    EchoServer instance = new EchoServer();
    instance.netSrv = new Server(port);

    Consumer<ContextualRawMessage> messageHandler = new Consumer<ContextualRawMessage>() {
      private int limit = 0;

      public void accept(ContextualRawMessage msg) {
        msg.context.send(msg.rawBytes);
        limit += 1;
        if (limit > 512) instance.netSrv.shutdown();
      }
    };

    Thread watcher = new Thread(new Runnable() {
      public void run() {
        Scanner cli = new Scanner(System.in);
        System.out.println("Watcher started. ENTER some INPUT to stop the server.");
        cli.next();
        instance.netSrv.shutdown();
      }
    });

    watcher.setDaemon(true);
    watcher.start();

    try {
      instance.netSrv.setInboundMessageHandler(messageHandler)
        .setOnConnectionEstablishedHandler((x) -> System.out.println("connection in!!"))
        .setOnConnectionCloseHandler(
          (x) -> System.out.println("disconnected client: " + x.getHostString()))
        .start();
      watcher.join();
    } catch (Exception e) {
      System.out.println("Ignore exception..." + e);
    }
  }

}
