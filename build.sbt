// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.18"
ThisBuild / version          := "0.2.0"
ThisBuild / organization     := "com.github.kazutomo"

val chiselVersion = "7.13.0"

lazy val root = (project in file("."))
  .settings(
    name := "SRV32I",

    Compile / unmanagedSourceDirectories ++= Seq(
      baseDirectory.value / "garageworks" / "src" / "main" / "scala",
    ),
    libraryDependencies ++= Seq(
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
