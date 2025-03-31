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
import scala.io.Source

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
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  private def filterModifiers(modifiers: List[String]): List[String] = {
    // Define the conventional order for TypeScript modifiers
    val modifierOrder = Map(
      "public" -> 0,
      "private" -> 0,
      "protected" -> 0,
      "static" -> 1,
      "readonly" -> 2,
      "abstract" -> 3
    )

    // Filter out unwanted modifiers and sort by conventional order
    modifiers.filterNot(mod => mod == "virtual")
      .sortBy(mod => modifierOrder.getOrElse(mod, 99))
  }

  /**
   * TypeScript-specific method signature builder.
   */
  override protected def methodSignature(m: Method): String = {
    // Helper function to filter out unwanted modifiers

    val modifiers = filterModifiers(m.modifier.map(_.modifierType.toLowerCase)
      .filter(_.nonEmpty).toList).mkString(" ")
    var modString = if (modifiers.nonEmpty) modifiers + " " else ""

    // Check if the method has a void return type by examining only the first line of code
    val firstLine = m.code.split("\n").headOption.getOrElse("")
    val isVoidReturn = firstLine.matches(".*\\)\\s*:\\s*void.*")
    val returnType = sanitizeType(makeSimpleType(extractType(m.methodReturn.properties)))
    var returnTypeStr = if (isVoidReturn) ": void"
    else if (returnType == "any") ""
    else ": " + returnType

    // Build the parameter list
    val paramList = m.parameter
      .sortBy(_.order).filterNot(_.name == "this").l
      .map { p =>
        //        val paramType = sanitizeType(makeSimpleType(extractType(p.properties)))
        //        val typeAnnotation = if (paramType == "any") "" else s": $paramType"
        //        s"${p.name}${typeAnnotation}"
        s"${p.code}" // this has more information and parameters are always in one line
      }
      .mkString(", ")

    // Handle constructor methods
    var methodName = m.name
    if (methodName == "<init>") {
      methodName = ""
      returnTypeStr = ""
      modString = modString.trim()
    }
    s"${modString}${methodName}(${paramList})${returnTypeStr}"
  }

  /**
   * TypeScript-specific logic for resolving method names.
   */
  override private[brokk] def resolveMethodName(methodName: String): String = {
    methodName
  }

  /**
   * TypeScript-specific type sanitization.
   */
  override private[brokk] def sanitizeType(t: String): String = {
    // In TypeScript, we want to simplify complex types
    if (t == "<empty>" || t == "<global>" || t.isEmpty) "any"
    else {
      // Replace common TypeScript type patterns
      val result = t.replace("__ecma.Number", "number")
        .replace("__ecma.String", "string")
        .replace("__ecma.Boolean", "boolean")
        .replace("__ecma.Array", "[]")
        .replace("ANY", "any")
        .replace("VOID", "void")
      result
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
   * Gets the source code for the entire file containing a class.
   * Overrides AbstractAnalyzer implementation to handle TypeScript-specific class names.
   */
  override def getClassSource(className: String): String = {
    // Transform the class name to TypeScript CPG format
    var classNodes = cpg.typeDecl.fullNameExact(className).l

    // Similar fallback strategy as in JavaAnalyzer
    if (classNodes.isEmpty) {
      // Try simple name
      val simpleClassName = makeSimpleType(className)
      val nameMatches = cpg.typeDecl.name(simpleClassName).l

      if (nameMatches.size == 1) {
        classNodes = nameMatches
      }
    }

    if (classNodes.isEmpty) return null

    val td = classNodes.head
    val fileOpt = toFile(td.filename)
    if (fileOpt.isEmpty) return null

    val file = fileOpt.get
    scala.util.Using(Source.fromFile(file.absPath().toFile))(_.mkString).toOption.orNull
  }

  /**
   * Extracts a simple class name from a fully qualified name.
   * Only splits the name when it contains "::program:".
   */
  private def makeSimpleType(className: String): String = {
    if (className.contains("::program:")) {
      className.split("::program:").last
    } else {
      className
    }
  }

  /**
   * Extract just the last symbol name (a.b.C -> C, a.b.C.foo -> foo)
   */
  override def extractName(fqName: String): String = {
    // Handle TypeScript module syntax
    if (fqName.contains("::program:")) {
      fqName.split("::program:").last.split("\\.").last
    } else {
      val lastDotIndex = fqName.lastIndexOf('.')
      if (lastDotIndex == -1) fqName else fqName.substring(lastDotIndex + 1)
    }
  }

  /**
   * Extract for classes: just the class name
   * for functions and fields: className.memberName (last two components)
   */
  override def extractShortName(fqName: String, kind: CodeUnitType): String = {
    // For TypeScript, we handle both dot-notation and ::program: notation
    val normalizedName = fqName.replace("::program:", ".")
    val parts = normalizedName.split("\\.")

    kind match {
      case CodeUnitType.CLASS => parts.last
      case _ =>
        if (parts.length >= 2)
          s"${parts(parts.length - 2)}.${parts.last}"
        else
          parts.last
    }
  }

  /**
   * Extract the package portion of the fully qualified name
   * For TypeScript, we consider the module path as the package name
   */
  override def extractPackageName(fqName: String): String = {
    if (fqName.contains("::program:")) {
      val moduleParts = fqName.split("::program:")
      if (moduleParts.length > 1) moduleParts(0) else ""
    } else {
      // For conventional dot notation, use the same logic as Java
      val parts = fqName.split("\\.")
      parts.takeWhile(part => part.isEmpty || !Character.isUpperCase(part.charAt(0)))
        .mkString(".")
    }
  }

  /**
   * Recursively builds a structural "skeleton" for a given TypeDecl.
   * Override to handle TypeScript specific formatting.
   */
  private def outlineTypeDecl(td: io.shiftleft.codepropertygraph.generated.nodes.TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)
    sb.append("  " * indent).append(s"// ${td.fullName}\n")
    sb.append("  " * indent).append("class ").append(className).append(" {\n")

    // Methods: skip any whose name starts with "<clinit>"
    td.method
      .filterNot(_.name.startsWith("<clinit>")) // skip static constructors
      .filterNot(_.name.startsWith("<lambda>")) // skip lambdas which are arrow functions, these will covered by fields
      .foreach { m =>
        sb.append("  " * (indent + 1))
          .append(methodSignature(m))
          .append(" {...}\n")
      }
    // Fields
    td.member
      .filterNot(m => m.astParent.astChildren.isMethod.name.contains(m.name))
      .groupBy(_.name)
      .map(_._2.head) // Take only the first occurrence; there can be multiple fields with the same name
      .foreach { f =>
        val modifiers = f.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty)
        val modString = if (modifiers.nonEmpty) filterModifiers(modifiers.toList).mkString(" ") + " " else ""
        sb.append("  " * (indent + 1))
          .append(s"${modString}${f.name}: ${sanitizeType(makeSimpleType(extractType(f.properties)))};\n")
      }
    sb.append("  " * indent).append("}")
    sb.toString
  }

  /**
   * Override getSkeleton to handle TypeScript specific class structure.
   */
  override def getSkeleton(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) None else Some(outlineTypeDecl(decls.head))
  }

  /**
   * Extracts the most specific type from a properties map.
   * Prioritizes types in this order:
   * 1. Types containing "::program:" (most specific)
   * 2. Other non-empty types that aren't "ANY" or "any"
   * 3. Defaults to "any" if no specific type found
   */
  private def extractType(properties: Map[String, Any]): String = {
    // Collect all types from different sources
    val allTypes = scala.collection.mutable.ListBuffer[String]()

    // Add DYNAMIC_TYPE_HINT_FULL_NAME types (Prio 3)
    properties.get("DYNAMIC_TYPE_HINT_FULL_NAME") match {
      case Some(types: IndexedSeq[_]) =>
        allTypes ++= types.map(_.toString)
      case _ => // Do nothing
    }

    // Add POSSIBLE_TYPES (Prio 2)
    properties.get("POSSIBLE_TYPES") match {
      case Some(types: IndexedSeq[_]) =>
        allTypes ++= types.map(_.toString)
      case _ => // Do nothing
    }

    // Add TYPE_FULL_NAME (Prio 1)
    properties.get("TYPE_FULL_NAME") match {
      case Some(t: String) if t.nonEmpty =>
        allTypes += t
      case _ => // Do nothing
    }

    // Filter out empty strings
    val validTypes = allTypes.filter(_.nonEmpty).toList

    if (validTypes.isEmpty) {
      return "any"
    }

    // First priority: find types containing "::program:" => internal full types
    val programTypes = validTypes.filter(_.contains("::program:"))
    if (programTypes.nonEmpty) {
      return programTypes.head // pick by prio
    }

    // Second priority: find non-ANY types
    val nonSupportedTypes = validTypes.filterNot(
      t => t == "ANY" || t == "any" || t == "this"
        || t.contains("<returnValue>")) // i.e. dynamic external types like @angular/common/http:HttpClient:get:<returnValue>
    if (nonSupportedTypes.nonEmpty) {
      return nonSupportedTypes.head // pick by prio
    }

    // Default to "any"
    "any"
  }
}

object TypescriptAnalyzer {
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    // Use jssrc2cpg for TypeScript analysis
    val config = Config()
      .withInputPath(absPath.toString)

    val newCpg = JsSrc2Cpg().createCpgWithAllOverlays(config).getOrElse {
      throw new IOException("Failed to create TypeScript CPG")
    }
    newCpg
  }
}
