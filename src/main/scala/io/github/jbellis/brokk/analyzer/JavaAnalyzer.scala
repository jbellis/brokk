package io.github.jbellis.brokk.analyzer

import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.X2Cpg
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Method, TypeDecl}
import io.shiftleft.semanticcpg.language.* // Import necessary for extension methods
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.IOException
import java.nio.file.Path
import scala.util.matching.Regex

import scala.util.Try

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

  override def isCpg: Boolean = true

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

  /**
   * Recursively builds a structural "skeleton" for a given TypeDecl.
   */
  override protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)
    sb.append("  " * indent).append("class ").append(className).append(" {\n")

    // Methods: skip any whose name starts with "<lambda>"
    td.method.filterNot(_.name.startsWith("<lambda>")).foreach { m =>
      sb.append("  " * (indent + 1))
        .append(methodSignature(m))
        .append(" {...}\n")
    }

    // Fields: skip any whose name is exactly "outerClass"
    td.member.filterNot(_.name == "outerClass").foreach { f =>
      sb.append("  " * (indent + 1))
        .append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
    }

    // Nested classes: skip any named "<lambda>N" or purely numeric suffix
    td.astChildren.isTypeDecl.filterNot { nested =>
      nested.name.startsWith("<lambda>") ||
        nested.name.split("\\$").exists(_.forall(_.isDigit))
    }.foreach { nested =>
      sb.append(outlineTypeDecl(nested, indent + 1)).append("\n")
    }

    sb.append("  " * indent).append("}")
    sb.toString
  }

  override def getFunctionLocation(
                                    fqMethodName: String,
                                    paramNames: java.util.List[String]
                                  ): IAnalyzer.FunctionLocation = {
    import scala.jdk.CollectionConverters.*

    var methodPattern = Regex.quote(fqMethodName)
    var allCandidates = cpg.method.fullName(s"$methodPattern:.*").l

    if (allCandidates.size == 1) {
      return toFunctionLocation(allCandidates.head)
    }

    if (allCandidates.isEmpty) {
      // Try to resolve the method name without the package (but with the class)
      val shortName = fqMethodName.split('.').takeRight(2).mkString(".")
      methodPattern = Regex.quote(shortName)
      allCandidates = cpg.method.fullName(s".*$methodPattern:.*").l
    }

    val paramList = paramNames.asScala.toList
    val matched = allCandidates.filter { m =>
      val actualNames = m.parameter
        .filterNot(_.name == "this")
        .sortBy(_.order)
        .map(_.name)
        .l
      actualNames == paramList
    }

    if (matched.isEmpty) {
      throw new SymbolNotFoundException(
        s"No methods found in $fqMethodName matching provided parameter names $paramList"
      )
    }

    if (matched.size > 1) {
      throw new SymbolAmbiguousException(
        s"Multiple methods match $fqMethodName with parameter names $paramList"
      )
    }

    toFunctionLocation(matched.head)
  }

  /**
   * Turns a method node into a FunctionLocation.
   * Throws SymbolNotFoundError if file/line info or code extraction fails.
   */
  private def toFunctionLocation(chosen: Method): IAnalyzer.FunctionLocation = {
    // chosen.typeDecl is a Traversal. Get the TypeDecl node, then its filename.
    val fileOpt = chosen.typeDecl.headOption.flatMap(td => toFile(td.filename))
    if (fileOpt.isEmpty || chosen.lineNumber.isEmpty || chosen.lineNumberEnd.isEmpty) {
      throw new SymbolNotFoundException("File or line info missing for chosen method.")
    }

    val file = fileOpt.get
    val start = chosen.lineNumber.get
    val end = chosen.lineNumberEnd.get

    val maybeCode = try {
      val lines = scala.io.Source
        .fromFile(file.absPath().toFile)
        .getLines()
        .slice(start - 1, end) // Use slice for safer indexing
        .mkString("\n")
      Some(lines)
    } catch {
      case _: Throwable => None
    }

    if (maybeCode.isEmpty) {
      throw new SymbolNotFoundException("Could not read code for chosen method.")
    }

    IAnalyzer.FunctionLocation(file, start, end, maybeCode.get)
  }

  /**
   * Parses a Java fully qualified name into its components.
   * For classes: packageName = everything up to last dot, className = last segment, memberName = empty
   * For members: packageName = everything up to class, className = class part, memberName = member
   */
  override protected def parseFqName(fqName: String): CodeUnit.Tuple3[String, String, String] = {
    val lastDot = fqName.lastIndexOf('.')
    if (lastDot == -1) {
      // No dots, it's a simple class name with no package
      new CodeUnit.Tuple3("", fqName, "")
    } else {
      val lastSegment = fqName.substring(lastDot + 1)
      
      // Check if this is a member (method/field/constructor) or a class name
      if (lastSegment.equals("<init>") || // Constructor is a member
          (!Character.isUpperCase(lastSegment.charAt(0)) && !lastSegment.contains("$"))) {
          // Likely a regular method or field (not starting with uppercase, no '$')

          // Find the class part (everything up to the last dot)
          val classEndPos = lastDot
        // Find where the package ends and class begins (last dot before class or whole string)
        var classStartPos = 0
        var pkgEndPos = 0
        
        // Scan backward for the first uppercase letter after a dot - that's likely the class start
        val foundIndex = (classEndPos - 1 to 0 by -1).find { i =>
          fqName.charAt(i) == '.' && i + 1 < fqName.length && 
            Character.isUpperCase(fqName.charAt(i + 1))
          }
          
          foundIndex.foreach { i =>
            classStartPos = i + 1
            pkgEndPos = i
          }
          
          val pkg = if (pkgEndPos > 0) fqName.substring(0, pkgEndPos) else ""
          val className = fqName.substring(classStartPos, classEndPos)
          new CodeUnit.Tuple3(pkg, className, lastSegment)
      } else {
          // Likely a class name (starts with uppercase or contains '$', and isn't <init>)
          val pkg = if (lastDot > 0) fqName.substring(0, lastDot) else ""
          new CodeUnit.Tuple3(pkg, lastSegment, "") // Member part is empty for classes
      }      
    }
  }
  
  // --- Implementations of Abstract CodeUnit Creation ---
  override def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqcn)
    if (!parts._3().isEmpty) {
      throw new IllegalArgumentException(s"Expected a class FQCN but parsing indicated a member: $fqcn")
    }
    Try(CodeUnit.cls(file, parts._1(), parts._2())).toOption
  }

  override def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqmn)
    if (parts._3().isEmpty) {
      throw new IllegalArgumentException(s"Expected a method FQCN but parsing indicated it was not a member: $fqmn")
    }
    val pkg = parts._1()
    val className = parts._2()
    val methodName = parts._3()
    // Pass just the package, and the Class.method short name
    Try(CodeUnit.fn(file, pkg, s"$className.$methodName")).toOption
  }

  override def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit] = {
    val parts = parseFqName(fqfn)
    if (parts._3().isEmpty) {
      throw new IllegalArgumentException(s"Expected a field FQCN but parsing indicated it was not a member: $fqfn")
    }
    val pkg = parts._1()
    val className = parts._2()
    val fieldName = parts._3()
    // Pass just the package, and the Class.field short name
    Try(CodeUnit.field(file, pkg, s"$className.$fieldName")).toOption
  }
  // -----------------------------------------------------

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
