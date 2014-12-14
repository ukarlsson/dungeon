package se.netcat.dungeon

import akka.actor._
import akka.io.Tcp
import akka.util.ByteString

import scala.concurrent.duration._

object Player {
  case class IncomingMessage(data: String)
  case class OutgoingMessage(data: String)
  case class Terminate()
}

class TcpPlayer(connection: ActorRef, characters: ActorRef)
  extends Player(connection, characters) with ActorLogging {

  def decode(data: ByteString) = data.decodeString("UTF-8")
  def encode(data: String) = ByteString(data, "UTF-8")

  val newline = "\r\n"

  override def receive: Actor.Receive = {
    case Tcp.Received(data) =>
      self ! Player.IncomingMessage(decode(data))
    case Tcp.PeerClosed =>
      self ! Player.Terminate
    case message @ _ =>
      super.receive(message)
  }

  def send(data: String) =
    connection ! Tcp.Write(encode(data + newline))
}

object PlayerState extends Enumeration {
  type PlayerConnectionState = Value

  val CharacterLogin = Value
  val CharacterLoginPending = Value
  val CharacterCreate = Value
  val CharacterCreatePending = Value
  val Play = Value
}

object PlayerData {
  sealed trait Data

  final case class DataPlaying(character: ActorRef) extends Data
  final case class DataName(name: String) extends Data
  final case class DataNone() extends Data
}

abstract class Player(connection: ActorRef, characters: ActorRef)
  extends LoggingFSM[PlayerState.Value, PlayerData.Data]
  with ActorLogging {

  import se.netcat.dungeon.PlayerData._
  import se.netcat.dungeon.PlayerState._

  def send(data: String) : Unit

  override def preStart() = send("Please enter your name:")

  startWith(CharacterLogin, DataNone())

  onTransition {
    case _ -> CharacterLogin =>
      send("Please enter your name:")
    case _ -> Play =>
      send("Welcome to the Dungeon!")
      nextStateData match {
        case DataPlaying(character) =>
          character ! Character.Connect(self)
      }
    case Play -> _ =>
      stateData match {
      case DataPlaying(character) =>
          character ! Character.Disconnect(self)
      }
      send("Please visit the Dungeon soon again.")
    case _ -> CharacterCreate =>
      send("Create new character? (yes/no)")
  }

  when(CharacterLogin, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), DataNone()) =>
      val name = ConnectionParsers.parse(ConnectionParsers.name, data)

      if (name.successful) {
        characters ! Characters.GetCharacterByName(name.get)
        goto(CharacterLoginPending).using(DataName(name.get))
      } else {
        send("That is not a valid name! Please enter name:")
        stay().using(DataNone())
      }
  }
  when (CharacterLoginPending, stateTimeout = 5 second) {
    case Event(Characters.GetCharacterResult(result), DataName(name)) =>
      if (result.isDefined) {
        goto(Play).using(DataPlaying(result.get))
      } else {
        goto(CharacterCreate).using(DataName(name))
      }
  }

  when (CharacterCreate, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), DataName(name)) =>
      val boolean = ConnectionParsers.parse(ConnectionParsers.boolean, data)

      if (boolean.successful) {
        if (boolean.get) {
          characters ! Characters.CreateCharacter(name)
          goto(CharacterCreatePending).using(DataName(name))
        } else {
          goto(CharacterLogin).using(DataNone())
        }
      } else {
        send("That is not a valid answer! Create new character? (yes/no)")
        stay()
      }
  }

  when (CharacterCreatePending, stateTimeout = 5 second) {
    case Event(Characters.CreateCharacterResult(result), DataName(name)) =>
      if (result.isDefined) {
        goto(Play).using(DataPlaying(result.get))
      } else {
        goto(CharacterLogin).using(DataNone())
      }
  }

  when (Play, stateTimeout = 3600 second) {
    case Event(Player.IncomingMessage(data), DataPlaying(character)) =>
      val command = DungeonParsers.parse(DungeonParsers.command, data)

      if (command.successful) {
        command.get match {
          case message @ DungeonParsers.Look(direction) =>
            character ! message
            stay()
          case message @ DungeonParsers.Walk(direction) =>
            character ! message
            stay()
          case DungeonParsers.Exit() =>
            goto(CharacterLogin).using(DataNone())
        }
      } else {
        send("?SYNTAX ERROR")
        stay()
      }
    case Event(Player.OutgoingMessage(data), _) =>
      send(data)
      stay()
  }

  whenUnhandled {
    case Event(Player.Terminate, DataPlaying(character)) =>
      character ! Character.Disconnect(connection)
      stop()
    case Event(Player.Terminate, _) =>
      stop()
    case Event(message, _) =>
      receive(message)
      stay()
  }
}
