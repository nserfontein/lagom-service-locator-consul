organization := "com.lightbend.lagom"

name := "lagom-service-locator-consul"

version := "1.1.0-SNAPSHOT"

scalaVersion := "2.11.8"

val lagomVersion = "1.3.10"

libraryDependencies ++= Seq(
  "com.lightbend.lagom" %% "lagom-scaladsl-api" % lagomVersion,
  "com.ecwid.consul"     % "consul-api"        % "1.2.4",
  "org.scalatest"       %% "scalatest"         % "2.2.4" % Test
)
