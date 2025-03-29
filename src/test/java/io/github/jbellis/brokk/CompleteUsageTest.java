package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Test;
import org.msgpack.core.annotations.VisibleForTesting;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.github.jbellis.brokk.Completions.getShortClassName;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompleteUsageTest {
    @VisibleForTesting
    static List<CodeUnit> completeUsage(String input, IAnalyzer analyzer) {
        return Completions.completeClassesAndMembers(input, analyzer);
    }

    // A simple inline "mock" analyzer: no mocking library used.
    private static class MockAnalyzer implements IAnalyzer {
        private final ProjectFile mockFile = new ProjectFile(null, "MockFile.java");
        
        private final List<CodeUnit> allClasses = Stream.of(
                "a.b.Do",
                "a.b.Do$Re",
                "a.b.Do$Re$Sub",  // nested inside Re
                "x.y.Zz",
                "w.u.Zz",
                "test.CamelClass"
        ).map(name -> CodeUnit.cls(mockFile, name)).toList();

        private final Map<String, List<CodeUnit>> methodsMap = Map.of(
                "a.b.Do", Stream.of("a.b.Do.foo", "a.b.Do.bar").map(name -> CodeUnit.fn(mockFile, name)).toList(),
                "a.b.Do$Re", Stream.of("a.b.Do$Re.baz").map(name -> CodeUnit.fn(mockFile, name)).toList(),
                "a.b.Do$Re$Sub", Stream.of("a.b.Do$Re$Sub.qux").map(name -> CodeUnit.fn(mockFile, name)).toList(),
                "x.y.Zz", List.of(),
                "w.u.Zz", List.of(),
                "test.CamelClass", Stream.of("test.CamelClass.someMethod").map(name -> CodeUnit.fn(mockFile, name)).toList()
        );

        @Override
        public List<CodeUnit> getAllClasses() {
            return allClasses;
        }

        @Override
        public List<CodeUnit> getMembersInClass(String fqClass) {
            return methodsMap.getOrDefault(fqClass, List.of());
        }

        @Override
        public List<CodeUnit> getDefinitions(String pattern) {
            // Case-insensitive pattern matching for compatibility with Analyzer
            var regex = "^(?i)" + pattern + "$";

            // Find matching classes
            var matchingClasses = allClasses.stream()
                .filter(cu -> {
                    String className = cu.fqName();
                    String shortName = getShortClassName(className);
                    return shortName.matches(regex);
                })
                .toList();

            // Find matching methods
            var matchingMethods = methodsMap.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .filter(cu -> {
                    String methodName = cu.fqName();
                    // Extract just the method name (after last dot)
                    String simpleName = methodName.substring(methodName.lastIndexOf('.') + 1);
                    return simpleName.matches(regex);
                })
                .toList();

            // Fields not tested

            // Combine results
            return Stream.concat(matchingClasses.stream(), matchingMethods.stream())
                .toList();
        }
    }
    
    // Helper to extract values for easy assertion
    private static Set<String> toValues(List<CodeUnit> candidates) {
        return candidates.stream()
               .map(CodeUnit::fqName)
               .collect(Collectors.toSet());
    }
    private static Set<String> toShortValues(List<CodeUnit> candidates) {
        return candidates.stream()
                .map(CodeUnit::name)
                .collect(Collectors.toSet());
    }

    @Test
    public void testGetShortClassName() {
        assertEquals("C", getShortClassName("a.b.C"));
        assertEquals("C", getShortClassName("a.b.C."));
        assertEquals("C", getShortClassName("C"));
        assertEquals("C$D", getShortClassName("a.b.C$D"));
        assertEquals("C$D", getShortClassName("a.b.C$D."));

        // Extra edge cases
        assertEquals("C$D$E", getShortClassName("a.b.C$D$E"));
        assertEquals("C", getShortClassName("C."));
        assertEquals("$D", getShortClassName("$D"));
        assertEquals("C$D", getShortClassName("C$D"));
    }

    @Test
    public void testUnqualifiedSingleLetter() {
        var mock = new MockAnalyzer();

        // Input "d" -> we want it to match "a.b.Do"
        // Because "Do" simple name starts with 'D'
        var completions = completeUsage("d", mock);

        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub", "test.CamelClass.someMethod"), values);
    }

    @Test
    public void testUnqualifiedR() {
        var mock = new MockAnalyzer();
        // Input "r" -> user wants to find "a.b.Do$Re" by partial name "Re"
        var completions = completeUsage("r", mock);
        var values = toValues(completions);
        assertEquals(Set.of("a.b.Do$Re", "a.b.Do$Re$Sub", "a.b.Do.bar"), values);
    }

    @Test
    public void testNestedClassRe() {
        var mock = new MockAnalyzer();
        var completions = completeUsage("Re", mock);
        var values = toValues(completions);

        assertEquals(Set.of("a.b.Do$Re", "a.b.Do$Re$Sub"), values);
    }

    @Test
    public void testCamelCaseCompletion() {
        var mock = new MockAnalyzer();
        // Input "CC" -> should match "test.CamelClass" due to camel case matching
        var completions = completeUsage("CC", mock);
        var values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);

        completions = completeUsage("cam", mock);
        values = toValues(completions);
        assertEquals(Set.of("test.CamelClass"), values);
    }

    @Test
    public void testEmptyInput() {
        var mock = new MockAnalyzer();
        // Input "" => propose everything
        var completions = completeUsage("", mock);
        var values = toValues(completions);
        
        assertEquals(
            Set.of("a.b.Do", "a.b.Do$Re", "a.b.Do$Re$Sub", "x.y.Zz", "w.u.Zz", "test.CamelClass"),
            values
        );
    }
    
    @Test
    public void testShortNameCompletions() {
        var mock = new MockAnalyzer();

        var completions = Completions.completeClassesAndMembers("Do", mock);
        assertEquals(Set.of("Do", "Do$Re", "Do$Re$Sub"), toShortValues(completions));
    }
}
