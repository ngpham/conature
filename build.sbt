lazy val commonSettings = Seq(
  organization := "np",
  scalaVersion := "2.12.4",
  version := "0.1-SNAPSHOT",
  test in assembly := {},
  autoCompilerPlugins := true,
  javacOptions ++= CompilerOptions.javacOptions,
  scalacOptions ++= CompilerOptions.scalacBasic,
  resolvers += ("Sonatype OSS Snapshots"
    at "https://oss.sonatype.org/content/repositories/snapshots"),
  publishTo := None
)

lazy val root = (project in file(".")).aggregate(actor, nbnet, remote, systest).
  dependsOn(actor, nbnet, remote, systest). // for 'sbt console' to find packages
  settings(name := "conature")

lazy val actor = (project in file("actor")).
  settings(
    commonSettings,
    name := "actor",
    libraryDependencies ++= Seq(
      Dependencies.scalatest % Test,
      Dependencies.scalacheck % Test)
  )

lazy val nbnet = (project in file("nbnet")).
  dependsOn(actor).
  settings(
    commonSettings,
    name := "nbnet"
  )

lazy val remote = (project in file("remote")).
  dependsOn(actor, nbnet).
  settings(
    commonSettings,
    name := "remote"
  )

lazy val systest = (project in file("systest")).
  dependsOn(actor, nbnet, remote).
  settings(
    commonSettings,
    name := "systest"
  )
