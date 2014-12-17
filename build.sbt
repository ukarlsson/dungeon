name := "dungeon"

organization := "netcat"

version := "1.0"

scalaVersion := "2.11.1"

scalacOptions := Seq("-encoding", "utf8",
                     "-target:jvm-1.7",
                     "-feature",
                     "-language:implicitConversions",
                     "-language:postfixOps",
                     "-unchecked",
                     "-deprecation",
                     "-Xlog-reflective-calls"
                    )

mainClass := Some("se.netcat.dungeon.Main")

resolvers ++= Seq("Sonatype Releases"   at "http://oss.sonatype.org/content/repositories/releases",
                  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
                  "Spray Repository"    at "http://repo.spray.io/",
                  "Spray Nightlies"     at "http://nightlies.spray.io/",
                  "Base64 Repo"         at "http://dl.bintray.com/content/softprops/maven")

libraryDependencies ++= {
  val akkaVersion  = "2.3.4"
  Seq(
    "com.typesafe.akka"       %%  "akka-actor"             % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-experimental" % akkaVersion,
    "com.typesafe.akka"       %%  "akka-slf4j"             % akkaVersion,
    "ch.qos.logback"          %   "logback-classic"        % "1.0.12",
    "com.typesafe.akka"       %%  "akka-testkit"           % akkaVersion    % "test",
    "org.specs2"              %%  "specs2"                 % "2.3.13"        % "test",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2"
  )
}
