package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.Language
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.X2Cpg
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.IOException
import java.nio.file.Path
import scala.util.matching.Regex

/**
 * A concrete analyzer for Java source code, extending AbstractAnalyzer
 * with Java-specific logic for building the CPG, method signatures, etc.
 */
class JavaAnalyzer private(sourcePath: Path, cpgInit: Cpg)
  extends AbstractAnalyzer(sourcePath, cpgInit) {

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path) =
    this(sourcePath, JavaAnalyzer.createNewCpgForSource(sourcePath))

  def this(sourcePath: Path, language: Language) = this(sourcePath)

  def this(sourcePath: Path, preloadedPath: Path, language: Language) =
    this(sourcePath, preloadedPath)

  /**
   * Java-specific method signature builder.
   */
  override protected def methodSignature(m: Method): String = {
    val knownModifiers = Map(
      "public" -> "public",
      "private" -> "private",
      "protected" -> "protected",
      "static" -> "static",
      "final" -> "final",
      "abstract" -> "abstract",
      "native" -> "native",
      "synchronized" -> "synchronized"
    )

    val modifiers = m.modifier.map { modNode =>
      knownModifiers.getOrElse(modNode.modifierType.toLowerCase, "")
    }.filter(_.nonEmpty)

    val modString = if (modifiers.nonEmpty) modifiers.mkString(" ") + " " else ""
    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val paramList = m.parameter
      .sortBy(_.order)
      .filterNot(_.name == "this")
      .l
      .map(p => s"${sanitizeType(p.typeFullName)} ${p.name}")
      .mkString(", ")

    s"$modString$returnType ${m.name}($paramList)"
  }

  /**
   * Java-specific logic for removing lambda suffixes, nested class numeric suffixes, etc.
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    val segments = methodName.split("\\.")
    val idx = segments.indexWhere(_.matches(".*\\$\\d+$"))
    val relevant = if (idx == -1) segments else segments.take(idx)
    relevant.mkString(".")
  }

  override private[brokk] def sanitizeType(t: String): String = {
    def processType(input: String): String = {
      val isArray = input.endsWith("[]")
      val base = if (isArray) input.dropRight(2) else input
      val shortName = base.split("\\.").lastOption.getOrElse(base)
      if (isArray) s"$shortName[]" else shortName
    }

    if (t.contains("<")) {
      val mainType = t.substring(0, t.indexOf("<"))
      val genericPart = t.substring(t.indexOf("<") + 1, t.lastIndexOf(">"))
      val processedMain = processType(mainType)
      val processedParams = genericPart.split(",").map { param =>
        val trimmed = param.trim
        if (trimmed.contains("<")) sanitizeType(trimmed)
        else processType(trimmed)
      }.mkString(", ")
      s"$processedMain<$processedParams>"
    } else processType(t)
  }

  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escaped + ":.*").l
  }
}

object JavaAnalyzer {
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    // Build the CPG
    val config = Config()
      .withInputPath(absPath.toString)
      .withEnableTypeRecovery(true)

    val newCpg = JavaSrc2Cpg().createCpg(config).getOrElse {
      throw new IOException("Failed to create Java CPG")
    }
    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)
    newCpg
  }
}
