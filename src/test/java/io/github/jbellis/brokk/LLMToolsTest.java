package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

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
                    public java.util.Optional<FunctionLocation> findFunctionLocation(String fqMethodName,
                                                                                     List<String> paramNames) {
                        if (fqMethodName.equals("Test.sayHello")
                                && paramNames.equals(List.of("name"))) {
                            // We'll say the method is from line 4 to line 6 (just a small range)
                            String methodText = """
                                public void sayHello(String name) {
                                    System.out.println("Hello " + name);
                                }""";
                            return java.util.Optional.of(
                                    new FunctionLocation(toFile("Test.java"), 4, 6, methodText)
                            );
                        }
                        return java.util.Optional.empty();
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
                    {"filename":"Test.java","text":"hello world"}
                    """)
                .build();
        var result = tools.execute(request);
        assertTrue(result.output().startsWith("Successfully replaced file"),
                   "Expected success message, got: " + result.output());

        // Verify the file actually changed in memory
        assertEquals("hello world", inMemoryFileContents.get("Test.java"),
                     "File content should match the replaced text");
    }

    @Test
    void testReplaceLinesFail() {
        // Attempt partial match that can't be found
        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("""
                    {"filename":"Test.java","oldText":"nonexistent","newText":"???"}
                    """)
                .build();
        var result = tools.execute(request);
        assertTrue(result.output().contains("ERROR:"),
                   "Should fail because 'nonexistent' not found. Output: " + result.output());
    }

    @Test
    void testReplaceLines() {
        // oldText to find in the file:
        String oldText = "// Some text we will replace";
        String newText = "// Replaced line successfully";

        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("""
                    {
                      "filename": "Test.java",
                      "oldText": "%s",
                      "newText": "%s"
                    }
                    """.formatted(oldText, newText))
                .build();

        var result = tools.execute(request);
        assertTrue(result.output().startsWith("Successfully replaced text in file"),
                   "Expected success, got: " + result.output());

        // Verify that the file now contains the new text and not the old
        String updated = inMemoryFileContents.get("Test.java");
        assertFalse(updated.contains(oldText),
                    "Old text should be removed from file content");
        assertTrue(updated.contains(newText),
                   "New text should appear in file content");
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

        var result = tools.execute(request);
        assertTrue(result.output().contains("Successfully replaced function Test.sayHello"),
                   "Should indicate success. Output: " + result.output());

        // Verify file was updated in memory
        String updated = inMemoryFileContents.get("Test.java");
        assertTrue(updated.contains("Hi there, "),
                   "New function body should appear in the updated file content");
        assertFalse(updated.contains("Hello "),
                    "Old function body should be removed");
    }
}
