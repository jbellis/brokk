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
  def isClassInProjectTest(): Unit = {
    val analyzer = getAnalyzer
    assert(analyzer.isClassInProject("A"))

    assert(!analyzer.isClassInProject("java.nio.filename.Path"))
    assert(!analyzer.isClassInProject("org.foo.Bar"))
  }
  
  @Test
  def extractMethodSource(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A.method2").get

    val expected =
      """    public String method2(String input) {
        |        return "prefix_" + input;
        |    }
        |
        |    public String method2(String input, int otherInput) {
        |        // overload of method2
        |        return "prefix_" + input + " " + otherInput;
        |    }""".stripMargin

    assertEquals(expected, source)
  }

  @Test
  def extractMethodSourceNested(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("A$AInner$AInnerInner.method7").get

    val expected =
      """            public void method7() {
        |                System.out.println("hello");
        |            }""".stripMargin

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
  def getSkeletonTestA(): Unit = {
    val skeleton = getAnalyzer.getSkeleton("A").get
    // https://github.com/joernio/joern/issues/5297
    //    |  public Function<Integer, Integer> method3() {...}
    val expected =
      """class A {
        |  public void method1() {...}
        |  public String method2(String input) {...}
        |  public String method2(String input, int otherInput) {...}
        |  public Function method3() {...}
        |  public static int method4(double foo, Integer bar) {...}
        |  public void method5() {...}
        |  public void method6() {...}
        |  public void <init>() {...}
        |  class A$AInner {
        |    public void <init>(A outerClass) {...}
        |    class A$AInner$AInnerInner {
        |      public void method7() {...}
        |      public void <init>(A$AInner outerClass) {...}
        |    }
        |  }
        |  class A$AInnerStatic {
        |    public void <init>() {...}
        |  }
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getSkeletonTestD(): Unit = {
    val skeleton = getAnalyzer.getSkeleton("D").get
    val expected =
      """class D {
        |  public void methodD1() {...}
        |  public void methodD2() {...}
        |  public void <init>() {...}
        |  int field1;
        |  String field2;
        |  class D$DSubStatic {
        |    public void <init>() {...}
        |  }
        |  class D$DSub {
        |    public void <init>(D outerClass) {...}
        |  }
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getGetSkeletonHeaderTest(): Unit = {
    val skeleton = getAnalyzer.getSkeletonHeader("D").get
    val expected =
      """class D {
        |  public int field1;
        |  private String field2;
        |  [... methods not shown ...]
        |}""".stripMargin
    assertEquals(expected, skeleton)
  }

  @Test
  def getAllClassesTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getAllClasses
  }

  @Test
  def getMembersInClassTest(): Unit = {
    val analyzer = getAnalyzer
    val members = analyzer.getMembersInClass("D")
    val expected = Set("D.field1", "D.field2").map(CodeUnit.field) ++ Set("D.methodD1", "D.methodD2").map(CodeUnit.fn) ++ Set("D$DSub", "D$DSubStatic").map(CodeUnit.cls)
    assertEquals(expected, asScala(members).toSet)
  }

  @Test
  def getClassesInFilePythonTest(): Unit = {
    val analyzer = Analyzer(Path.of("src/test/resources/testcode"), Language.Python)
    val classes = analyzer.getClassesInFile(analyzer.toFile("A.py"))
//    val expected = Set("D", "D$DSub", "D$DSubStatic").map(CodeUnit.cls)
//    assertEquals(expected, asScala(classes).toSet)
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
    
    val seeds = CollectionConverters.asJava(Map("D" -> (1.0: java.lang.Double)))
    val ranked = analyzer.getPagerank(seeds, 3)
    
    // A and B should rank highly as they are both called by D
    assert(ranked.size() == 3, ranked)
    val classes = asScala(ranked).map(_._1).toSet
    assertEquals(Set("A", "B", "AnonymousUsage.foo.Runnable$0"), classes)
  }

  @Test
  def getClassesInFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getClassesInFile(analyzer.toFile("D.java"))
    val expected = Set("D", "D$DSub", "D$DSubStatic").map(CodeUnit.cls)
    assertEquals(expected, asScala(classes).toSet)
  }

  @Test 
  def classesInPackagedFileTest(): Unit = {
    val analyzer = getAnalyzer
    val classes = analyzer.getClassesInFile(analyzer.toFile("Packaged.java"))
    assertEquals(Set(CodeUnit.cls("io.github.jbellis.brokk.Foo")), asScala(classes).toSet)
  }

  @Test
  def getUsesMethodExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "A.method2"
    val usages = analyzer.getUses(symbol)

    // Expect references in B.callsIntoA() because it calls a.method2("test")
    val actualMethodRefs = asScala(usages).filter(_.isFunction).map(_.reference).toSet
    val actualRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("B.callsIntoA", "AnonymousUsage.foo"), actualRefs)
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

    val actualRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("D.methodD2", "E.dMethod"), actualRefs)
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
    val foundRefs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("B.callsIntoA", "D.methodD1", "AnonymousUsage.foo"), foundRefs)
  }

  @Test
  def getUsesClassNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "NoSuchClass"
    val ex = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("Symbol 'NoSuchClass' not found"))
  }

  @Test
  def getDefinitionsTest(): Unit = {
    val analyzer = getAnalyzer

    // bare regexp testing
    assertTrue("E".matches("(?i)e"))
    assertTrue("E".matches("(?i).*e"))
    assertTrue("e".matches("(?i)E"))

    // Find classes matching "*E"
    val classMatches = analyzer.getDefinitions(".*e")
    val classRefs = asScala(classMatches).filter(_.isClass).map(_.reference).toSet
    assertEquals(Set("E", "UseE", "AnonymousUsage", "double", "java.lang.Runnable"), classRefs)

    // Find methods matching "method*"
    val methodMatches = analyzer.getDefinitions("method.*1")
    val methodRefs = asScala(methodMatches).map(_.reference).toSet
    assertEquals(Set("A.method1", "D.methodD1"), methodRefs)

    // Find fields matching "field.*"
    val fieldMatches = analyzer.getDefinitions(".*field.*")
    val fieldRefs = asScala(fieldMatches).map(_.reference).toSet
    assertEquals(Set("D.field1", "D.field2", "E.iField", "E.sField", "<operator>.fieldAccess"), fieldRefs)
  }

  @Test
  def getUsesClassWithStaticMembersTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "E"
    val usages = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.reference).toSet
    assertEquals(Set("UseE.some", "UseE.<init>", "UseE.moreM", "UseE.moreF", "UseE"), refs)
  }

  @Test
  def resolveMethodNameTest(): Unit = {
    val analyzer = getAnalyzer
    
    // Regular Methods
    assertEquals("java.util.List.isEmpty", analyzer.resolveMethodName("java.util.List.isEmpty"))
    assertEquals("java.lang.Math.max", analyzer.resolveMethodName("java.lang.Math.max"))
    assertEquals("java.util.UUID.toString", analyzer.resolveMethodName("java.util.UUID.toString"))

    // Static Methods
    assertEquals("java.lang.Integer.valueOf", analyzer.resolveMethodName("java.lang.Integer.valueOf"))
    assertEquals("java.nio.file.Files.createDirectories", analyzer.resolveMethodName("java.nio.file.Files.createDirectories"))
    assertEquals("java.util.Collections.unmodifiableList", analyzer.resolveMethodName("java.util.Collections.unmodifiableList"))
    assertEquals("org.apache.cassandra.utils.FBUtilities.waitOnFuture", analyzer.resolveMethodName("org.apache.cassandra.utils.FBUtilities.waitOnFuture"))

    // Inner Class Methods
    assertEquals("org.apache.cassandra.db.ClusteringPrefix$Kind.ordinal", analyzer.resolveMethodName("org.apache.cassandra.db.ClusteringPrefix$Kind.ordinal"))
    assertEquals("org.apache.cassandra.io.sstable.format.big.BigTableWriter$IndexWriter.prepareToCommit", 
      analyzer.resolveMethodName("org.apache.cassandra.io.sstable.format.big.BigTableWriter$IndexWriter.prepareToCommit"))
    assertEquals("org.apache.cassandra.index.sai.disk.v1.kdtree.BKDReader$IteratorState.getMinLeafBlockFP", 
      analyzer.resolveMethodName("org.apache.cassandra.index.sai.disk.v1.kdtree.BKDReader$IteratorState.getMinLeafBlockFP"))
    assertEquals("org.apache.cassandra.repair.consistent.ConsistentSession$State.transitions", 
      analyzer.resolveMethodName("org.apache.cassandra.repair.consistent.ConsistentSession$State.transitions"))
    
    // Anonymous Inner Classes used in a method
    assertEquals("org.apache.cassandra.repair.RepairJob.run",
      analyzer.resolveMethodName("org.apache.cassandra.repair.RepairJob.run.FutureCallback$0.set"))
    assertEquals("org.apache.cassandra.db.lifecycle.View.updateCompacting",
      analyzer.resolveMethodName("org.apache.cassandra.db.lifecycle.View.updateCompacting.Function$0.all"))
    assertEquals("org.apache.cassandra.index.sai.plan.ReplicaPlans.writeNormal",
      analyzer.resolveMethodName("org.apache.cassandra.index.sai.plan.ReplicaPlans.writeNormal.Selector$1.any"))

    // Anonymous inner classes used in a field
    //    assertEquals("org.apache.cassandra.cql3.functions.TimeFcts.minTimeuuidFct.NativeScalarFunction$0.<init>",
    //      analyzer.resolveMethodName("org.apache.cassandra.cql3.functions.TimeFcts.minTimeuuidFct.NativeScalarFunction$0.<init>"))
    //    assertEquals("org.apache.cassandra.db.Clustering.STATIC_CLUSTERING.BufferClustering$0.<init>",
    //      analyzer.resolveMethodName("org.apache.cassandra.db.Clustering.STATIC_CLUSTERING.BufferClustering$0.<init>"))

    // Constructors
    assertEquals("java.util.HashMap.<init>", analyzer.resolveMethodName("java.util.HashMap.<init>"))
    assertEquals("org.apache.cassandra.db.marshal.UserType.<init>", analyzer.resolveMethodName("org.apache.cassandra.db.marshal.UserType.<init>"))

    // Enum-related Methods
    assertEquals("org.apache.cassandra.db.ConsistencyLevel.valueOf", analyzer.resolveMethodName("org.apache.cassandra.db.ConsistencyLevel.valueOf"))
    assertEquals("org.apache.cassandra.repair.consistent.ConsistentSession$State.ordinal", 
      analyzer.resolveMethodName("org.apache.cassandra.repair.consistent.ConsistentSession$State.ordinal"))

    // Interface Methods
    assertEquals("org.apache.cassandra.io.IVersionedSerializer.deserialize", 
      analyzer.resolveMethodName("org.apache.cassandra.io.IVersionedSerializer.deserialize"))
    assertEquals("io.github.jbellis.jvector.graph.GraphIndex.ramBytesUsed", 
      analyzer.resolveMethodName("io.github.jbellis.jvector.graph.GraphIndex.ramBytesUsed"))
    assertEquals("java.util.Comparator.comparing", analyzer.resolveMethodName("java.util.Comparator.comparing"))
    assertEquals("org.apache.cassandra.dht.RingPosition.compareTo", analyzer.resolveMethodName("org.apache.cassandra.dht.RingPosition.compareTo"))
    assertEquals("com.google.common.collect.SortedSetMultimap.values", analyzer.resolveMethodName("com.google.common.collect.SortedSetMultimap.values"))
    
    // Operator-related Methods
    assertEquals("<operator>.assignmentDivision", analyzer.resolveMethodName("<operator>.assignmentDivision"))
    assertEquals("<operator>.not", analyzer.resolveMethodName("<operator>.not"))
    assertEquals("<operator>.plus", analyzer.resolveMethodName("<operator>.plus"))
    assertEquals("<operators>.assignmentModulo", analyzer.resolveMethodName("<operators>.assignmentModulo"))
    assertEquals("<operators>.assignmentLogicalShiftRight", analyzer.resolveMethodName("<operators>.assignmentLogicalShiftRight"))
  }

  /** Helper to get a prebuilt analyzer */
  private def getAnalyzer = {
    Analyzer(Path.of("src/test/resources/testcode"))
  }
}
