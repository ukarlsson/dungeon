package se.netcat.dungeon

import java.util.UUID
import akka.actor._
import akka.io.Tcp
import akka.util.ByteString
import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers
import com.escalatesoft.subcut.inject.BindingModule
import reactivemongo.bson.BSONObjectID
import scala.util.Success
import scala.util.Failure
import scala.concurrent.ExecutionContext.Implicits.global
import com.escalatesoft.subcut.inject.Injectable
import Implicits.convertPairToPath

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
  val CharacterUpdate = Value
  val CharacterPlay = Value
}

object PlayerData {

  sealed trait Data

  final case class DataCharacterInfo(id: BSONObjectID, name: String) extends Data
  final case class DataCharacter(character: ActorRef) extends Data

}

abstract class Player(connection: ActorRef, characters: ActorRef)(implicit bindingModule: BindingModule)
  extends LoggingFSM[PlayerState.Value, Option[PlayerData.Data]] with Injectable
  with ActorLogging {

  import se.netcat.dungeon.PlayerData._
  import se.netcat.dungeon.PlayerState._

  case class CharacterInsertResult(result: Boolean)
  case class CharacterFindResult(result: Option[CharacterMongo])

  implicit val managers = inject[Map[Manager.Value, String]]

  val store = new CharacterStore()

  def send(data: String): Unit

  startWith(Start, None)

  override def preStart() = self ! StateTimeout

  onTransition {
    case _ -> CharacterCheckExisting =>
      send("Use existing character? (yes/no)")
    case _ -> CharacterLogin =>
      send("Please enter the name of your existing character:")
    case _ -> CharacterCreate =>
      send("Please enter the name of your new character:")
    case _ -> CharacterUpdate =>
      send("Updating character.")
      nextStateData match {
        case Some(DataCharacterInfo(id, name)) =>
          characters ! CharacterManager.UpdateRequest(id)
      }
    case _ -> CharacterPlay =>
      send("Welcome to the Dungeon!")
      nextStateData match {
        case Some(DataCharacter(character)) =>
          character ! Character.Connect(self)
      }
    case CharacterPlay -> _ =>
      stateData match {
        case Some(DataCharacter(character)) =>
          character ! Character.Disconnect(self)
      }
      send("Please visit the Dungeon soon again.")
  }

  when(Start, stateTimeout = 0 second) {
    case Event(StateTimeout, None) =>
      goto(CharacterCheckExisting)
  }

  when(CharacterCheckExisting, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), None) =>
      PlayerParsers.parse(PlayerParsers.boolean, data) match {
        case PlayerParsers.Success(true, _) =>
          goto(CharacterLogin).using(None)
        case PlayerParsers.Success(false, _) =>
          goto(CharacterCreate).using(None)
        case PlayerParsers.Failure(_, _) =>
          send("Use existing character? (yes/no)")
          stay()
      }
  }

  when(CharacterLogin, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), None) =>
      PlayerParsers.parse(PlayerParsers.name, data) match {
        case PlayerParsers.Success(name, _) =>
          store.find(name).andThen {
            case Success(result) => self ! CharacterFindResult(result)
            case Failure(_) => self ! CharacterFindResult(None)
          }
          stay().using(None)
        case PlayerParsers.Failure(_, _) =>
          send("Parse error.")
          goto(Start).using(None)
      }

    case Event(CharacterFindResult(result), None) =>
      result match {
        case Some(CharacterMongo(id, name)) =>
          goto(CharacterUpdate).using(Some(DataCharacterInfo(id, name)))
        case None =>
          send("Unable lo locate character.")
          goto(Start).using(None)
      }
  }

  when(CharacterCreate, stateTimeout = 60 second) {
    case Event(Player.IncomingMessage(data), None) =>
      PlayerParsers.parse(PlayerParsers.name, data) match {
        case PlayerParsers.Success(name, _) =>
          val id = BSONObjectID.generate
          store.insert(id, name).andThen {
            case Success(()) => self ! CharacterInsertResult(true)
            case Failure(_) => self ! CharacterInsertResult(false)
          }
          stay().using(Some(DataCharacterInfo(id, name)))
        case PlayerParsers.Failure(_, _) =>
          send("That is not a valid name!")
          goto(Start).using(None)
      }

    case Event(CharacterInsertResult(result), Some(DataCharacterInfo(id, name))) =>
      result match {
        case true =>
          goto(CharacterUpdate)
        case false =>
          send("That character did not want to play!")
          goto(Start).using(None)
      }

  }

  when(CharacterUpdate, stateTimeout = 60 second) {
    case Event(CharacterManager.UpdateResponse(_, character), Some(DataCharacterInfo(id, name))) =>
      goto(CharacterPlay).using(Some(DataCharacter(character)))
  }

  when(CharacterPlay, stateTimeout = 3600 second) {
    case Event(Player.IncomingMessage(data), Some(DataCharacter(character))) =>
      PlayerParsers.parse(PlayerParsers.command, data) match {
        case PlayerParsers.Success(command, _) =>
          command match {
            case PlayerParsers.Exit() =>
              goto(Start).using(None)
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
    case Event(Player.Terminate, Some(DataCharacter(character))) =>
      character ! Character.Disconnect(connection)
      stop()
    case Event(Player.Terminate, _) =>
      stop()
  }
}

object TcpPlayer {

  def props(connection: ActorRef, characters: ActorRef)(implicit bindingModule: BindingModule) = Props(
    new TcpPlayer(connection = connection, characters = characters))
}

class TcpPlayer(connection: ActorRef, characters: ActorRef)(implicit val bindingModule: BindingModule)
  extends Player(connection = connection, characters = characters) with ActorLogging {

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

