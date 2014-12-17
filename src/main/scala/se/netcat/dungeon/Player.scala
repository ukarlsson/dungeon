package se.netcat.dungeon

import java.util.UUID

import akka.actor._
import akka.io.Tcp
import akka.util.ByteString

import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers

trait PlayerParsers extends RegexParsers {

  import se.netcat.dungeon.PlayerParsers._

  def name: Parser[String] = "^[a-zA-Z]+$".r ^^ {
    _.toLowerCase
  }

  def yes: Parser[Boolean] = "^yes$".r ^^^ true

  def no: Parser[Boolean] = "^no$".r ^^^ false

  def exit: Parser[Exit] = "^exit$".r ^^^ Exit()

  def boolean: Parser[Boolean] = yes | no

  def command: Parser[Command] = exit
}

object PlayerParsers extends PlayerParsers {

  sealed trait Command

  case class Use(name: String) extends Command

  case class Create(name: String) extends Command

  case class Exit() extends Command

}

object Player {

  case class IncomingMessage(data: String)

  case class OutgoingMessage(data: String)

  case class Terminate()

}

object PlayerState extends Enumeration {

  type PlayerConnectionState = Value

  val Start = Value
  val CharacterCheckExisting = Value
  val CharacterLogin = Value
  val CharacterCreate = Value
  val Play = Value
}

object PlayerData {

  sealed trait Data

  final case class DataNone() extends Data
  final case class DataLogin(name: Option[String], uuid: Option[UUID]) extends Data
  final case class DataCreate(name: Option[String], uuid: Option[UUID], resolved: Boolean) extends Data
  final case class DataPlaying(character: ActorRef) extends Data

}

abstract class Player(connection: ActorRef, characters: ActorRef, resolver: ActorRef)
  extends LoggingFSM[PlayerState.Value, PlayerData.Data]
  with ActorLogging {

  import se.netcat.dungeon.PlayerData._
  import se.netcat.dungeon.PlayerState._

  def send(data: String): Unit

  startWith(Start, DataNone())

  override def preStart() = self ! StateTimeout

  onTransition {
    case _ -> CharacterCheckExisting =>
      send("Use existing character? (yes/no)")
    case _ -> CharacterLogin =>
      send("Please enter the name of your existing character:")
    case _ -> CharacterCreate =>
      send("Please enter the name of your new character:")
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
  }

  when(Start, stateTimeout = 0 second) {
    case Event(StateTimeout, DataNone()) =>
      goto(CharacterCheckExisting)
  }

  when(CharacterCheckExisting, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), DataNone()) =>
      PlayerParsers.parse(PlayerParsers.boolean, data) match {
        case PlayerParsers.Success(true, _) =>
          goto(CharacterLogin).using(DataLogin(None, None))
        case PlayerParsers.Success(false, _) =>
          goto(CharacterCreate).using(DataCreate(None, None, resolved = false))
        case PlayerParsers.Failure(_, _) =>
          send("Use existing character? (yes/no)")
          stay()
      }
  }

  when(CharacterLogin, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), DataLogin(None, None)) =>
      PlayerParsers.parse(PlayerParsers.name, data) match {
        case PlayerParsers.Success(name, _) =>
          resolver ! CharacterByNameResolver.Get(name)
          stay().using(DataLogin(Some(name), None))
        case PlayerParsers.Failure(_, _) =>
          goto(Start).using(DataNone())
      }

    case Event(CharacterByNameResolver.GetResult(result), DataLogin(Some(name), None)) =>
      result match {
        case Some(uuid) =>
          characters ! CharacterSupervisor.Get(uuid)
          stay().using(DataLogin(Some(name), Some(uuid)))
        case None =>
          goto(Start).using(DataNone())
      }

    case Event(CharacterSupervisor.GetResult(result), DataLogin(Some(name), Some(uuid))) =>
      result match {
        case Some(character) => goto(Play).using(DataPlaying(character))
        case None => goto(Start).using(DataNone())
      }
  }

  when(CharacterCreate, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), DataCreate(None, None, false)) =>
      PlayerParsers.parse(PlayerParsers.name, data) match {
        case PlayerParsers.Success(name, _) =>
          val uuid = UUID.randomUUID()
          resolver ! CharacterByNameResolver.Set(name, uuid)
          stay().using(DataCreate(Some(name), Some(uuid), resolved = false))
        case PlayerParsers.Failure(_, _) =>
          send("That is not a valid name!")
          goto(Start).using(DataNone())
      }

    case Event(CharacterByNameResolver.SetResult(result), DataCreate(Some(name), Some(uuid), false)) =>
      result match {
        case true =>
          characters ! CharacterSupervisor.Create(uuid)
          stay().using(DataCreate(Some(name), Some(uuid), resolved = true))
        case false =>
          send("That name is already used!")
          goto(Start).using(DataNone())
      }

    case Event(CharacterSupervisor.CreateResult(result), DataCreate(Some(name), Some(uuid), true)) =>
      result match {
        case Some(character) =>
          goto(Play).using(DataPlaying(character))
        case None =>
          send("That character did not want to play!")
          goto(Start).using(DataNone())
      }

  }

  when(Play, stateTimeout = 3600 second) {
    case Event(Player.IncomingMessage(data), DataPlaying(character)) =>
      PlayerParsers.parse(PlayerParsers.command, data) match {
        case PlayerParsers.Success(command, _) =>
          command match {
            case PlayerParsers.Exit() =>
              goto(Start).using(DataNone())
          }
        case PlayerParsers.Failure(_, _) =>
          character ! Player.IncomingMessage(data)
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
  }
}

object TcpPlayer {

  def props(connection: ActorRef, characters: ActorRef, resolver: ActorRef) = Props(
    new TcpPlayer(connection = connection, characters = characters, resolver = resolver))
}

class TcpPlayer(connection: ActorRef, characters: ActorRef, resolver: ActorRef)
  extends Player(connection, characters, resolver) with ActorLogging {

  def decode(data: ByteString) = data.decodeString("UTF-8")

  def encode(data: String) = ByteString(data, "UTF-8")

  val newline = "\r\n"

  override def receive: Actor.Receive = {
    case Tcp.Received(data) =>
      self ! Player.IncomingMessage(decode(data))
    case Tcp.PeerClosed =>
      self ! Player.Terminate
    case message =>
      super.receive(message)
  }

  def send(data: String) =
    connection ! Tcp.Write(encode(data + newline))
}

