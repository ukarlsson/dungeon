package se.netcat.dungeon

import java.io.{ObjectOutputStream, ByteArrayOutputStream}
import java.net.InetSocketAddress
import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import akka.io.{IO, Tcp}

import Implicits.convertUUIDToString
import Implicits.convertPairToPath

object SpecialRoom extends Enumeration {
  type SpecialRoom = Value

  val Start = Value
}

object Module extends Enumeration {
  type Module = Value

  var Character = Value
  var Item = Value
  var Room = Value
}

object Main extends App {
  implicit val system = ActorSystem("netcat")

  implicit val config = DungeonConfig(
    modules = Map(
      Module.Character -> "characters",
      Module.Item -> "items",
      Module.Room -> "rooms"
    ),
    system = system
  )

  system.actorSelection(Module.Character, UUID.randomUUID())
  /*

  lazy val rooms: ActorRef = system.actorOf(RoomManager.props(), config.modules(Module.Room))

  lazy val characters: ActorRef = system.actorOf(CharacterManager.props(rooms = () => rooms, items = () => items), config.modules(Module.Character))

  lazy val items: ActorRef = system.actorOf(ItemManager.props(rooms = () => rooms, characters = () => characters), config.modules(Module.Item))

  val resolver: ActorRef = system.actorOf(CharacterResolver.props(), "character-resolver")

  val server: ActorRef = system.actorOf(Server.props(characters = () => characters, resolver = resolver), "server")

  (rooms, characters, items)
  */

  trait Trait {
    case class InTrait()
  }
  object Object extends Trait {
    case class InObject(v: Int)
  }


  val o1 = Object.InTrait()
  val o2 = Object.InObject(1)

  o2.getClass

  val bos = new ByteArrayOutputStream
  val out = new ObjectOutputStream(bos)
  out.writeObject(o1)
  out.writeObject(o2)
}

object Server {
  def props(characters: () => ActorRef, resolver: ActorRef) = Props(
    new Server(characters = characters, resolver = resolver))
}

class Server(characters: () => ActorRef, resolver: ActorRef) extends Actor with ActorLogging {
  import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected, Register}

  implicit val system = context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 30000))

  override def receive: Receive = {
    case bound @ Bound(localAddress) =>
      log.info("Successfully bound to %s".format(localAddress))

    case CommandFailed(_ : Bind) => context.stop(self)

    case Connected(remote, local) =>
      val connection = sender()
      val player = context.actorOf(TcpPlayer.props(
        connection = connection, characters = characters(), resolver = resolver),
        "%s:%d".format(remote.getHostName, remote.getPort))
      connection ! Register(player)
  }
}

