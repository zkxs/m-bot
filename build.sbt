import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      scalaVersion := "2.12.6"
    )),
    organization := "net.michaelripley",
    name := "m-bot",
    version := "0.2.1-SNAPSHOT",
    resolvers ++= extraResolvers,
    libraryDependencies ++= testLibraries,
    libraryDependencies ++= dependencies,
    scalacOptions ++= Seq("-unchecked", "-deprecation"),
    mainClass := Some("net.michaelripley.emdashbot.EmDashBot")
  )
