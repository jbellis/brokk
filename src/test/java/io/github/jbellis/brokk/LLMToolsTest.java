package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.SymbolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the LLMTools class.
 */
class LLMToolsTest {
    private LLMTools tools;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Write initial content to Test.java on disk.
        String initialContent = """
            package test;

            public class Test {

                public void sayHello(String name) {
                    System.out.println("Hello " + name);
                }

                // Some text we will replace
                // Another line to test partial replacements
            }
            """.stripIndent();
        Path testFile = tempDir.resolve("Test.java");
        Files.writeString(testFile, initialContent);

        // Create a TestContextManager with the temp directory and valid file "Test.java"
        IContextManager contextManager = new TestContextManager(tempDir, Set.of("Test.java", "ToDelete.java")) {
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
        };
        // Create the tools instance
        tools = new LLMTools(contextManager);
    }

    @Test
    void testReplaceFile() throws IOException {
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
        assertNull(validated.error(), "Expected no parse error for replaceFile request");

        var result = tools.executeTool(validated);
        assertEquals("SUCCESS", result.text(), "File replacement should succeed");
        assertEquals(LLMTools.Action.EDIT, result.getAction(), "Action should be EDIT");

        // Verify the file content on disk
        String fileContent = Files.readString(tempDir.resolve("Test.java"));
        assertEquals("hello world", fileContent, "File content should match the replaced text");
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
        assertNull(validated.error(), "Expected no parse error for replaceLines request");

        var result = tools.executeTool(validated);
        assertTrue(result.text().startsWith("Failed: "), "Expected failure result for unknown text");
        assertTrue(result.text().contains("No matching"), result.text());
        assertEquals(LLMTools.Action.NONE, result.getAction(), "Action should be NONE for failed tool execution");
    }

    @Test
    void testReplaceLines() throws IOException {
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
        assertNull(validated.error(), "Expected no parse error for replaceLines request");

        var result = tools.executeTool(validated);
        assertEquals("SUCCESS", result.text(), "Replacing lines should succeed");
        assertEquals(LLMTools.Action.EDIT, result.getAction(), "Action should be EDIT");

        String updated = Files.readString(tempDir.resolve("Test.java"));
        assertFalse(updated.contains(oldLines), "Old line should be removed from file content");
        assertTrue(updated.contains(newLines), "New line should appear in file content");
    }

    @Test
    void testReplaceFunction() throws IOException {
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
        assertNull(validated.error(), "Expected no parse error for replaceFunction request");

        var result = tools.executeTool(validated);
        assertEquals("SUCCESS", result.text(), "Replacing function body should succeed");
        assertEquals(LLMTools.Action.EDIT, result.getAction(), "Action should be EDIT");

        String updated = Files.readString(tempDir.resolve("Test.java"));
        assertTrue(updated.contains("Hi there, "), "New function body should appear in the updated file content");
        assertFalse(updated.contains("Hello "), "Old function body should be removed");
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException, EditBlock.AmbiguousMatchException, EditBlock.NoMatchException {
        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var tools = new LLMTools(ctx);

        // existing filename
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");
        
        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("{}") // Arguments don't matter for this test
                .build();
                
        var result = tools.replaceLines(request, ctx.toFile("fileA.txt"), "Original text", "Updated");
        assertEquals(LLMTools.Action.EDIT, result.getAction());
        
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"));

        // new filename
        result = tools.replaceFile(request, ctx.toFile("newFile.txt"), "Created content");
        assertEquals(LLMTools.Action.NEW, result.getAction());
        
        String newFileText = Files.readString(tempDir.resolve("newFile.txt"));
        assertEquals("Created content", newFileText);
    }

    @Test
    void testFailureToMatch(@TempDir Path tempDir) throws IOException {
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var f = ctx.toFile("fileA.txt");
        var tools = new LLMTools(ctx);
        
        var request = ToolExecutionRequest.builder()
                .name("replaceLines")
                .arguments("{}") // Arguments don't matter for this test
                .build();
                
        assertThrows(EditBlock.NoMatchException.class, () -> tools.replaceLines(request, f, "DDD", "EEE"));
    }

    @Test
    void testRemoveFile() throws IOException {
        // Create a file to test deletion
        Path fileToDelete = tempDir.resolve("ToDelete.java");
        Files.writeString(fileToDelete, "file to be deleted");

        // Verify it exists before we start
        assertTrue(Files.exists(fileToDelete));

        var request = ToolExecutionRequest.builder()
                .name("removeFile")
                .arguments("""
            {
              "filename": "ToDelete.java"
            }
            """)
                .build();

        var validated = tools.parseToolRequest(request);
        assertNull(validated.error(), "Expected no parse error for removeFile request");

        var result = tools.executeTool(validated);
        assertEquals("SUCCESS", result.text(), "File removal should succeed");
        assertEquals(LLMTools.Action.REMOVE, result.getAction(), "Action should be REMOVE");

        // Verify the file was actually deleted
        assertFalse(Files.exists(fileToDelete), "File should have been deleted");
    }

    @Test
    void testRemoveNonExistentFile() {
        // Try to remove a file that doesn't exist
        var request = ToolExecutionRequest.builder()
                .name("removeFile")
                .arguments("""
            {
              "filename": "NonExistent.java"
            }
            """)
                .build();

        var validated = tools.parseToolRequest(request);
        assertTrue(validated.error().contains("File not found"));
    }
}