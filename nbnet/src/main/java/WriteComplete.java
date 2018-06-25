
package np.conature.nbnet;

public class WriteComplete {
  private boolean isSuccess;
  private SocketContext sockCtx;
  private Throwable cause = null;

  public WriteComplete(SocketContext sockCtx) {
    isSuccess = true;
    this.sockCtx = sockCtx;
  }
  public WriteComplete(SocketContext sockCtx, Throwable error) {
    isSuccess = false;
    this.sockCtx = sockCtx;
    cause = error;
  }

  public boolean isSuccess() { return isSuccess; }
  public Throwable cause() { return cause; }
  public SocketContext sockCtx() { return sockCtx; }
}
