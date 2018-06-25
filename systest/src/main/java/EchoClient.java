
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
        Socket server = new Socket();
        try {
          server.connect(new InetSocketAddress(args[0], port), 100);

          InputStream in = server.getInputStream();
          OutputStream out = server.getOutputStream();

          for (int i = 0; i < numMsg; i++) {
            String data = randomString(strLen, rand);

            byte[] bytes = data.getBytes("UTF-8");
            int bytesLen = bytes.length;

            int numBytesForSize =
              Integer.numberOfTrailingZeros(Integer.highestOneBit(bytesLen)) / 8 + 1;

            int msgLen = 1 + numBytesForSize + bytesLen;
            byte[] msg = new byte[msgLen];
            msg[0] = (byte)(numBytesForSize & 0xff);

            int pos = 1;
            while (bytesLen > 0) {
              msg[pos] = (byte)(bytesLen & 0xff);
              bytesLen = bytesLen >>> 8;
              pos += 1;
            }

            for (int j = pos; j < msgLen; j++) {
              msg[j] = bytes[j - 1 - numBytesForSize];
            }

            out.write(msg);

            int totalRec = 0;
            int r = 0;
            byte[] recv = new byte[msgLen];
            while (totalRec < msgLen) {
              r = in.read(recv, totalRec, msgLen - totalRec);
              if (r == -1) throw new SocketException("Connection closed during read().");
              totalRec += r;
            }

            byte[] payload = Arrays.copyOfRange(recv, pos, msgLen);

            assert(data.equals(new String(payload, "UTF-8")));
            count += 1;
            Thread.sleep(rand.nextInt(5) + 5);
          }
        } catch (Exception e) {
          System.out.println(e.getMessage());
        } finally {
          try { server.close(); } catch (Exception e) { System.out.println(e.getMessage()); }
        }

        System.out.println("Total messages echoed, expected: " + numMsg + ", actual: " + count);
      }
    };

    Thread[] threads = new Thread[numThread];

    for (int i = 0; i < numThread; i++) {
      threads[i] = new Thread(task);
      threads[i].start();
    }

    for (int i = 0; i < numThread; i++) {
      threads[i].join();
    }
  }
}

// np.conature.systest.EchoClient localhost 9999 10 4 256
