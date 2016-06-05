//       ___       ___       ___       ___       ___
//      /\  \     /\__\     /\__\     /\  \     /\__\
//     /::\  \   /:/ _/_   /:| _|_   /::\  \   /:/  /
//    /::\:\__\ /::-"\__\ /::|/\__\ /::\:\__\ /:/__/
//    \;:::/  / \;:;-",-" \/|::/  / \;:::/  / \:\  \
//     |:\/__/   |:|  |     |:/  /   |:\/__/   \:\__\
//      \|__|     \|__|     \/__/     \|__|     \/__/

package rknrl

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.ByteString

object CrossDomainPolicyServer {
  def props(host: String, port: Int) =
    Props(classOf[CrossDomainPolicyServer], host, port)
}

class CrossDomainPolicyServer(host: String, port: Int) extends Actor with ActorLogging {

  override def supervisorStrategy = OneForOneStrategy() {
    case _: Exception ⇒ Stop
  }

  val address = new InetSocketAddress(host, port)

  import context.system

  IO(Tcp) ! Bind(self, address)

  def receive = {
    case Bound(localAddress) ⇒
      log.debug("policy server bound " + localAddress)

    case CommandFailed(_: Bind) ⇒
      log.debug("policy server command failed " + address)
      context stop self

    case Connected(remote, local) ⇒
      val name = remote.getAddress.getHostAddress + ":" + remote.getPort
      log.debug("policy peer connected " + name)

      val clientConnection = context.actorOf(Props(classOf[PolicyClientConnection], name), "policy-" + name)
      sender ! Register(clientConnection)
  }
}

private class PolicyClientConnection(name: String) extends Actor with ActorLogging {

  import akka.io.Tcp._
  import context.dispatcher

  import scala.concurrent.duration._

  val policyResponseText =
    "<?xml version=\"1.0\"?>" +
      "<!DOCTYPE cross-domain-policy SYSTEM \"/xml/dtds/cross-domain-policy.dtd\">" +
      "<cross-domain-policy>" +
      "<allow-access-from domain=\"*\" to-ports=\"*\" />" +
      "</cross-domain-policy>\0"

  val policyResponse = Write(ByteString(policyResponseText.getBytes(StandardCharsets.US_ASCII.toString)))

  context.system.scheduler.scheduleOnce(1 minute, self, PoisonPill)

  def receive = {
    case Received(receivedData) ⇒
      sender ! policyResponse

    case PeerClosed ⇒
      log.debug("policy peer closed " + name)
      context stop self
  }
}

