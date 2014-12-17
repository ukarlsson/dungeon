package se.netcat.dungeon

import java.net.InetSocketAddress

import akka.actor._
import akka.event.LoggingReceive
import akka.io.{IO, Tcp}

object SpecialRoom extends Enumeration {
  type SpecialRoom = Value

  val Start = Value
}

object Main extends App {
  implicit val system = ActorSystem("netcat")

  val dungeon = system.actorOf(Dungeon.props(), "dungeon")

  val characters = system.actorOf(CharacterSupervisor.props(dungeon = dungeon), "characters")

  val resolver = system.actorOf(CharacterByNameResolver.props(), "resolver")

  val server = system.actorOf(Server.props(characters = characters, resolver = resolver), "server")
}

object Server {
  def props(characters: ActorRef, resolver: ActorRef) = Props(
    new Server(characters = characters, resolver = resolver))
}

class Server(characters: ActorRef, resolver: ActorRef) extends Actor {
  import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected, Register}

  implicit val system = context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 30000))

  override def receive: Receive = {
    case bound @ Bound(localAddress) => println(localAddress)

    case CommandFailed(_ : Bind) => context.stop(self)

    case Connected(remote, local) =>
      val connection = sender()
      val player = context.actorOf(TcpPlayer.props(
        connection = connection, characters = characters, resolver = resolver))
      connection ! Register(player)
  }
}

object Dungeon {
  def props() = Props(new Dungeon())

  case class GetRoom(value: SpecialRoom.Value)
  case class GetRoomResult(room: Option[ActorRef])
}

class Dungeon() extends Actor {
  import Dungeon._

  lazy val room1 : ActorRef = context.actorOf(Room.props("This is a plain room.",
    () => Map((Direction.South, room2), (Direction.East, room3))))

  lazy val room2 : ActorRef = context.actorOf(Room.props("This is another plain room",
    () => Map((Direction.North, room1))))

  lazy val room3 : ActorRef = context.actorOf(Room.props("This is the Dark Room",
    () => Map((Direction.West, room1))))

  val rooms = Map((SpecialRoom.Start, room1))

  override def receive: Actor.Receive = LoggingReceive {
    case GetRoom(value) => sender() ! GetRoomResult(rooms.get(value))
  }
}

