package se.netcat.dungeon.common

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

object Manager extends Enumeration {
  var Character = Value
  var Item = Value
  var Room = Value
}
