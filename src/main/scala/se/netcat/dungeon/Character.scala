package se.netcat.dungeon

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern._
import akka.persistence.{PersistentActor, SnapshotOffer}
import se.netcat.dungeon.Room.CharacterEnter

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers

object CharacterParsers extends CharacterParsers {

  sealed trait Command

  case class Look(direction: Option[Direction.Value]) extends Command

  case class Walk(direction: Direction.Value) extends Command

  case class Say(line: String) extends Command

  case class Sleep() extends Command

}

trait CharacterParsers extends RegexParsers {

  import se.netcat.dungeon.CharacterParsers._

  def direction: Parser[Direction.Value] = Direction.values.toList.map(v => v.toString ^^^ v)
    .reduceLeft(_ | _)

  def wildcard: Parser[String] = """(.+)""".r ^^ {
    _.toString
  }

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

  def sleep: Parser[Sleep] = "sleep" ^^^ {
    Sleep()
  }

  def command: Parser[Command] = look | walk | say | sleep
}

object CharacterStateData {

  sealed trait Data

  case object DataNone extends Data

}

object CharacterMessageCategory extends Enumeration {

  type MessageCategory = Value

  val Say = Value("say")
  val Tell = Value("tell")
}

object CharacterState extends Enumeration {

  type CharacterState = Value

  val Idle = Value
  val RoomPending = Value
  val LookPending = Value
  val WalkPending = Value
  val SleepPending = Value
}

object Character {

  def props(uuid: UUID, dungeon: ActorRef) = Props(new Character(uuid = uuid, dungeon = dungeon))

  case class Connect(connection: ActorRef)

  case class Disconnect(connection: ActorRef)

  case class GetDescription()

  case class GetDescriptionResult(description: Character.BasicDescription)

  case class BasicDescription(name: String, brief: String)

  case class LookRoomResult(description: Room.Description,
    characters: Map[ActorRef, Character.BasicDescription])

  case class WalkResult(to: Option[ActorRef])

  case class Message(category: CharacterMessageCategory.Value, sender: UUID,
    description: Character.BasicDescription, message: String)

}

class CharacterLookCollector(character: ActorRef, input: Room.Description)
  extends LoggingFSM[Option[Nothing], Option[Nothing]] {

  val characters = mutable.Map[ActorRef, Option[Character.BasicDescription]]()

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

  when(None, stateTimeout = 1 second) {
    case Event(result@Character.GetDescriptionResult(data), _) =>
      characters.update(sender(), Option(result.description))
      check()
    case Event(StateTimeout, _) =>
      character ! Character.LookRoomResult(input, Map())
      stop()
  }
}

