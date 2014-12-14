package se.netcat.dungeon

import akka.actor._
import akka.pattern._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers



trait CharacterParsers extends RegexParsers {
  import se.netcat.dungeon.CharacterParsers._

  def direction:Parser[Direction.Value] = Direction.values.toList.map(v => v.toString ^^^ v).reduceLeft(_ | _)

  def wildcard: Parser[String] = """(.+)""".r ^^ { _.toString }

  def look: Parser[Look] = "look" ~ opt(direction) ^^ {
    case _ ~ Some(v) => Look(Some(v))
    case _ ~ None => Look(None)
  }

  def walk: Parser[Walk] = direction ^^ {
    case v => Walk(v)
  }

  def say: Parser[Say] = "say" ~ wildcard ^^ {
    case _ ~ message => Say(message)
  }

  def command: Parser[Command] = look | walk | say
}

object CharacterParsers extends CharacterParsers {
  sealed trait Command
  case class Look(direction: Option[Direction.Value]) extends Command
  case class Walk(direction: Direction.Value) extends Command
  case class Say(line: String) extends Command
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

object CharacterMessageCategory extends Enumeration {
  type MessageCategory = Value

  val Say = Value("say")
  val Tell = Value("tell")
}

object Character {
  case class Connect(connection: ActorRef)
  case class Disconnect(connection: ActorRef)

  case class GetDescription()
  case class GetDescriptionResult(description: Character.Description)

  case class Description(name: String, brief: String, complete: String)

  case class LookRoomResult(description: Room.Description, characters: Map[ActorRef, Character.Description])
  case class WalkResult(to: Option[ActorRef])

  case class Message(category: CharacterMessageCategory.Value, sender: ActorRef, description: Character.Description, message: String)
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

class Character(name: String, val start: ActorRef)
  extends LoggingFSM[CharacterState.Value, CharacterData.Data] with ActorLogging {

  import se.netcat.dungeon.CharacterData._
  import se.netcat.dungeon.CharacterState._

  val connections: mutable.Set[ActorRef] = mutable.Set()

  object CurrentRoom {
    var reference = start
  }

  startWith(Idle, DataNone)

  def write(line: String): Unit = for (connection <- connections) {
    connection ! Player.OutgoingMessage(line)
  }

  def description(): Character.Description = {
    val brief = "%s the furry creature.".format(name.capitalize)
    Character.Description(name, brief, brief)
  }

  override def preStart(): Unit = {
    CurrentRoom.reference ! Room.CharacterEnter(self, description())
  }

  override def postStop(): Unit = {
    CurrentRoom.reference ! Room.CharacterLeave(self, description())
  }

  def walk(direction : Direction.Value) =
    CurrentRoom.reference.ask(Room.GetDescription())(5 second).map({
      case Room.GetDescriptionResult(description) =>
        self ! Character.WalkResult(description.exits.get(direction))
    })

  def look() =
    CurrentRoom.reference.ask(Room.GetDescription())(5 second).map({
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

          case CharacterParsers.Say(message) =>
            CurrentRoom.reference ! Character.Message(CharacterMessageCategory.Say, self, description(), message)
            stay()
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
    case Event(Character.WalkResult(Some(room)), DataNone) =>
      CurrentRoom.reference ! Room.CharacterLeave(self, description())
      room ! Room.CharacterEnter(self, description())
      CurrentRoom.reference = room
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
    case Event(Character.Message(category, sender, description, message), _) =>
      if (self == sender) {
        write("You say: %s".format(message.capitalize))
      } else {
        write("%s says: %s".format(description.name.capitalize, message.capitalize))
      }
      stay()
    case Event(Room.CharacterEnter(character, description), _) =>
      if (self != character) {
        write("%s enters the room.".format(description.name.capitalize))
      }
      stay()
    case Event(Room.CharacterLeave(character, description), _) =>
      if (self != character) {
        write("%s leaves the room.".format(description.name.capitalize))
      }
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
