import sbt._

object Dependencies {

  lazy val dependencies = Seq(
    "net.dv8tion" % "JDA" % "3.3.1_295",
    "org.slf4j" % "slf4j-api" % "1.7.25",
    "org.slf4j" % "slf4j-jdk14" % "1.7.25",
    "net.michaelripley" %% "m-bot-module-api" % "0.1.0-SNAPSHOT",
    "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % "2.9.2",
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.2",
    "com.fasterxml.jackson.core" % "jackson-annotations" % "2.9.2",
    "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.9.1"
  )

  lazy val testLibraries: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.0.3"
  ).map(_ % Test)

  lazy val extraResolvers = Seq(
    Resolver.jcenterRepo
  )

}
