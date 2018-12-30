// example run of multi-jvm test:
// systest/multi-jvm:run np.conature.systest.multijvm.Chat
lazy val commonSettings = Seq(
  organization := "np",
  scalaVersion := Config.scalaVer,
  version := "0.1-SNAPSHOT",
  test in assembly := {},
  Test / parallelExecution := false,
  autoCompilerPlugins := true,
  javacOptions ++= CompilerOptions.javacOptions,
  scalacOptions ++= CompilerOptions.scalacBasic,
  resolvers += ("Sonatype OSS Snapshots"
    at "https://oss.sonatype.org/content/repositories/snapshots"),
  publishTo := None
)

lazy val root = (project in file(".")).aggregate(util, actor, nbnet, remote, systest).
  dependsOn(util, actor, nbnet, remote, systest). // for 'sbt console' to find packages
  settings(name := "conature")

lazy val util = (project in file("util")).
  settings(
    commonSettings,
    name := "util",
    libraryDependencies ++= Seq(
      Dependencies.scalareflect,
      Dependencies.scalatest % Test,
      Dependencies.scalacheck % Test)
  )

lazy val actor = (project in file("actor")).
  dependsOn(util).
  settings(
    commonSettings,
    name := "actor",
    libraryDependencies ++= Seq(
      Dependencies.scalareflect,
      Dependencies.scalatest % Test,
      Dependencies.scalacheck % Test)
  )

lazy val nbnet = (project in file("nbnet")).
  dependsOn(util).
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
  dependsOn(util, actor, nbnet, remote).
  settings(
    commonSettings,
    name := "systest",
    libraryDependencies ++= Seq(
      Dependencies.scalareflect,
      Dependencies.scalatest % Test)
  ).
  enablePlugins(MultiJvmPlugin).
  configs(MultiJvm)
