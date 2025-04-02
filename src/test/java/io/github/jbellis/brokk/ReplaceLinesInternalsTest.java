package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaceLinesInternalsTest {
    static class TestConsoleIO implements IConsoleIO {
        private final StringBuilder outputLog = new StringBuilder();
        private final StringBuilder errorLog = new StringBuilder();

        @Override
        public void actionOutput(String text) {
            outputLog.append(text).append("\n");
        }

        @Override
        public void toolErrorRaw(String msg) {
            errorLog.append(msg).append("\n");
        }

        @Override
        public void llmOutput(String token) {
            // not needed for these tests
        }

        public String getOutputLog() {
            return outputLog.toString();
        }

        public String getErrorLog() {
            return errorLog.toString();
        }
    }

    @Test
    void testReplaceSimpleExact() {
        String original = "This is a sample text.\nAnother line\nYet another line.\n";
        String search = "Another line\n";
        String replace = "Changed line\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = "This is a sample text.\nChanged line\nYet another line.\n";
        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringWhitespace() {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        String search = """
                line2
                    line3
                """;
        String replace = """
                new_line2
                    new_line3
                """;
        String expected = """
                line1
                    new_line2
                    new_line3
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testDeletionIgnoringWhitespace() {
        String original = """
                One
                  Two
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, "Two\n", "");
        assertEquals("One\n", updated);
    }

    @Test
    void testReplaceFirstOccurrenceOnly() {
        String original = """
                line1
                line2
                line1
                line3
                """;
        String search = "line1\n";
        String replace = "new_line\n";
        String expected = """
                new_line
                line2
                line1
                line3
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testEmptySearchCreatesOrAppends() {
        // If beforeText is empty, treat it as create/append
        String original = "one\ntwo\n";
        String search = "";
        String replace = "new content\n";
        String expected = "one\ntwo\nnew content\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException, EditBlock.NoMatchException {
        TestConsoleIO io = new TestConsoleIO();

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var tools = new LLMTools(ctx);

        // existing filename
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");
        tools.replaceLines(ctx.toFile("fileA.txt"), "Original text", "Updated");
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"));

        // new filename
        tools.replaceLines(ctx.toFile("newFile.txt"), "", "Created content");
        String newFileText = Files.readString(tempDir.resolve("newFile.txt"));
        assertEquals("Created content\n", newFileText);

        // no errors
        assertTrue(io.getErrorLog().isEmpty(), "No error expected");
    }

    /**
     * LLM likes to start blocks without the leading whitespace sometimes
     */
    @Test
    void testReplacePartWithMissingLeadingWhitespace() {
        String original = """
                line1
                    line2
                    line3
                line4
                """.stripIndent();

        // We'll omit some leading whitespace in the beforeText block
        String search = """
                line2
                    line3
                """.stripIndent();
        String replace = """
                NEW_line2
                    NEW_line3
                """.stripIndent();

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);

        String expected = """
                line1
                    NEW_line2
                    NEW_line3
                line4
                """.stripIndent();

        assertEquals(expected, updated);
    }

    /**
     * Test blank line with missing leading whitespace in beforeText.
     * (Similar to python test_replace_part_with_missing_leading_whitespace_including_blank_line)
     */
    @Test
    void testReplaceIgnoringWhitespaceIncludingBlankLine() {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                
                  line2
                """;
        String replace = """
                
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);

        // The beforeText block basically tries to match line2 ignoring some whitespace and skipping a blank line
        // We expect line2 -> replaced_line2, with same leading indentation as original (4 spaces).
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();

        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringTrailingWhitespace() {
        String original = """
                line1
                    line2  
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                  line2
                """;
        String replace = """
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();
        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringInternalWhitespace() {
        String original = """
                line1
                    a   b 
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = """
                  a b
                """;
        String replace = """
                  replaced_line2
                """;

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();
        assertEquals(expected, updated);
    }

    /**
     * Tests that if beforeText block lines are already in the filename, but user tries the same "afterText",
     * we do not break anything. We can't confirm the "already replaced" scenario fully
     * but we can ensure no weird edge crash.
     */
    @Test
    void testApplyFuzzySearchReplaceIfReplaceAlreadyPresent() {
        String original = """
                line1
                line2
                line3
                """;
        // Suppose the "beforeText" is line2 => line2
        // but "afterText" is line2 => line2 (the same text).
        // The code should see a perfect match and effectively do no change but not crash.
        String search = "line2\n";
        String replace = "line2\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        // We expect no change
        assertEquals(original, updated);
    }

    @Test
    void testFailureToMatch(@TempDir Path tempDir) throws IOException {
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var f = ctx.toFile("fileA.txt");
        var tools = new LLMTools(ctx);
        assertThrows(EditBlock.NoMatchException.class, () -> tools.replaceLines(f, "DDD", "EEE"));
    }

    @Test
    void testEmptySearchOnEmptyFile() {
        String original = "";
        String search = "";  // empty
        String replace = "initial content\n";

        String updated = EditBlock.replaceMostSimilarChunk(original, search, replace);
        assertEquals("initial content\n", updated);
    }
}
