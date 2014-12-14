package se.netcat.dungeon

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import akka.util.Timeout
import se.netcat.dungeon.Character.Description

import scala.collection
import scala.collection.mutable
import scala.util.parsing.combinator.RegexParsers
import akka.pattern._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global



trait CharacterParsers extends RegexParsers {
  import CharacterParsers._

  def direction:Parser[Direction.Value] = Direction.values.toList.map(v => v.toString ^^^ v).reduceLeft(_ | _)

  def look: Parser[Look] = "look" ~ opt(direction) ^^ {
    case _ ~ Some(v) => Look(Some(v))
    case _ ~ None => Look(None)
  }

  def walk: Parser[Walk] = direction ^^ {
    case v => Walk(v)
  }

  def command: Parser[Command] = look | walk
}

object CharacterParsers extends CharacterParsers {
  sealed trait Command
  case class Look(direction: Option[Direction.Value]) extends Command
  case class Walk(direction: Direction.Value) extends Command
}
object Characters {
  case class CreateCharacter(name: String)
  case class CreateCharacterResult(character: Option[ActorRef])

  case class GetCharacterByName(name: String)
  case class GetCharacterResult(player: Option[ActorRef])
}

object CharacterState extends Enumeration {
  type CharacterState = Value

  val Idle = Value
  val LookPending = Value
  val WalkPending = Value
}

object CharacterData {
  sealed trait Data

  case object DataNone extends Data
}

object Character {
  case class Connect(connection: ActorRef)
  case class Disconnect(connection: ActorRef)

  case class GetDescription()
  case class GetDescriptionResult(description: Character.Description)

  case class Description(brief: String, complete: String)

  case class LookRoomResult(description: Room.Description, characters: Map[ActorRef, Character.Description])
  case class WalkResult(to: Option[ActorRef])
}

class CharacterLookCollector(character: ActorRef, input: Room.Description) extends LoggingFSM[Option[Nothing], Option[Nothing]] {
  val characters = mutable.Map[ActorRef, Option[Character.Description]]()

  startWith(None, None)

  override def preStart(): Unit = {
    for (character <- input.characters) {
      characters.put(character, Option.empty)
      character ! Character.GetDescription()
    }
  }

  def check() = {
    if (characters.values.forall(_.isDefined)) {
      character ! Character.LookRoomResult(input, characters.mapValues(v => v.get).toMap)
      stop()
    } else {
      stay()
    }
  }

  when (None, stateTimeout = 1 second) {
    case Event(result @ Character.GetDescriptionResult(data), _) =>
      characters.update(sender(), Option(result.description))
      check()
    case Event(StateTimeout, _) =>
      character ! Character.LookRoomResult(input, Map())
      stop()
  }
}

class Character(name: String, var room: ActorRef)
  extends LoggingFSM[CharacterState.Value, CharacterData.Data] with ActorLogging {

  import CharacterState._
  import CharacterData._

  val connections: mutable.HashSet[ActorRef] = mutable.HashSet()

  startWith(Idle, DataNone)

  def write(line: String): Unit = for (connection <- connections) {
    connection ! Player.OutgoingMessage(line)
  }

  def description(): Character.Description = {
    val brief = "%s the furry creature.".format(name.capitalize)
    Character.Description(brief, brief)
  }

  override def preStart(): Unit = {
    room ! Room.CharacterEnter(self)
  }

  override def postStop(): Unit = {
    room ! Room.CharacterLeave(self)
  }

  def walk(direction : Direction.Value) =
    room.ask(Room.GetDescription())(5 second).map({
      case Room.GetDescriptionResult(description) =>
        self ! Character.WalkResult(description.exits.get(direction))
    })

  def look() =
    room.ask(Room.GetDescription())(5 second).map({
      case Room.GetDescriptionResult(description) =>
        context.actorOf(Props(classOf[CharacterLookCollector], self, description))
    })

  when (Idle) {
    case Event(Player.IncomingMessage(data), DataNone) =>
      val command = CharacterParsers.parse(CharacterParsers.command, data)

      if (command.successful) {
        command.get match {
          case CharacterParsers.Look(direction) =>
            look()
            goto(LookPending)

          case CharacterParsers.Walk(direction) =>
            walk(direction)
            goto(WalkPending)
        }
      } else {
        write("What?")
        stay()
      }
  }

  when (LookPending) {
    case Event(Character.LookRoomResult(description, characters), DataNone) =>
      write(description.brief)
      for (character <- (characters - self).values) {
        write(character.brief)
      }
      write("The obvious exits are: %s".format(description.exits.keys.mkString(", ")))
      goto(Idle)
  }

  when (WalkPending) {
    case Event(Character.WalkResult(Some(to)), DataNone) =>
      room ! Room.CharacterLeave(self)
      to ! Room.CharacterEnter(self)
      room = to
      look()
      goto(LookPending)

    case Event(Character.WalkResult(None), DataNone) =>
      write("Ouch that hurts!")
      goto(Idle)
  }

  whenUnhandled {
    case Event(Character.Connect(connection), _) =>
      connections += connection
      stay()
    case Event(Character.Disconnect(connection), _) =>
      connections -= connection
      stay()
    case Event(Character.GetDescription(), _) =>
      sender() ! Character.GetDescriptionResult(description())
      stay()
  }
}

class Characters(val room: ActorRef) extends Actor with ActorLogging {
  val characters = mutable.HashMap[String, ActorRef]()

  override def receive: Actor.Receive = {
    case Characters.GetCharacterByName(name) => sender ! Characters.GetCharacterResult(characters.get(name))
    case Characters.CreateCharacter(name) =>
      if (characters.contains(name)) {
        sender ! Characters.CreateCharacterResult(Option.empty)
      } else {
        val character = context.actorOf(Props(classOf[Character], name, room))
        characters.update(name, character)
        sender ! Characters.CreateCharacterResult(Option(character))
      }
  }
}
