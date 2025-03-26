import sbt._
import sbt.Keys._

scalaVersion := "3.5.2"
version := "0.6.3"
organization := "io.github.jbellis"
name := "brokk"

val javaVersion = "21"
javacOptions ++= Seq(
  "-source", javaVersion, 
  "-target", javaVersion,
  // Reflection-specific flags
  "-parameters",           // Preserve method parameter names
  "-g:source,lines,vars"   // Generate full debugging information
)
scalacOptions ++= Seq(
  "-print-lines",
  "-encoding",
  "UTF-8",
  // Reflection-related compiler options
  "-language:reflectiveCalls",
  "-feature",
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
  "org.slf4j" % "slf4j-api" % "2.0.16",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.20.0",

  // Joern dependencies
  "io.joern" %% "x2cpg" % "4.0.265",
  "io.joern" %% "javasrc2cpg" % "4.0.265",
  "io.joern" %% "jssrc2cpg" % "4.0.265",
  "io.joern" %% "pysrc2cpg" % "4.0.265",
  "io.joern" %% "joern-cli" % "4.0.265",
  "io.joern" %% "semanticcpg" % "4.0.265",
  
  // Utilities
  "com.formdev" % "flatlaf" % "3.5.4",
  "com.fifesoft" % "rsyntaxtextarea" % "3.5.4",
  "com.fifesoft" % "autocomplete" % "3.3.2",
  "io.github.java-diff-utils" % "java-diff-utils" % "4.15",
  "org.yaml" % "snakeyaml" % "2.3",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.1.0.202411261347-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.apache" % "7.1.0.202411261347-r",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.0",
  "com.vladsch.flexmark" % "flexmark" % "0.64.8",
  "com.vladsch.flexmark" % "flexmark-html2md-converter" % "0.64.8",
  "org.jsoup" % "jsoup" % "1.19.1",

  // Testing
  "org.junit.jupiter" % "junit-jupiter" % "5.10.2" % Test,
  "org.junit.jupiter" % "junit-jupiter-engine"  % "5.10.2" % Test,
  "com.github.sbt.junit" % "jupiter-interface"  % "0.13.3" % Test,
)

Compile / unmanagedResources := {
  val resources = (Compile / unmanagedResources).value
  resources.filterNot(_.getName == "version.properties")
}

Compile / resourceGenerators += Def.task {
  val sourceFile = (Compile / resourceDirectory).value / "version.properties"
  val targetDir = (Compile / resourceManaged).value
  val targetFile = targetDir / "version.properties"
  val updatedProperties = IO.read(sourceFile).replaceAll("version=.*", s"version=${version.value}")
  IO.write(targetFile, updatedProperties)
  Seq(targetFile)
}.taskValue

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) =>
    xs.last match {
      case x if x.endsWith(".SF") || x.endsWith(".DSA") || x.endsWith(".RSA") => MergeStrategy.discard
      case "MANIFEST.MF" => MergeStrategy.discard 
      case _ => MergeStrategy.first
    }
  case _ => MergeStrategy.first
}
assembly / mainClass := Some("io.github.jbellis.brokk.Brokk")

testFrameworks += new TestFramework("com.github.sbt.junit.JupiterFramework")

Compile / run / fork := true
