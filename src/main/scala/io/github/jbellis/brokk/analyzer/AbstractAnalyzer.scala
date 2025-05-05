package io.github.jbellis.brokk.analyzer

import flatgraph.storage.Serialization
import io.github.jbellis.brokk.*
import org.slf4j.LoggerFactory
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{Config, JavaSrc2Cpg}
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.{ValidationMode, X2Cpg}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{Call, Method, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import scala.jdk.OptionConverters.RichOptional

import java.io.{Closeable, IOException}
import java.nio.file.Path
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util.Try
import scala.util.matching.Regex

/**
 * An abstract base for language-specific analyzers.
 * It implements the bulk of "IAnalyzer" using Joern's CPG,
 * but delegates language-specific operations (like building a CPG or
 * constructing method signatures) to concrete subclasses.
 */
abstract class AbstractAnalyzer protected(sourcePath: Path, private[brokk] val cpg: Cpg)
  extends IAnalyzer with Closeable {

  // Logger instance for this class
  private val logger = LoggerFactory.getLogger(getClass)

  // Convert to absolute filename immediately and verify it's a directory
  protected val absolutePath: Path = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }

  // implicits at the top, you will regret it otherwise
  protected implicit val ec: ExecutionContext = ExecutionContext.global
  protected implicit val callResolver: ICallResolver = NoResolve

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]] = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String] = Set.empty
  initializePageRank()

  /**
   * Secondary constructor: create a new analyzer, loading a pre-built CPG from `preloadedPath`.
   */
  def this(sourcePath: Path, preloadedPath: Path) =
    this(sourcePath, CpgBasedTool.loadFromFile(preloadedPath.toString))

  /**
   * Simplest constructor: build a brand new CPG for the given source path.
   */
  def this(sourcePath: Path) = this(sourcePath, Cpg.empty)

  /**
   * Utility constructor that can override the language, or any other parameter.
   * The default just calls `this(sourcePath)`.
   */
  def this(sourcePath: Path, maybeUnused: Language) = this(sourcePath)

  override def isCpg: Boolean = true

  /**
   * Return the method signature as a language-appropriate String,
   * e.g. for Java: "public int foo(String bar)"
   */
  protected def methodSignature(m: Method): String

  /**
   * Language-specific method to parse a fully qualified name into its components.
   * 
   * @param fqName The fully qualified name to parse
   * @return A Tuple3 containing (packageName, className, memberName)
   *         For a class, memberName will be empty
   */
  protected def parseFqName(fqName: String): CodeUnit.Tuple3[String, String, String]
  
  // --- Abstract CodeUnit Creation Methods ---
  /** Create a CLASS CodeUnit from FQCN. Relies on language-specific heuristics. Prefer CodeUnit factories where complete information is available. */
  def cuClass(fqcn: String, file: ProjectFile): Option[CodeUnit]

  /** Create a FUNCTION CodeUnit from FQN. Relies on language-specific heuristics. Prefer CodeUnit factories where complete information is available. */
  def cuFunction(fqmn: String, file: ProjectFile): Option[CodeUnit]

  /** Create a FIELD CodeUnit from FQN. Relies on language-specific heuristics. Prefer CodeUnit factories where complete information is available. */
  def cuField(fqfn: String, file: ProjectFile): Option[CodeUnit]
  // -----------------------------------------

  /**
   * Transform method node fullName to a stable "resolved" name
   * (e.g. removing lambda suffixes).
   */
  private[brokk] def resolveMethodName(methodName: String): String

  /**
   * Possibly remove package names from a type string, or do
   * other language-specific cleanup.
   */
  private[brokk] def sanitizeType(t: String): String

  /**
   * Return all Method nodes that match the given fully qualified method name
   * (subclasses can handle language-specific naming).
   */
  protected def methodsFromName(resolvedMethodName: String): List[Method]

  /**
   * Optional final override if the notion of "class in project" differs
   * by language. By default, we check that the typeDecl is present
   * and has some members or methods.
   */
  override def isClassInProject(className: String): Boolean = {
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  private def initializePageRank(): Unit = {
    if (cpg.metaData.headOption.isEmpty)
      throw new IllegalStateException("CPG root not found for " + absolutePath)

    // Initialize adjacency map
    adjacency = buildWeightedAdjacency()
    val reverseMap = TrieMap[String, TrieMap[String, Int]]()

    // Build reverse adjacency from the forward adjacency
    adjacency.par.foreach { case (src, tgtMap) =>
      tgtMap.foreach { case (dst, weight) =>
        val targetMap = reverseMap.getOrElseUpdate(dst, TrieMap.empty)
        targetMap.update(src, targetMap.getOrElse(src, 0) + weight)
      }
    }
    reverseAdjacency = reverseMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap

    classesForPagerank = (adjacency.keys ++ adjacency.values.flatMap(_.keys)).toSet
  }

  /**
   * Gets the source code for a given method name. If multiple methods match,
   * returns them all concatenated. If none match, returns None.
   */
  override def getMethodSource(methodName: String): Option[String] = {
    val resolvedMethodName = resolveMethodName(methodName)
    val methods = methodsFromName(resolvedMethodName)

    // static constructors often lack line info
    val sources = methods.flatMap { method =>
      for {
        file <- toFile(method.filename)
        startLine <- method.lineNumber
        endLine <- method.lineNumberEnd
      } yield scala.util.Using(Source.fromFile(file.absPath().toFile)) { source =>
        source.getLines().slice(startLine - 1, endLine).mkString("\n")
      }.toOption
    }.flatten

    if (sources.isEmpty) None else Some(sources.mkString("\n\n"))
  }

  /**
   * Gets the source code for the entire file containing a class.
   */
  override def getClassSource(className: String): String = {
    var classNodes = cpg.typeDecl.fullNameExact(className).l

    // This is called by the search agent, so be forgiving: if no exact match, try fuzzy matching
    if (classNodes.isEmpty) {
      // Attempt by simple name
      val simpleClassName = className.split("[.$]").last
      val nameMatches = cpg.typeDecl.name(simpleClassName).l

      if (nameMatches.size == 1) {
        classNodes = nameMatches
      } else if (nameMatches.size > 1) {
        // Second attempt: try replacing $ with .
        val dotClassName = className.replace('$', '.')
        val dotMatches = nameMatches.filter(td => td.fullName.replace('$', '.') == dotClassName)
        if (dotMatches.size == 1) classNodes = dotMatches
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
   * Recursively builds a structural "skeleton" for a given TypeDecl.
   * Language-specific details like method signatures and filtering rules
   * are handled by the concrete implementation.
   */
  protected def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String

  /**
   * Builds a structural skeleton for a given class by name (simple or FQCN).
   */
  override def getSkeleton(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) None else Some(outlineTypeDecl(decls.head))
  }

  /**
   * Build a weighted adjacency map at the class level: className -> Map[targetClassName -> weight].
   */
  protected def buildWeightedAdjacency()(implicit callResolver: ICallResolver): Map[String, Map[String, Int]] = {
    val adjacencyMap = TrieMap[String, TrieMap[String, Int]]()
    val primitiveTypes = Set("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")

    def isRelevant(t: String): Boolean =
      !primitiveTypes.contains(t) && !t.startsWith("java.")

    cpg.typeDecl.l.par.foreach { td =>
      val sourceClass = td.fullName

      // (1) Collect calls
      td.method.call.callee.typeDecl.fullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(increment(adjacencyMap, sourceClass, _))

      // (2) Collect field references
      td.member.typeFullName
        .map(_.replaceAll("""\[\]""", ""))
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(increment(adjacencyMap, sourceClass, _))

      // (3) Collect "extends"/"implements" edges; these count 5x
      td.inheritsFromTypeFullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach(parent => increment(adjacencyMap, sourceClass, parent, 5))
    }
    adjacencyMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap
  }

  /**
   * Increment a (source -> target) edge by `count` in an adjacency map.
   */
  protected def increment(
                           map: TrieMap[String, TrieMap[String, Int]],
                           source: String,
                           target: String,
                           count: Int = 1
                         ): Unit = {
    val targetMap = map.getOrElseUpdate(source, TrieMap.empty)
    targetMap.update(target, targetMap.getOrElse(target, 0) + count)
  }

  override def getFileFor(fqcn: String): java.util.Optional[ProjectFile] = {
    val scalaOpt = cpg.typeDecl.fullNameExact(fqcn).headOption.flatMap(toFile)
    scalaOpt match
      case Some(file) => java.util.Optional.of(file)
      case None       => java.util.Optional.empty()
  }

  private def toFile(td: TypeDecl): Option[ProjectFile] = {
    if (td.filename.isEmpty || td.filename == "<empty>" || td.filename == "<unknown>") None
    else toFile(td.filename)
  }

  private[brokk] def toFile(relName: String): Option[ProjectFile] =
    Some(ProjectFile(absolutePath, relName))

  // using cpg.all doesn't work because there are always-present nodes for files and the ANY typedecl
  override def isEmpty: Boolean = cpg.member.isEmpty

  override def getAllClasses: java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    cpg.typeDecl
      .filter(toFile(_).isDefined)
      .flatMap { td =>
        toFile(td).flatMap(file => cuClass(td.fullName, file))
      }
      .l
      .asJava
  }

  /**
   * Returns just the class signature and field declarations, without method details.
   */
  override def getSkeletonHeader(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) None
    else Some(headerString(decls.head, 0) + "  [... methods not shown ...]\n}")
  }

  private def headerString(td: TypeDecl, indent: Int): String = {
    val sb = new StringBuilder
    val className = sanitizeType(td.name)
    sb.append("  " * indent).append("class ").append(className).append(" {\n")
    td.member.foreach { m =>
      val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
      val modString = if (modifiers.nonEmpty) s"$modifiers " else ""
      val typeName = sanitizeType(m.typeFullName)
      sb.append("  " * (indent + 1)).append(s"$modString$typeName ${m.name};\n")
    }
    sb.toString
  }

  private def chopColon(full: String) = full.split(":").head

  /**
   * For a given method node `m`, returns the calling method .fullName (with the suffix after `:` chopped).
   * If `excludeSelfRefs` is true, we skip callers whose TypeDecl matches `m.typeDecl`.
   */
  protected def callersOfMethodNode(m: Method, excludeSelfRefs: Boolean): List[String] = {
    var calls = m.callIn
    if (excludeSelfRefs) {
      val selfSource = m.typeDecl.fullName.head
      calls = calls.filterNot { call =>
        val callerSource = call.method.typeDecl.fullName.head
        partOfClass(selfSource, callerSource)
      }
    }
    calls.method
      .fullName
      .map(name => resolveMethodName(chopColon(name)))
      .distinct
      .l
  }

  protected def partOfClass(parentFqcn: String, childFqcn: String): Boolean = {
    childFqcn == parentFqcn ||
      childFqcn.startsWith(parentFqcn + ".") ||
      childFqcn.startsWith(parentFqcn + "$")
  }

  /**
   * Return all methods that reference a given field "classFullName.fieldName".
   */
  protected def referencesToField(selfSource: String, fieldName: String, excludeSelfRefs: Boolean): List[String] = {
    var calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      .where(_.argument(1).typ.fullNameExact(selfSource))
      .where(_.argument(2).codeExact(fieldName))

    if (excludeSelfRefs) {
      calls = calls.filterNot(call =>
        partOfClass(selfSource, call.method.typeDecl.fullName.head)
      )
    }
    calls.method
      .fullName
      .map(x => resolveMethodName(chopColon(x)))
      .distinct
      .l
  }

  /**
   * Find references to a class used as a type:
   * - fields typed with that class
   * - parameters/locals typed with that class
   * - classes that inherit from that class
   * - methods that return that class
   */
  protected def referencesToClassAsType(classFullName: String): List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val typePattern = "^" + Regex.quote(classFullName) + "(\\$.*)?(\\[\\])?"

    // Fields typed with this class → return as field CodeUnits
    val fieldRefs = cpg.member
      .typeFullName(typePattern)
      .astParent
      .isTypeDecl
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td => // td is the TypeDecl containing the field
        toFile(td).map { file => // Use map here since we return a List[CodeUnit] below
          // Find members matching the type pattern within this TypeDecl
          td.member.typeFullName(typePattern).flatMap { member =>
            // Here we have all the parts: package, class, member
            val lastDot = td.fullName.lastIndexOf('.')
            val packageName = if (lastDot > 0) td.fullName.substring(0, lastDot) else ""
            val className = td.name
            val fieldName = member.name
            
            // Create using exact 3-parameter factory
            Try(CodeUnit.field(file, packageName, s"$className.$fieldName")).toOption
          }.toList // Convert the final Iterator[CodeUnit] to List[CodeUnit]
        }.getOrElse(List.empty) // If toFile(td) was None, return empty list
      } // This outer flatMap now correctly flattens List[List[CodeUnit]] into List[CodeUnit]

    // Parameters typed with this class → return as function CodeUnits
    val paramRefs = cpg.parameter
      .typeFullName(typePattern)
      .method
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot = fullClassPath.lastIndexOf('.')
              val packageName = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName = methodName.substring(lastDot + 1)
              
              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Locals typed with this class → return as function CodeUnits
    val localRefs = cpg.local
      .typeFullName(typePattern)
      .method
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot = fullClassPath.lastIndexOf('.')
              val packageName = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName = methodName.substring(lastDot + 1)
              
              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Methods returning this class → return as function CodeUnits
    val methodReturnRefs = cpg.method
      .where(_.methodReturn.typeFullName(typePattern))
      .filter(m => m.typeDecl.fullName.headOption.exists(ownerFqcn => !partOfClass(classFullName, ownerFqcn)))
      .flatMap { m =>
        m.typeDecl.headOption.flatMap { td =>
          toFile(td).flatMap { file =>
            val methodName = resolveMethodName(chopColon(m.fullName))
            val lastDot = methodName.lastIndexOf('.')
            if (lastDot > 0) {
              val fullClassPath = methodName.substring(0, lastDot)
              val classLastDot = fullClassPath.lastIndexOf('.')
              val packageName = if (classLastDot > 0) fullClassPath.substring(0, classLastDot) else ""
              val className = if (classLastDot > 0) fullClassPath.substring(classLastDot + 1) else fullClassPath
              val memberName = methodName.substring(lastDot + 1)
              
              Try(CodeUnit.fn(file, packageName, s"$className.$memberName")).toOption
            } else {
              cuFunction(methodName, file)
            }
          }
        }
      }
      .l

    // Classes that inherit from this class → return as class CodeUnits
    val inheritingClasses = cpg.typeDecl
      .filter(_.inheritsFromTypeFullName.contains(classFullName))
      .filter(td => !partOfClass(classFullName, td.fullName))
      .flatMap { td =>
        toFile(td).flatMap { file =>
          val lastDot = td.fullName.lastIndexOf('.')
          val packageName = if (lastDot > 0) td.fullName.substring(0, lastDot) else ""
          val className = td.name
          
          Try(CodeUnit.cls(file, packageName, className)).toOption
        }
      }
      .l

    (fieldRefs ++ paramRefs ++ localRefs ++ methodReturnRefs ++ inheritingClasses).toList.distinct
  }

  /**
   * Recursively collects the fully-qualified names of all subclasses of the given class.
   */
  private[brokk] def allSubclasses(className: String): Set[String] = {
    val direct = cpg.typeDecl.l.filter { td =>
      td.inheritsFromTypeFullName.contains(className)
    }.map(_.fullName).toSet
    direct ++ direct.flatMap { sub => allSubclasses(sub) }
  }

  override def getUses(symbol: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*

    logger.debug(s"Getting uses for symbol: '$symbol'")

    // (1) Method matches?
    // Expand method lookup to include overrides in subclasses.
    val baseMethodMatches = methodsFromName(symbol)
    logger.debug(s"Found ${baseMethodMatches.size} base method matches for '$symbol'")

    val expandedMethodMatches =
      if (symbol.contains(".")) {
        val lastDot = symbol.lastIndexOf('.')
        val classPart = symbol.substring(0, lastDot)
        val methodPart = symbol.substring(lastDot + 1)
        logger.debug(s"Symbol contains dot: classPart='$classPart', methodPart='$methodPart'")

        val subclasses = allSubclasses(classPart)
        logger.debug(s"Found ${subclasses.size} subclasses of '$classPart'")

        // candidate fully-qualified method names: original and each subclass override
        val expandedSymbols = (Set(symbol) ++ subclasses.map(sub => s"$sub.$methodPart")).toList
        logger.debug(s"Expanded to ${expandedSymbols.size} symbol candidates: ${expandedSymbols.take(5).mkString(", ")}${if (expandedSymbols.size > 5) "..." else ""}")

        val expanded = expandedSymbols.flatMap(mn => methodsFromName(mn))
        logger.debug(s"After expanding, found ${expanded.size} method matches")
        expanded
      } else {
        logger.debug("Symbol doesn't contain a dot, using base matches only")
        baseMethodMatches
      }

    if expandedMethodMatches.nonEmpty then {
      logger.debug(s"Processing ${expandedMethodMatches.size} matched methods")
      // Collect all callers from all matched methods
      val calls = expandedMethodMatches.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = false)).distinct
      logger.debug(s"Call sites: ${calls.take(5).mkString(", ")}${if (calls.size > 5) "..." else ""}")

      val results = calls.flatMap { methodName => // Changed variable name to results
        // Find method nodes for the caller's name
        val methods = cpg.method.fullName(s"$methodName:.*").l
        if (methods.nonEmpty) {
          // Attempt to get the file for the method's declaring type
          methods.head.typeDecl.headOption.flatMap(toFile).flatMap { file =>
            // If file found, create the CodeUnit
            cuFunction(methodName, file)
          }.orElse {
            // If file not found, log and return None for this method
            logger.debug(s"No file found for method: $methodName")
            None
          }
        } else {
          // If no method node found, log and return None
          logger.debug(s"No method node found for: $methodName")
          None
        }
      }
      logger.debug(s"Calling methods: ${results.take(5).mkString(", ")}${if (results.size > 5) "..." else ""}") // Use results here
      return results.asJava // Use results here
    }

    // (2) Possibly a field: com.foo.Bar.field
    val lastDot = symbol.lastIndexOf('.')
    if lastDot > 0 then {
      logger.debug("Trying to interpret as field reference")
      val classPart = symbol.substring(0, lastDot)
      val fieldPart = symbol.substring(lastDot + 1)
      logger.debug(s"Parsed as potential field: class='$classPart', field='$fieldPart'")

      val clsDecls = cpg.typeDecl.fullNameExact(classPart).l
      if clsDecls.nonEmpty then {
        logger.debug(s"Found class declaration for: $classPart")
        val maybeFieldDecl = clsDecls.head.member.nameExact(fieldPart).l
        if maybeFieldDecl.nonEmpty then {
          logger.debug(s"Found field declaration: $fieldPart")
          val refs = referencesToField(classPart, fieldPart, excludeSelfRefs = false)
          logger.debug(s"Found ${refs.size} references to field '$fieldPart'")

          val result = refs.flatMap { methodName =>
            val methods = cpg.method.fullName(s"$methodName:.*").l
            if methods.nonEmpty then {
              methods.head.typeDecl.headOption.flatMap(toFile).flatMap { file =>
                cuFunction(methodName, file)
              }
            } else {
              logger.debug(s"No method node found for field reference: $methodName")
              None
            }
          }
          logger.debug(s"Returning ${result.size} field references")
          return result.asJava // Reinstate return keyword
        } else {
          logger.debug(s"No field named '$fieldPart' found in class '$classPart'")
        }
      } else {
        logger.debug(s"No class declaration found for: $classPart")
      }
    }

    // (3) Otherwise treat as a class
    logger.debug("Treating symbol as a class")
    val classDecls = cpg.typeDecl.fullNameExact(symbol).l
    if classDecls.isEmpty then {
      logger.warn(s"Symbol '$symbol' not found as a method, field, or class")
      throw IllegalArgumentException(s"Symbol '$symbol' not found as a method, field, or class")
    }

    logger.debug(s"Found ${classDecls.size} class declarations for '$symbol'")

    // Include the original class plus all subclasses
    val subclasses = allSubclasses(symbol)
    logger.debug(s"Found ${subclasses.size} subclasses of '$symbol'")

    val allClasses = (classDecls.map(_.fullName).toSet ++ subclasses).toList
    logger.debug(s"Processing ${allClasses.size} classes in total")

    val methodUses = allClasses.flatMap { cn =>
      val uses = cpg.typeDecl.fullNameExact(cn).l.flatMap { td =>
        td.method.l.flatMap(m => callersOfMethodNode(m, true))
      }
      logger.debug(s"Found ${uses.size} method uses for class: $cn")
      uses
    }
    logger.debug(s"Total method uses across all classes: ${methodUses.size}")

    val fieldRefUses = classDecls.flatMap { td =>
      val uses = td.member.l.flatMap(mem => referencesToField(td.fullName, mem.name, excludeSelfRefs = true))
      logger.debug(s"Found ${uses.size} field reference uses for class: ${td.fullName}")
      uses
    }
    logger.debug(s"Total field reference uses: ${fieldRefUses.size}")

    val typeUses = allClasses.flatMap { cn =>
      val uses = referencesToClassAsType(cn)
      logger.debug(s"Found ${uses.size} type uses for class: $cn")
      uses
    }
    logger.debug(s"Total type uses: ${typeUses.size}")

    // Convert methodUses and fieldRefUses to actual CodeUnits
    val methodUseUnits = methodUses.distinct.flatMap { methodName =>
      val methods = cpg.method.fullName(s"$methodName:.*").l
      if methods.nonEmpty then {
        methods.head.typeDecl.headOption.flatMap(toFile).flatMap { file =>
          cuFunction(methodName, file)
        }
      } else {
        logger.debug(s"No method node found for: $methodName")
        None
      }
    }
    logger.debug(s"Converted to ${methodUseUnits.size} method use code units")

    val fieldUseUnits = fieldRefUses.distinct.flatMap { methodName =>
      val methods = cpg.method.fullName(s"$methodName:.*").l
      if methods.nonEmpty then {
        methods.head.typeDecl.headOption.flatMap(toFile).flatMap { file =>
          cuFunction(methodName, file)
        }
      } else {
        logger.debug(s"No method node found for field reference: $methodName")
        None
      }
    }
    logger.debug(s"Converted to ${fieldUseUnits.size} field use code units")

    val results = (methodUseUnits ++ fieldUseUnits ++ typeUses).distinct
    logger.debug(s"Final results: ${results.size} distinct usage references for '$symbol'")
    results.asJava
  }

  /**
   * Builds either a forward or reverse call graph from a starting method up to a given depth.
   */
  private def buildCallGraph(
                              startingMethod: String,
                              isIncoming: Boolean,
                              maxDepth: Int
                            ): java.util.Map[String, java.util.List[CallSite]] = {
    import scala.jdk.CollectionConverters.*
    val result = new java.util.HashMap[String, java.util.List[CallSite]]()
    val startMethods = cpg.method.filter(m => chopColon(m.fullName) == startingMethod).l
    if (startMethods.isEmpty) return result

    val visited = mutable.Set[String]()
    val startMethodNames = startMethods.map(m => resolveMethodName(chopColon(m.fullName))).toSet
    visited ++= startMethodNames

    def shouldIncludeMethod(methodName: String): Boolean = {
      !methodName.startsWith("<operator>") &&
        !methodName.startsWith("java.") &&
        !methodName.startsWith("javax.") &&
        !methodName.startsWith("sun.") &&
        !methodName.startsWith("com.sun.")
    }

    def getSourceLine(call: io.shiftleft.codepropertygraph.generated.nodes.Call): String =
      call.code.trim.replaceFirst("^this\\.", "")

    def addCallSite(methodName: String, callSite: CallSite): Unit = {
      val existing = result.getOrDefault(methodName, new java.util.ArrayList[CallSite]())
      existing.add(callSite)
      result.put(methodName, existing)
    }

    def explore(methods: List[Method], currentDepth: Int): Unit = {
      if (currentDepth > maxDepth || methods.isEmpty) return
      val nextMethods = mutable.ListBuffer[Method]()

      methods.foreach { method =>
        val methodName = resolveMethodName(chopColon(method.fullName))
        val calls = if (isIncoming) method.callIn.l else method.call.l

        calls.foreach { call =>
          if (isIncoming) {
            // The caller is the next method
            val callerMethod = call.method
            val callerName = resolveMethodName(chopColon(callerMethod.fullName))

            if (!visited.contains(callerName) && shouldIncludeMethod(callerName)) {
              val callerFileOpt = callerMethod.typeDecl.headOption.flatMap(toFile)
              callerFileOpt.foreach { file =>
                cuFunction(callerName, file).foreach { cu =>
                  addCallSite(methodName, CallSite(cu, getSourceLine(call)))
                  visited += callerName
                  nextMethods += callerMethod
                }
              }
            }
          } else {
            // The callee is the next method
            val calleeFullName = chopColon(call.methodFullName)
            val calleeName = resolveMethodName(calleeFullName)

            if (!visited.contains(calleeName) && shouldIncludeMethod(calleeName)) {
              val calleePattern = s"^${Regex.quote(calleeFullName)}.*"
              val calleeMethods = cpg.method.fullName(calleePattern).l
              if (calleeMethods.nonEmpty) {
                calleeMethods.head.typeDecl.headOption.flatMap(toFile).foreach { file =>
                  cuFunction(calleeName, file).foreach { cu =>
                    addCallSite(methodName, CallSite(cu, getSourceLine(call)))
                    visited += calleeName
                    nextMethods ++= calleeMethods
                  }
                }
              }
            }
          }
        }
      }
      explore(nextMethods.toList, currentDepth + 1)
    }

    explore(startMethods, 1)
    result
  }

  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = true, maxDepth = depth)
  }

  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] = {
    val resolvedMethodName = resolveMethodName(methodName)
    buildCallGraph(resolvedMethodName, isIncoming = false, maxDepth = depth)
  }

  override def searchDefinitions(pattern: String): java.util.List[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    val ciPattern = "(?i)" + pattern // case-insensitive

    // Classes
    val matchingClasses = cpg.typeDecl
      .name(ciPattern)
      .fullName
      .filter(isClassInProject)
      .flatMap { className =>
        val classTypeDecl = cpg.typeDecl.fullNameExact(className).headOption
        classTypeDecl.flatMap(toFile).flatMap { file =>
          cuClass(className, file)
        }
      }
      .l

    // Methods
    val matchingMethods = cpg.method
      .nameNot("<.*>")
      .name(ciPattern)
      .filter { m =>
        m.typeDecl.headOption.exists(td => isClassInProject(td.fullName)) // Correct check
      }
      .flatMap { m =>
        m.typeDecl.headOption.flatMap(toFile).flatMap { file => // Use flatMap and Option
          cuFunction(resolveMethodName(chopColon(m.fullName)), file)
        }
      }
      .l

    // Fields
    val matchingFields = cpg.member
      .name(ciPattern)
      .filter { f =>
        // f.typeDecl returns a TypeDecl node directly. Check if its class is in the project.
        isClassInProject(f.typeDecl.fullName)
      }
      .flatMap { f =>
        val td = f.typeDecl // Get the TypeDecl node directly
        // Extract package and class part separately
        val className = td.name
        val packageName = if (td.fullName.contains('.')) {
          td.fullName.substring(0, td.fullName.lastIndexOf('.'))
        } else {
          ""
        }

        toFile(td).flatMap { file =>
          // Create the field with explicit parts: package name, and Class.member short name
          Try(CodeUnit.field(file, packageName, s"$className.${f.name}")).toOption
        }
      }
      .l // .l is now correctly called on the Traversal[CodeUnit] from flatMap

    (matchingClasses ++ matchingMethods ++ matchingFields).asJava
  }

  /**
   * Finds a single CodeUnit definition matching the exact symbol name.
   *
   * @param symbol The exact, case-sensitive FQ name of the class, method, or field.
   *               Symbols are checked in that order, so if you have a field and a method with the same name,
   *               the method will be returned.
   * @return An Optional containing the CodeUnit if exactly one match is found, otherwise empty.
   */
  override def getDefinition(symbol: String): java.util.Optional[CodeUnit] = {
    // lots of similarity to searchDefinitions, but that uses pattern-matching fullName and this
    // uses fullNameExact so trying to share code seems like more trouble than it's worth
    import scala.jdk.CollectionConverters.*

    // Try finding as a class
    val classMatch = cpg.typeDecl
      .fullNameExact(symbol)
      .filter(td => isClassInProject(td.fullName)) // Filter TypeDecl based on its fullName
      .flatMap { td =>
        toFile(td).flatMap { file =>
          cuClass(symbol, file)
        }
      }
      .headOption

    if (classMatch.isDefined) return java.util.Optional.of(classMatch.get)

    // Try finding as a method
    // First, find all raw method nodes matching the resolved symbol name within project classes
    val rawMethodMatches = cpg.method
      .filter(m => resolveMethodName(chopColon(m.fullName)) == symbol)
      .filter(m => m.typeDecl.fullName.headOption.exists(isClassInProject))
      .l

    // If any method matches the resolved name, pick the first one.
    // CodeUnit doesn't distinguish overloads, so ambiguity at this level is ignored.
    if (rawMethodMatches.nonEmpty) {
      val theMethod = rawMethodMatches.head
      val methodCodeUnitOpt = theMethod.typeDecl.headOption
        .flatMap(toFile) // Get Option[ProjectFile]
        .flatMap(file => cuFunction(symbol, file)) // Call cuFunction

      // Convert Option[CodeUnit] to java.util.Optional[CodeUnit]
      // Moved the closing brace after the match statement
      methodCodeUnitOpt match {
        case Some(cu) => return java.util.Optional.of(cu) // Add return here
        case None     => // Fall through if None
      }
    } // Moved closing brace here

    // Try finding as a field (symbol must be className.fieldName)
    val lastDot = symbol.lastIndexOf('.')
    if (lastDot > 0) {
      val className = symbol.substring(0, lastDot)
      val fieldName = symbol.substring(lastDot + 1)

      val fieldMatch = cpg.member
        .nameExact(fieldName)
        .where(_.typeDecl.fullNameExact(className))
        .filter(f => isClassInProject(f.typeDecl.fullName)) // f.typeDecl is a TypeDecl node
        .flatMap { f =>
          val td = f.typeDecl // Get the TypeDecl node directly
          toFile(td).flatMap { file => 
            // Extract package and class properly
            val packageName = if (td.fullName.contains('.')) {
              td.fullName.substring(0, td.fullName.lastIndexOf('.'))
            } else {
              ""
            }
            val shortName = td.name + "." + fieldName
            // Pass just the package name, and the Class.member short name
            Try(CodeUnit.field(file, packageName, shortName)).toOption
          }
        }
        .headOption // Expecting only one field with this name in the class

      if (fieldMatch.isDefined) return java.util.Optional.of(fieldMatch.get)
    }

    // Not found as class, unique method, or field
    java.util.Optional.empty()
  }


  /**
   * Weighted PageRank at the class level. If seedClassWeights is non-empty,
   * seeds are assigned according to those weights. Otherwise, all classes
   * are seeded equally.
   */
  override def getPagerank(
                            seedClassWeights: java.util.Map[String, java.lang.Double],
                            k: Int,
                            reversed: Boolean
                          ): java.util.List[(CodeUnit, java.lang.Double)] = {
    import scala.jdk.CollectionConverters.*
    val seedWeights = seedClassWeights.asScala.view.mapValues(_.doubleValue()).toMap
    // restrict to classes (FQCNs) that are in the graph
    var validSeeds = seedWeights.keys.toSeq.filter(classesForPagerank.contains)
    if (validSeeds.isEmpty) validSeeds = classesForPagerank.toSeq

    val danglingNodes = classesForPagerank.par.filter { c =>
      adjacency.get(c).forall(_.isEmpty)
    }
    val damping = 0.85
    val epsilon = 1e-4
    val maxIter = 50

    val scores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0) *)
    val nextScores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0) *)
    val totalWeight = seedWeights.values.sum
    validSeeds.foreach { c =>
      scores(c) = seedWeights.getOrElse(c, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
    }

    var iteration = 0
    var diffSum = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {
      // zero nextScores
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      val localDiffs = classesForPagerank.par.map { node =>
        val (inMap, outMap) = if (reversed) (adjacency, reverseAdjacency) else (reverseAdjacency, adjacency)
        val inboundSum = inMap.get(node).map { inboundMap =>
          inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
            val outLinks = outMap(u)
            val outWeight = outLinks.values.sum.max(1)
            acc + (scores(u) * weight / outWeight)
          }
        }.getOrElse(0.0)

        var newScore = damping * inboundSum
        if (validSeeds.contains(node)) {
          newScore += (1.0 - damping) *
            (seedWeights.getOrElse(node, 0.0) / (if (totalWeight == 0) 1 else totalWeight))
        }
        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // handle dangling nodes
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        validSeeds.par.foreach { seed =>
          val weight = seedWeights.getOrElse(seed, 0.0) / (if (totalWeight == 0) 1 else totalWeight)
          nextScores(seed) += damping * danglingScore * weight
        }
        // zero out dangling
        danglingNodes.par.foreach { dn => nextScores(dn) = 0.0 }
      }

      diffSum = localDiffs
      classesForPagerank.foreach { node => scores(node) = nextScores(node) }
      iteration += 1
    }

    val sortedAll = scores.toList.sortBy { case (_, s) => -s }
    val filteredSortedAll = sortedAll.filterNot { case (cls, _) =>
      // cls here is the FQCN string from the sorted scores
      seedWeights.keys.exists(seed => partOfClass(seed, cls))
    }

    // Coalesce inner classes: if both parent and inner class are present, keep only the parent.
    // Operates on a buffer of (CodeUnit, Double) tuples.
    def coalesceInnerClasses(initial: List[(CodeUnit, Double)], limit: Int): mutable.Buffer[(CodeUnit, Double)] = {
      var results = initial.take(limit).toBuffer
      var offset = limit
      var changed = true
      val initialFqns = initial.map(_._1.fqName()).toSet // For quick lookups during addition

      while (changed) {
        changed = false
        val fqnsToRemove = mutable.Set[String]() // Store FQNs of inner classes to remove
        for (i <- results.indices) {
          val pFqn = results(i)._1.fqName()
          for (j <- results.indices if j != i) {
            val cFqn = results(j)._1.fqName()
            if (partOfClass(pFqn, cFqn)) fqnsToRemove += cFqn
          }
        }
        if (fqnsToRemove.nonEmpty) {
          changed = true
          results = results.filterNot { case (cu, _) => fqnsToRemove.contains(cu.fqName()) }
        }
        // Add new candidates until the limit is reached
        while (results.size < limit && offset < initial.size) {
          val candidate@(cu, _) = initial(offset)
          offset += 1
          // Add if not already present (by FQN) and not marked for removal
          if (!results.exists(_._1.fqName() == cu.fqName()) && !fqnsToRemove.contains(cu.fqName())) {
            // Also ensure its potential parent isn't already in initialFqns if it's an inner class
            val isInner = cu.fqName().contains('$')
            val parentFqnOpt = if (isInner) Some(cu.fqName().substring(0, cu.fqName().lastIndexOf('$'))) else None
            if (!isInner || parentFqnOpt.forall(p => !initialFqns.contains(p))) {
              results += candidate
              changed = true
            }
          }
        }
      }
      results // Buffer[(CodeUnit, Double)]
    }

    // Map sorted FQCNs to CodeUnit tuples, filtering out those without files
    val sortedCodeUnits = filteredSortedAll.flatMap { case (fqcn, score) =>
      getFileFor(fqcn).toScala.flatMap { file => // Use flatMap here
        cuClass(fqcn, file).map((_, score)) // Use cuClass
      }
    }

    // Coalesce and convert score to Java Double, filtering out zero scores
    coalesceInnerClasses(sortedCodeUnits, k)
      .map { case (cu, d) => (cu, java.lang.Double.valueOf(d)) }
      .filter(_._2 > 0.0) // Filter out results with zero score
      .asJava
  }

  /**
   * Gets all classes in a given file.
   */
  override def getClassesInFile(file: ProjectFile): java.util.Set[CodeUnit] = {
    import scala.jdk.CollectionConverters.*
    cpg.typeDecl.l
      .filter(td => toFile(td).contains(file))
      .flatMap { td => // Use flatMap here
        cuClass(td.fullName, file) // Use cuClass
      }
      .toSet
      .asJava
  }

  /**
   * Write the underlying CPG to the specified path.
   */
  def writeCpg(path: Path): Unit = {
    Serialization.writeGraph(cpg.graph, path)
  }

  override def close(): Unit = cpg.close()

  /**
   * Gets a set of relevant symbol names (classes, methods, fields) defined within the given source CodeUnits.
   */
  override def getSymbols(sources: java.util.Set[CodeUnit]): java.util.Set[String] = {
    import scala.jdk.CollectionConverters.*

    val sourceUnits = sources.asScala
    val sourceFiles = sourceUnits.map(_.source).toSet
    val symbols = mutable.Set[String]()

    // Add short names of the source units themselves
    sourceUnits.foreach(cu => symbols += cu.shortName())

    // Find TypeDecls within the specified source files
    cpg.typeDecl
      .filter(td => toFile(td).exists(sourceFiles.contains))
      .foreach { td =>
        symbols += td.fullName.split('.').last // Add class short name

        // Add method short names (resolved)
        td.method
          .nameNot("<init>", "<clinit>", "<lambda>.*") // Exclude constructors, static initializers, lambdas
          .foreach { m =>
            val resolvedName = resolveMethodName(chopColon(m.fullName))
            symbols += resolvedName.split('.').last // Add method short name
          }

        // Add field short names
        td.member.foreach(f => symbols += f.name) // Add just the field name
      }

    symbols.asJava
  }
}
