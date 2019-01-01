
package np.conature.systest.multijvm

import np.conature.systest.networkask.{ Server, Client }

// systest/multi-jvm:run np.conature.systest.multijvm.NetworkAsk

object NetworkAskMultiJvmNode1 {
  def main(args: Array[String]): Unit =  {
    Server.main(Array("9999", "2"))
  }
}

object NetworkAskMultiJvmNode2 {
  def main(args: Array[String]): Unit =  {
    Client.main(Array("7777", "9999"))
  }
}

object NetworkAskMultiJvmNode3 {
  def main(args: Array[String]): Unit =  {
    Client.main(Array("8888", "9999"))
  }
}
