package se.netcat.dungeon

import java.net.InetSocketAddress
import java.util.UUID
import reactivemongo.api.MongoDriver

import akka.actor._
import akka.io.{IO, Tcp}
import se.netcat.dungeon.Implicits.convertPairToPath

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

  val mongo = new MongoDriver(system)
  
  implicit val config = DungeonConfig(
    modules = Map(
      Module.Character -> "characters",
      Module.Item -> "items",
      Module.Room -> "rooms"
    ),
    system = system,
    mongo = mongo
  )

  system.actorSelection((Module.Character, UUID.randomUUID()))

  lazy val rooms: ActorRef = system.actorOf(RoomManager.props(), config.modules(Module.Room))

  lazy val characters: ActorRef = system.actorOf(CharacterManager.props(rooms = () => rooms, items = () => items), config.modules(Module.Character))

  lazy val items: ActorRef = system.actorOf(ItemManager.props(rooms = () => rooms, characters = () => characters), config.modules(Module.Item))

  val resolver: ActorRef = system.actorOf(CharacterResolver.props(), "character-resolver")

  val server: ActorRef = system.actorOf(Server.props(characters = () => characters, resolver = resolver), "server")

  (rooms, characters, items)
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

