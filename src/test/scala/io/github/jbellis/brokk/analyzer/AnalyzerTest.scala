package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.analyzer.{JavaAnalyzer, CodeUnit, Language}
import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.semanticcpg.language.*
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

import java.nio.file.Path
import scala.jdk.javaapi.*
import scala.jdk.javaapi.CollectionConverters.asScala

class AnalyzerTest {
  implicit val callResolver: ICallResolver = NoResolve

  @Test
  def callerTest(): Unit = {
    val analyzer = getAnalyzer
    val callOut = analyzer.cpg.method.call.l
    assert(callOut.nonEmpty)
    val callIn = analyzer.cpg.method.caller.l
    assert(callIn.nonEmpty)
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
  def extractMethodSourceConstructor(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getMethodSource("B.<init>").get

    val expected =
      """    public B() {
        |        System.out.println("B constructor");
        |    }""".stripMargin

    assertEquals(expected, source)
  }

  @Test
  def getClassSourceTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("A")

    // Verify the source contains class definition and methods
    assertTrue(source.contains("class A {"))
    assertTrue(source.contains("public void method1()"))
    assertTrue(source.contains("public String method2(String input)"))
  }

  @Test
  def getClassSourceNestedTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("A$AInner")

    // Verify the source contains inner class definition
    assertTrue(source.contains("class AInner {"))
    assertTrue(source.contains("class AInnerInner {"))
  }

  @Test
  def getClassSourceNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val source = analyzer.getClassSource("NonExistentClass")
    assertEquals(null, source)
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
  def getClassesInFilePythonTest(): Unit = {
    val analyzer = JavaAnalyzer(Path.of("src/test/resources/testcode"), Language.PYTHON)
    val file = analyzer.toFile("A.py").get
    val classes = analyzer.getClassesInFile(file)
    //    val expected = Set("D", "D$DSub", "D$DSubStatic").map(name => CodeUnit.cls(file, name))
    //    assertEquals(expected, asScala(classes).toSet)
  }

  @Test
  def getCallgraphToTest(): Unit = {
    val analyzer = getAnalyzer
    val callgraph = analyzer.getCallgraphTo("A.method1", 5)

    // Convert to a more convenient form for testing
    val callsites = asScala(callgraph).toMap

    // Expect A.method1 -> [B.callsIntoA, D.methodD1]
    assertTrue(callsites.contains("A.method1"), "Should contain A.method1 as a key")

    val callers = callsites.get("A.method1").map(sites => asScala(sites).map(_.target().fqName).toSet).getOrElse(Set.empty)
    assertEquals(Set("B.callsIntoA", "D.methodD1"), callers)
  }

  @Test
  def getCallgraphFromTest(): Unit = {
    val analyzer = getAnalyzer
    val callgraph = analyzer.getCallgraphFrom("B.callsIntoA", 5)

    // Convert to a more convenient form for testing
    val callsites = asScala(callgraph).toMap

    // Expect B.callsIntoA -> [A.method1, A.method2]
    assertTrue(callsites.contains("B.callsIntoA"), "Should contain B.callsIntoA as a key")

    val callees = callsites.get("B.callsIntoA").map(sites => asScala(sites).map(_.target().fqName).toSet).getOrElse(Set.empty)
    assertTrue(callees.contains("A.method1"), "Should call A.method1")
    assertTrue(callees.contains("A.method2"), "Should call A.method2")
  }

  @Test
  def getPagerankTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi.*

    val seeds = CollectionConverters.asJava(Map("D" -> (1.0: java.lang.Double)))
    val ranked = analyzer.getPagerank(seeds, 3, false)

    // D calls A and B
    assert(ranked.size() == 2, ranked)
    val classes = asScala(ranked).map(_._1.fqName()).toSet
    assertEquals(Set("A", "B"), classes)
  }

  @Test
  def getPagerankEmptyClassTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi.*

    // Seed with CamelClass, which has no connections
    val seeds = CollectionConverters.asJava(Map("CamelClass" -> (1.0: java.lang.Double)))
    val ranked = analyzer.getPagerank(seeds, 5, false)

    // Expect an empty list because CamelClass has few connections,
    // and after filtering itself and zero-scores, nothing should remain.
    assertTrue(ranked.isEmpty, s"Expected empty pagerank results, but got: $ranked")
  }

  @Test
  def getClassesInFileTest(): Unit = {
    val analyzer = getAnalyzer
    val file = analyzer.toFile("D.java").get
    val classes = analyzer.getClassesInFile(file)
    val expected = Set("D", "D$DSub", "D$DSubStatic").map(name => CodeUnit.cls(file, name))
    assertEquals(expected, asScala(classes).toSet)
  }

  @Test
  def classesInPackagedFileTest(): Unit = {
    val analyzer = getAnalyzer
    val file = analyzer.toFile("Packaged.java").get
    val classes = analyzer.getClassesInFile(file)
    assertEquals(Set(CodeUnit.cls(file, "io.github.jbellis.brokk.Foo")), asScala(classes).toSet)
  }

  @Test
  def getUsesMethodExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "A.method2"
    val usages = analyzer.getUses(symbol)

    // Expect references in B.callsIntoA() because it calls a.method2("test")
    val actualMethodRefs = asScala(usages).filter(_.isFunction).map(_.fqName).toSet
    val actualRefs = asScala(usages).map(_.fqName).toSet
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

    val file = analyzer.toFile("D.java").get
    val actualRefs = asScala(usages).map(_.fqName).toSet
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

    // References to A include both function references and local variable references
    val foundRefs = asScala(usages).map(_.fqName).toSet

    // Get the usages of each type
    val functionRefs = asScala(usages).filter(_.isFunction).map(_.fqName).toSet
    val fieldRefs = asScala(usages).filter(cu => !cu.isFunction && !cu.isClass).map(_.fqName).toSet
    val classRefs = asScala(usages).filter(_.isClass).map(_.fqName).toSet

    // There should be function usages in these methods
    assertEquals(Set("B.callsIntoA", "D.methodD1", "AnonymousUsage.foo"), functionRefs)

    // Ensure we have the correct usage types with our refactored implementation
    assertEquals(foundRefs, functionRefs ++ fieldRefs ++ classRefs)
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
    val classMatches = analyzer.searchDefinitions(".*e")
    val classRefs = asScala(classMatches).filter(_.isClass).map(_.fqName).toSet
    assertEquals(Set("E", "UseE", "AnonymousUsage"), classRefs)

    // Find methods matching "method*"
    val methodMatches = analyzer.searchDefinitions("method.*1")
    val methodRefs = asScala(methodMatches).map(_.fqName).toSet
    assertEquals(Set("A.method1", "D.methodD1"), methodRefs)

    // Find fields matching "field.*"
    val fieldMatches = analyzer.searchDefinitions(".*field.*")
    val fieldRefs = asScala(fieldMatches).map(_.fqName).toSet
    assertEquals(Set("D.field1", "D.field2", "E.iField", "E.sField"), fieldRefs)
  }

  @Test
  def getDefinitionTest(): Unit = {
    val analyzer = getAnalyzer

    // Test finding a class
    val classDefOpt = analyzer.getDefinition("D")
    assertTrue(classDefOpt.isPresent, "Should find definition for class D")
    assertEquals("D", classDefOpt.get().fqName())
    assertTrue(classDefOpt.get().isClass)

    // Test finding a method (unique non-overloaded)
    val methodDefOpt = analyzer.getDefinition("A.method1")
    assertTrue(methodDefOpt.isPresent, "Should find definition for method A.method1")
    assertEquals("A.method1", methodDefOpt.get().fqName())
    assertTrue(methodDefOpt.get().isFunction)

    // Test finding a method
    val overloadedMethodOpt = analyzer.getDefinition("A.method2")
    assertTrue(overloadedMethodOpt.isPresent, "Should find a definition for overloaded method A.method2")
    assertEquals("A.method2", overloadedMethodOpt.get().fqName())
    assertTrue(overloadedMethodOpt.get().isFunction)

    // Test finding a field
    val fieldDefOpt = analyzer.getDefinition("D.field1")
    assertTrue(fieldDefOpt.isPresent, "Should find definition for field D.field1")
    assertEquals("D.field1", fieldDefOpt.get().fqName())
    assertFalse(fieldDefOpt.get().isClass)
    assertFalse(fieldDefOpt.get().isFunction)

    // Test non-existent symbol
    val nonExistentOpt = analyzer.getDefinition("NonExistentSymbol")
    assertFalse(nonExistentOpt.isPresent, "Should not find definition for NonExistentSymbol")
  }


  @Test
  def getUsesClassWithStaticMembersTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "E"
    val usages = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.fqName).toSet
    // Now includes field reference UseE.e as a FIELD type
    assertEquals(Set("UseE.some", "UseE.<init>", "UseE.moreM", "UseE.moreF", "UseE.e"), refs)
  }

  @Test
  def getUsesClassInheritanceTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol = "BaseClass"
    val usages = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.fqName).toSet

    // Get references by type
    val classRefs = asScala(usages).filter(_.isClass).map(_.fqName).toSet

    // Create an error message capturing actual usages
    val errorMsg = s"Expected XExtendsY to be a usage of BaseClass. Actual usages: ${refs.mkString(", ")}"

    // XExtendsY should show up in the results because it extends BaseClass
    assertTrue(refs.exists(name => name.contains("XExtendsY")), errorMsg)

    // Verify that XExtendsY is specifically a CLASS type reference
    val classErrorMsg = s"Expected XExtendsY to be a CLASS type usage. Class references: ${classRefs.mkString(", ")}"
    assertTrue(classRefs.exists(name => name.contains("XExtendsY")), classErrorMsg)

    // New test: Methods returning BaseClass should be included (e.g. MethodReturner.getBase)
    assertTrue(refs.exists(name => name.contains("MethodReturner.getBase")), "Expected MethodReturner.getBase to be included in BaseClass usages")
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

  @Test
  def getFunctionLocationSingleMatchTest(): Unit = {
    val analyzer = getAnalyzer
    // This method has exactly one parameter (String input) and so exactly one matching overload
    val location = analyzer.getFunctionLocation("A.method2", java.util.List.of("input"))
    assertTrue(location.startLine > 0, "Start line should be positive")
    assertTrue(location.endLine >= location.startLine, "End line should not precede start line")
    assertTrue(location.code.contains("public String method2(String input)"),
      s"Method code should contain signature for 'method2(String)'; got:\n${location.code}")
  }

  @Test
  def getFunctionLocationMissingParamTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.method2" has two overloads, but neither takes zero parameters
    assertThrows(classOf[SymbolNotFoundException], () => {
      analyzer.getFunctionLocation("A.method2", java.util.Collections.emptyList())
    })
  }

  @Test
  def getFunctionLocationMissingPackageTest(): Unit = {
    val analyzer = getAnalyzer
    analyzer.getFunctionLocation("Foo.bar", java.util.Collections.emptyList())
  }

  @Test
  def getFunctionLocationParamMismatchTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.method2" has overloads, but none with param name "bogusParam"
    assertThrows(classOf[SymbolNotFoundException], () => {
      analyzer.getFunctionLocation("A.method2", java.util.List.of("bogusParam"))
    })
  }

  @Test
  def getFunctionLocationNoSuchMethodTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.noSuchMethod" does not exist at all
    assertThrows(classOf[SymbolNotFoundException], () => {
      analyzer.getFunctionLocation("A.noSuchMethod", java.util.Collections.emptyList())
    })
  }

  @Test
  def getFunctionLocationConstructorTest(): Unit = {
    val analyzer = getAnalyzer
    // "B.<init>" is a constructor with no params
    val location = analyzer.getFunctionLocation("B.<init>", java.util.Collections.emptyList())
    assertTrue(location.startLine > 0 && location.endLine > 0)
    assertTrue(location.code.contains("public B()"), s"Constructor code:\n${location.code}")
  }

  /** Helper to get a prebuilt analyzer */
  private def getAnalyzer = {
    JavaAnalyzer(Path.of("src/test/resources/testcode"))
  }
}
