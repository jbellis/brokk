package io.github.jbellis.brokk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class ContextManagerTest {

    @Test
    public void testParseAsGitDiffs() {
        String gitDiffs = """
            commit abc123def456
            Author: Jane Doe <jane@example.com>
            Date:   Mon Oct 16 10:00:00 2023 -0700
            
            First commit message
            With multiple lines
            That should be ignored
            
            commit def456abc789
            Author: John Smith <john@example.com>
            Date:   Mon Oct 16 11:00:00 2023 -0700
            
            Second commit message
            Also with details
            To ignore
            """;

        List<String> expected = List.of(
            "First commit message",
            "Second commit message"
        );

        assertEquals(expected, ContextManager.parseAsGitDiffs(gitDiffs));
    }

    @Test
    public void testParseAsGitDiffsWithNonDiffText() {
        String mixedText = """
            Some random text
            That isn't a git diff
            
            commit abc123def456
            Author: Jane Doe <jane@example.com>
            Date:   Mon Oct 16 10:00:00 2023 -0700
            
            The actual commit message
            
            More random text
            """;

        List<String> expected = List.of("The actual commit message");
        assertEquals(expected, ContextManager.parseAsGitDiffs(mixedText));
    }

    @Test
    public void testParseAsGitDiffsWithEmptyInput() {
        assertTrue(ContextManager.parseAsGitDiffs("").isEmpty());
        assertTrue(ContextManager.parseAsGitDiffs(null).isEmpty());
        assertTrue(ContextManager.parseAsGitDiffs("   ").isEmpty());
    }

    @Test
    public void testParseAsGitDiffsWithNoMatches() {
        String nonDiffText = """
            This is just some
            Random text with no
            Git diffs in it
            """;
        
        assertTrue(ContextManager.parseAsGitDiffs(nonDiffText).isEmpty());
    }
}
