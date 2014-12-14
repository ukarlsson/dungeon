package se.netcat.dungeon

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}

object Main extends App {
  implicit val system = ActorSystem("netcat")

  lazy val room1 : ActorRef = system.actorOf(Props(classOf[Room], "This is a plain room.",
    () => Map((Direction.South, room2), (Direction.East, room3))))

  lazy val room2 : ActorRef = system.actorOf(Props(classOf[Room], "This is another plain room",
    () => Map((Direction.North, room1))))

  lazy val room3 : ActorRef = system.actorOf(Props(classOf[Room], "This is the Dark Room",
    () => Map((Direction.West, room1))))

  val characters = system.actorOf(Props(classOf[Characters], room1), "characters")

  val server = system.actorOf(Props(classOf[Server], characters), "server")
}

class Server(characters: ActorRef) extends Actor {
  import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected, Register}

  implicit val system = context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 30000))

  override def receive: Receive = {
    case bound @ Bound(localAddress) => println(localAddress)

    case CommandFailed(_ : Bind) => context.stop(self)

    case Connected(remote, local) =>
      val connection = sender()
      val player = context.actorOf(
        Props(classOf[TcpPlayer], connection, characters))
      connection ! Register(player)
  }
}

