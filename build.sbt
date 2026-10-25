// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.2.0"
ThisBuild / organization     := "com.github.kazutomo"

val chiselVersion = "7.13.0"

lazy val root = (project in file("."))
  .settings(
    name := "SRV32I",

    resolvers += "central-snapshots" at "https://central.sonatype.com/repository/maven-snapshots/",

    libraryDependencies ++= Seq(
      "io.github.hwspec" %% "garageworks" % "0.4.0-SNAPSHOT",
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "org.scalatest" %% "scalatest" % "3.2.19" % "test",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
