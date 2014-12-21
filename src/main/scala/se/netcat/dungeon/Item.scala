package se.netcat.dungeon

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence.{ PersistentActor, SnapshotOffer }
import reactivemongo.bson.BSONObjectID
import reactivemongo.bson.BSONDocumentReader
import reactivemongo.bson.BSONDocument
import com.escalatesoft.subcut.inject.BindingModule
import com.escalatesoft.subcut.inject.Injectable
import reactivemongo.api.collections.default.BSONCollection
import reactivemongo.api.DefaultDB
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object ItemOwner {

  def props(id: BSONObjectID, characters: ActorRef) = Props(
    new ItemOwner(id = id, characters = characters))

  case class Snapshot()
}

class ItemOwner(id: BSONObjectID, characters: ActorRef) extends PersistentActor {

  import ItemOwner._
  import Item._

  override def persistenceId: String = "item-owner-%s".format(id)

  var sequence: Long = 0
  var owner: Option[BSONObjectID] = None

  case class SnapshotStateV1(sequence: Long, owner: Option[BSONObjectID])

  val receiveRecover: Receive = LoggingReceive {
    case message @ SetOwnerRequest(_, _) =>
      sequence += 1
      owner = message.owner
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      sequence = snapshot.sequence
      owner = snapshot.owner
  }

  val receiveCommand: Receive = LoggingReceive {
    case message @ SetOwnerRequest(_, _) =>
      if (message.sequence == sequence) {
        persist(message) {
          message =>
            sequence += 1
            owner = message.owner
            sender() ! SetOwnerResponse(success = true)
        }
      } else {
        sender() ! SetOwnerResponse(success = false)
      }
    case GetOwnerRequest() => sender() ! GetOwnerResponse(sequence, owner)
    case Snapshot() => saveSnapshot(SnapshotStateV1(sequence, owner))
  }
}

object ItemBasicState extends Enumeration {

  val Start = Value
}

object ItemClass extends Enumeration {

  val Basic = Value(0)
}

object Item extends {

  val ItemClassMap: Map[ItemClass.Value, Class[_ <: Item]] = Map(
    ItemClass.Basic -> classOf[ItemBasic])

  def props(clazz: ItemClass.Value, id: BSONObjectID, characters: ActorRef) =
    Props(ItemClassMap(clazz), id, characters)

  case class SetOwnerRequest(sequence: Long, owner: Option[BSONObjectID])
  case class SetOwnerResponse(success: Boolean)
  case class GetOwnerRequest()
  case class GetOwnerResponse(sequence: Long, owner: Option[BSONObjectID])
}

abstract class Item

class ItemBasic(id: BSONObjectID, characters: ActorRef)
  extends Item with LoggingFSM[ItemBasicState.Value, Option[Nothing]] {

  import ItemBasicState._

  val owner = context.actorOf(ItemOwner.props(id, characters), "owner")

  startWith(Start, None)

  when(Start) {
    case Event(Start, _) =>
      stay()
  }

  whenUnhandled {
    case Event(message @ Item.GetOwnerRequest(), _) =>
      owner.forward(message)
      stay()
    case Event(message @ Item.SetOwnerRequest(_, _), _) =>
      owner.forward(message)
      stay()
  }
}

object ItemManager {

  def props(rooms: () => ActorRef, characters: () => ActorRef)(implicit bindingModule: BindingModule) =
    Props(new ItemManager(rooms = rooms, characters = characters))

  case class UpdateRequest(id: BSONObjectID, clazz: ItemClass.Value)
  case class UpdateResponse(id: BSONObjectID, item: ActorRef)
}

class ItemManager(rooms: () => ActorRef, characters: () => ActorRef)(implicit bindingModule: BindingModule)

  extends Actor with ActorLogging {

  import ItemManager._

  val receive: Receive = LoggingReceive {
    case UpdateRequest(id, clazz) =>
      val item = context.child(id.stringify) match {
        case Some(item) => item
        case None => context.actorOf(Item.props(clazz, id, characters()), id.stringify)
      }
      sender() ! UpdateResponse(id, item)
  }
}

object ItemManagerDataCollector {
  def props(ids: Set[BSONObjectID]) = Props(new ItemManagerDataCollector(ids = ids))
}

class ItemManagerDataCollector(ids: Set[BSONObjectID]) extends Actor {
  override def receive: Receive = {
    case _ =>
  }
}

case class ItemData(id: BSONObjectID, handles: Set[String], brief: String)

object ItemData {

  implicit object PersonReader extends BSONDocumentReader[ItemData] {
    def read(document: BSONDocument): ItemData = {
      val id = document.getAs[BSONObjectID]("_id").get
      val handles = document.getAs[Set[String]]("handles").get
      val brief = document.getAs[String]("brief").get

      ItemData(id, handles, brief)
    }
  }
}

class ItemStore()(implicit val bindingModule: BindingModule) extends Injectable {

  import MongoBindingKey._

  val collection = inject[DefaultDB].collection[BSONCollection]("item")

  def insert(id: BSONObjectID, handles: Set[String], brief: String): Future[Unit] = {
    collection.insert(BSONDocument("_id_" -> id, "handles" -> handles, "brief" -> brief)).map(_ => ())
  }

  def find(id: BSONObjectID): Future[Option[ItemData]] = {
    collection.find(BSONDocument("_id" -> id)).one[ItemData]
  }
}