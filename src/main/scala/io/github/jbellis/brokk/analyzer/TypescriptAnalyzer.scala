package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.Language
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.semanticcpg.language._
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.shiftleft.semanticcpg.language.*
import io.joern.jssrc2cpg.{Config, JsSrc2Cpg}

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

  override def isClassInProject(className: String): Boolean = {
    val fqClassName = applyJsSrc2CpgNamespaceSyntax(className)
    val td = cpg.typeDecl.fullNameExact(fqClassName).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  /**
   * TypeScript-specific method signature builder.
   */
  override protected def methodSignature(m: Method): String = {
    val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
    val modString = if (modifiers.nonEmpty) modifiers + " " else ""

    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val returnTypeStr = if (returnType == "any") "" else s": $returnType"

    val paramList = m.parameter
      .sortBy(_.order)
      .filterNot(_.name == "this")
      .l
      .map { p =>
        val paramType = sanitizeType(p.typeFullName)
        val typeAnnotation = if (paramType == "any") "" else s": $paramType"
        s"${p.name}${typeAnnotation}"
      }
      .mkString(", ")

    s"${modString}${m.name}(${paramList})${returnTypeStr}"
  }

  /**
   * TypeScript-specific logic for resolving method names.
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    applyJsSrc2CpgNamespaceSyntax(methodName)
  }

  /**
   * TypeScript-specific type sanitization.
   */
  override private[brokk] def sanitizeType(t: String): String = {
    // In TypeScript, we want to simplify complex types
    if (t == "<empty>" || t == "<global>" || t.isEmpty) "any"
    else {
      val typeName = t.split("\\.").lastOption.getOrElse(t)
      // Handle basic TypeScript types
      typeName match {
        case "NUMBER" => "number"
        case "STRING" => "string"
        case "BOOLEAN" => "boolean"
        case "ANY" => "any"
        case "VOID" => "void"
        case other => other.toLowerCase
      }
    }
  }

  /**
   * Find methods matching a given name in the TypeScript CPG.
   */
  override protected def methodsFromName(resolvedMethodName: String): List[Method] = {
    val escaped = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escaped + ".*").l
  }

  /**
   * TypeScript-specific logic for extracting the full class name.
   * Handles cases like:
   * - "A/B/C/A.ts::A" -> "A/B/C/A.ts::program:A"
   * - "A/B/C/A.ts::program:A" -> "A/B/C/A.ts::program:A"
   */
  private[brokk] def applyJsSrc2CpgNamespaceSyntax(className: String): String = {
    if (className.contains("::program:")) {
      // Already has the full format
      className
    } else if (className.contains("::")) {
      // Has path::name format, need to add program:
      val parts = className.split("::")
      s"${parts(0)}::program:${parts(1).replace(".", ":")}"
    } else {
      // Just a class name, can't determine full path
      className
    }
  }
}

object TypescriptAnalyzer {
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    // Use jssrc2cpg for TypeScript analysis
    val config = Config()
      .withInputPath(absPath.toString)

    val newCpg = JsSrc2Cpg().createCpg(config).getOrElse {
      throw new IOException("Failed to create TypeScript CPG")
    }

    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)

    newCpg
  }
}
