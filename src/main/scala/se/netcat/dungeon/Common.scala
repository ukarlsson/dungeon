package se.netcat.dungeon

import java.util.UUID
import akka.actor.{ActorPath, ActorSystem}
import reactivemongo.api.MongoDriver
import reactivemongo.api.DefaultDB
import reactivemongo.bson.BSONBinary
import reactivemongo.bson.BSONValue
import reactivemongo.bson.Subtype
import reactivemongo.bson.BSONObjectID
import akka.actor.ActorContext

object Implicits {
  implicit def convertPairToPath(pair: (Manager.Value, BSONObjectID))(implicit managers: Map[Manager.Value, String], context: ActorContext): ActorPath = pair match {
    case (value, id) => context.system.child(managers(value)).child(id.stringify)
  }
}

class DungeonConfig(val system: ActorSystem, val modules: Map[Manager.Value, String] )

object DungeonConfig {
  def apply(system: ActorSystem, modules: Map[Manager.Value, String] ) =
    new DungeonConfig(system, modules)
}