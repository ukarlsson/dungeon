package se.netcat.dungeon.mongo

import com.escalatesoft.subcut.inject.BindingId
import com.escalatesoft.subcut.inject.NewBindingModule
import akka.actor.ActorSystem
import reactivemongo.api.MongoDriver
import reactivemongo.api.MongoConnection
import reactivemongo.api.DefaultDB
import scala.concurrent.ExecutionContext.Implicits.global
  
object MongoBindingKey {
  object NodeList extends BindingId
  object Database extends BindingId
}

object MongoModule extends NewBindingModule(module => {
  import module._
  
  import MongoBindingKey._

  bind[ActorSystem].toSingle {
    ActorSystem("dungeon")
  }

  bind[MongoDriver].toModuleSingle {
    implicit module => new MongoDriver()
  }

  bind[List[String]].identifiedBy(NodeList).toSingle {
    List("localhost")
  }

  bind[MongoConnection].toModuleSingle {
    implicit module =>
      val driver = module.inject[MongoDriver](None)
      val nodes = module.inject[List[String]](Some(NodeList))
      driver.connection(nodes)
  }
  
  bind[String].identifiedBy(Database).toSingle {
    "dungeon"
  }
  
  bind[DefaultDB].toModuleSingle {
    implicit module =>
      val connection = module.inject[MongoConnection](None)
      val name = module.inject[String](Some(Database))
      connection(name)
  }
})