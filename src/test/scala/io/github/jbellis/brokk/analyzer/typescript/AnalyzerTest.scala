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
    Assertions.assertTrue(analyzer.isClassInProject("A.ts::A"))

    Assertions.assertFalse(analyzer.isClassInProject("NonExistentClass"))
    Assertions.assertFalse(analyzer.isClassInProject("console"))
  }

  @Test
  def extractMethodSource(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.ts::A.method2").get

    val expected =
      """    public method2(input: string, otherInput?: number): string {
        |        return otherInput !== undefined
        |            ? `prefix_${input} ${otherInput}`
        |            : `prefix_${input}`;
        |    }""".stripMargin

    Assertions.assertEquals(expected, source)
  }

  @Test
  def extractMethodSourceNested(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.ts::A.<anon-class>0:method7").get

    val expected =
      """        public method7(): void {
        |            console.log("hello");
        |        }""".stripMargin

    Assertions.assertEquals(expected, source)
  }

  private def getAnalyzer = {
    TypescriptAnalyzer(Path.of("src/test/resources/testcode/typescript"))
  }
}
