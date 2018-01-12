
package np.conature.systest;

import np.conature.nbnet.Server;
import np.conature.nbnet.ContextualRawMessage;

import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.Scanner;

public class EchoServer {
  public static void main(String[] args) {
    int port = 9999;
    if (args.length == 1) port = Integer.parseInt(args[0]);
    Server server = new Server(port);

    Consumer<ContextualRawMessage> messageHandler = new Consumer<ContextualRawMessage>() {
      public void accept(ContextualRawMessage msg) {
        msg.context.send(msg.rawBytes);
      }
    };

    Thread watcher = new Thread(new Runnable() {
      public void run() {
        Scanner cli = new Scanner(System.in);
        System.out.println("Watcher started. ENTER some INPUT to stop the server.");
        cli.next();
        server.shutdown();
      }
    });

    watcher.setDaemon(true);
    watcher.start();

    try {
      server.setHandler(messageHandler).start();
      watcher.join();
    } catch (Exception e) {
      System.out.println("Ignore exception...");
      e.printStackTrace();
    }
  }
}
