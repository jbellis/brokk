package io.github.jbellis.brokk

import flatgraph.storage.Serialization
import io.joern.dataflowengineoss.layers.dataflows.OssDataFlow
import io.joern.javasrc2cpg.{Config => JavaConfig, JavaSrc2Cpg}
import io.joern.pysrc2cpg.Py2Cpg
import io.joern.x2cpg.ValidationMode
import io.joern.joerncli.CpgBasedTool
import io.joern.x2cpg.X2Cpg
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.codepropertygraph.generated.nodes.{Method, TypeDecl}
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve, *}
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.Closeable
import java.nio.file.Path
import java.util
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.jdk.javaapi.CollectionConverters
import scala.util.Using
import scala.util.matching.Regex

sealed trait CodeUnit extends Comparable[CodeUnit] {
  def reference: String

  def isClass: Boolean = this match {
    case _: CodeUnit.ClassType => true
    case _ => false
  }

  def isFunction: Boolean = this match {
    case _: CodeUnit.FunctionType => true
    case _ => false
  }

  override def toString: String = this match {
    case CodeUnit.ClassType(ref) => s"CLASS[$ref]"
    case CodeUnit.FunctionType(ref) => s"FUNCTION[$ref]"
  }

  override def hashCode(): Int = reference.hashCode()
  override def equals(obj: Any): Boolean = obj.isInstanceOf[CodeUnit] && this.reference == obj.asInstanceOf[CodeUnit].reference
  override def compareTo(other: CodeUnit): Int = this.reference.compareTo(other.reference)
}

object CodeUnit {
  case class ClassType(reference: String) extends CodeUnit
  case class FunctionType(reference: String) extends CodeUnit
  case class FieldType(reference: String) extends CodeUnit

  def cls(reference: String): CodeUnit = ClassType(reference)
  def fn(reference: String): CodeUnit = FunctionType(reference)
  def field(reference: String): CodeUnit = FieldType(reference)
}

sealed trait Language
object Language {
  case object Java extends Language
  case object Python extends Language
}

