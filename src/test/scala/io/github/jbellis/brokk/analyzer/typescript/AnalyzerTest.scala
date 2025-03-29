package io.github.jbellis.brokk.analyzer.typescript

import io.github.jbellis.brokk.analyzer.{TypescriptAnalyzer, Language}
import org.junit.jupiter.api.{Test, Assertions}
import java.nio.file.Path
import io.shiftleft.semanticcpg.language.*

class AnalyzerTest {
  implicit val callResolver: ICallResolver = NoResolve

  @Test
  def callerTest(): Unit = {
    val analyzer = getAnalyzer
    val callOut = analyzer.cpg.method.call.l
    Assertions.assertTrue(callOut.nonEmpty)
    val callIn = analyzer.cpg.method.caller.l
    Assertions.assertTrue(callIn.nonEmpty)
  }

  @Test
  def isClassInProjectTest(): Unit = {
    val analyzer = getAnalyzer
    Assertions.assertTrue(analyzer.isClassInProject("A"))
    
    Assertions.assertFalse(analyzer.isClassInProject("NonExistentClass"))
    Assertions.assertFalse(analyzer.isClassInProject("console"))
  }

  private def getAnalyzer = {
    TypescriptAnalyzer(Path.of("src/test/resources/testcode/typescript"))
  }
}
