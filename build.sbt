lazy val commonSettings = Seq(
  organization := "np",
  scalaVersion := "2.12.4",
  version := "0.1-SNAPSHOT",
  autoCompilerPlugins := true,
  javacOptions ++= CompilerOptions.javacOptions,
  scalacOptions ++= CompilerOptions.scalacBasic ++ CompilerOptions.scalacLanguage,
  scalacOptions --= Seq("-Xfatal-warnings"),
  resolvers += ("Sonatype OSS Snapshots"
    at "https://oss.sonatype.org/content/repositories/snapshots"),
  publishTo := None
)

lazy val root = (project in file(".")).
  settings(
    commonSettings,
    name := "conature",
    libraryDependencies ++= Seq(
      Dependencies.scalatest % Test,
      Dependencies.scalacheck % Test)
  )
