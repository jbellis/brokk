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

  private def getAnalyzer = {
    TypescriptAnalyzer(Path.of("src/test/resources/testcode/typescript"))
  }
}
