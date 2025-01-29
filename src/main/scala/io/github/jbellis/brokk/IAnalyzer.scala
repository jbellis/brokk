package io.github.jbellis.brokk

import java.util
import java.util.List

trait IAnalyzer {
  def getAllClasses: util.List[String]
  def getMembersInClass(fqClass: String): util.List[String]
}
