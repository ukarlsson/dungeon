package se.netcat.dungeon

import java.util.UUID

import akka.actor._
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, SnapshotOffer}
import se.netcat.dungeon.Implicits.convertUUIDToString

trait ItemDataCommon {

  case class SetBasicRequest(handles: Set[String], brief: String)

  case class SetBasicResponse()

  case class GetBasicRequest()

  case class GetBasicResponse(handles: Set[String], brief: Option[String])

}

object ItemData extends ItemDataCommon {

  def props(id: UUID) = Props(new CharacterData(id = id))

  case class Snapshot()

}

class ItemData(id: UUID) extends PersistentActor {

  import se.netcat.dungeon.ItemData._

  override def persistenceId: String = "item-data-%s".format(id)

  var handles: Set[String] = Set()
  var brief: Option[String] = None

  case class SnapshotStateV1(handles: Set[String], brief: Option[String])

  val receiveRecover: Receive = LoggingReceive {
    case message@SetBasicRequest(_, _) =>
      handles = message.handles
      brief = Some(message.brief)
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      handles = snapshot.handles
      brief = snapshot.brief
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@SetBasicRequest(_, _) =>
      persist(message) {
        message =>
          handles = message.handles
          brief = Some(message.brief)
          sender() ! SetBasicResponse()
      }
    case GetBasicRequest() => sender() ! GetBasicResponse(handles, brief)
    case Snapshot() => saveSnapshot(SnapshotStateV1(handles, brief))
  }
}

trait ItemOwnerCommon {

  case class SetOwnerRequest(sequence: Long, owner: Option[UUID])

  case class SetOwnerResponse(success: Boolean)

  case class GetOwnerRequest()

  case class GetOwnerResponse(sequence: Long, owner: Option[UUID])

}

object ItemOwner extends ItemOwnerCommon {

  def props(id: UUID, characters: ActorRef) = Props(
    new ItemOwner(id = id, characters = characters))

  case class Snapshot()

}

class ItemOwner(id: UUID, characters: ActorRef) extends PersistentActor {

  import se.netcat.dungeon.ItemOwner._

  override def persistenceId: String = "item-owner-%s".format(id)

  var sequence: Long = 0
  var owner: Option[UUID] = None

  case class SnapshotStateV1(sequence: Long, owner: Option[UUID])

  val receiveRecover: Receive = LoggingReceive {
    case message@SetOwnerRequest(_, _) =>
      if (message.sequence == sequence) {
        owner = message.owner
      }
    case SnapshotOffer(_, snapshot: SnapshotStateV1) =>
      sequence = snapshot.sequence
      owner = snapshot.owner
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@SetOwnerRequest(_, _) =>
      persist(message) {
        message =>
          if (message.sequence == sequence) {
            owner = message.owner
            sender() ! SetOwnerResponse(success = true)
          } else {
            sender() ! SetOwnerResponse(success = false)
          }
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
  val ItemClassMap: Map[ItemClass.Value, Class[_ <: Item ]] = Map(
    ItemClass.Basic -> classOf[ItemBasic]
  )

  def props(clazz: ItemClass.Value, id: UUID, characters: ActorRef) =
    Props(ItemClassMap(clazz), id, characters)
}

abstract class Item

class ItemBasic(id: UUID, characters: ActorRef)
  extends Item with LoggingFSM[ItemBasicState.Value, Option[Nothing]] {

  import se.netcat.dungeon.ItemBasicState._

  val data = context.actorOf(ItemData.props(id), "data")
  val owner = context.actorOf(ItemOwner.props(id, characters), "owner")

  startWith(Start, None)

  when(Start) {
    case Event(Start, _) =>
      stay()
  }

  whenUnhandled {
    case Event(message@Item.SetBasicRequest(_, _), _) =>
      data.tell(message, sender())
      stay()
    case Event(message@Item.GetBasicRequest(), _) =>
      data.tell(message, sender())
      stay()
    case Event(message@Item.GetOwnerRequest(), _) =>
      owner.tell(message, sender())
      stay()
    case Event(message@Item.SetOwnerRequest(_, _), _) =>
      owner.tell(message, sender())
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

  case class CreateRequest(id: UUID, clazz: ItemClass.Value)

  case class CreateResponse(item: Option[ActorRef])

  case class GetRequest(id: UUID)

  case class GetResponse(id: UUID, item: Option[ActorRef])

  case class Snapshot()
}

class ItemManager(rooms: () => ActorRef, characters: () => ActorRef)
  extends PersistentActor with ActorLogging {

  import se.netcat.dungeon.ItemManager._

  override def persistenceId = "item"

  var state = Map[UUID, ItemClass.Value]()
  var items = Map[UUID, ActorRef]()

  val receiveRecover: Receive = LoggingReceive {
    case CreateRequest(id, clazz) =>
      if (!state.contains(id)) {
        state += ((id, clazz))
        items += ((id, context.actorOf(Item.props(clazz, id, characters()), id)))
      }
    case SnapshotOffer(_, snapshot: Map[UUID, ItemClass.Value]) =>
      for ((id, clazz) <- snapshot) {
        state += ((id, clazz))
        items += ((id, context.actorOf(Item.props(clazz, id, characters()), id)))
      }
  }

  val receiveCommand: Receive = LoggingReceive {
    case message@CreateRequest(_, _) =>
      persist(message) {
        case CreateRequest(id, clazz) =>
          if (!state.contains(id)) {
            state += ((id, clazz))
            items += ((id, context.actorOf(Item.props(clazz, id, characters()), id)))
          }
          sender() ! CreateResponse(items.get(id))
      }

    case GetRequest(id) => sender() ! GetResponse(id, items.get(id))

    case CharacterManager.Snapshot() => saveSnapshot(state)
  }
}
