package se.netcat.dungeon

import akka.actor._

import scala.collection.mutable

object Direction extends Enumeration {
  type Direction = Value

  val North = Value("north")
  val South = Value("south")
  val East = Value("east")
  val West = Value("west")
}

class Room(brief: String, exits: () => Map[Direction.Value, ActorRef]) extends Actor {
  import se.netcat.dungeon.Room._

  val characters = mutable.Set[ActorRef]()

  def description(): Room.Description = Room.Description(brief, brief, characters.toSet, exits())

  override def receive: Actor.Receive = {
    case GetDescription() => sender ! GetDescriptionResult(description())
    case message @ CharacterEnter(character, description) =>
      characters += character
      for (character <- characters) {
        character ! message
      }
    case message @ CharacterLeave(character, description) =>
      for (character <- characters) {
        character ! message
      }
      characters -= character
    case message @ Character.Message(_, _, _, _) =>
      for (character <- characters) {
        character ! message
      }
  }
}

object Room {
  case class GetDescription()
  case class GetDescriptionResult(description: Description)

  case class CharacterEnter(character: ActorRef, description: Character.Description)
  case class CharacterLeave(character: ActorRef, description: Character.Description)

  case class Description(brief: String, complete: String, characters: Set[ActorRef], exits: Map[Direction.Value, ActorRef])
}

