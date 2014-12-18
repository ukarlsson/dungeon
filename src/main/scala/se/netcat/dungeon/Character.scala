package se.netcat.dungeon

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern._
import akka.persistence.{PersistentActor, SnapshotOffer}
import se.netcat.dungeon.Implicits.{convertUUIDToString, convertPairToPath}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers

object CharacterParsers extends CharacterParsers {

  sealed trait Command

  case class Look(direction: Option[Direction.Value]) extends Command

  case class Walk(direction: Direction.Value) extends Command

  case class Say(line: String) extends Command

  case class Sleep() extends Command

  case class Create() extends Command

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

  def create: Parser[Create] = "create" ^^^ {
    Create()
  }

  def command: Parser[Command] = look | walk | say | sleep | create
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
  val CreatePending = Value
}

object Character {

  def props(id: UUID, rooms: ActorRef, items: ActorRef) =
    Props(new Character(id = id, rooms = rooms, items = items))

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

  var characters = Map[ActorRef, Option[Character.BasicDescription]]()

  for (character <- input.characters) {
    characters += character -> Option.empty
    character ! Character.GetDescription()
  }

  startWith(None, None)

  when(None, stateTimeout = 1 second) {
    case Event(result@Character.GetDescriptionResult(data), _) =>
      characters += sender() -> Option(result.description)

      if (!characters.values.exists(_.isEmpty)) {
        character ! Character.LookRoomResult(input, characters.mapValues(_.get))
        stop()
      } else {
        stay()
      }

    case Event(StateTimeout, _) =>
      character ! Character.LookRoomResult(input, Map())
      stop()
  }
}

