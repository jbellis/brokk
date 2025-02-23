import sbt._
import sbt.Keys._

scalaVersion := "3.5.2"
version := "0.3-SNAPSHOT"
organization := "org.example"
name := "brokk"

val javaVersion = "21"
javacOptions ++= Seq("-source", javaVersion, "-target", javaVersion)
scalacOptions ++= Seq(
  "-print-lines",
  "-encoding",
  "UTF-8"
)

// Additional repositories
resolvers += "Gradle Libs" at "https://repo.gradle.org/gradle/libs-releases"

libraryDependencies ++= Seq(
  // File watching
  "io.methvin" % "directory-watcher" % "0.18.0",
  
  // LangChain4j dependencies
  "dev.langchain4j" % "langchain4j" % "1.0.0-beta1",
  "dev.langchain4j" % "langchain4j-anthropic" % "1.0.0-beta1",
  "dev.langchain4j" % "langchain4j-open-ai" % "1.0.0-beta1",
  
  // Console and logging
  "org.jline" % "jline" % "3.28.0",
  "org.slf4j" % "slf4j-api" % "2.0.16",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.20.0",

  // Joern dependencies
  "io.joern" %% "x2cpg" % "4.0.242",
  "io.joern" %% "javasrc2cpg" % "4.0.242",
  "io.joern" %% "pysrc2cpg" % "4.0.242",
  "io.joern" %% "joern-cli" % "4.0.242",
  "io.joern" %% "semanticcpg" % "4.0.242",
  
  // Utilities
  "io.github.java-diff-utils" % "java-diff-utils" % "4.15",
  "org.yaml" % "snakeyaml" % "2.3",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.1.0.202411261347-r",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.0",
  
  // Testing
  "org.junit.jupiter" % "junit-jupiter" % "5.10.2" % Test
)

lazy val updateVersionTask = taskKey[Unit]("Updates version in version.properties file")
updateVersionTask := {
  val versionFile = (Compile / resourceDirectory).value / "version.properties"
  if (versionFile.exists()) {
    val updatedProperties = IO.read(versionFile).replaceAll("version=.*", s"version=${version.value}")
    IO.write(versionFile, updatedProperties)
  }
}
Compile /compile := (Compile / compile).dependsOn(updateVersionTask).value

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _*) => MergeStrategy.discard  // Discard all META-INF files
  case _ => MergeStrategy.first
}
assembly / mainClass := Some("io.github.jbellis.brokk.Brokk")

