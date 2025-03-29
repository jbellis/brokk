package io.github.jbellis.brokk.analyzer

import java.util

trait IAnalyzer {
  def isEmpty: Boolean =
    throw new UnsupportedOperationException()

  def getAllClasses: util.List[CodeUnit] = 
    throw new UnsupportedOperationException()

  def getMembersInClass(fqClass: String): util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getClassesInFile(file: ProjectFile): util.Set[CodeUnit] =
    throw new UnsupportedOperationException()
    
  def isClassInProject(className: String): Boolean = 
    throw new UnsupportedOperationException()
    
  def getPagerank(seedClassWeights: java.util.Map[String, java.lang.Double], k: Int, reversed: Boolean = false): java.util.List[scala.Tuple2[CodeUnit, java.lang.Double]] =
    throw new UnsupportedOperationException()

  def getSkeleton(className: String): Option[String] =
    throw new UnsupportedOperationException()
    
  def getSkeletonHeader(className: String): Option[String] = 
    throw new UnsupportedOperationException()
    
  def getFileFor(fqcn: String): Option[ProjectFile] =
    throw new UnsupportedOperationException()
    
  def getDefinitions(pattern: String): util.List[CodeUnit] =
    throw new UnsupportedOperationException()
    
  def getUses(symbol: String): util.List[CodeUnit] = 
    throw new UnsupportedOperationException()
    
  def getMethodSource(methodName: String): Option[String] = 
    throw new UnsupportedOperationException()
    
  def getClassSource(className: String): java.lang.String = 
    throw new UnsupportedOperationException()
    
  /**
   * Gets the call graph to a specified method.
   *
   * @param methodName The fully-qualified name of the target method
   * @return A map where keys are fully-qualified method signatures and values are lists of CallSite objects
   */
  def getCallgraphTo(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    throw new UnsupportedOperationException()

  /**
   * Gets the call graph from a specified method.
   *
   * @param methodName The fully-qualified name of the source method
   * @return A map where keys are fully-qualified method signatures and values are lists of CallSite objects
   */
  def getCallgraphFrom(methodName: String, depth: Int): java.util.Map[String, java.util.List[CallSite]] =
    throw new UnsupportedOperationException()
}
