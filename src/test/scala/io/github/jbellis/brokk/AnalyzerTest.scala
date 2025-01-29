package io.github.jbellis.brokk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue}
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.semanticcpg.language.*

import java.nio.file.Path
import scala.jdk.javaapi.*
import scala.jdk.javaapi.CollectionConverters.{asJava, asScala}

class AnalyzerTest {
  implicit val callResolver: ICallResolver = NoResolve

  @Test
  def callerTest(): Unit = {
    val analyzer = getAnalyzer
    val callOut = analyzer.cpg.method.call.l
    assert(callOut.size > 0)
    val callIn = analyzer.cpg.method.caller.l
    assert(callIn.size > 0)
  }

  @Test
  def classInProjectTest(): Unit = {
    val analyzer = getAnalyzer
    assert(analyzer.classInProject("A"))

    assert(!analyzer.classInProject("java.nio.filename.Path"))
    assert(!analyzer.classInProject("org.foo.Bar"))
  }
  
  @Test
  def extractsMethodSource(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.method2")

    val expected =
      """    public String method2(String input) {
        |        return "prefix_" + input;
        |    }""".stripMargin

    assertEquals(expected, source)
  }

  @Test 
  def sanitizeTypeTest(): Unit = {
    val analyzer = getAnalyzer
    
    // Simple types
    assertEquals("String", analyzer.sanitizeType("java.lang.String"))
    assertEquals("String[]", analyzer.sanitizeType("java.lang.String[]"))
    
    // Generic types
    assertEquals("Function<Integer, Integer>", 
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"))
    
    // Nested generic types
    assertEquals("Map<String, List<Integer>>",
      analyzer.sanitizeType("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>"))
      
    // Method return type with generics
    assertEquals("Function<Integer, Integer>",
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>"))
  }

  @Test
  def getSkeletonTest(): Unit = {
    val analyzer = getAnalyzer
    val skeleton = analyzer.getSkeleton("A").get
    // https://github.com/joernio/joern/issues/5297
//    val expected =
//      """public class A {
//        |  public void method1() {...}
//        |  public String method2(String input) {...}
//        |  public Function<Integer, Integer> method3() {...}
//        |}""".stripMargin
    val expected =
      """public class A {
        |  public void method1() {...}
        |  public String method2(String input) {...}
        |  public Function method3() {...}
        |  public static int method4(double foo, Integer bar) {...}
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getSkeletonHeaderTest(): Unit = {
    val analyzer = getAnalyzer
    val skeleton = analyzer.skeletonHeader("D").get
    val expected =
      """public class D {
        |  private int field1;
        |  private String field2;
        |  [... methods not shown ...]
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getAllClassesTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getAllClasses
    assert(classes.contains("A"))
    assert(classes.contains("B"))
    assert(classes.contains("C"))
    assert(classes.contains("D"))
  }

  @Test
  def getMembersInClassTest(): Unit = {
    val analyzer = getAnalyzer
    val members = analyzer.getMembersInClass("D")
    assertEquals(Set("D.field1", "D.field2", "D.methodD1", "D.methodD2", "D$DSub", "D$DSubStatic"), asScala(members).toSet)
  }

  @Test
  def getReferrersOfFieldTest(): Unit = {
    val analyzer = getAnalyzer
    val referrers = analyzer.getReferrersOfField("D.field1")
    assert(referrers.contains("D.methodD2"))
  }

  @Test
  def getCallersOfMethodTest(): Unit = {
    val analyzer = getAnalyzer
    val callers = analyzer.getCallersOfMethod("A.method1")
    assert(callers.contains("B.callsIntoA"))
    assert(callers.contains("D.methodD1"))
  }

  @Test
  def getPagerankTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi._
    
    val seeds = asJava(List("D"))
    val ranked = analyzer.getPagerank(seeds, 2)
    
    // A and B should rank highly as they are both called by D
    assert(ranked.size() == 2, ranked)
    val classes = asScala(ranked).map(_._1)
    assertEquals(Set("B", "E"), classes.toSet)
  }

  @Test
  def classesInFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.classesInFile(analyzer.toFile("D.java"))
    assertEquals(Set("D", "D$DSub", "D$DSubStatic"), asScala(classes).toSet)
  }

  @Test 
  def classesInPackagedFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.classesInFile(analyzer.toFile("Packaged.java"))
    assertEquals(Set("io.github.jbellis.brokk.Foo"), asScala(classes).toSet)
  }

  @Test
  def getUsesMethodExistingTest(): Unit = {
    val analyzer = getAnalyzer
    // This is the fully qualified Joern style:
    //    "A.method2:java.lang.String(java.lang.String)"
    val symbol = "A.method2:java.lang.String(java.lang.String)"
    val usages = analyzer.getUses(symbol)

    // Expect references in B.callsIntoA() because it calls a.method2("test")
    assertTrue(usages.getMethodUses.contains("B.callsIntoA"), s"Expected B.callsIntoA in methodUses: ${usages.getMethodUses}")
    assertEquals(0, usages.getTypeUses.size(), "No type usages expected")
  }

  @Test
  def getUsesMethodNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "A.noSuchMethod:java.lang.String()"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found as a method, field, or class"))
  }

  @Test
  def getUsesFieldExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "D.field1" // fully qualified field name
    val usages = analyzer.getUses(symbol)

    // We expect methodD2 references "field1 = 42"
    assertEquals(java.util.List.of("D.methodD2"), usages.getMethodUses)
    assertEquals(java.util.List.of(), usages.getTypeUses, "No type usages expected")
  }

  @Test
  def getUsesFieldNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "D.notAField"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found"))
  }

  @Test
  def getUsesClassBasicTest(): Unit = {
    val analyzer = getAnalyzer
    // “A” has no static members, but it is used as a local type in B.callsIntoA and D.methodD1
    val symbol = "A"
    val usages = analyzer.getUses(symbol)

    // methodUses => references to A as a type in B.callsIntoA() and D.methodD1()
    val foundMethodUses = asScala(usages.getMethodUses).toSet
    assertEquals(Set("B.callsIntoA", "D.methodD1"), foundMethodUses)
  }

  @Test
  def getUsesClassNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "NoSuchClass"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("Symbol 'NoSuchClass' not found"))
  }

  @Test
  def getUsesClassWithStaticMembersTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "E"
    val usages = analyzer.getUses(symbol)

    // 1) methodUses
    val methodUses = asScala(usages.getMethodUses).toSet
    assertEquals(Set("UseE.some", "UseE.<init>", "UseE.moreM", "UseE.moreF"), methodUses)

    // 3) typeUses (usage as a type)
    //    Expect: "UseE.e" for the field declaration
    val typeUses = asScala(usages.getTypeUses).toSet
    assertEquals(Set("UseE"), typeUses)
  }

  //
  // Helper to get a prebuilt analyzer
  //
  private def getAnalyzer = {
    // Points to your test code directory with A, B, C, D, CamelClass, E, UseE, etc.
    new Analyzer(Path.of("src/test/resources/testcode"))
  }
}
