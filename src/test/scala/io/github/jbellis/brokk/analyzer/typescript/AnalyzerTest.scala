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
    Assertions.assertTrue(analyzer.isClassInProject("A.ts::program:A"))

    Assertions.assertFalse(analyzer.isClassInProject("NonExistentClass"))
    Assertions.assertFalse(analyzer.isClassInProject("console"))
  }

  @Test
  def extractMethodSource(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.ts::program:A:method2").get

    val expected =
      """    public method2(input: string, otherInput?: number): string {
        |        return otherInput !== undefined
        |            ? `prefix_${input} ${otherInput}`
        |            : `prefix_${input}`;
        |    }""".stripMargin

    Assertions.assertEquals(expected, source)
  }

  @Test
  def extractMethodSourceConstructor(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("sub/B.ts::program:B:<init>").get

    val expected =
      """    constructor() {
        |        console.log("B constructor");
        |    }""".stripMargin

    Assertions.assertEquals(expected, source)
  }

  @Test
  def getClassSourceTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("A.ts::program:A")

    // Verify the source contains class definition and methods
    Assertions.assertTrue(source.contains("export class A {"))
    Assertions.assertTrue(source.contains("public method1(): void"))
    Assertions.assertTrue(source.contains("public method2(input: string"))
  }

  @Test
  def getClassSourceNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("NonExistentClass")
    Assertions.assertNull(source)
  }

  @Test
  def getSkeletonTestA(): Unit = {
    val analyzer = getAnalyzer
    val skeleton = analyzer.getSkeleton("A.ts::program:A").get

    val expected =
      """// A.ts::program:A
        |class A {
        |  constructor(private bar?: B) {...}
        |  public method1(): void {...}
        |  public method2(input: string, otherInput?: number): string {...}
        |  public method3() {...}
        |  public static method4(foo: number, bar: number | null): number {...}
        |  public method5(b: B): B {...}
        |  public method6(): void {...}
        |  private fieldDArray: [];
        |  private fieldA: A;
        |  public fieldB: B;
        |  private fieldCArray: Array<A>;
        |  public foo: () => string;
        |}""".stripMargin

    Assertions.assertEquals(expected, skeleton)
  }

  @Test
  def getSkeletonTestB(): Unit = {
    val analyzer = getAnalyzer
    val skeleton = analyzer.getSkeleton("sub/B.ts::program:B").get

    val expected =
      """// sub/B.ts::program:B
        |class B {
        |  constructor() {...}
        |  public callsIntoA(): void {...}
        |}""".stripMargin

    Assertions.assertEquals(expected, skeleton)
  }

  private def getAnalyzer = {
    TypescriptAnalyzer(Path.of("src/test/resources/testcode/typescript"))
  }
}