class Character(uuid: UUID, dungeon: ActorRef)
  extends LoggingFSM[CharacterState.Value, CharacterStateData.Data] with ActorLogging {

  import se.netcat.dungeon.CharacterState._
  import se.netcat.dungeon.CharacterStateData._

  val characterData: ActorRef = context.actorOf(CharacterData.props(uuid))

  var connections: Set[ActorRef] = Set()

  var location: Option[ActorRef] = None

  startWith(RoomPending, DataNone)

  def write(line: String): Unit = for (connectionRef <- connections) {
    connectionRef ! Player.OutgoingMessage(line)
  }

  def description(): Character.BasicDescription = {
    Character.BasicDescription("xxx", "furry creature")
  }

  override def preStart(): Unit = {
    dungeon ! Dungeon.GetRoom(SpecialRoom.Start)
  }

  override def postStop(): Unit = {
    if (location.isDefined) {
      location.get ! Room.CharacterLeave(self, description())
    }
  }

  def walk(direction: Direction.Value): Boolean = location match {
    case Some(room) =>
      room.ask(Room.GetDescription())(5 second).map({
        case Room.GetDescriptionResult(description) =>
          self ! Character.WalkResult(description.exits.get(direction))
      })
      true
    case None => false
  }

  def look(): Boolean = location match {
    case Some(room) =>
      room.ask(Room.GetDescription())(5 second).map({
        case Room.GetDescriptionResult(description) =>
          context.actorOf(Props(classOf[CharacterLookCollector], self, description))
      })
      true
    case None =>
      write("It is pitch black.")
      false
  }

  when(RoomPending) {
    case Event(Dungeon.GetRoomResult(Some(room)), DataNone) =>
      room ! Room.CharacterEnter(self, description())
      location = Some(room)
      goto(Idle)
  }

  when(Idle) {
    case Event(Player.IncomingMessage(data), DataNone) =>
      val command = CharacterParsers.parse(CharacterParsers.command, data)

      if (command.successful) {
        command.get match {
          case CharacterParsers.Look(direction) =>
            if (look()) goto(LookPending) else stay()
          case CharacterParsers.Walk(direction) =>
            if (walk(direction)) goto(WalkPending) else stay()
          case CharacterParsers.Sleep() =>
            write("You fall asleep.")
            goto(SleepPending)
          case CharacterParsers.Say(message) =>
            // CurrentRoom.reference !
            //   Character.Message(CharacterMessageCategory.Say, self, description(), message)
            stay()
        }
      } else {
        write("What?")
        stay()
      }
  }

  when(LookPending) {
    case Event(Character.LookRoomResult(description, characters), DataNone) =>
      write(description.brief)
      for (character <- (characters - self).values) {
        write(character.brief)
      }
      write("The obvious exits are: %s".format(description.exits.keys.mkString(", ")))
      goto(Idle)
  }

  when(WalkPending) {
    case Event(Character.WalkResult(Some(nextRoomRef)), DataNone) =>
      location.get ! Room.CharacterLeave(self, description())
      nextRoomRef ! Room.CharacterEnter(self, description())
      location = Option(nextRoomRef)
      look()
      goto(LookPending)

    case Event(Character.WalkResult(None), DataNone) =>
      write("Ouch that hurts!")
      goto(Idle)
  }

  when(SleepPending) {
    case Event(Player.IncomingMessage(_), DataNone) =>
      write("Sure, if you were awake.")
      stay()
    case Event(StateTimeout, _) =>
      write("You wake up.")
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
    case Event(Character.Message(category, uuid, description, message), _) =>
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

object CharacterData {

  def props(uuid: UUID) = Props(new CharacterData(uuid = uuid))

  case class SetName(name: String)

  case class SetBrief(brief: String)

  case class SetResult(success: Boolean)

  case class GetBasic()

  case class GetBasicResult(name: Option[String], brief: Option[String])

  case class Snapshot()

}

class CharacterData(uuid: UUID) extends PersistentActor {

  import se.netcat.dungeon.CharacterData._

  case class SnapshotDataV1(name: Option[String], brief: Option[String])

  override def persistenceId = "character-data-%s".format(uuid)

  var name: Option[String] = None
  var brief: Option[String] = None

  val receiveRecover: Receive = {
    case SetBrief(data) => brief = Option(data)
    case SnapshotOffer(_, snapshot: SnapshotDataV1) =>
      name = snapshot.name
      brief = snapshot.brief
  }

  val receiveCommand: Receive = {
    case message@SetName(_) =>
      persist(message) {
        message => name = Option(message.name)
      }
    case message@SetBrief(_) =>
      persist(message) {
        message => name = Option(message.brief)
      }
    case GetBasic() => sender() ! GetBasicResult(name, brief)
    case Snapshot() => saveSnapshot(SnapshotDataV1(name, brief))
  }
}

object CharacterSupervisor {

  def props(dungeon: ActorRef) = Props(new CharacterSupervisor(dungeon = dungeon))

  case class Create(uuid: UUID)

  case class CreateResult(character: Option[ActorRef])

  case class Get(uuid: UUID)

  case class GetResult(character: Option[ActorRef])

  case class Snapshot()

}

class CharacterSupervisor(dungeon: ActorRef) extends PersistentActor with ActorLogging {

  import se.netcat.dungeon.CharacterSupervisor._

  override def persistenceId = "characters"

  var characters = Map[UUID, ActorRef]()

  val receiveRecover: Receive = LoggingReceive {
    case Create(uuid) =>
      if (!characters.contains(uuid)) {
        characters += ((uuid, context.actorOf(Character.props(uuid, dungeon), uuid.toString)))
      }
    case SnapshotOffer(_, snapshot: Set[UUID]) =>
      for (uuid <- snapshot) {
        characters += ((uuid, context.actorOf(Character.props(uuid, dungeon), uuid.toString)))
      }
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@Create(_) =>
      persist(message) {
        case Create(uuid) =>
          if (!characters.contains(uuid)) {
            characters += ((uuid, context.actorOf(Character.props(uuid, dungeon), uuid.toString)))
            sender() ! CreateResult(Option(characters(uuid)))
          } else {
            sender() ! CreateResult(None)
          }
      }

    case Get(uuid) => sender() ! GetResult(characters.get(uuid))

    case CharacterSupervisor.Snapshot() => saveSnapshot(characters.keys.toSet)
  }
}


object CharacterByNameResolver {

  def props() = Props(new CharacterByNameResolver())

  case class Set(name: String, uuid: UUID)

  case class SetResult(success: Boolean)

  case class Get(name: String)

  case class GetResult(uuid: Option[UUID])

}

class CharacterByNameResolver extends PersistentActor with ActorLogging {

  override def persistenceId = "character-by-name-resolver"

  import se.netcat.dungeon.CharacterByNameResolver._

  var map = Map[String, UUID]()

  val receiveRecover: Receive = LoggingReceive {
    case Set(name, uuid) =>
      if (!map.contains(name)) {
        map += ((name, uuid))
      }
    case SnapshotOffer(_, snapshot: (Map[String, UUID])) =>
      map = snapshot
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@Set(_, _) =>
      persist(message) {
        case Set(name, uuid) =>
          if (!map.contains(name)) {
            map += ((name, uuid))
            sender() ! SetResult(success = true)
          } else {
            sender() ! SetResult(success = false)
          }
      }
    case Get(name) => sender() ! GetResult(map.get(name))

    case CharacterSupervisor.Snapshot() => saveSnapshot(map)
  }
}
