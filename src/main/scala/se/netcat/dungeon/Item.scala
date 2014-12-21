package se.netcat.dungeon

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, SnapshotOffer}
import reactivemongo.bson.BSONObjectID

trait ItemDataCommon {

}

object ItemData extends ItemDataCommon {

  def props(id: BSONObjectID) = Props(new ItemData(id = id))

  case class Snapshot()

}

class ItemData(id: BSONObjectID) extends PersistentActor {

  import se.netcat.dungeon.ItemData._
  import se.netcat.dungeon.Item._

  override def persistenceId: String = "item-data-%s".format(id)

  var handles: Set[String] = Set()
  var brief: Option[String] = None

  case class SnapshotStateV1(handles: Set[String], brief: Option[String])

  val receiveRecover: Receive = LoggingReceive {
    case message@SetDataRequest(_, _) =>
      handles = message.handles
      brief = message.brief
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      handles = snapshot.handles
      brief = snapshot.brief
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@SetDataRequest(_, _) =>
      persist(message) {
        message =>
          handles = message.handles
          brief = message.brief
          sender() ! SetDataResponse()
      }
    case GetDataRequest() => sender() ! GetDataResponse(handles, brief)
    case Snapshot() => saveSnapshot(SnapshotStateV1(handles, brief))
  }
}

trait ItemOwnerCommon {

}

object ItemOwner extends ItemOwnerCommon {

  def props(id: BSONObjectID, characters: ActorRef) = Props(
    new ItemOwner(id = id, characters = characters))

  case class Snapshot()

}

class ItemOwner(id: BSONObjectID, characters: ActorRef) extends PersistentActor {

  import se.netcat.dungeon.ItemOwner._
  import se.netcat.dungeon.Item._

  override def persistenceId: String = "item-owner-%s".format(id)

  var sequence: Long = 0
  var owner: Option[BSONObjectID] = None

  case class SnapshotStateV1(sequence: Long, owner: Option[BSONObjectID])

  val receiveRecover: Receive = LoggingReceive {
    case message@SetOwnerRequest(_, _) =>
      sequence += 1
      owner = message.owner
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      sequence = snapshot.sequence
      owner = snapshot.owner
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@SetOwnerRequest(_, _) =>
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

  type ItemBasicState = Value

  val Start = Value
}

object Item extends ItemDataCommon with ItemOwnerCommon {

  val ItemClassMap: Map[ItemClass.Value, Class[_ <: Item]] = Map(
    ItemClass.Basic -> classOf[ItemBasic]
  )

  def props(clazz: ItemClass.Value, id: BSONObjectID, characters: ActorRef) =
    Props(ItemClassMap(clazz), id, characters)

  case class SetOwnerRequest(sequence: Long, owner: Option[BSONObjectID])

  case class SetOwnerResponse(success: Boolean)

  case class GetOwnerRequest()

  case class GetOwnerResponse(sequence: Long, owner: Option[BSONObjectID])

  case class SetDataRequest(handles: Set[String], brief: Option[String])

  case class SetDataResponse()

  case class GetDataRequest()

  case class GetDataResponse(handles: Set[String], brief: Option[String])

  case class Data(handles: Set[String], brief: Option[String])

}

abstract class Item

class ItemBasic(id: BSONObjectID, characters: ActorRef)
  extends Item with LoggingFSM[ItemBasicState.Value, Option[Nothing]] {

  import se.netcat.dungeon.ItemBasicState._

  val owner = context.actorOf(ItemOwner.props(id, characters), "owner")
  val data = context.actorOf(ItemData.props(id), "data")

  startWith(Start, None)

  when(Start) {
    case Event(Start, _) =>
      stay()
  }

  whenUnhandled {
    case Event(message@Item.SetDataRequest(_, _), _) =>
      data.forward(message)
      stay()
    case Event(message@Item.GetDataRequest(), _) =>
      data.forward(message)
      stay()
    case Event(message@Item.GetOwnerRequest(), _) =>
      owner.forward(message)
      stay()
    case Event(message@Item.SetOwnerRequest(_, _), _) =>
      owner.forward(message)
      stay()
  }
}

object ItemClass extends Enumeration {

  type ItemClass = Value

  val Basic = Value(0)
}

object ItemManager {

  def props(rooms: () => ActorRef, characters: () => ActorRef) =
    Props(new ItemManager(rooms = rooms, characters = characters))

  case class CreateRequest(id: BSONObjectID, clazz: ItemClass.Value)

  case class CreateResponse(item: Option[ActorRef])

  case class GetRequest(id: BSONObjectID)

  case class GetResponse(id: BSONObjectID, item: Option[ActorRef])

  case class GetDataRequest(ids: Set[BSONObjectID])

  case class GetDataResponse(datas: Map[BSONObjectID, Option[Item.Data]])

  case class Snapshot()

}

class ItemManager(rooms: () => ActorRef, characters: () => ActorRef)
  extends PersistentActor with ActorLogging {

  import se.netcat.dungeon.ItemManager._

  override def persistenceId = "item"

  var state = Map[BSONObjectID, ItemClass.Value]()
  var items = Map[BSONObjectID, ActorRef]()

  val receiveRecover: Receive = LoggingReceive {
    case CreateRequest(id, clazz) =>
      if (!state.contains(id)) {
        state += ((id, clazz))
        items += ((id, context.actorOf(Item.props(clazz, id, characters()), id.stringify)))
      }
    case SnapshotOffer(_, snapshot: Map[BSONObjectID, ItemClass.Value]) =>
      for ((id, clazz) <- snapshot) {
        state += ((id, clazz))
        items += ((id, context.actorOf(Item.props(clazz, id, characters()), id.stringify)))
      }
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@CreateRequest(_, _) =>
      persist(message) {
        case CreateRequest(id, clazz) =>
          if (!state.contains(id)) {
            state += ((id, clazz))
            items += ((id, context.actorOf(Item.props(clazz, id, characters()), id.stringify)))
          }
          sender() ! CreateResponse(items.get(id))
      }

    case GetRequest(id) => sender() ! GetResponse(id, items.get(id))

    case ItemManager.Snapshot() => saveSnapshot(state)
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

