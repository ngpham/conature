
package np.conature.nbnet;

import java.util.function.Consumer;
import np.conature.util.Scheduler;
import np.conature.util.Cancellable;

public class ChannelIdleTimeout {
  protected Cancellable readIdleTimeout = null;
  protected Cancellable writeIdleTimeout = null;
  protected Cancellable allIdleTimeout = null;
  protected int readIdle = 0;
  protected int writeIdle = 0;
  protected int allIdle = 0;
  protected SocketContext sockCtx;

  @SuppressWarnings("unchecked")
  private Consumer<ChannelIdleEvent> callback =
    (Consumer<ChannelIdleEvent>) NbTransport.EmptyHandler;

  public void reScheduleReadIdleTimeout() {
    if (readIdleTimeout != null) readIdleTimeout.cancel();

    readIdleTimeout = sockCtx.nbTrans.scheduler.schedule(readIdle, false, new Runnable() {
      public void run() {
        callback.accept(new ChannelIdleEvent(sockCtx, true, false, false));
      }
    });
  }

  public void reScheduleWriteIdleTimeout() {
    if (writeIdleTimeout != null) writeIdleTimeout.cancel();

    writeIdleTimeout = sockCtx.nbTrans.scheduler.schedule(writeIdle, false, new Runnable() {
      public void run() {
        callback.accept(new ChannelIdleEvent(sockCtx, false, true, false));
      }
    });
  }

  public void reScheduleAllIdleTimeout() {
    if (allIdleTimeout != null) allIdleTimeout.cancel();

    allIdleTimeout = sockCtx.nbTrans.scheduler.schedule(allIdle, false, new Runnable() {
      public void run() {
        callback.accept(new ChannelIdleEvent(sockCtx, false, false, true));
      }
    });
  }

  public ChannelIdleTimeout(
      SocketContext sockCtx, int readIdle, int writeIdle, int allIdle,
      Consumer<ChannelIdleEvent> callback) {
    this.callback = callback;
    this.sockCtx = sockCtx;

    if (readIdle > 0) reScheduleReadIdleTimeout();

    if (writeIdle > 0) reScheduleWriteIdleTimeout();

    if (allIdle > 0) reScheduleAllIdleTimeout();
  }

  public void destroy() {
    if (readIdleTimeout != null) readIdleTimeout.cancel();
    if (writeIdleTimeout != null) writeIdleTimeout.cancel();
    if (allIdleTimeout != null) writeIdleTimeout.cancel();
  }
}
