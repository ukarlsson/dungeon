package se.netcat.dungeon

import akka.actor._
import akka.event.LoggingReceive

import scala.collection.mutable

object Direction extends Enumeration {

  type Direction = Value

  val North = Value("north")
  val South = Value("south")
  val East = Value("east")
  val West = Value("west")
}

object Room {

  def props(brief: String, exits: () => Map[Direction.Value, ActorRef]) = Props(
    new Room(brief = brief, exits = exits))

  case class GetDescription()

  case class GetDescriptionResult(description: Description)

  case class CharacterEnter(character: ActorRef, description: Character.BasicDescription)

  case class CharacterLeave(character: ActorRef, description: Character.BasicDescription)

  case class Description(brief: String, complete: String, characters: Set[ActorRef],
    exits: Map[Direction.Value, ActorRef])

}

class Room(brief: String, exits: () => Map[Direction.Value, ActorRef]) extends Actor with ActorLogging {

  import se.netcat.dungeon.Room._

  val characters = mutable.Set[ActorRef]()

  def description(): Room.Description = Room.Description(brief, brief, characters.toSet, exits())

  override def receive: Actor.Receive = LoggingReceive {
    case GetDescription() => sender ! GetDescriptionResult(description())
    case message@CharacterEnter(character, description) =>
      characters += character
      for (character <- characters) {
        character ! message
      }
    case message@CharacterLeave(character, description) =>
      for (character <- characters) {
        character ! message
      }
      characters -= character
    case message@Character.Message(_, _, _, _) =>
      for (character <- characters) {
        character ! message
      }
  }
}

object RoomManager {
  def props() = Props(new RoomManager())

  case class GetRoom(value: SpecialRoom.Value)
  case class GetRoomResult(room: Option[ActorRef])
}

class RoomManager() extends Actor {
  import RoomManager._

  lazy val room1 : ActorRef = context.actorOf(Room.props("This is a plain room.",
    () => Map((Direction.South, room2), (Direction.East, room3))))

  lazy val room2 : ActorRef = context.actorOf(Room.props("This is another plain room",
    () => Map((Direction.North, room1))))

  lazy val room3 : ActorRef = context.actorOf(Room.props("This is the Dark Room",
    () => Map((Direction.West, room1))))

  val rooms = Map((SpecialRoom.Start, room1))

  override def receive: Actor.Receive = LoggingReceive {
    case GetRoom(value) => sender() ! GetRoomResult(rooms.get(value))
  }
}
