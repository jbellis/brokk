package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EditBlockTest {
    static class TestContextManager implements IContextManager {
        private final Path root;
        private final Set<RepoFile> validFiles;

        public TestContextManager(Path root, Set<String> validFiles) {
            this.root = root;
            this.validFiles = validFiles.stream().map(f -> new RepoFile(root, Path.of(f))).collect(Collectors.toSet());
        }

        @Override
        public RepoFile toFile(String relName) {
            return new RepoFile(root, Path.of(relName));
        }

        @Override
        public Set<RepoFile> getEditableFiles() {
            return validFiles;
        }

        @Override
        public void addFiles(Collection<RepoFile> path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void moveToHistory(List<ChatMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<RepoFile> findMissingFileMentions(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<RepoFile> confirmAddRequestedFiles(Set<RepoFile> mentioned) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextManager.OperationResult runBuild() {
            throw new UnsupportedOperationException();
        }
    }

    static class TestConsoleIO implements IConsoleIO {
        private final StringBuilder outputLog = new StringBuilder();
        private final StringBuilder errorLog = new StringBuilder();

        @Override
        public void toolOutput(String text) {
            outputLog.append(text).append("\n");
        }

        @Override
        public void toolErrorRaw(String msg) {
            errorLog.append(msg).append("\n");
        }

        @Override
        public boolean confirmAsk(String msg) {
            return false;
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

    
    // ----------------------------------------------------
    // Helper methods
    // ----------------------------------------------------
    private EditBlock.SearchReplaceBlock[] parseBlocks(String fullResponse, Set<String> validFilenames) {
        var files = validFilenames.stream().map(f -> new RepoFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var blocks = EditBlock.findOriginalUpdateBlocks(fullResponse, files).blocks();
        return blocks.toArray(new EditBlock.SearchReplaceBlock[0]);
    }

    private String fuzzyReplace(String original, String search, String replace) {
        return EditBlock.doReplace(original, search, replace);
    }


    // ----------------------------------------------------
    // Original tests from the question snippet
    // ----------------------------------------------------

    @Test
    void testFindOriginalUpdateBlocksSimple() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                Two
                =======
                Tooooo
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("Two\n", blocks[0].beforeText());
        assertEquals("Tooooo\n", blocks[0].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksMultipleSameFile() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE

                ...

                <<<<<<< SEARCH
                three
                =======
                four
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(2, blocks.length);
        // first block
        assertEquals("foo.txt", blocks[0].filename().toString());
        assertEquals("one\n", blocks[0].beforeText());
        assertEquals("two\n", blocks[0].afterText());
        // second block
        assertEquals("foo.txt", blocks[1].filename().toString());
        assertEquals("three\n", blocks[1].beforeText());
        assertEquals("four\n", blocks[1].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksNoFinalNewline() {
        String edit = """
                aider/coder.py
                <<<<<<< SEARCH
                lineA
                =======
                lineB
                >>>>>>> REPLACE

                aider/coder.py
                <<<<<<< SEARCH
                lineC
                =======
                lineD
                >>>>>>> REPLACE"""; // no newline at the end

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("aider/coder.py"));
        assertEquals(2, blocks.length);
        assertEquals("lineA\n", blocks[0].beforeText());
        assertEquals("lineB\n", blocks[0].afterText());
        assertEquals("lineC\n", blocks[1].beforeText());
        assertEquals("lineD\n", blocks[1].afterText());
    }

    @Test
    void testFindOriginalUpdateBlocksNewFileThenExisting() {
        String edit = """
                Here's the change:

                filename/to/a/file2.txt
                ```python
                <<<<<<< SEARCH
                =======
                three
                >>>>>>> REPLACE
                ```

                another change

                filename/to/a/file1.txt
                ```python
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```

                Hope you like it!
                """;

        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("filename/to/a/file1.txt"));
        assertEquals(2, blocks.length);
        assertEquals("filename/to/a/file2.txt", blocks[0].filename());
        assertEquals("", blocks[0].beforeText().trim());
        assertEquals("three", blocks[0].afterText().trim());
        assertEquals("filename/to/a/file1.txt", blocks[1].filename());
        assertEquals("one", blocks[1].beforeText().trim());
        assertEquals("two", blocks[1].afterText().trim());
    }

    @Test
    void testReplaceSimpleExact() {
        String original = "This is a sample text.\nAnother line\nYet another line.\n";
        String search = "Another line\n";
        String replace = "Changed line\n";

        String updated = fuzzyReplace(original, search, replace);
        String expected = "This is a sample text.\nChanged line\nYet another line.\n";
        assertEquals(expected, updated);
    }

    @Test
    void testReplaceIgnoringLeadingWhitespace() {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        String search = "line2\n    line3\n";
        String replace = "new_line2\n    new_line3\n";
        String expected = """
                line1
                    new_line2
                    new_line3
                """.stripIndent();

        String updated = fuzzyReplace(original, search, replace);
        assertEquals(expected, updated);
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

        String updated = fuzzyReplace(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testEmptySearchCreatesOrAppends() {
        // If beforeText is empty, treat it as create/append
        String original = "one\ntwo\n";
        String search = "";
        String replace = "new content\n";
        String expected = "one\ntwo\nnew content\n";

        String updated = fuzzyReplace(original, search, replace);
        assertEquals(expected, updated);
    }

    @Test
    void testApplyEditsCreatesNewFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Original text\n");

        String response = """
                fileA.txt
                <<<<<<< SEARCH
                Original text
                =======
                Updated
                >>>>>>> REPLACE

                newFile.txt
                <<<<<<< SEARCH
                =======
                Created content
                >>>>>>> REPLACE
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        EditBlock.applyEditBlocks(ctx, io, blocks);

        // existing filename
        String actualA = Files.readString(existingFile);
        assertTrue(actualA.contains("Updated"));

        // new filename
        Path newFile = tempDir.resolve("newFile.txt");
        String newFileText = Files.readString(newFile);
        assertEquals("Created content\n", newFileText);

        // no errors
        assertTrue(io.getErrorLog().isEmpty(), "No error expected");
    }

    @Test
    void testApplyEditsFailsForUnknownFile(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();

        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "Line X\n");

        String response = """
                unknownFile.txt
                <<<<<<< SEARCH
                something
                =======
                replacement
                >>>>>>> REPLACE
                """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var failed = EditBlock.applyEditBlocks(ctx, io, blocks);

        assertNotEquals(List.of(), failed);
    }


    // ----------------------------------------------------
    // Additional Tests (cover corner cases from Python)
    // ----------------------------------------------------

    /**
     * Test partial or fuzzy leading whitespace issues with multiple lines,
     * verifying that indentation differences are handled.
     * (Similar to python's test_replace_part_with_missing_varied_leading_whitespace)
     */
    @Test
    void testReplacePartWithMissingVariedLeadingWhitespace() {
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

        String updated = EditBlock.doReplace(original, search, replace);

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
    void testReplacePartWithMissingLeadingWhitespaceIncludingBlankLine() {
        String original = """
                line1
                    line2
                    line3
                """.stripIndent();
        // Insert a blank line in the beforeText, plus incomplete indentation
        String search = "\n  line2\n";
        String replace = "\n  replaced_line2\n";

        String updated = fuzzyReplace(original, search, replace);

        // The beforeText block basically tries to match line2 ignoring some whitespace and skipping a blank line
        // We expect line2 -> replaced_line2, with same leading indentation as original (4 spaces).
        String expected = """
                line1
                    replaced_line2
                    line3
                """.stripIndent();

        assertEquals(expected, updated);
    }

    /**
     * Check that we can parse an unclosed block and fail gracefully.
     * (Similar to python test_find_original_update_blocks_unclosed)
     */
    @Test
    void testFindOriginalUpdateBlocksUnclosed() {
        String edit = """
                Here's the change:

                ```text
                foo.txt
                <<<<<<< SEARCH
                Two
                =======
                Tooooo

                oops! no trailing >>>>>> REPLACE
                """;

        var files = Set.of("foo.txt").stream().map(f -> new RepoFile(Path.of("/"), Path.of(f))).collect(Collectors.toSet());
        var result = EditBlock.findOriginalUpdateBlocks(edit, files);
        assertNotEquals(null, result.parseError());
    }

    /**
     * Check that we parse blocks that have no filename at all (which is a parse error if we
     * haven't yet established a 'currentFile').
     */
    @Test
    void testFindOriginalUpdateBlocksMissingFilename(@TempDir Path tempDir) {
        String edit = """
                Here's the change:

                ```text
                <<<<<<< SEARCH
                Two
                =======
                Tooooo
                >>>>>>> REPLACE
                ```
                """;

        // Expect an exception about missing filename
        TestConsoleIO io = new TestConsoleIO();
        TestContextManager ctx = new TestContextManager(tempDir, Set.of());
        var blocks = EditBlock.findOriginalUpdateBlocks(edit, ctx.getEditableFiles()).blocks();
        var failed = EditBlock.applyEditBlocks(ctx, io, blocks);;
        assertNotEquals(List.of(), failed);
    }

    /**
     * Test detection of a possible "mangled" or fuzzy filename match.
     */
    @Test
    void testFindOriginalUpdateBlocksFuzzyFilename() {
        String edit = """
                Here's the change:
                fOo.TXt
                <<<<<<< SEARCH
                alpha
                =======
                beta
                >>>>>>> REPLACE
                """;

        // We only have "foo.txt" in the valid set => fuzzy match
        EditBlock.SearchReplaceBlock[] blocks = parseBlocks(edit, Set.of("foo.txt"));
        assertEquals(1, blocks.length);
        assertEquals("foo.txt", blocks[0].filename());
        assertEquals("alpha\n", blocks[0].beforeText());
        assertEquals("beta\n", blocks[0].afterText());
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

        String updated = fuzzyReplace(original, search, replace);
        // We expect no change
        assertEquals(original, updated);
    }

    @Test
    void testDidYouMeanSuggestionsOnFailure(@TempDir Path tempDir) throws IOException {
        TestConsoleIO io = new TestConsoleIO();
        Path existingFile = tempDir.resolve("fileA.txt");
        Files.writeString(existingFile, "AAA\nBBB\nCCC\n");

        // The "beforeText" is too different from anything in the file
        String response = """
            fileA.txt
            <<<<<<< SEARCH
            something totally unknown
            =======
            replacement
            >>>>>>> REPLACE
            """;

        TestContextManager ctx = new TestContextManager(tempDir, Set.of("fileA.txt"));
        var blocks = EditBlock.findOriginalUpdateBlocks(response, ctx.getEditableFiles()).blocks();
        var failed = EditBlock.applyEditBlocks(ctx, io, blocks);;
        assertNotEquals(List.of(), failed);
    }

    /**
     * Test that if "beforeText" is empty and the filename also is empty,
     * we handle it gracefully (like "create a new filename or append").
     */
    @Test
    void testEmptySearchOnEmptyFile() {
        String original = "";
        String search = "";  // empty
        String replace = "initial content\n";

        String updated = fuzzyReplace(original, search, replace);
        assertEquals("initial content\n", updated);
    }
}
