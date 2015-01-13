package se.netcat.dungeon.character

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern._
import akka.persistence.{ PersistentActor, SnapshotOffer }
import se.netcat.dungeon.common.Implicits.{ convertPairToPath }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.parsing.combinator.RegexParsers
import com.escalatesoft.subcut.inject.BindingModule
import com.escalatesoft.subcut.inject.Injectable
import reactivemongo.api.DefaultDB
import scala.util.Failure
import scala.util.Success
import scala.concurrent.Future
import reactivemongo.core.commands.LastError
import reactivemongo.api.Collection
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.bson._
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType
import reactivemongo.api.indexes.IndexType
import se.netcat.dungeon.room._
import se.netcat.dungeon.player._
import se.netcat.dungeon.item._

object CharacterParsers extends CharacterParsers {

  sealed trait Command

  case class Look(direction: Option[Direction.Value]) extends Command
  case class Walk(direction: Direction.Value) extends Command
  case class Say(line: String) extends Command
  case class Sleep() extends Command
  case class Create() extends Command
  case class Inventory() extends Command
}

trait CharacterParsers extends RegexParsers {

  import CharacterParsers._

  def direction: Parser[Direction.Value] = Direction.values.toList.map(v => v.toString ^^^ v)
    .reduceLeft(_ | _)

  def wildcard: Parser[String] = "(.+)".r ^^ {
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

  def sleep: Parser[Sleep] = "sleep" ^^^ { Sleep() }

  def create: Parser[Create] = "create" ^^^ { Create() }

  def inventory: Parser[Inventory] = "^(i|inventory)$".r ^^^ { Inventory() }

  def command: Parser[Command] = look | walk | say | sleep | create | inventory
}

object CharacterStateData {

  sealed trait Data

  case object DataNone extends Data
}

object CharacterMessageCategory extends Enumeration {
  val Say = Value("say")
  val Tell = Value("tell")
}

object CharacterState extends Enumeration {

  val Idle = Value
  val RoomPending = Value
  val LookPending = Value
  val WalkPending = Value
  val SleepPending = Value
  val CreatePending = Value
  val InventoryPending = Value
}

object Character {

  def props(characterId: BSONObjectID, rooms: ActorRef, items: ActorRef)(implicit bindingModule: BindingModule) =
    Props(new Character(characterId = characterId, rooms = rooms, items = items))

  case class Connect(connection: ActorRef)
  case class Disconnect(connection: ActorRef)
  case class GetDescription()
  case class GetDescriptionResult(description: Character.BasicDescription)
  case class BasicDescription(name: String, brief: String)
  case class LookRoomResult(description: Room.Description,
    characters: Map[ActorRef, Character.BasicDescription])
  case class WalkResult(to: Option[ActorRef])
  case class Message(category: CharacterMessageCategory.Value, sender: BSONObjectID,
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
    case Event(result @ Character.GetDescriptionResult(data), _) =>
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

class Character(characterId: BSONObjectID, rooms: ActorRef, items: ActorRef)(implicit bindingModule: BindingModule)
  extends LoggingFSM[CharacterState.Value, CharacterStateData.Data] with ActorLogging {

  import CharacterState._
  import CharacterStateData._

  case class InventoryResult(documents: List[CharacterItemDocument])

  var connections: Set[ActorRef] = Set()

  var location: Option[ActorRef] = None

  var characterItemStore = new CharacterItemStore()

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
          context.actorOf(Props(classOf[CharacterLookCollector], self, description), "look-collector")
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
            context.actorOf(CharacterItemCreator.props(characterId, items), "item-creator")
            goto(CreatePending)
          case CharacterParsers.Inventory() =>
            write("You start checking your inventory.")
            characterItemStore.find(characterId)
              .map(documents => InventoryResult(documents))
              .recover {
                case e =>
                  log.error("find failure: {}", e)
                  InventoryResult(List())
              }
              .pipeTo(self)
            goto(InventoryPending)
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
        case Some(document) =>
          characterItemStore.insert(CharacterItemDocument(characterId, document.id, document.handles, document.brief))
          write("You created an item.")
        case None =>
          write("You failed to create an item.")
      }
      goto(Idle)
  }

