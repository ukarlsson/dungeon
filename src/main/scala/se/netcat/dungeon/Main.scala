package se.netcat.dungeon

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import akka.util.Timeout
import se.netcat.dungeon.Character.{GetBriefDescriptionResult, GetBriefDescription}

import scala.collection.mutable
import scala.util.parsing.combinator.RegexParsers
import akka.pattern._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {
  implicit val system = ActorSystem("netcat")

  val room = system.actorOf(Props(classOf[Room]), "room1")

  val characters = system.actorOf(Props(classOf[Characters], room), "characters")

  val server = system.actorOf(Props(classOf[Server], characters), "server")
}

class Server(characters: ActorRef) extends Actor {
  import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected, Register}

  implicit val system = context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 30000))

  override def receive: Receive = {
    case bound @ Bound(localAddress) => println(localAddress)

    case CommandFailed(_ : Bind) => context.stop(self)

    case Connected(remote, local) =>
      val connection = sender()
      val player = context.actorOf(
        Props(classOf[TcpPlayer], connection, characters))
      connection ! Register(player)
  }
}

object Direction extends Enumeration {
  type Direction = Value

  val North = Value("north")
  val South = Value("south")
  val East = Value("east")
  val West = Value("west")
}

trait DungeonParsers extends RegexParsers {
  import DungeonParsers._

  def direction:Parser[Direction.Value] = Direction.values.toList.map(v => v.toString ^^^ v).reduceLeft(_ | _)

  def look: Parser[Look] = "look" ~ opt(direction) ^^ {
    case _ ~ Some(v) => Look(Some(v))
    case _ ~ None => Look(None)
  }

  def walk: Parser[Walk] = "walk" ~ direction ^^ {
    case _ ~ v => Walk(v)
  }

  def exit: Parser[Exit] = "exit" ^^^ Exit()

  def command: Parser[Command] = look | walk | exit
}

object DungeonParsers extends DungeonParsers {
  sealed trait Command
  case class Look(direction: Option[Direction.Value]) extends Command
  case class Walk(direction: Direction.Value) extends Command
  case class Exit() extends Command
}

trait ConnectionParsers extends RegexParsers {
  def name: Parser[String] = "[a-zA-Z]+".r ^^ { _.toLowerCase }

  def yes: Parser[Boolean] = "yes" ^^^ true
  def no: Parser[Boolean] = "no" ^^^ false

  def boolean: Parser[Boolean] = yes | no
}

object ConnectionParsers extends ConnectionParsers {
  sealed trait Command
  case class Use(name: String) extends Command
  case class Create(name: String) extends Command
  case class Exit() extends Command
}

object Characters {
  case class CreateCharacter(name: String)
  case class CreateCharacterResult(character: Option[ActorRef])

  case class GetCharacterByName(name: String)
  case class GetCharacterResult(player: Option[ActorRef])
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

  case class GetBriefDescription()
  case class GetBriefDescriptionResult(data: String)

  case class LookRoomResult(description: String, characters: Iterable[String])
}

class CharacterLookRoomCollector(character: ActorRef, input: Room.GetDataResult) extends LoggingFSM[Option[Nothing], Option[Nothing]] {
  val characters = mutable.Map[ActorRef, Option[String]]()

  startWith(None, None)

  override def preStart(): Unit = {
    for (character <- input.characters) {
      characters.put(character, Option.empty)
      character ! Character.GetBriefDescription()
    }
  }

  def check() = {
    if (characters.values.forall(_.isDefined)) {
      character ! Character.LookRoomResult(input.description, characters.values.flatten)
      stop()
    } else {
      stay()
    }
  }

  when (None, stateTimeout = 5 second) {
    case Event(Character.GetBriefDescriptionResult(data), _) =>
      characters.update(sender(), Option(data))
      check()
    case Event(StateTimeout, _) =>
      character ! Character.LookRoomResult("Everything is black around you.", List())
      stop()
  }
}

class Character(name: String, var room: ActorRef)
  extends LoggingFSM[CharacterState.Value, CharacterData.Data] with ActorLogging {

  import CharacterState._
  import CharacterData._

  val connections: mutable.HashSet[ActorRef] = mutable.HashSet()

  startWith(Idle, DataNone)

  def write(line: String) = for (connection <- connections) {
    connection ! Player.OutgoingMessage(line)
  }

  override def preStart(): Unit = {
    room ! Room.CharacterEnter(self)
  }

  override def postStop(): Unit = {
    room ! Room.CharacterLeave(self)
  }

  when (Idle) {
    case Event(DungeonParsers.Walk(direction), DataNone) =>
      write("You enjoy walking in the sun!")
      stay()
    case Event(DungeonParsers.Look(direction), DataNone) =>
      implicit val timeout = Timeout(5.second)
      room.ask(Room.GetData()).map({
        case result @ Room.GetDataResult(_, _, _) =>
          context.actorOf(Props(classOf[CharacterLookRoomCollector], self, result))
        })
      goto(LookPending)
  }

  when (LookPending) {
    case Event(Character.LookRoomResult(description, characters), DataNone) =>
      write(description)
      for (character <- characters) {
        write(character)
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
    case Event(Character.GetBriefDescription(), _) =>
      val description = "%s the furry creature.".format(name.capitalize)
      sender() ! Character.GetBriefDescriptionResult(description)
      stay()
    case Event(Player.OutgoingMessage(data), _) =>
      write(data)
      stay()
  }
}

object Room {
  case class GetData()
  case class GetDataResult(description: String, characters: Set[ActorRef], exits: Map[Direction.Value, ActorRef])

  case class CharacterEnter(character: ActorRef)
  case class CharacterLeave(character: ActorRef)
}

class Room extends Actor {
  import Room._

  val description = "This is a plain room with no exits."

  val characters = mutable.Set[ActorRef]()
  val exits = mutable.Map[Direction.Value, ActorRef]()

  override def receive: Actor.Receive = {
    case GetData() => sender ! GetDataResult(description, characters.toSet, exits.toMap)
  case CharacterEnter(character) => characters += character
  case CharacterLeave(character) => characters -= character
  }
}