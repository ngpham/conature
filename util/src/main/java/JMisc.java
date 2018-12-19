
package np.conature.util;

import java.util.function.Consumer;

public class JMisc {
  public static Runnable Nop = () -> {};
  public static Consumer<? extends Object> EmptyFunc = (x) -> {};
}
