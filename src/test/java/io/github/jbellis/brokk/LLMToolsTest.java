package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SymbolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the LLMTools class.
 */
class LLMToolsTest {
    private LLMTools tools;
    private final Map<String, String> inMemoryFileContents = new HashMap<>();

    @BeforeEach
    void setUp() {
        // We store content for each file in a Map, keyed by its name (e.g. "Test.java").
        inMemoryFileContents.clear();
        inMemoryFileContents.put("Test.java", """
            package test;

            public class Test {

                public void sayHello(String name) {
                    System.out.println("Hello " + name);
                }

                // Some text we will replace
                // Another line to test partial replacements
            }
            """);

        // Minimal in-memory ProjectFile that reads/writes from the map above
        IContextManager contextManager = new IContextManager() {
            @Override
            public ProjectFile toFile(String path) {
                // We'll store everything by the short filename as key
                // e.g. "Test.java"
                return new ProjectFile(Paths.get("/tmp"), path) {
                    @Override
                    public String read() throws IOException {
                        var content = inMemoryFileContents.get(path);
                        if (content == null) {
                            throw new IOException("No such file in memory: " + path);
                        }
                        return content;
                    }

                    @Override
                    public void write(String st) throws IOException {
                        if (!inMemoryFileContents.containsKey(path)) {
                            throw new IOException("File not recognized as editable: " + path);
                        }
                        inMemoryFileContents.put(path, st);
                    }
                };
            }

            @Override
            public Set<ProjectFile> getEditableFiles() {
                // For simplicity, allow editing only "Test.java"
                return Set.of(toFile("Test.java"));
            }

            @Override
            public IAnalyzer getAnalyzer() {
                // Return a stub that only knows about "Test.sayHello" with param ["name"]
                return new IAnalyzer() {
                    @Override
                    public FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
                        if (fqMethodName.equals("Test.sayHello")
                                && paramNames.equals(List.of("name"))) {
                            // We'll say the method is from line 4 to line 6 (just a small range)
                            String methodText = """
                                public void sayHello(String name) {
                                    System.out.println("Hello " + name);
                                }""";
                            return new FunctionLocation(toFile("Test.java"), 4, 6, methodText);
                        }

                        throw new SymbolNotFoundException("not found");
                    }
                };
            }

            // The rest of the interface methods are unused test stubs:
        };

        // Create the tools instance
        tools = new LLMTools(contextManager);
    }

    @Test
    void testReplaceFile() {
        var request = ToolExecutionRequest.builder()
                .name("replaceFile")
                .arguments("""
            {
              "filename": "Test.java",
              "text": "hello world"
            }
            """)
                .build();

        var validated = tools.parseToolRequest(request);
        assertNull(validated.error(),
                   "Expected no parse error for replaceFile request");

        var result = tools.executeTool(validated);
        assertEquals("Success", result.text(),
                     "File replacement should succeed");

        assertEquals("hello world", inMemoryFileContents.get("Test.java"),
                     "File content should match the replaced text");
    }

    @Test
    void testReplaceLinesFail() {
        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("""
            {
              "filename": "Test.java",
              "oldLines": "nonexistent",
              "newLines": "???"
            }
            """)
                .build();

        var validated = tools.parseToolRequest(request);
        assertNull(validated.error(),
                   "Expected no parse error for replaceLines request");

        var result = tools.executeTool(validated);
        assertTrue(result.text().startsWith("Failed to apply: "), "Expected failure result for unknown text");
        assertTrue(result.text().contains("No matching location"), result.text());
    }

    @Test
    void testReplaceLines() {
        String oldLines = "// Some text we will replace";
        String newLines = "// Replaced line successfully";

        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("""
            {
              "filename": "Test.java",
              "oldLines": "%s",
              "newLines": "%s"
            }
            """.formatted(oldLines, newLines))
                .build();

        var validated = tools.parseToolRequest(request);
        assertNull(validated.error(),
                   "Expected no parse error for replaceLines request");

        var result = tools.executeTool(validated);
        assertEquals("Success", result.text(),
                     "Replacing lines should succeed");

        String updated = inMemoryFileContents.get("Test.java");
        assertFalse(updated.contains(oldLines),
                    "Old line should be removed from file content");
        assertTrue(updated.contains(newLines),
                   "New line should appear in file content");
    }

    @Test
    void testReplaceFunction() {
        var request = ToolExecutionRequest.builder()
                .name("replaceFunction")
                .arguments("""
            {
              "fullyQualifiedFunctionName": "Test.sayHello",
              "functionParameterNames": ["name"],
              "newFunctionBody": "public void sayHello(String name) {\\n    System.out.println(\\"Hi there, \\" + name + \\"!!!\\");\\n}"
            }
            """)
                .build();

        var validated = tools.parseToolRequest(request);
        assertNull(validated.error(),
                   "Expected no parse error for replaceFunction request");

        var result = tools.executeTool(validated);
        assertEquals("Success", result.text(),
                     "Replacing function body should succeed");

        String updated = inMemoryFileContents.get("Test.java");
        assertTrue(updated.contains("Hi there, "),
                   "New function body should appear in the updated file content");
        assertFalse(updated.contains("Hello "),
                    "Old function body should be removed");
    }
}
