package io.github.jbellis.brokk.analyzer

import java.util

trait IAnalyzer {
  def isEmpty: Boolean =
    throw new UnsupportedOperationException()

  def isCpg: Boolean =
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

  def getDefinition(symbol: String): util.Optional[CodeUnit] =
    throw new UnsupportedOperationException()

  def searchDefinitions(pattern: String): util.List[CodeUnit] =
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

  /**
   * Locates the source file and line range for the given fully-qualified method name.
   * The paramNames list contains the *parameter variable names* (not types).
   * If there is only a single match, or exactly one match with matching param names, return it.
   * Otherwise throw SymbolNotFoundException or SymbolAmbiguousException
   */
  def getFunctionLocation(fqMethodName: String, paramNames: util.List[String]): FunctionLocation = {
    throw new UnsupportedOperationException()
  }

  /**
   * Gets a set of relevant symbol names (classes, methods, fields) defined within the given source CodeUnits.
   *
   * @param sources A set of CodeUnit objects representing the source files or classes to analyze.
   * @return A set of fully-qualified symbol names found within the sources.
   */
  def getSymbols(sources: java.util.Set[CodeUnit]): java.util.Set[String] = {
    throw new UnsupportedOperationException()
  }

  def getSkeletons(file: ProjectFile): java.util.Map[CodeUnit, String] = {
    val skeletons = new java.util.HashMap[CodeUnit, String]()
    getClassesInFile(file).forEach(cls => {
      getSkeleton(cls.fqName()).foreach(skel => skeletons.put(cls, skel))
    })
    skeletons
  }
}

/**
 * A container for the function’s location and current text.
 */
case class FunctionLocation(file: ProjectFile, startLine: Int, endLine: Int, code: String)
