package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.Language
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow

import java.io.IOException
import java.nio.file.Path
import scala.util.matching.Regex

/**
 * A concrete analyzer for TypeScript source code, extending AbstractAnalyzer
 * with TypeScript-specific logic for building the CPG, method signatures, etc.
 */
class TypescriptAnalyzer private(sourcePath: Path, cpgInit: Cpg)
  extends AbstractAnalyzer(sourcePath, cpgInit) {

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path) =
    this(sourcePath, TypescriptAnalyzer.createNewCpgForSource(sourcePath))

  def this(sourcePath: Path, language: Language) = this(sourcePath)

  def this(sourcePath: Path, preloadedPath: Path, language: Language) =
    this(sourcePath, preloadedPath)

  /**
   * TypeScript-specific method signature builder.
   */
  override protected def methodSignature(m: Method): String = {
    // Basic placeholder implementation
    s"function ${m.name}() { /* TypeScript method */ }"
  }

  /**
   * TypeScript-specific logic for resolving method names.
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    // Simple implementation - will need enhancement
    methodName
  }

  /**
   * TypeScript-specific type sanitization.
   */
  override private[brokk] def sanitizeType(t: String): String = {
    // Simple implementation - will need enhancement
    t.split("\\.").lastOption.getOrElse(t)
  }

  /**
   * Find methods matching a given name in the TypeScript CPG.
   */
  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escaped + ".*").l
  }
}

object TypescriptAnalyzer {
  // This will need to be implemented with TypeScript-specific CPG creation logic
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    // For now, create an empty CPG as placeholder
    // In the future, this would use a TypeScript-specific CPG generator
    val newCpg = Cpg.empty

    // Apply default overlays similar to JavaAnalyzer
    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)

    newCpg
  }
}
