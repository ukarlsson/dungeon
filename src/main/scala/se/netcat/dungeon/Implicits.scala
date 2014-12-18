package se.netcat.dungeon

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem}

object Implicits {
  implicit def convertUUIDToString(uuid: UUID): String = uuid.toString
  implicit def convertPairToPath(pair: (Module.Value, UUID))(implicit config: DungeonConfig): ActorPath = pair match {
    case (value, uuid) => config.system.child(config.modules(value)).child(uuid)
  }
}

class DungeonConfig(val system: ActorSystem, val modules: Map[Module.Value, String]) {

}

object DungeonConfig {
  def apply(system: ActorSystem, modules: Map[Module.Value, String]) = new DungeonConfig(system, modules)
}