class Analyzer(sourcePath: java.nio.file.Path, language: Language) extends IAnalyzer, Closeable {
  // Convert to absolute filename immediately and verify it's a directory
  private val absolutePath = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }
  private implicit val ec: ExecutionContext = ExecutionContext.global

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]] = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String] = Set.empty

  // A single queue for all writeGraph calls
  // i.e., each new writeGraph job enqueues behind this Future.

  private[brokk] var cpg: Cpg = createNewCpg()

  private implicit val callResolver: ICallResolver = NoResolve
  
  // Initialize pagerank data structures 
  initializePageRank()

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path, language: Language) = {
    this(sourcePath, language)
    this.cpg = CpgBasedTool.loadFromFile(preloadedPath.toString)
  }

  private def initializePageRank(): Unit = {
    if (cpg.metaData.headOption.isEmpty) {
      throw new IllegalStateException("CPG root not found for " + absolutePath)
    }

    // Initialize adjacency maps
    adjacency = buildWeightedAdjacency()

    // Build reverse adjacency from the forward adjacency
    val reverseMap = TrieMap[String, TrieMap[String, Int]]()
    adjacency.par.foreach { case (src, tgtMap) =>
      tgtMap.foreach { case (dst, weight) =>
        val targetMap = reverseMap.getOrElseUpdate(dst, TrieMap.empty)
        targetMap.update(src, targetMap.getOrElse(src, 0) + weight)
      }
    }
    reverseAdjacency = reverseMap.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap

    classesForPagerank = (adjacency.keys ++ adjacency.values.flatMap(_.keys)).toSet
  }

  def this(sourcePath: java.nio.file.Path) = {
    this(sourcePath, Language.Java)
  }

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path) = {
    this(sourcePath, preloadedPath, Language.Java) 
  }

  private def createNewCpg(): Cpg = {
    val newCpg = language match {
      case Language.Java =>
        val config = JavaConfig()
          .withInputPath(absolutePath.toString)
          .withEnableTypeRecovery(true)
        // unnecessary?
        //  .withDisableFileContent(false)
        // https://github.com/joernio/joern/issues/5297
        //  .withKeepTypeArguments(true)
        JavaSrc2Cpg().createCpg(config).get
      case Language.Python =>
        val cpg = Cpg.empty
        
        // Create list of all .py files in directory recursively, as relative paths
        val pythonFiles = {
          import scala.collection.JavaConverters._
          val files = java.nio.file.Files.walk(absolutePath).iterator().asScala.toList
          files
            .filter(f => f.toString.endsWith(".py") && f.toFile.isFile)
            .map(absolutePath.relativize(_))
        }

        // Create input providers for each Python file
        val inputProviders = pythonFiles.map { relPath =>
          val absPath = absolutePath.resolve(relPath)
          () => Py2Cpg.InputPair(
            Source.fromFile(absPath.toFile).mkString,
            relPath.toString
          )
        }

        new Py2Cpg(
          inputProviders,
          cpg,
          absolutePath.toString,
          "requirements.txt",
          ValidationMode.Enabled,
          false
        ).buildCpg()
        cpg
    }
    X2Cpg.applyDefaultOverlays(newCpg)

    // Add dataflow overlay
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)
    newCpg
  }

  /**
   * Return the method signature as a String, including return type, name and parameters
   */
  private[brokk] def methodSignature(m: Method): String = {
    val modifiers = m.modifier.map { modNode =>
      val lower = modNode.modifierType.toLowerCase
      knownModifiers.getOrElse(lower, "")
    }.filter(_.nonEmpty)

    val modString  = if (modifiers.nonEmpty) modifiers.mkString(" ") + " " else ""
    val returnType = sanitizeType(m.methodReturn.typeFullName)
    val paramList = m.parameter
      .sortBy(_.order)
      .filterNot(_.name == "this")
      .l
      .map { p =>
        s"${sanitizeType(p.typeFullName)} ${p.name}"
      }.mkString(", ")

    s"$modString$returnType ${m.name}($paramList)"
  }

  def methodsFromName(resolvedMethodName: String): List[Method] = {
    // Joern's method names look like this
    //   org.apache.cassandra.db.DeletionPurger.shouldPurge:boolean(org.apache.cassandra.db.DeletionTime)
    // constructor of a nested class:
    //   org.apache.cassandra.db.Directories$SSTableLister.<init>:void(org.apache.cassandra.io.util.File[])
    cpg.method.fullName(resolvedMethodName + ":.*").l
  }

  def resolveMethodName(methodName: String): String = {
    val javaLambdaPattern = """(.*)\.lambda\$(.*)\$.*""".r
    methodName match {
      case javaLambdaPattern(parent, method) => s"$parent.$method"
      case _ =>
        val parts = methodName.split("\\.")
        val index = parts.indexWhere(_.contains("$"))
        if (index > 0) parts.take(index).mkString(".") else methodName
    }
  }

  def getMethodSource(methodName: String): Option[String] = {
    val resolvedMethodName = resolveMethodName(methodName)
    val methods = methodsFromName(resolvedMethodName)

    val sources = methods.flatMap { method =>
      for {
        file <- Option(toFile(method.filename)) // TODO log empty filenames
        startLine <- method.lineNumber
        endLine <- method.lineNumberEnd
      } yield {
        scala.util.Using(Source.fromFile(file.absPath().toFile)) { source =>
          source
            .getLines()
            .slice(startLine - 1, endLine)
            .mkString("\n")
        }.toOption
      }
    }.flatten

    if (sources.isEmpty) None
    else Some(sources.mkString("\n\n"))
  }

  /**
   * A helper to remove package names from a type string.
   * E.g. "java.lang.String[]" => "String[]", "com.foo.Bar" => "Bar".
   */
  private[brokk] def sanitizeType(t: String): String = {
    def processType(input: String): String = {
      val isArray = input.endsWith("[]")
      val base = if (isArray) input.dropRight(2) else input
      val shortName = base.split("\\.").lastOption.getOrElse(base)
      if (isArray) s"$shortName[]" else shortName
    }

    // Handle generic type parameters
    if (t.contains("<")) {
      val mainType = t.substring(0, t.indexOf("<"))
      val genericPart = t.substring(t.indexOf("<") + 1, t.lastIndexOf(">"))
      
      // Process the main type and each generic parameter
      val processedMain = processType(mainType)
      val processedParams = genericPart.split(",").map { param =>
        val trimmed = param.trim
        // Handle nested generic types recursively
        if (trimmed.contains("<")) {
          sanitizeType(trimmed)
        } else {
          processType(trimmed)
        }
      }.mkString(", ")
      
      s"$processedMain<$processedParams>"
    } else {
      processType(t)
    }
  }

  /**
   * Recursively builds a structural "skeleton" for a given TypeDecl,
   * including fields, methods, and nested type declarations.
   */
  // Convert or omit certain CPG modifiers to proper Java keywords
  // (this allows us to ignore Joern adding extra keywords like "virtual")
  private val knownModifiers = Map(
    "public" -> "public",
    "private" -> "private",
    "protected" -> "protected",
    "static" -> "static",
    "final" -> "final",
    "abstract" -> "abstract",
    "native" -> "native",
    "synchronized" -> "synchronized"
  )

  private def outlineTypeDecl(td: TypeDecl, indent: Int = 0): String = {
    val sb = new StringBuilder

    val className = sanitizeType(td.name)

    // class signature + fields
    sb.append(headerString(td, indent))

    // methods
    td.method.foreach { m =>
      val name = m.name
      if (!name.startsWith("<")) {
        sb.append(indentStr(indent + 1))
          .append(methodSignature(m))
          .append(" {...}\n")
      }
    }

    sb.append(indentStr(indent)).append("}")
    sb.toString
  }

  def indentStr(level: Int) = "  " * level

  /**
   * Builds a structural skeleton for a given class by name (simple or FQCN),
   * or None if the class is not found (bug in Joern).
   */
  def getSkeleton(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) {
      return None // TODO log?
    }
    val td = decls.head
    Some(outlineTypeDecl(td))
  }

  /**
   * Builds a weighted adjacency map at the class level: className -> Map[targetClassName -> weight].
   */
  private[brokk] def buildWeightedAdjacency()(implicit callResolver: ICallResolver): Map[String, Map[String, Int]] = {
    val adjacency = TrieMap[String, TrieMap[String, Int]]()

    // Common Java primitives
    val primitiveTypes = Set("byte", "short", "int", "long", "float", "double", "boolean", "char", "void")

    def isRelevant(t: String): Boolean =
      !primitiveTypes.contains(t) && !t.startsWith("java.")

    val typeDecls = cpg.typeDecl.l
    // Build adjacency in parallel
    typeDecls.par.foreach { td =>
      val sourceClass = td.fullName

      // Collect calls
      td.method.call.callee.typeDecl.fullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { tc =>
          increment(adjacency, sourceClass, tc)
        }

      // Collect field references
      td.member.typeFullName
        .map(_.replaceAll("""\[\]""", "")) // array type -> base type
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { fieldClass =>
          increment(adjacency, sourceClass, fieldClass)
        }

      // 3) Collect "extends"/"implements" edges; these count 5x
      td.inheritsFromTypeFullName
        .filter(isRelevant)
        .filter(_ != sourceClass)
        .foreach { parentClass =>
          increment(adjacency, sourceClass, parentClass, 5)
        }
    }

    // Convert the inner TrieMaps to ordinary Maps
    adjacency.map { case (src, tgtMap) => src -> tgtMap.toMap }.toMap
  }

  def pathOf(fullClassName: String): RepoFile = {
    val clsNode = cpg.typeDecl.fullNameExact(fullClassName).head
    toFile(clsNode)
  }

  private def increment(map: TrieMap[String, TrieMap[String, Int]], source: String, target: String, count: Int = 1): Unit = {
    val targetMap = map.getOrElseUpdate(source, TrieMap.empty)
    targetMap.update(target, targetMap.getOrElse(target, 0) + count)
  }

  /**
   * Weighted PageRank at the class level, using multiple seed classes.
   * If validSeeds are present, seed scores and random jumps are weighted
   * by lines of code (LOC). Otherwise, use uniform seeds.
   */
  def getPagerank(seedClasses: util.Collection[String], k: Int): java.util.List[(String, Double)] = {
    val seedSeq = CollectionConverters.asScala(seedClasses).toSeq

    // restrict to classes that are in the graph
    var validSeeds = seedSeq.filter(classesForPagerank.contains)
    // if we ended up with no seeds, fall back to standard pagerank
    if (validSeeds.isEmpty) {
      validSeeds = classesForPagerank.l
    }

    // Initialize locMap based on skeleton lengths
    // (Full source length would be fine but we have a convenient way to get skeleton length)
    val locMap = if (validSeeds.nonEmpty) {
      validSeeds.flatMap { cls =>
        getSkeleton(cls).map(skeleton => cls -> skeleton.split("\n").length)
      }.toMap
    } else {
      Map.empty[String, Int]
    }

    // Identify dangling nodes (nodes without outgoing edges)
    val danglingNodes = classesForPagerank.par.filter { c =>
      adjacency.get(c).forall(_.isEmpty)
    }

    val damping = 0.85
    val epsilon = 1e-4
    val maxIter = 50

    val scores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)
    val nextScores = TrieMap[String, Double](classesForPagerank.toSeq.map(_ -> 0.0)*)

    // -- Compute total LOC across valid seeds; if none have LOC, fallback to uniform seeds
    val totalSeedLoc = validSeeds.map(c => locMap.getOrElse(c, 0)).sum
    val useLocWeights = validSeeds.nonEmpty && totalSeedLoc > 0

    // If using LOC-based weighting, the initial score for seed c is loc(c)/totalSeedLoc.
    // Otherwise, uniform 1.0 / validSeeds.size
    val uniformScore = if (validSeeds.nonEmpty) 1.0 / validSeeds.size else 0.0
    validSeeds.foreach { c =>
      scores(c) =
        if (useLocWeights) locMap.getOrElse(c, 0).toDouble / totalSeedLoc
        else               uniformScore
    }

    var iteration = 0
    var diffSum   = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {

      // Zero nextScores in parallel
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      // Handle inbound edges using reverseAdjacency
      val localDiffs = classesForPagerank.par.map { node =>
        val inboundSum = reverseAdjacency
          .get(node)
          .map { inboundMap =>
            inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
              val outLinks = adjacency(u)
              val outWeight = outLinks.values.sum.max(1)
              acc + (scores(u) * weight / outWeight)
            }
          }.getOrElse(0.0)

        // Damping
        var newScore = damping * inboundSum

        // Random jump to seeds, weighted by LOC if desired
        if (validSeeds.contains(node)) {
          if (useLocWeights) {
            val locRatio = locMap.getOrElse(node, 0).toDouble / totalSeedLoc
            newScore += (1.0 - damping) * locRatio
          } else {
            newScore += (1.0 - damping) * uniformScore
          }
        }

        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // Handle dangling nodes: push their entire score to seeds
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        if (useLocWeights) {
          validSeeds.par.foreach { seed =>
            val locRatio = locMap.getOrElse(seed, 0).toDouble / totalSeedLoc
            nextScores(seed) += damping * danglingScore * locRatio
          }
        } else {
          val share = danglingScore / validSeeds.size
          validSeeds.par.foreach { seed =>
            nextScores(seed) += damping * share
          }
        }
        // Zero out dangling
        danglingNodes.par.foreach { dn =>
          nextScores(dn) = 0.0
        }
      }

      // Update main scores from nextScores
      diffSum = localDiffs
      classesForPagerank.foreach { node =>
        scores(node) = nextScores(node)
      }

      iteration += 1
    }

    // Sort results, ignoring seed classes in final listing
    val sortedAll = scores.toList.sortBy { case (_, score) => -score }
    val filteredSortedAll = sortedAll.filterNot { case (cls, _) => seedSeq.exists(seed => partOfClass(seed, cls)) }

    // Coalesce inner classes while maintaining k results
    def coalesceInnerClasses(initial: List[(String, Double)], k: Int): mutable.Buffer[(String, Double)] = {
      var results = initial.take(k).toBuffer
      var offset = k
      var changed = true

      while (changed) {
        changed = false
        val toRemove = mutable.Set[String]()

        for (i <- results.indices) {
          val (pClass, _) = results(i)
          for (j <- results.indices if j != i) {
            val (cClass, _) = results(j)
            if (partOfClass(pClass, cClass)) {
              toRemove += cClass
            }
          }
        }

        if (toRemove.nonEmpty) {
          changed = true
          results = results.filterNot { case (cls, _) => toRemove.contains(cls) }
        }

        while (results.size < k && offset < initial.size) {
          val candidate@(cls, _) = initial(offset)
          offset += 1
          if (!results.exists(_._1 == cls) && !toRemove.contains(cls)) {
            results += candidate
            changed = true
          }
        }
      }
      results
    }

    CollectionConverters.asJava(coalesceInnerClasses(filteredSortedAll, k))
  }

  /**
   * Returns a set of all classes in the given .java filename.
   */
  def getClassesInFile(file: RepoFile): java.util.Set[CodeUnit] = {
    val matches = cpg.typeDecl.l.filter { td =>
      file == toFile(td)
    }
    CollectionConverters.asJava(matches.map(td => CodeUnit.cls(td.fullName)).toSet)
  }

  private def toFile(td: TypeDecl): RepoFile = {
    if (td.filename.isEmpty || td.filename == "<empty>" || td.filename == "<unknown>") {
      null
    } else {
      toFile(td.filename)
    }
  }

  private[brokk] def toFile(relName: String): RepoFile = {
    RepoFile(absolutePath, relName)
  }

  def isClassInProject(className: String): Boolean = {
    val td = cpg.typeDecl.fullNameExact(className).l
    td.nonEmpty && !(td.member.isEmpty && td.method.isEmpty && td.derivedTypeDecl.isEmpty)
  }

  def getAllClasses: java.util.List[CodeUnit] = {
    val results = cpg.typeDecl
      .filterNot(toFile(_) == null)
      .fullName
      .l
      .map(CodeUnit.cls)
    CollectionConverters.asJava(results)
  }

  /**
   * Returns just the class signature and field declarations, without method details.
   */
  def getSkeletonHeader(className: String): Option[String] = {
    val decls = cpg.typeDecl.fullNameExact(className).l
    if (decls.isEmpty) {
      return None
    }
    val td = decls.head
    Some(headerString(td, 0)  + "  [... methods not shown ...]\n}")
  }

  // does not include closing brace
  private def headerString(td: TypeDecl, indent: Int): String = {
    val sb = new StringBuilder
    val className = sanitizeType(td.name)

    // I think it's okay to leave visibility out at the class level
    sb.append(indentStr(indent))
      .append("class ")
      .append(className)
      .append(" {\n")

    // Add fields
    td.member.foreach { m =>
      val modifiers = m.modifier.map(_.modifierType.toLowerCase).filter(_.nonEmpty).mkString(" ")
      val modString = if (modifiers.nonEmpty) modifiers + " " else ""
      val typeName = sanitizeType(m.typeFullName)
      sb.append(indentStr(indent + 1))
        .append(s"$modString$typeName ${m.name};\n")
    }

    sb.toString
  }

  def getMembersInClass(className: String): java.util.List[CodeUnit] = {
    val matches = cpg.typeDecl.fullNameExact(className)
    if (matches.isEmpty) {
      throw new IllegalArgumentException(s"Class '$className' not found")
    }
    val typeDecl = matches.head

    // Get all method declarations as FunctionType
    val methods = typeDecl.method
      .filterNot(_.name.startsWith("<")) // skip constructors/initializers
      .fullName.l
      .map(chopColon)
      .map(CodeUnit.fn)

    // Get all field declarations as FieldType
    val fields = typeDecl.member.name.l.map(name => CodeUnit.field(className + "." + name))

    // Get all nested types as ClassType
    // typeDecl.typeDecl doesn't exist and typeDecl.derivedTypeDecl is empty, so search by name instead
    val nestedPrefix = className + "\\$.*"
    val nestedTypes = cpg.typeDecl.fullName(nestedPrefix).fullName.l.map(CodeUnit.cls)

    // Combine all members and convert to Java list
    CollectionConverters.asJava(methods ++ fields ++ nestedTypes)
  }

  /**
   * For a given fully qualified field name like "com.foo.Bar.fieldName",
   * find all distinct methods that reference that field.
   */
  private[brokk] def getReferrersOfField(fullyQualifiedFieldName: String): List[String] = {
    val lastDot = fullyQualifiedFieldName.lastIndexOf('.')
    if (lastDot < 0) {
      throw new IllegalArgumentException(
        s"Expected fully qualified field name, found '$fullyQualifiedFieldName'"
      )
    }
    val classFullName = fullyQualifiedFieldName.substring(0, lastDot)
    val rawFieldName  = fullyQualifiedFieldName.substring(lastDot + 1)

    if (cpg.typeDecl.fullNameExact(classFullName).isEmpty) {
      throw new IllegalArgumentException(
        s"'$classFullName' not found in code graph"
      )
    }
    // Ensure the field is really declared there
    val fieldDecls = cpg.typeDecl.fullNameExact(classFullName).member.nameExact(rawFieldName).l
    if (fieldDecls.isEmpty) {
      throw new IllegalArgumentException(
        s"Field '$rawFieldName' not found on '$classFullName'"
      )
    }

    // Field usage is typically <operator>.fieldAccess, with argument(1) = class, argument(2) = field name
    val calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      .where(_.argument(1).typ.fullNameExact(classFullName))
      .where(_.argument(2).codeExact(rawFieldName))
      .method
      .fullName
      .distinct
      .l

    calls.map(chopColon)
  }


  /**
   * For a given methodName, find all distinct methods that call it.
   */
  private[brokk] def getCallersOfMethod(methodName: String): List[String] = {
    val calls = cpg.call
      .methodFullName(s"""^${Regex.quote(methodName)}.*""")
      .l

    calls
      .map(_.method.fullName)
      .map(chopColon)
      .distinct
  }

  import scala.util.matching.Regex

  /**
   * For a given method node `m`, returns the calling method .fullName (with the suffix after `:` chopped off).
   * If `excludeSelfRefs` is true, we skip callers whose TypeDecl matches `m.typeDecl`.
   */
  private def callersOfMethodNode(m: Method, excludeSelfRefs: Boolean): List[String] = {
    var calls = m.callIn
    if (excludeSelfRefs) {
      val selfSource = m.typeDecl.fullName.head
      calls = calls.filterNot(call => {
        val callerSource = call.method.typeDecl.fullName.head
        partOfClass(selfSource, callerSource)
      })
    }
    calls
      .method
      .fullName
      .map((name: String) => resolveMethodName(chopColon(name)))
      .distinct
      .l
  }

  private def partOfClass(parentFqcn: String, childFqcn: String) = {
    childFqcn == parentFqcn || childFqcn.startsWith(parentFqcn + ".") || childFqcn.startsWith(parentFqcn + "$")
  }

  /**
   * Return all methods that reference a given field "classFullName.fieldName".
   * If `excludeSelfRefs` is true, references from the same class are omitted.
   */
  private def referencesToField(selfSource: String,
                                 fieldName: String,
                                 excludeSelfRefs: Boolean): List[String] = {
    var calls = cpg.call
      .nameExact("<operator>.fieldAccess")
      // The first argument is the "base" or receiver,
      // typed with `classFullName` for an instance, or referencing the class for a static field.
      .where(_.argument(1).typ.fullNameExact(selfSource))
      // The second argument is the field identifier code
      .where(_.argument(2).codeExact(fieldName))

    if (excludeSelfRefs) {
      calls = calls.filterNot(call => {
        partOfClass(selfSource, call.method.typeDecl.fullName.head)
      })
    }
    calls
      .method
      .fullName
      // Chop off everything after the colon, e.g. "Foo.bar:void()" → "Foo.bar"
      .map(x => resolveMethodName(chopColon(x)))
      .distinct
      .l
  }

  private def chopColon(full: String) = full.split(":").head

  /**
   * Find all references to a given class used as a type (fields, parameters, locals).
   * If excludeSelfRefs is true, omit references from within the same class.
   */
  private def referencesToClassAsType(classFullName: String): List[String] = {
    val typePattern = "^" + Regex.quote(classFullName) + "(\\$.*)?(\\[\\])?"

    // Fields typed with this class → parent is a TypeDecl.
    // 1) Grab all TypeDecl parents for these fields
    // 2) Filter out the same class if excludeSelfRefs is true
    val fieldRefs = cpg.member
      .typeFullName(typePattern)
      .astParent
      .isTypeDecl
      .filter(td => !partOfClass(classFullName, td.fullName))
      .fullName
      .l

    // Parameters typed with this class → parent is a Method.
    val paramRefs = cpg.parameter
      .typeFullName(typePattern)
      .method
      .filter(m => !partOfClass(classFullName, m.typeDecl.fullName.l.head))
      .fullName
      .l

    // Locals typed with this class → parent is a Method.
    val localRefs = cpg.local
      .typeFullName(typePattern)
      .method
      .filter(m => !partOfClass(classFullName, m.typeDecl.fullName.l.head))
      .fullName
      .l

    (fieldRefs ++ paramRefs ++ localRefs).map(chopColon).map(resolveMethodName).distinct
  }

  /**
   * IntelliJ-style "find usages" for a given symbol. The symbol may be:
   *  - A method (fully qualified, e.g. "com.foo.Bar.baz:int(java.lang.String)"),
   *  - A field (fully qualified, e.g. "com.foo.Bar.fieldName"),
   *  - Or a class (fully qualified, e.g. "com.foo.Bar").
   *
   * Returns a SymbolUsages containing:
   *  - methodUses: references to methods if symbol is a method, or references to static methods if symbol is a class.
   *  - fieldUses: references to the field if symbol is a field, or references to static fields if symbol is a class.
   *  - typeUses: references to the class as a type (field declarations, parameters, locals), if symbol is a class.
   *
   * If symbol is not found at all, throws IllegalArgumentException.
   */
  def getUses(symbol: String): java.util.List[CodeUnit] = {
    //
    // 1) If symbol is recognized as a method name
    //
    val methodMatches = methodsFromName(symbol)
    if (methodMatches.nonEmpty) {
      // Do NOT exclude self references in the method branch
      val calls = methodMatches.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = false)).distinct
      val methodUnits = calls.map(CodeUnit.fn)
      return CollectionConverters.asJava(methodUnits)
    }

    //
    // 2) If symbol is recognized as a field, e.g. com.foo.Bar.fieldName
    //
    val lastDot = symbol.lastIndexOf('.')
    if (lastDot > 0) {
      val classPart = symbol.substring(0, lastDot)
      val fieldPart = symbol.substring(lastDot + 1)
      val clsDecls = cpg.typeDecl.fullNameExact(classPart).l

      if (clsDecls.nonEmpty) {
        // Check that the class actually has a member named `fieldPart`
        val maybeFieldDecl = clsDecls.head.member.nameExact(fieldPart).l
        if (maybeFieldDecl.nonEmpty) {
          // Do NOT exclude self references in the field branch
          val refs = referencesToField(classPart, fieldPart, excludeSelfRefs = false)
          val refUnits = refs.map(CodeUnit.fn)
          return CollectionConverters.asJava(refUnits)
        }
      }
    }

    //
    // 3) Otherwise, treat `symbol` as a class name.
    //    Gather references to all methods in that class, references to its fields,
    //    plus references to the class as a type. DO exclude self references.
    //
    val classDecls = cpg.typeDecl.fullNameExact(symbol).l
    if (classDecls.isEmpty) {
      throw new IllegalArgumentException(
        s"Symbol '$symbol' not found as a method, field, or class"
      )
    }

    val methodUses = classDecls.flatMap(td =>
      td.method.l.flatMap(m => callersOfMethodNode(m, excludeSelfRefs = true))
    ) ++ classDecls.flatMap(td =>
      td.member.l.flatMap(mem => referencesToField(td.fullName, mem.name, excludeSelfRefs = true))
    )
    val typeUses = referencesToClassAsType(symbol)

    val combined = methodUses.distinct ++ typeUses
    CollectionConverters.asJava(combined.map(CodeUnit.fn))
  }

  def writeCpg(path: java.nio.file.Path): Unit = {
    val langSuffix = language match {
      case Language.Java => "java"
      case Language.Python => "python"
    }
    val finalPath = path.resolveSibling(s"joern-${langSuffix}.cpg")
    Serialization.writeGraph(cpg.graph, finalPath)
  }
  
  override def close(): Unit = {
    cpg.close()
  }
}
