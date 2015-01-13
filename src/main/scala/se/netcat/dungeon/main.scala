package se.netcat.dungeon.main

import java.net.InetSocketAddress
import java.util.UUID
import akka.actor._
import akka.io.{ IO, Tcp }
import scala.concurrent.ExecutionContext.Implicits.global
import reactivemongo.api.MongoDriver
import reactivemongo.api.DefaultDB
import com.escalatesoft.subcut.inject.NewBindingModule
import reactivemongo.api.MongoConnection
import com.escalatesoft.subcut.inject.BindingId
import com.escalatesoft.subcut.inject.BindingModule
import reactivemongo.core.nodeset.Connection
import reactivemongo.api.collections.default.BSONCollection

import se.netcat.dungeon.common.Implicits.convertPairToPath
import se.netcat.dungeon.mongo._
import se.netcat.dungeon.common._
import se.netcat.dungeon.character.CharacterManager
import se.netcat.dungeon.item.ItemManager
import se.netcat.dungeon.room.RoomManager
import se.netcat.dungeon.player.TcpPlayer

object MongoBindingKey {
  object NodeList extends BindingId
  object Database extends BindingId
}

object MongoModule extends NewBindingModule(module => {
  import module._
  
  import MongoBindingKey._

  bind[ActorSystem].toSingle {
    ActorSystem("dungeon")
  }

  bind[MongoDriver].toModuleSingle {
    implicit module => new MongoDriver()
  }

  bind[List[String]].identifiedBy(NodeList).toSingle {
    List("localhost")
  }

  bind[MongoConnection].toModuleSingle {
    implicit module =>
      val driver = module.inject[MongoDriver](None)
      val nodes = module.inject[List[String]](Some(NodeList))
      driver.connection(nodes)
  }
  
  bind[String].identifiedBy(Database).toSingle {
    "dungeon"
  }
  
  bind[DefaultDB].toModuleSingle {
    implicit module =>
      val connection = module.inject[MongoConnection](None)
      val name = module.inject[String](Some(Database))
      connection(name)
  }

  
  bind[Map[Manager.Value, String]].toSingle {
    Map(
      Manager.Character -> "characters",
      Manager.Item -> "items",
      Manager.Room -> "rooms");
  }
})

object Main extends App {
  implicit val bindingModule = MongoModule
  
  val system = bindingModule.inject[ActorSystem](None)
  val modules = bindingModule.inject[Map[Manager.Value, String]](None)

  lazy val rooms: ActorRef = system.actorOf(RoomManager.props(), modules(Manager.Room))

  lazy val characters: ActorRef = system.actorOf(CharacterManager.props(rooms = () => rooms, items = () => items), modules(Manager.Character))

  lazy val items: ActorRef = system.actorOf(ItemManager.props(rooms = () => rooms, characters = () => characters), modules(Manager.Item))

  val server: ActorRef = system.actorOf(Server.props(characters = () => characters), "server")

  (rooms, characters, items)
}

object Server {
  def props(characters: () => ActorRef)(implicit bindingModule: BindingModule) = Props(
    new Server(characters = characters))
}

class Server(characters: () => ActorRef)(implicit bindingModule: BindingModule) extends Actor with ActorLogging {
  import akka.io.Tcp.{ Bind, Bound, CommandFailed, Connected, Register }

  implicit val system = context.system
  
  // IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 30000))
  IO(Tcp) ! Bind(self, new InetSocketAddress("10.48.32.117", 30000))

  override def receive: Receive = {
    case bound @ Bound(localAddress) =>
      log.info("Successfully bound to %s".format(localAddress))

    case CommandFailed(_: Bind) => context.stop(self)

    case Connected(remote, local) =>
      val connection = sender()
      val player = context.actorOf(TcpPlayer.props(
        connection = connection, characters = characters()),
        "%s:%d".format(remote.getHostName, remote.getPort))
      connection ! Register(player)
  }
}