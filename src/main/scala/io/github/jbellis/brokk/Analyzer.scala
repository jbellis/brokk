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
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext

import java.io.Closeable
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.IterableIsParallelizable
import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.jdk.javaapi.CollectionConverters
import scala.util.matching.Regex


class Analyzer private (sourcePath: java.nio.file.Path, language: Language, cpgInit: Cpg) extends IAnalyzer with Closeable {
  // Convert to absolute filename immediately and verify it's a directory
  private val absolutePath = {
    val path = sourcePath.toAbsolutePath.toRealPath()
    require(path.toFile.isDirectory, s"Source path must be a directory: $path")
    path
  }
  // implicits at the top, you will regret it otherwise
  private implicit val ec: ExecutionContext = ExecutionContext.global
  private implicit val callResolver: ICallResolver = NoResolve

  // Adjacency maps for pagerank
  private var adjacency: Map[String, Map[String, Int]] = Map.empty
  private var reverseAdjacency: Map[String, Map[String, Int]] = Map.empty
  private var classesForPagerank: Set[String] = Set.empty

  private[brokk] var cpg: Cpg = cpgInit
  initializeAnalyzer(cpg)

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path, language: Language) = {
    this(sourcePath, language, CpgBasedTool.loadFromFile(preloadedPath.toString))
  }

  def this(sourcePath: java.nio.file.Path, language: Language) = {
    this(sourcePath, language, Analyzer.createNewCpgFor(sourcePath, language))
  }

  def this(sourcePath: java.nio.file.Path) = {
    this(sourcePath, Language.Java)
  }

  def this(sourcePath: java.nio.file.Path, preloadedPath: java.nio.file.Path) = {
    this(sourcePath, preloadedPath, Language.Java)
  }

  private def initializeAnalyzer(initialCpg: Cpg): Unit = {
    cpg = initialCpg
    initializePageRank()
  }

  private def initializePageRank(): Unit = {
    if (cpg.metaData.headOption.isEmpty) {
      throw new IllegalStateException("CPG root not found for " + absolutePath)
    }

    // Initialize adjacency map
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

  private def methodsFromName(resolvedMethodName: String): List[Method] = {
    // Joern's method names look like this
    //   org.apache.cassandra.db.DeletionPurger.shouldPurge:boolean(org.apache.cassandra.db.DeletionTime)
    // constructor of a nested class:
    //   org.apache.cassandra.db.Directories$SSTableLister.<init>:void(org.apache.cassandra.io.util.File[])
    val escapedMethodName = Regex.quote(resolvedMethodName)
    cpg.method.fullName(escapedMethodName + ":.*").l
  }

  private[brokk] def resolveMethodName(methodName: String): String = {
    val segments = methodName.split("\\.")
    // Find the first occurrence of a segment with $ followed by digits, e.g. "FutureCallback$0"
    val idx = segments.indexWhere(_.matches(".*\\$\\d+$"))
    // Keep everything before that index (or all if none found)
    val relevant = if (idx == -1) segments else segments.take(idx)
    relevant.mkString(".")
  }

  def getMethodSource(methodName: String): Option[String] = {
    val resolvedMethodName = resolveMethodName(methodName)
    val methods = methodsFromName(resolvedMethodName)

    // static constructors are missing filenames and start/end lines, not much we can do about it
    // https://github.com/joernio/joern/issues/5328
    val sources = methods.flatMap { method =>
      for {
        file <- Option(toFile(method.filename))
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

    // Print this class's signature
    sb.append(indentStr(indent))
      .append("class ")
      .append(className)
      .append(" {\n")

    // Methods: skip any whose name starts with "<lambda>"
    td.method
      .filterNot(_.name.startsWith("<lambda>"))
      .foreach { m =>
        sb.append(indentStr(indent + 1))
          .append(methodSignature(m))
          .append(" {...}\n")
      }

    // Fields: skip any whose name is exactly "outerClass"
    td.member
      .filterNot(_.name == "outerClass")
      .foreach { f =>
        sb.append(indentStr(indent + 1))
          .append(s"${sanitizeType(f.typeFullName)} ${f.name};\n")
      }

    // Nested classes: skip any named "<lambda>N", skip if a segment is purely numeric (like Runnable$0)
    td.astChildren.isTypeDecl.filterNot { nested =>
      nested.name.startsWith("<lambda>") ||
        nested.name.split("\\$").exists(_.forall(_.isDigit))
    }.foreach { nested =>
      sb.append(outlineTypeDecl(nested, indent + 1))
        .append("\n")
    }

    sb.append(indentStr(indent)).append("}")
    sb.toString
  }

  private def indentStr(level: Int) = "  " * level

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
  def getPagerank(seedClassWeights: java.util.Map[String, java.lang.Double], k: Int, reversed: Boolean = false): java.util.List[(String, java.lang.Double)] = {
    import scala.jdk.CollectionConverters._
    val seedWeights = seedClassWeights.asScala.view.mapValues(_.doubleValue()).toMap
    val seedSeq = seedWeights.keys.toSeq

    // restrict to classes that are in the graph
    var validSeeds = seedSeq.filter(classesForPagerank.contains)
    // if we ended up with no seeds, fall back to standard pagerank
    if (validSeeds.isEmpty) {
      validSeeds = classesForPagerank.l
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

    // Calculate total weight for normalization
    val totalWeight = seedWeights.values.sum
    
    // Use provided weights directly, normalized
    validSeeds.foreach { c =>
      scores(c) = seedWeights.getOrElse(c, 0.0) / totalWeight
    }

    var iteration = 0
    var diffSum   = Double.MaxValue

    while (iteration < maxIter && diffSum > epsilon) {

      // Zero nextScores in parallel
      classesForPagerank.par.foreach { c => nextScores(c) = 0.0 }

      // Handle graph edges, using either forward or reverse direction
      val localDiffs = classesForPagerank.par.map { node =>
        val (inMap, outMap) = if (reversed) (adjacency, reverseAdjacency) else (reverseAdjacency, adjacency)
        val inboundSum = inMap
          .get(node)
          .map { inboundMap =>
            inboundMap.foldLeft(0.0) { case (acc, (u, weight)) =>
              val outLinks = outMap(u)
              val outWeight = outLinks.values.sum.max(1)
              acc + (scores(u) * weight / outWeight)
            }
          }.getOrElse(0.0)

        // Damping
        var newScore = damping * inboundSum

        // Random jump to seeds with normalized weights
        if (validSeeds.contains(node)) {
          newScore += (1.0 - damping) * (seedWeights.getOrElse(node, 0.0) / totalWeight)
        }

        nextScores(node) = newScore
        math.abs(scores(node) - newScore)
      }.sum

      // Handle dangling nodes: push their entire score to seeds
      val danglingScore = danglingNodes.par.map(scores).sum
      if (danglingScore > 0.0 && validSeeds.nonEmpty) {
        validSeeds.par.foreach { seed =>
          val weight = seedWeights.getOrElse(seed, 0.0) / totalWeight
          nextScores(seed) += damping * danglingScore * weight
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

    CollectionConverters.asJava(coalesceInnerClasses(filteredSortedAll, k).map { case (s, d) => (s, java.lang.Double.valueOf(d)) })
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
   * Searches for classes, methods, and fields matching a given regular expression pattern.
   * 
   * @param pattern A regular expression to match against class, method, and field names
   * @return A list of CodeUnit objects representing the matches
   */
  def getDefinitions(pattern: String): java.util.List[CodeUnit] = {
    // Compile the regex pattern
    val ciPattern = "(?i)" + pattern // case-insensitive
    
    // Find matching classes (typeDecl)
    val matchingClasses = cpg.typeDecl
      .name(ciPattern)
      .fullName
      .map(CodeUnit.cls)
      .l
      
    // Find matching methods (by name, not fullName)
    val matchingMethods = cpg.method
      .nameNot("<.*>") // Filter out constructors and special methods
      .name(ciPattern)
      .map(m => CodeUnit.fn(resolveMethodName(chopColon(m.fullName))))
      .l
      
    // Find matching fields
    val matchingFields = cpg.member
      .name(ciPattern)
      .map(f => {
        val className = f.typeDecl.fullName.head
        CodeUnit.field(s"$className.${f.name}")
      })
      .l
      
    // Combine all results
    val combined = matchingClasses ++ matchingMethods ++ matchingFields
    CollectionConverters.asJava(combined)
  }
  
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
    Serialization.writeGraph(cpg.graph, path)
  }
  
  override def close(): Unit = {
    cpg.close()
  }
}
object Analyzer {
  private def createNewCpgFor(sourcePath: java.nio.file.Path, language: Language): Cpg = {
    val absPath = sourcePath.toAbsolutePath.toRealPath()
    require(absPath.toFile.isDirectory, s"Source path must be a directory: $absPath")
    val newCpg = language match {
      case Language.Java =>
        val config = JavaConfig()
          .withInputPath(absPath.toString)
          .withEnableTypeRecovery(true)
        JavaSrc2Cpg().createCpg(config).get
      case Language.Python =>
        val cpg = Cpg.empty
        import scala.collection.JavaConverters._
        val files = java.nio.file.Files.walk(absPath).iterator().asScala.toList
        val pythonFiles = files.filter(f => f.toString.endsWith(".py") && f.toFile.isFile).map(absPath.relativize(_))
        val inputProviders = pythonFiles.map { relPath =>
          val absFile = absPath.resolve(relPath)
          () => Py2Cpg.InputPair(scala.io.Source.fromFile(absFile.toFile).mkString, relPath.toString)
        }
        new Py2Cpg(
          inputProviders,
          cpg,
          absPath.toString,
          "requirements.txt",
          ValidationMode.Enabled,
          false
        ).buildCpg()
        cpg
    }
    X2Cpg.applyDefaultOverlays(newCpg)
    val context = new LayerCreatorContext(newCpg)
    new OssDataFlow(OssDataFlow.defaultOpts).create(context)
    newCpg
  }
}
