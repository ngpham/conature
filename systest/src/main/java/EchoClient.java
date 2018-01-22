
package np.conature.systest;

import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;
import java.net.InetSocketAddress;

import java.io.IOException;
import java.net.SocketException;

// multithread blocking client
public class EchoClient {
  static final char[] source = "日本語漢語العَرَبِيَّة‎".toCharArray();

  static String randomString(int len, Random rand) {
    char[] buf = new char[len];
    for (int i = 0; i < len; i++) {
      buf[i] = source[rand.nextInt(source.length)];
    }
    return new String(buf);
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 5) {
      System.err.println("Parameters: "
        + "server " + "port " + " string_length" + " num_threads" + " max_msg_per_thread");
      System.exit(1);
    }

    int port = Integer.parseInt(args[1]);
    int numThread = Integer.parseInt(args[3]);
    int maxMsg = Integer.parseInt(args[4]);
    int strLen = Integer.parseInt(args[2]);

    Runnable task = new Runnable() {
      public void run() {
        Random rand = new Random();
        int count = 0;
        int numMsg = (rand.nextInt(maxMsg / 4) + 1) * 4;

        try {
          Socket server = new Socket();
          server.connect(new InetSocketAddress(args[0], port), 100);

          InputStream in = server.getInputStream();
          OutputStream out = server.getOutputStream();

          for (int i = 0; i < numMsg; i++) {
            String data = randomString(strLen, rand);

            byte[] bytes = data.getBytes("UTF-8");
            int bytesLen = bytes.length;

            byte[] msg = new byte[bytesLen + 2];
            msg[0] = (byte)((bytesLen >> 8) & 0xff);
            msg[1] = (byte)(bytesLen & 0xff);
            for (int j = 2; j < bytesLen + 2; j++) { msg[j] = bytes[j-2]; }

            out.write(msg);

            int totalRec = 0;
            int r = 0;
            byte[] recv = new byte[bytesLen + 2];
            while (totalRec < bytesLen + 2) {
              r = in.read(recv, totalRec, bytesLen + 2 - totalRec);
              if (r == -1) throw new SocketException("Connection closed during read().");
              totalRec += r;
            }

            byte[] payload = Arrays.copyOfRange(recv, 2, bytesLen + 2);

            assert(data.equals(new String(payload, "UTF-8")));
            count += 1;
            Thread.sleep(rand.nextInt(5) + 5);
          }
          server.close();
        } catch (Exception e) { System.out.println(e.getMessage()); }

        System.out.println("Total messages echoed, expected: " + numMsg + ", actual: " + count);
      }
    };

    Thread[] threads = new Thread[numThread];

    for (int i = 0; i < numThread; i++) {
      threads[i] = new Thread(task);
      threads[i].run();
    }

    for (int i = 0; i < numThread; i++) {
      threads[i].join();
    }
  }
}