class Character(id: UUID, rooms: ActorRef, items: ActorRef)
  extends LoggingFSM[CharacterState.Value, CharacterStateData.Data] with ActorLogging {

  import se.netcat.dungeon.CharacterState._
  import se.netcat.dungeon.CharacterStateData._

  val characterData: ActorRef = context.actorOf(CharacterData.props(id))

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
    rooms ! RoomManager.GetRoom(SpecialRoom.Start)
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
    case Event(RoomManager.GetRoomResult(Some(room)), DataNone) =>
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
            if (look()) {
              goto(LookPending)
            } else {
              stay()
            }
          case CharacterParsers.Walk(direction) =>
            if (walk(direction)) {
              goto(WalkPending)
            } else {
              stay()
            }
          case CharacterParsers.Sleep() =>
            write("You fall asleep.")
            goto(SleepPending)
          case CharacterParsers.Say(message) =>
            // CurrentRoom.reference !
            //   Character.Message(CharacterMessageCategory.Say, self, description(), message)
            stay()
          case CharacterParsers.Create() =>
            write("You start creating something.")
            context.actorOf(CharacterItemCreator.props(id, items))
            goto(CreatePending)
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

  when(CreatePending) {
    case Event(CharacterItemCreator.CreateItemResponse(result), DataNone) =>
      result match {
        case Some(itemId) =>
          write("You created an item.")
        case None =>
          write("You failed to create an item.")
      }
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
    case Event(Character.Message(category, id, description, message), _) =>
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

  def props(id: UUID) = Props(new CharacterData(id = id))

  case class SetNameRequest(name: String)

  case class SetNameResponse()

  case class SetBriefRequest(brief: String)

  case class SetBriefResponse()

  case class GetBasicRequest()

  case class GetBasicResponse(name: Option[String], brief: Option[String])

  case class Snapshot()

}

class CharacterData(id: UUID) extends PersistentActor {

  import se.netcat.dungeon.CharacterData._

  case class SnapshotStateV1(name: Option[String], brief: Option[String])

  override def persistenceId = "character-data-%s".format(id)

  var name: Option[String] = None
  var brief: Option[String] = None

  val receiveRecover: Receive = {
    case SetBriefRequest(data) => brief = Option(data)
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      name = snapshot.name
      brief = snapshot.brief
  }

  val receiveCommand: Receive = {
    case message@SetNameRequest(_) =>
      persist(message) {
        message =>
          name = Option(message.name)
          sender() ! SetNameResponse()
      }
    case message@SetBriefRequest(_) =>
      persist(message) {
        message =>
          name = Option(message.brief)
          sender() ! SetBriefResponse()
      }
    case GetBasicRequest() => sender() ! GetBasicResponse(name, brief)
    case Snapshot() => saveSnapshot(SnapshotStateV1(name, brief))
  }
}

object CharacterItemCreatorState extends Enumeration {

  type CharacterItemCreaterState = Value

  val PreStart = Value
  val CreateItemPending = Value
  val SetItemPending = Value
}

object CharacterItemCreator {

  def props(characterId: UUID, items: ActorRef) =
    Props(new CharacterItemCreator(id = characterId, items = items))

  case class CreateItemResponse(id: Option[UUID])

  sealed trait Data

  case class DataNone() extends Data

  case class DataItem(item: ActorRef) extends Data

  case class DataItemSetResult(owner: Boolean, basic: Boolean) extends Data

}


class CharacterItemCreator(id: UUID, items: ActorRef)
  extends LoggingFSM[CharacterItemCreatorState.Value, CharacterItemCreator.Data] {

  import se.netcat.dungeon.CharacterItemCreator._
  import se.netcat.dungeon.CharacterItemCreatorState._

  case class Start()

  val itemId = UUID.randomUUID()

  startWith(PreStart, DataNone())

  self ! Start()

  when(PreStart) {
    case Event(Start(), DataNone()) =>
      items ! ItemManager.CreateRequest(itemId, ItemClass.Basic)
      goto(CreateItemPending).using(DataNone())
  }

  when(CreateItemPending) {
    case Event(ItemManager.CreateResponse(Some(item)), DataNone()) =>
      item ! Item.SetBasicRequest(Set("basic"), "a basic item")
      item ! Item.SetOwnerRequest(0, Some(id))
      goto(SetItemPending).using(DataItemSetResult(owner = false, basic = false))
  }

  when(SetItemPending) {
    case Event(Item.SetBasicResponse(), DataItemSetResult(true, true)) =>
      context.parent ! CreateItemResponse(Some(itemId))
      stop()
    case Event(Item.SetBasicResponse(), DataItemSetResult(_, _)) =>
      stay()
  }
}

object CharacterItems {

  case class SetRequest(id: UUID)

  case class SetResponse()

  case class ClearRequest(id: UUID)

  case class ClearResponse()

  case class GetRequest()

  case class GetResponse(ids: Set[UUID])

  case class Snapshot()

}

class CharacterItems(id: UUID) extends PersistentActor {

  import se.netcat.dungeon.CharacterItems._

  case class SnapshotStateV1(items: Set[UUID])

  override def persistenceId = "character-items-%s".format(id)

  var items: Set[UUID] = Set[UUID]()

  val receiveRecover: Receive = {
    case message@SetRequest(_) =>
      items += message.id
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      items = snapshot.items
  }

  val receiveCommand: Receive = {
    case message@SetRequest(_) =>
      persist(message) {
        message =>
          items += message.id
          sender() ! SetResponse()
      }
    case GetRequest() => sender() ! GetResponse(items)
    case Snapshot() => saveSnapshot(SnapshotStateV1(items))
  }
}

object CharacterManager {

  def props(rooms: () => ActorRef, items: () => ActorRef)(implicit config: DungeonConfig) =
    Props(new CharacterManager(rooms = rooms, items = items))

  case class CreateRequest(id: UUID)

  case class CreateResponse(character: Option[ActorRef])

  case class GetRequest(id: UUID)

  case class GetResponse(character: Option[ActorRef])

  case class Snapshot()

}

class CharacterManager(rooms: () => ActorRef, items: () => ActorRef)(implicit config: DungeonConfig)
  extends PersistentActor with ActorLogging {

  import se.netcat.dungeon.CharacterManager._

  context.actorSelection((Module.Character, UUID.randomUUID()))

  override def persistenceId = "character"

  var characters = Map[UUID, ActorRef]()

  val receiveRecover: Receive = LoggingReceive {
    case CreateRequest(id) =>
      if (!characters.contains(id)) {
        characters += ((id, context.actorOf(Character.props(id, rooms(), items()), id)))
      }
    case SnapshotOffer(_, snapshot: Set[UUID]) =>
      for (id <- snapshot) {
        characters += ((id, context.actorOf(Character.props(id, rooms(), items()), id)))
      }
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@CreateRequest(_) =>
      persist(message) {
        case CreateRequest(id) =>
          if (!characters.contains(id)) {
            characters += ((id, context.actorOf(Character.props(id, rooms(), items()), id)))
          }
          sender() ! CreateResponse(characters.get(id))
      }

    case GetRequest(id) => sender() ! GetResponse(characters.get(id))

    case CharacterManager.Snapshot() => saveSnapshot(characters.keys.toSet)
  }
}


object CharacterResolver {

  def props() = Props(new CharacterResolver())

  case class SetRequest(name: String, id: UUID)

  case class SetResponse(success: Boolean)

  case class GetRequest(name: String)

  case class GetResponse(id: Option[UUID])

}

class CharacterResolver extends PersistentActor with ActorLogging {

  override def persistenceId = "character-resolver"

  import se.netcat.dungeon.CharacterResolver._

  var state = Map[String, UUID]()

  val receiveRecover: Receive = LoggingReceive {
    case SetRequest(name, id) =>
      if (!state.contains(name)) {
        state += ((name, id))
      }
    case SnapshotOffer(_, snapshot: (Map[String, UUID])) =>
      state = snapshot
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@SetRequest(_, _) =>
      persist(message) {
        case SetRequest(name, id) =>
          if (!state.contains(name)) {
            state += ((name, id))
            sender() ! SetResponse(success = true)
          } else {
            sender() ! SetResponse(success = false)
          }
      }
    case GetRequest(name) => sender() ! GetResponse(state.get(name))

    case CharacterManager.Snapshot() => saveSnapshot(state)
  }
}
