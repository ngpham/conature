
package np.conature.nbnet;

public class ChannelIdleEvent {
  private boolean isReadIdle;
  private boolean isWriteIdle;
  private boolean isAllIdle;
  private SocketContext sockCtx;

  public ChannelIdleEvent(
      SocketContext sockCtx, boolean isReadIdle, boolean isWriteIdle, boolean isAllIdle) {
    this.sockCtx = sockCtx;
    this.isReadIdle = isReadIdle;
    this.isWriteIdle = isWriteIdle;
    this.isAllIdle = isAllIdle;
  }

  public SocketContext sockCtx() { return sockCtx; }
  public boolean isReadIdle() { return isReadIdle; }
  public boolean isWriteIdle() { return isWriteIdle; }
  public boolean isAllIdle() { return isAllIdle; }
}
