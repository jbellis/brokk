package io.github.jbellis.brokk.analyzer

import java.util
import java.util.Collections

/**
 * An IAnalyzer implementation that does nothing and returns empty results.
 */
class DisabledAnalyzer extends IAnalyzer {
  override def isEmpty: Boolean =
    true

  override def getAllClasses: util.List[CodeUnit] =
    Collections.emptyList()

  override def getMembersInClass(fqClass: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getClassesInFile(file: ProjectFile): util.Set[CodeUnit] =
    Collections.emptySet()

  override def isClassInProject(className: String): Boolean =
    false

  override def getPagerank(seedClassWeights: java.util.Map[String, java.lang.Double], k: Int, reversed: Boolean = false): java.util.List[scala.Tuple2[CodeUnit, java.lang.Double]] =
    Collections.emptyList()

  override def getSkeleton(className: String): Option[String] =
    None

  override def getSkeletonHeader(className: String): Option[String] =
    None

  override def getFileFor(fqcn: String): Option[ProjectFile] =
    None

  override def getDefinitions(pattern: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getUses(symbol: String): util.List[CodeUnit] =
    Collections.emptyList()

  override def getMethodSource(methodName: String): Option[String] =
    None

  override def getClassSource(className: String): java.lang.String =
    ""

  override def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    Collections.emptyMap()

  override def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    Collections.emptyMap()
}
