package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.git.IGitRepo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


public final class TreeSitterAnalyzerTest {

    /**
     * Lightweight IProject implementation for unit-testing Tree-sitter analyzers.
     */
    final static class TestProject implements IProject {
        private final Path root;
        private final io.github.jbellis.brokk.analyzer.Language language; // Use Brokk's Language enum
        private IAnalyzer analyzer;      // lazy-initialised

        TestProject(Path root, io.github.jbellis.brokk.analyzer.Language language) {
            this.root     = root;
            this.language = language;
        }

        @Override
        public io.github.jbellis.brokk.analyzer.Language getAnalyzerLanguage() { // Use Brokk's Language enum
            return language;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public synchronized IAnalyzer getAnalyzer() {
            if (analyzer == null) {
                analyzer = switch (language) { // Use Brokk's Language enum
                    case PYTHON -> new PythonAnalyzer(this);
                    case C_SHARP -> new CSharpAnalyzer(this);
                    // add more languages as needed
                    default      -> throw new IllegalArgumentException(
                            "No TreeSitterAnalyzer registered for: " + language);
                };
            }
            return analyzer;
        }

        @Override
        public IGitRepo getRepo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<ProjectFile> getAllFiles() {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(p -> new ProjectFile(root, root.relativize(p)))
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                System.err.printf("ERROR (TestProject.getAllFiles): walk failed on %s: %s%n",
                                  root, e.getMessage());
                e.printStackTrace(System.err);
                return Collections.emptySet();
            }
        }

        @Override
        public IAnalyzer getAnalyzerUninterrupted() {
            return getAnalyzer();
        }
    }

    /** Creates a TestProject rooted under src/test/resources/{subDir}. */
    private static TestProject createTestProject(String subDir, io.github.jbellis.brokk.analyzer.Language lang) { // Use Brokk's Language enum
        Path testDir = Path.of("src/test/resources", subDir);
        assertTrue(Files.exists(testDir), "Test resource dir missing: " + testDir);
        assertTrue(Files.isDirectory(testDir), testDir + " is not a directory");
        return new TestProject(testDir.toAbsolutePath(), lang);
    }

    /* -------------------- Python -------------------- */

    @Test
    void testPythonInitializationAndSkeletons() {
        TestProject project = createTestProject("testcode-py", io.github.jbellis.brokk.analyzer.Language.PYTHON); // Use Brokk's Language enum
        IAnalyzer ana = Optional.ofNullable(project.getAnalyzer()).orElseThrow();
        assertInstanceOf(PythonAnalyzer.class, ana);

        TreeSitterAnalyzer analyzer = (TreeSitterAnalyzer) ana;
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed Python files");

        ProjectFile fileA = new ProjectFile(project.getRoot(), "a/A.py");
        var skelA = analyzer.getSkeletons(fileA);
        assertFalse(skelA.isEmpty(), "Skeletons map for file A should not be empty.");

        // Use the 3-parameter factory methods with corrected Python structure:
        // Package path is directory-based (e.g., "a").
        // Class shortName is just the class name ("A"). fqName = "a.A".
        // Function shortName includes module ("A.funcA"). fqName = "a.A.funcA".

        var classA = CodeUnit.cls(fileA, "a", "A"); // package="a", shortName="A"
        assertEquals("A", classA.shortName(), "Class A shortName mismatch");
        assertEquals("a.A", classA.fqName(), "Class A fqName mismatch"); // Corrected fqName
        assertTrue(skelA.containsKey(classA), "Skeleton map should contain class A.");
        assertTrue(skelA.get(classA).contains("class A:"));

        // Function shortName includes module name ("A.funcA")
        var funcA = CodeUnit.fn(fileA, "a", "A.funcA"); // package="a", shortName="A.funcA"
        assertEquals("A.funcA", funcA.shortName(), "Function funcA shortName mismatch"); // Corrected shortName
        assertEquals("a.A.funcA", funcA.fqName(), "Function funcA fqName mismatch"); // fqName remains the same
        assertTrue(skelA.containsKey(funcA), "Skeleton map should contain function funcA.");
        assertTrue(skelA.get(funcA).contains("def funcA():"));


        // Expected skeleton for the top-level function funcA
        var funcASummary = """
        def funcA(): …
        """;
        assertEquals(funcASummary.trim(), skelA.get(funcA).trim());


        // Expected skeleton for the top-level class A
        var classASummary = """
        class A: {
          def __init__(self): …
          def method1(self) -> None: …
          def method2(self, input_str: str, other_input: int = None) -> str: …
          def method3(self) -> Callable[[int], int]: …
          @staticmethod
          def method4(foo: float, bar: int) -> int: …
          def method5(self) -> None: …
          def method6(self) -> None: …
        }""".stripIndent(); // Using stripIndent for cleaner comparison
        assertEquals(classASummary.trim(), skelA.get(classA).trim(), "Class A skeleton mismatch");

        // test getClassesInFile
        assertEquals(Set.of(classA), analyzer.getClassesInFile(fileA), "getClassesInFile mismatch for file A");
        // non-py files are now filtered out during construction, so no need to check ignore.md

        // test getSummary - use fqName() method
        assertEquals(funcASummary.trim(), analyzer.getSkeleton(funcA.fqName()).get(), "getSkeleton mismatch for funcA");
    }


    /* -------------------- C# -------------------- */

//    @Test
//    void testCSharpInitializationAndSkeletons() {
//        TestProject project = createTestProject("testcode-cs", io.github.jbellis.brokk.analyzer.Language.C_SHARP); // Use Brokk's Language enum
//        IAnalyzer ana = Optional.ofNullable(project.getAnalyzer()).orElseThrow();
//        assertInstanceOf(CSharpAnalyzer.class, ana);
//
//        CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;
//        assertFalse(analyzer.isEmpty(), "Analyzer should have processed C# files");
//
//        ProjectFile fileA = new ProjectFile(project.getRoot(), "A.cs");
//        var skelA = analyzer.getSkeletons(fileA);
//        assertFalse(skelA.isEmpty());
//
//        var classA = CodeUnit.cls(fileA, "A");
//        assertTrue(skelA.containsKey(classA));
//        assertTrue(skelA.get(classA).trim().startsWith("public class A"));
//
//        var methodA = CodeUnit.fn(fileA, "MethodA");
//        assertTrue(skelA.containsKey(methodA));
//        assertTrue(skelA.get(methodA).contains("public void MethodA()"));
//
//        var fieldMyField = CodeUnit.field(fileA, "MyField");
//        assertTrue(skelA.containsKey(fieldMyField));
//        assertEquals("public int MyField;", skelA.get(fieldMyField).trim());
//
//        var propMyProp = CodeUnit.field(fileA, "MyProperty");
//        assertTrue(skelA.containsKey(propMyProp));
//        assertEquals("public string MyProperty { get; set; }",
//                     skelA.get(propMyProp).trim());
//
//        var ctor = CodeUnit.fn(fileA, "A.<init>");
//        assertTrue(skelA.containsKey(ctor));
//        assertTrue(skelA.get(ctor).contains("public A()"));
//
//        // Ensure attribute_list capture was ignored
//        boolean containsAttr = skelA.keySet().stream()
//                .anyMatch(cu -> cu.identifier().contains("attribute_list"));
//        assertFalse(containsAttr, "No @annotation captures expected");
//    }
}