  when(InventoryPending) {
    case Event(InventoryResult(documents), DataNone) =>
      for (document <- documents) {
        write(document.itemId.stringify)
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

object CharacterItemCreatorState extends Enumeration {

  val PreStart = Value
  val StateInsert = Value
  val StateUpdate = Value
  val StateOwner = Value
}

object CharacterItemCreator {

  def props(characterId: BSONObjectID, items: ActorRef)(implicit bindingModule: BindingModule) =
    Props(new CharacterItemCreator(characterId = characterId, items = items))

  case class CreateItemResponse(document: Option[ItemDocument])
}

class CharacterItemCreator(characterId: BSONObjectID, items: ActorRef)(implicit bindingModule: BindingModule)
  extends LoggingFSM[CharacterItemCreatorState.Value, Option[ActorRef]] with ActorLogging {

  import CharacterItemCreator._
  import CharacterItemCreatorState._

  case class Start()

  case class ItemInsertResult(result: Boolean)

  val store = new ItemStore()

  val document = ItemDocument(id = BSONObjectID.generate, handles = Set("basic"), brief = "a basic item")

  startWith(PreStart, None)

  self ! Start()

  def success() = {
    context.parent ! CreateItemResponse(Some(document))
    stop()
  }

  def failure() = {
    context.parent ! CreateItemResponse(None)
    stop()
  }

  override def receive: Receive = LoggingReceive {
    case message => super.receive(message)
  }

  onTransition {
    case _ -> StateInsert =>
      store.insert(document).andThen {
        case Success(()) =>
          self ! ItemInsertResult(true)
        case Failure(e) =>
          log.error("insert failure: {}", e)
          self ! ItemInsertResult(false)
      }
    case _ -> StateUpdate =>
      items ! ItemManager.UpdateRequest(document.id, ItemClass.Basic)
    case _ -> StateOwner =>
      nextStateData match {
        case Some(item) =>
          item ! Item.SetOwnerRequest(0, Some(characterId))
      }
  }

  when(PreStart) {
    case Event(Start(), None) =>
      goto(StateInsert)
  }

  when(StateInsert) {
    case Event(ItemInsertResult(result), None) =>
      result match {
        case true => goto(StateUpdate)
        case false => failure()
      }
  }

  when(StateUpdate) {
    case Event(ItemManager.UpdateResponse(_, item), None) =>
      goto(StateOwner).using(Some(item))
  }

  when(StateOwner) {
    case Event(Item.SetOwnerResponse(result), Some(item)) =>
      result match {
        case true => success()
        case false => failure()
      }
  }
}

object CharacterManager {

  def props(rooms: () => ActorRef, items: () => ActorRef)(implicit bindingModule: BindingModule) =
    Props(new CharacterManager(rooms = rooms, items = items))

  case class UpdateRequest(id: BSONObjectID)
  case class UpdateResponse(id: BSONObjectID, character: ActorRef)
}

class CharacterManager(rooms: () => ActorRef, items: () => ActorRef)(implicit bindingModule: BindingModule)
  extends Actor with ActorLogging {

  import CharacterManager._

  val store = new CharacterStore()

  val receive: Receive = LoggingReceive {
    case UpdateRequest(id) =>
      val character = context.child(id.stringify) match {
        case Some(character) => character
        case None => context.actorOf(Character.props(id, rooms(), items()), id.stringify)
      }
      sender() ! UpdateResponse(id, character)
  }
}

case class CharacterItemDocument(characterId: BSONObjectID, itemId: BSONObjectID, handles: Set[String], brief: String)

object CharacterItemDocument {

  implicit object CharacterItemWriter extends BSONDocumentWriter[CharacterItemDocument] {
    def write(document: CharacterItemDocument): BSONDocument = {
      BSONDocument(
        "characterId" -> document.characterId,
        "itemId" -> document.itemId,
        "handles" -> document.handles,
        "brief" -> document.brief)
    }
  }

  implicit object CharacterItemReader extends BSONDocumentReader[CharacterItemDocument] {
    def read(document: BSONDocument): CharacterItemDocument = {
      val characterId = document.getAs[BSONObjectID]("characterId").get
      val itemId = document.getAs[BSONObjectID]("itemId").get
      val handles = document.getAs[Set[String]]("handles").get
      val brief = document.getAs[String]("brief").get

      CharacterItemDocument(characterId, itemId, handles, brief)
    }
  }
}

class CharacterItemStore()(implicit val bindingModule: BindingModule) extends Injectable {
  val collection = inject[DefaultDB].collection[BSONCollection]("character.item")

  collection.indexesManager.ensure(Index(List("characterId" -> IndexType.Ascending, "itemId" -> IndexType.Ascending), unique = true))

  def insert(document: CharacterItemDocument): Future[Unit] = {
    collection.insert(document).map(_ => ())
  }

  def remove(characterId: BSONObjectID, itemId: BSONObjectID): Future[Unit] = {
    collection.remove(BSONDocument("characterId" -> characterId, "itemId" -> itemId)).map(_ => ())
  }

  def find(characterId: BSONObjectID): Future[List[CharacterItemDocument]] = {
    collection.find(BSONDocument("characterId" -> characterId)).cursor[CharacterItemDocument].collect[List]()
  }
}

case class CharacterDocument(id: BSONObjectID, name: String)

object CharacterDocument {
  implicit object PersonReader extends BSONDocumentReader[CharacterDocument] {
    def read(document: BSONDocument): CharacterDocument = {
      val id = document.getAs[BSONObjectID]("_id").get
      val name = document.getAs[String]("name").get

      CharacterDocument(id, name)
    }
  }
}

class CharacterStore()(implicit val bindingModule: BindingModule) extends Injectable {
  val collection = inject[DefaultDB].collection[BSONCollection]("character")

  collection.indexesManager.ensure(Index(List("name" -> IndexType.Ascending), unique = true))

  def insert(id: BSONObjectID, name: String): Future[Unit] = {
    collection.insert(BSONDocument("_id" -> id, "name" -> name)).map(_ => ())
  }

  def find(id: BSONObjectID): Future[Option[CharacterDocument]] = {
    collection.find(BSONDocument("_id" -> id)).one[CharacterDocument]
  }

  def find(name: String): Future[Option[CharacterDocument]] = {
    collection.find(BSONDocument("name" -> name)).one[CharacterDocument]
  }
}