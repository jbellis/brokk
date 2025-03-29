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
  
  @Test
  def extractMethodSourceConstructor(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("B.ts::B.<init>").get

    val expected =
      """    constructor() {
        |        console.log("B constructor");
        |    }""".stripMargin

    Assertions.assertEquals(expected, source)
  }

  @Test
  def getClassSourceTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("A.ts::A")

    // Verify the source contains class definition and methods
    Assertions.assertTrue(source.contains("export class A {"))
    Assertions.assertTrue(source.contains("public method1(): void"))
    Assertions.assertTrue(source.contains("public method2(input: string"))
  }

  @Test
  def getClassSourceNestedTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("A.ts::A.<anon-class>0")

    // Verify the source contains inner class definition
    Assertions.assertTrue(source.contains("static Inner = class {"))
    Assertions.assertTrue(source.contains("public method7(): void"))
  }

  @Test
  def getClassSourceNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("NonExistentClass")
    Assertions.assertNull(source)
  }

  private def getAnalyzer = {
    TypescriptAnalyzer(Path.of("src/test/resources/testcode/typescript"))
  }
}
