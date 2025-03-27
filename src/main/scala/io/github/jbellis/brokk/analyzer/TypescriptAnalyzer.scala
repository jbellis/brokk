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
import io.joern.x2cpg.layers.CallGraph
import io.joern.x2cpg.passes.callgraph.NaiveCallLinker
import org.slf4j.LoggerFactory

import java.io.IOException
import java.nio.file.Path
import scala.util.matching.Regex

/**
 * A concrete analyzer for TypeScript source code, extending AbstractAnalyzer
 * with TypeScript-specific logic for building the CPG, method signatures, etc.
 */
class TypescriptAnalyzer private(sourcePath: Path, cpgInit: Cpg)
  extends AbstractAnalyzer(sourcePath, cpgInit) {

  private val logger = LoggerFactory.getLogger(classOf[TypescriptAnalyzer])

  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  def this(sourcePath: Path) = {
    this(sourcePath, TypescriptAnalyzer.createNewCpgForSource(sourcePath))
    println("TypescriptAnalyzer created with path: " + sourcePath)
    logCpgStructure() // Log CPG structure on initialization
  }

  def this(sourcePath: Path, language: Language) = this(sourcePath)

  def this(sourcePath: Path, preloadedPath: Path, language: Language) =
    this(sourcePath, preloadedPath)

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
    // Remove anonymous function suffixes like <anonymous>-X.Y.Z
    val anonymousPattern = "<anonymous>-.*$".r
    val withoutAnonymous = anonymousPattern.replaceFirstIn(methodName, "")
    
    // Remove numeric suffixes like .123 that sometimes appear in JS/TS CPGs
    val numericSuffix = "\\.[0-9]+$".r
    numericSuffix.replaceFirstIn(withoutAnonymous, "")
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
   * Debug method to log CPG structure details
   */
  private def logCpgStructure(): Unit = {
    println("========== CPG STRUCTURE DEBUG ==========")
    println(s"Total methods in CPG: ${cpg.method.size}")
    println(s"Total calls in CPG: ${cpg.call.size}")
    println(s"Method names: ${cpg.method.name.l.mkString(", ")}")
    println(s"Files processed: ${cpg.file.name.l.mkString(", ")}")

    // Check if TypeScript files were processed
    val tsFiles = cpg.file.name.filter(_.endsWith(".ts")).l
    println(s"TypeScript files: ${tsFiles.mkString(", ")}")

    // Examine call structure
    println("--- METHOD AND CALL DETAILS ---")
    cpg.method.foreach { m =>
      println(s"Method: ${m.fullName}")
      println(s"  Outgoing calls: ${m.call.size}")
      println(s"  Incoming calls: ${m.caller.size}")
    }
    
    // Check for caller/callee relationships specifically
    println("--- CALLER/CALLEE RELATIONSHIPS ---")
    println(s"Total caller relationships: ${cpg.method.caller.size}")
    
    if (cpg.method.caller.isEmpty) {
      println("WARNING: No caller relationships found!")
      println("Checking if callee relationships exist...")
      println(s"Total callee relationships: ${cpg.method.callee.size}")
    }
    
    println("======== END DEBUG OUTPUT ========")
  }
}

object TypescriptAnalyzer {
  private val logger = LoggerFactory.getLogger(TypescriptAnalyzer.getClass)
  private def createNewCpgForSource(sourcePath: Path): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")

    println(s"Creating CPG for TypeScript source at: $absPath")

    // Use jssrc2cpg for TypeScript analysis with proper TypeScript options
    val config = Config()
      .withInputPath(absPath.toString)
      .withTsTypes(true)            // Enable TypeScript type information
      .withSchemaValidation(ValidationMode.Disabled)  // Disable schema validation for better performance

    println(s"JsSrc2Cpg config created for path: ${config.inputPath}")

    val newCpg = try {
      // Use createCpgWithAllOverlays to ensure all passes are applied
      val cpg = new JsSrc2Cpg().createCpg(config).getOrElse {
        println("ERROR: Failed to create TypeScript CPG")
        throw new IOException("Failed to create TypeScript CPG")
      }
      println(s"Successfully created base CPG with ${cpg.method.size} methods and ${cpg.call.size} calls")
      cpg
    } catch {
      case e: Exception =>
        println(s"ERROR creating CPG: ${e.getMessage}")
        e.printStackTrace()
        throw e
    }
    
    println("Base CPG created, now applying overlays")

    // Apply call graph manually to ensure we have caller/callee relationships
    try {
      val context = new LayerCreatorContext(newCpg)
      println("Explicitly applying CallGraph layer")
      new CallGraph().create(context)
      // Apply naive call linker to improve method resolution
      val naiveCallLinker = new io.joern.x2cpg.passes.callgraph.NaiveCallLinker(newCpg)
      naiveCallLinker.createAndApply()
      println("CallGraph layer and NaiveCallLinker applied successfully")
    } catch {
      case e: Exception =>
        println(s"ERROR applying CallGraph layer: ${e.getMessage}")
        e.printStackTrace()
    }

    // Since we can't access the caller relationships directly during CPG creation, just log the basic info
    println(s"After overlays: ${newCpg.method.size} methods, ${newCpg.call.size} calls")
    
    val analyzer = new TypescriptAnalyzer(absPath, newCpg)
    analyzer.logCpgStructure() // Log CPG structure for debugging
    
    newCpg
  }
  
  /**
   * Factory method for creating TypescriptAnalyzer instances
   */
  def apply(sourcePath: Path): TypescriptAnalyzer = {
    println("TypescriptAnalyzer.apply called with path: " + sourcePath)
    new TypescriptAnalyzer(sourcePath)
  }
}
