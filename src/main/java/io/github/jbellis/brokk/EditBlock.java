package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.min;

/**
 * Utility for extracting and applying before/after search-replace blocks in content.
 */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

    // Pattern for the start of a search block, capturing the filename
    private static final Pattern HEAD = Pattern.compile("^\\s*<{5,9} SEARCH\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the divider, capturing the filename
    private static final Pattern DIVIDER = Pattern.compile("^\\s*={5,9}\\s*(\\S+?)\\s*$", Pattern.MULTILINE);
    // Pattern for the end of a replace block, capturing the filename
    private static final Pattern REPLACE = Pattern.compile("^\\s*>{5,9} REPLACE\\s*(\\S+?)\\s*$", Pattern.MULTILINE);

    /**
     * Helper that returns the first code block found between triple backticks.
     * Returns an empty string if none found.
     */
    static String extractCodeFromTripleBackticks(String text) {
        // Pattern: ``` some code ```
        var matcher = Pattern.compile(
                "```(.*?)```",
                Pattern.DOTALL
        ).matcher(text);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    public enum EditBlockFailureReason {
        FILE_NOT_FOUND,
        NO_MATCH,
        AMBIGUOUS_MATCH,
        NO_FILENAME,
        IO_ERROR
    }

    public record EditResult(Map<ProjectFile, String> originalContents, List<FailedBlock> failedBlocks) { }

    public record FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) { }

    // -- Exceptions for file resolution --
    /** Thrown when a filename provided by the LLM cannot be uniquely resolved. */
    public static class SymbolNotFoundException extends Exception {
        public SymbolNotFoundException(String message) {
            super(message);
        }
    }

    /** Thrown when a filename provided by the LLM matches multiple files. */
    public static class SymbolAmbiguousException extends Exception {
        public SymbolAmbiguousException(String message) {
            super(message);
        }
    }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks (or shell blocks, etc.) and apply them.
     */
    public static EditResult applyEditBlocks(IContextManager contextManager, IConsoleIO io, Collection<SearchReplaceBlock> blocks) {
        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        List<SearchReplaceBlock> succeeded = new ArrayList<>();

        // Track original file contents before any changes
        Map<ProjectFile, String> changedFiles = new HashMap<>();

        for (SearchReplaceBlock block : blocks) {
            // Shell commands remain unchanged
            if (block.shellCommand() != null) {
                io.systemOutput("Shell command from LLM:\n" + block.shellCommand());
                continue;
            }

            // 1. Resolve the filename
            ProjectFile file;
            boolean isCreateNew = block.beforeText().trim().isEmpty();
            try {
                if (block.filename() == null || block.filename().isBlank()) {
                    throw new SymbolNotFoundException("Block is missing filename");
                }
                file = resolveProjectFile(contextManager, block.filename(), isCreateNew);
            } catch (SymbolNotFoundException | SymbolAmbiguousException e) {
                logger.debug("File resolution failed for block [{}]: {}", block.filename(), e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.FILE_NOT_FOUND));
                continue; // Skip to the next block
            }

            // 2. Apply the edit using replaceInFile
            try {
                // Save original content before attempting change
                if (!changedFiles.containsKey(file)) {
                    changedFiles.put(file, file.exists() ? file.read() : "");
                }

                // Perform the replacement
                replaceInFile(file, block.beforeText(), block.afterText());

                // If successful, add to succeeded list
                succeeded.add(block);
                if (isCreateNew) {
                    try {
                        contextManager.addToGit(List.of(file));
                        io.systemOutput("Added to git " + file);
                    } catch (IOException e) {
                        io.systemOutput("Failed to git add " + file + ": " + e.getMessage());
                        // Continue anyway, git add failure is not fatal here
                    }
                }
            } catch (NoMatchException | AmbiguousMatchException e) {
                logger.debug("Edit application failed for file [{}] {}: {}", file, e.getClass().getSimpleName(), e.getMessage());
                failed.add(new FailedBlock(block, e instanceof  NoMatchException ? EditBlockFailureReason.NO_MATCH : EditBlockFailureReason.AMBIGUOUS_MATCH));
                // Restore original content if we saved it and the edit failed
                if (changedFiles.containsKey(file)) {
                    try {
                        file.write(changedFiles.get(file));
                    } catch (IOException writeErr) {
                        io.toolError("Failed restoring original content for " + file + " after failed edit: " + writeErr.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.warn("IO error applying edit to file [{}]: {}", file, e.getMessage());
                io.toolError("Failed reading/writing " + file + ": " + e.getMessage());
                failed.add(new FailedBlock(block, EditBlockFailureReason.IO_ERROR));
                // Restore original content if possible
                if (changedFiles.containsKey(file)) {
                    try {
                        file.write(changedFiles.get(file));
                    } catch (IOException writeErr) {
                        io.toolError("Failed restoring original content for " + file + " after IO error: " + writeErr.getMessage());
                    }
                }
             }
         } // End of for loop

         if (!succeeded.isEmpty()) {
            io.llmOutput("\n" + succeeded.size() + " SEARCH/REPLACE blocks applied.");
        }
        return new EditResult(changedFiles, failed);
    }

    /**
     * Simple record storing the parts of a search-replace block.
     * If {@code filename} is non-null, then this block corresponds to a filename’s
     * search/replace. If {@code shellCommand} is non-null, then this block
     * corresponds to shell code that should be executed, not applied to a filename.
     */
    public record SearchReplaceBlock(String filename, String beforeText, String afterText, String shellCommand) {
        @Override
        public String toString() {
            if (shellCommand != null) {
                return "```shell\n" + shellCommand + "\n```";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("```\n");
            if (filename != null) {
                sb.append(filename);
            }
            sb.append("<<<<<<< SEARCH\n");
            sb.append(beforeText);
            if (!beforeText.endsWith("\n")) {
                sb.append("\n");
            }
            if (filename != null) {
                sb.append(filename);
            }
            sb.append("=======\n");
            sb.append(afterText);
            if (!afterText.endsWith("\n")) {
                sb.append("\n");
            }
            if (filename != null) {
                sb.append(filename);
            }
            sb.append(">>>>>>> REPLACE\n");
            sb.append("```");

            return sb.toString();
        }
    }

    public record ParseResult(List<SearchReplaceBlock> blocks, String parseError) { }

    // Default fence to match triple-backtick usage, e.g. ``` ... ```
    static final String[] DEFAULT_FENCE = {"```", "```"};

    private EditBlock() {
        // utility class
    }

    /**
     * Replace the first occurrence of `beforeText` lines with `afterText` lines in the given file.
     * Throws NoMatchException if `beforeText` is not found in the file content.
     * Throws AmbiguousMatchException if more than one match is found.
     */
    public static void replaceInFile(ProjectFile file, String beforeText, String afterText)
            throws IOException, NoMatchException, AmbiguousMatchException
    {
        String original = file.exists() ? file.read() : "";
        String updated = replaceMostSimilarChunk(original, beforeText, afterText);
        file.write(updated);
    }

    /**
     * Custom exception thrown when no matching location is found in the file.
     */
    public static class NoMatchException extends Exception {
        public NoMatchException(String msg) {
            super(msg);
        }
    }

    /**
     * Thrown when more than one matching location is found.
     */
    public static class AmbiguousMatchException extends Exception {
        public AmbiguousMatchException(String message) {
            super(message);
        }
    }

    /**
     * Parses the given content into ParseResult
     */
    public static ParseResult parseUpdateBlocks(String content) {
        List<SearchReplaceBlock> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            Matcher headMatcher = HEAD.matcher(lines[i]);

            // Check if it's a filename <<<<<<< SEARCH block
            if (headMatcher.matches()) {
                String currentFilename = headMatcher.group(1).trim();
                assert !currentFilename.isBlank(); // regexp construction ensures this

                // gather "before" lines until filename ======= divider
                i++;
                List<String> beforeLines = new ArrayList<>();
                Matcher dividerMatcher;
                while (i < lines.length) {
                    dividerMatcher = DIVIDER.matcher(lines[i]);
                    if (dividerMatcher.matches() && dividerMatcher.group(1).trim().equals(currentFilename)) {
                        break; // Found the divider
                    }
                    beforeLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new ParseResult(blocks, "Expected '%s =======' divider after `%s <<<<<<< SEARCH`".formatted(currentFilename, currentFilename));
                }

                // gather "after" lines until REPLACE marker
                i++; // skip the ======= line
                List<String> afterLines = new ArrayList<>();
                Matcher replaceMatcher = null;
                while (i < lines.length)
                {
                    replaceMatcher = REPLACE.matcher(lines[i]);
                    if (replaceMatcher.matches() && replaceMatcher.group(1).trim().equals(currentFilename)) {
                        break; // Found the end marker
                    }
                    afterLines.add(lines[i]);
                    i++;
                }
                if (i >= lines.length) {
                    return new ParseResult(blocks, "Expected '%s >>>>>>> REPLACE' marker after `%s <<<<<<< SEARCH`".formatted(currentFilename, currentFilename));
                }

                var beforeJoined = String.join("\n", beforeLines);
                var afterJoined = String.join("\n", afterLines);

                // Append trailing newline if not present
                if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                    beforeJoined += "\n";
                }
                if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                    afterJoined += "\n";
                }

                blocks.add(new SearchReplaceBlock(currentFilename, beforeJoined, afterJoined, null));
            }

            i++;
        }

        return new ParseResult(blocks, null);
    }

    /**
     * Attempts perfect/whitespace replacements, then tries "...", then fuzzy.
     * Returns the post-replacement content, or null if no match found.
     * Throws AmbiguousMatchException if multiple matches are found.
     */
    static String replaceMostSimilarChunk(String content, String target, String replace)
            throws AmbiguousMatchException, NoMatchException
    {
        // 1) prep for line-based matching
        ContentLines originalCL = prep(content);
        ContentLines targetCl = prep(target);
        ContentLines replaceCL = prep(replace);

        // 2) perfect or whitespace approach
        String attempt = perfectOrWhitespace(originalCL.lines, targetCl.lines, replaceCL.lines);
        if (attempt != null) {
            return attempt;
        }

        // 3) handle triple-dot expansions
        try {
            attempt = tryDotdotdots(content, target, replace);
            if (attempt != null) {
                return attempt;
            }
        } catch (IllegalArgumentException e) {
            // ignore if it fails
        }

        // 3a) If that failed, attempt dropping a spurious leading blank line from the "search" block:
        if (targetCl.lines.length > 2 && targetCl.lines[0].trim().isEmpty()) {
            String[] splicedTarget = Arrays.copyOfRange(targetCl.lines, 1, targetCl.lines.length);
            String[] splicedReplace = Arrays.copyOfRange(replaceCL.lines, 1, replaceCL.lines.length);

            attempt = perfectOrWhitespace(originalCL.lines, splicedTarget, splicedReplace);
            if (attempt != null) {
                return attempt;
            }

            attempt = tryDotdotdots(content,
                                    String.join("", splicedTarget),
                                    String.join("", splicedReplace));
            if (attempt != null) {
                return attempt;
            }
        }

        throw new NoMatchException("No matching oldLines found in content");
    }

    /** Counts how many leading lines in 'lines' are completely blank (trim().isEmpty()). */
    static int countLeadingBlankLines(String[] lines) {
        int c = 0;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) {
                c++;
            } else {
                break;
            }
        }
        return c;
    }

    /**
     * If the search/replace has lines of "..." as placeholders, do naive partial replacements.
     */
    public static String tryDotdotdots(String whole, String target, String replace) throws NoMatchException {
        // If there's no "..." in target or whole, skip
        if (!target.contains("...") && !whole.contains("...")) {
            return null;
        }
        // splits on lines of "..."
        Pattern dotsRe = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s*$");

        String[] targetPieces = dotsRe.split(target);
        String[] replacePieces = dotsRe.split(replace);

        if (targetPieces.length != replacePieces.length) {
            throw new IllegalArgumentException("Unpaired ... usage in search/replace");
        }

        String result = whole;
        for (int i = 0; i < targetPieces.length; i++) {
            String pp = targetPieces[i];
            String rp = replacePieces[i];

            if (pp.isEmpty() && rp.isEmpty()) {
                // no content
                continue;
            }
            if (!pp.isEmpty()) {
                if (!result.contains(pp)) {
                    throw new NoMatchException("Partial replacement failed: target piece not found: " + pp);
                }
                // replace only the first occurrence
                result = result.replaceFirst(Pattern.quote(pp), Matcher.quoteReplacement(rp));
            } else {
                // target piece empty, but replace piece is not -> just append
                result += rp;
            }
        }
        return result;
    }

    /**
     * Tries perfect replace first, then leading-whitespace-insensitive.
     * Throws AmbiguousMatchException if more than one match is found in either step.
     */
    public static String perfectOrWhitespace(String[] originalLines,
                                             String[] targetLines,
                                             String[] replaceLines)
            throws AmbiguousMatchException
    {
        String perfect = perfectReplace(originalLines, targetLines, replaceLines);
        if (perfect != null) {
            return perfect;
        }
        return replaceIgnoringWhitespace(originalLines, targetLines, replaceLines);
    }

    /**
     * Tries exact line-by-line match. Returns the post-replacement lines on success; null on failure.
     * Throws AmbiguousMatchException if multiple matches are found.
     */
    public static String perfectReplace(String[] originalLines,
                                        String[] targetLines,
                                        String[] replaceLines)
            throws AmbiguousMatchException
    {
        // special-case replace entire file (empty target)
        if (targetLines.length == 0) {
            return String.join("", replaceLines);
        }

        List<Integer> matches = new ArrayList<>();

        outer:
        for (int i = 0; i <= originalLines.length - targetLines.length; i++) {
            for (int j = 0; j < targetLines.length; j++) {
                if (!Objects.equals(originalLines[i + j], targetLines[j])) {
                    continue outer;
                }
            }
            matches.add(i);
            if (matches.size() > 1) {
                throw new AmbiguousMatchException("Multiple exact matches found for the oldLines");
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        // Exactly one match
        int matchStart = matches.getFirst();
        List<String> newLines = new ArrayList<>();
        // everything before
        newLines.addAll(Arrays.asList(originalLines).subList(0, matchStart));
        // add replacement
        newLines.addAll(Arrays.asList(replaceLines));
        // everything after
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + targetLines.length, originalLines.length));

        return String.join("", newLines);
    }

    /**
     * Attempt a line-for-line match ignoring whitespace. If found, replace that
     * slice by adjusting each replacement line's indentation to preserve the relative
     * indentation from the 'search' lines.
     * Throws AmbiguousMatchException if multiple matches are found ignoring whitespace.
     */
    static String replaceIgnoringWhitespace(String[] originalLines,
                                            String[] targetLines,
                                            String[] replaceLines)
            throws AmbiguousMatchException
    {
        var truncatedTarget = removeLeadingTrailingEmptyLines(targetLines);
        var truncatedReplace = removeLeadingTrailingEmptyLines(replaceLines);

        if (truncatedTarget.length == 0) {
            // Empty target is handled by perfectReplace -- this means we just had whitespace, so fail the edit
            return null;
        }

        List<Integer> matches = new ArrayList<>();
        int needed = truncatedTarget.length;

        for (int start = 0; start <= originalLines.length - needed; start++) {
            if (matchesIgnoringWhitespace(originalLines, start, truncatedTarget)) {
                matches.add(start);
                if (matches.size() > 1) {
                    throw new AmbiguousMatchException("No exact matches found, and multiple matches found ignoring whitespace");
                }
            }
        }

        if (matches.isEmpty()) {
            return null;
        }

        // Exactly one match
        int matchStart = matches.get(0);

        List<String> newLines = new ArrayList<>(Arrays.asList(originalLines).subList(0, matchStart));
        if (truncatedReplace.length > 0) {
            var adjusted = getLeadingWhitespace(originalLines[matchStart])
                    + truncatedReplace[0].trim() + "\n";
            newLines.add(adjusted);
            newLines.addAll(Arrays.asList(truncatedReplace).subList(1, truncatedReplace.length));
        }
        newLines.addAll(Arrays.asList(originalLines)
                                .subList(matchStart + needed, originalLines.length));
        return String.join("", newLines);
    }

    private static String[] removeLeadingTrailingEmptyLines(String[] targetLines) {
        int pStart = 0;
        while (pStart < targetLines.length && targetLines[pStart].trim().isEmpty()) {
            pStart++;
        }
        // Skip trailing blank lines in the search block
        int pEnd = targetLines.length;
        while (pEnd > pStart && targetLines[pEnd - 1].trim().isEmpty()) {
            pEnd--;
        }
        return Arrays.copyOfRange(targetLines, pStart, pEnd);
    }

    /**
     * return true if the targetLines match the originalLines starting at 'start', ignoring whitespace.
     */
    static boolean matchesIgnoringWhitespace(String[] originalLines, int start, String[] targetLines) {
        if (start + targetLines.length > originalLines.length) {
            return false;
        }
        for (int i = 0; i < targetLines.length; i++) {
            if (!nonWhitespace(originalLines[start + i]).equals(nonWhitespace(targetLines[i]))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the non-whitespace characters in `line`
     */
    private static String nonWhitespace(String line) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * @return the whitespace prefix in this line.
     */
    static String getLeadingWhitespace(String line) {
        assert line.endsWith("\n");
        int count = 0;
        for (int i = 0; i < line.length() - 1; i++) { // -1 because we threw newline onto everything
            if (Character.isWhitespace(line.charAt(i))) {
                count++;
            } else {
                break;
            }
        }
        return line.substring(0, count);
    }

    /**
     * Align the replacement line to the original target content, based on the prefixes from the first matched lines
     */
    static String adjustIndentation(String line, String targetPrefix, String replacePrefix) {
        if (replacePrefix.isEmpty()) {
            return targetPrefix + line;
        }

        if (line.startsWith(replacePrefix)) {
            return line.replaceFirst(replacePrefix, targetPrefix);
        }

        // no prefix match, either we have inconsistent whitespace in the replacement
        // or (more likely) we have a replacement block that ends at a lower level of indentation
        // than where it begins.  we'll do our best by counting characters
        int delta = replacePrefix.length() - targetPrefix.length();
        if (delta > 0) {
            // remove up to `delta` spaces
            delta = min(delta, getLeadingWhitespace(line).length());
            return line.substring(delta);
        }
        return replacePrefix.substring(0, -delta) + line;
    }

    /**
     * Uses LCS approximation for ratio.
     */
    private static double sequenceMatcherRatio(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int lcs = longestCommonSubsequence(a, b);
        double denom = a.length() + b.length();
        return (2.0 * lcs) / denom;
    }

    /**
     * Optimized LCS with rolling 1D array instead of a 2D matrix
     */
    private static int longestCommonSubsequence(String s1, String s2) {
        int n1 = s1.length();
        int n2 = s2.length();
        if (n1 == 0 || n2 == 0) {
            return 0;
        }
        int[] prev = new int[n2 + 1];
        int[] curr = new int[n2 + 1];

        for (int i = 1; i <= n1; i++) {
            // reset row
            curr[0] = 0;
            for (int j = 1; j <= n2; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(prev[j], curr[j - 1]);
                }
            }
            // swap references
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[n2];
    }

    /**
     * Removes any extra lines containing the filename or triple-backticks fences.
     */
    public static String stripQuotedWrapping(String block, String fname, String[] fence) {
        assert block != null;
        if (block.isEmpty()) {
            return block;
        }
        String[] lines = block.split("\n", -1);

        // If triple-backtick block, remove the fences
        // Note: We no longer strip the filename line here, as it's part of the SEARCH/REPLACE markers
        if (lines.length >= 2
                && lines[0].trim().equals(fence[0])
                && lines[lines.length - 1].trim().equals(fence[1])) {
            lines = Arrays.copyOfRange(lines, 1, lines.length - 1);
        } else if (lines.length >= 1 && lines[0].trim().equals(fence[0])) {
             // Handle missing closing fence
             lines = Arrays.copyOfRange(lines, 1, lines.length);
        } else if (lines.length >= 1 && lines[lines.length - 1].trim().equals(fence[1])) {
             // Handle missing opening fence
             lines = Arrays.copyOfRange(lines, 0, lines.length - 1);
        }

        String result = String.join("\n", lines);
        // Ensure trailing newline, primarily for the case where fences were stripped
        if (!result.isEmpty() && !result.endsWith("\n")) {
             result += "\n";
        }
        return result;
    }

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to
     * currentFilename if it's not null.
     */
    private static ContentLines prep(String content) {
        Objects.requireNonNull(content, "Content cannot be null");
        // ensure it ends with newline
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        String[] lines = content.split("\n", -1);
        lines = Arrays.copyOf(lines, lines.length - 1); // chop off empty element at the end
        // re-add newlines
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i] + "\n";
        }
        return new ContentLines(content, lines);
    }

    private record ContentLines(String original, String[] lines) { }

    /**
     * Collects suggestions for failed blocks by examining file contents
     */
    public static Map<FailedBlock, String> collectSuggestions(List<FailedBlock> failedBlocks, IContextManager cm) {
        Map<FailedBlock, String> suggestions = new HashMap<>();

        for (var failedBlock : failedBlocks) {
            if (failedBlock.block().filename() == null) continue;

            try {
                String fileContent = cm.toFile(failedBlock.block().filename()).read();
                if (fileContent.contains(failedBlock.block().afterText().trim())) {
                    var suggestion = """
                    Note: The replacement text is already present in the file. If we no longer need to apply
                    this block, omit it from your reply.
                    """.stripIndent();
                    suggestions.put(failedBlock, suggestion);
                }
            } catch (IOException ignored) {
                // Skip suggestions if we can't read the file
            }
        }
        return suggestions;
    }

    /**
     * Resolves a filename string to a ProjectFile.
     * Handles partial paths, checks against editable files, tracked files, and project files.
     *
     * @param cm         The context manager.
     * @param filename   The filename string to resolve (potentially partial).
     * @param createNew  If true, allow resolving to a non-existent file path for creation.
     * @return The resolved ProjectFile.
     * @throws SymbolNotFoundException if the file cannot be found.
     * @throws SymbolAmbiguousException if the filename matches multiple files.
     */
    static ProjectFile resolveProjectFile(IContextManager cm, String filename, boolean createNew)
            throws SymbolNotFoundException, SymbolAmbiguousException
    {
        var file = cm.toFile(filename);

        // 1. Exact match (common case)
        if (file.exists() || createNew) {
            return file;
        }

        // 2. Check editable files (case-insensitive basename match)
        var editableMatches = cm.getEditableFiles().stream()
                .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                .toList();
        if (editableMatches.size() == 1) {
            logger.debug("Resolved partial filename [{}] to editable file [{}]", filename, editableMatches.getFirst());
            return editableMatches.getFirst();
        }
        if (editableMatches.size() > 1) {
            throw new SymbolAmbiguousException("Filename '%s' matches multiple editable files: %s".formatted(filename, editableMatches));
        }

        // 3. Check tracked files in git repo (substring match)
        var repo = cm.getRepo();
        if (repo != null) {
            var trackedMatches = repo.getTrackedFiles().stream()
                    .filter(f -> f.toString().contains(filename))
                    .toList();
            if (trackedMatches.size() == 1) {
                logger.debug("Resolved partial filename [{}] to tracked file [{}]", filename, trackedMatches.getFirst());
                return trackedMatches.getFirst();
            }
            if (trackedMatches.size() > 1) {
                // Prefer exact basename match if available among tracked files
                var exactBaseMatches = trackedMatches.stream()
                        .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                        .toList();
                if (exactBaseMatches.size() == 1) {
                     logger.debug("Resolved ambiguous tracked filename [{}] to exact basename match [{}]", filename, exactBaseMatches.getFirst());
                     return exactBaseMatches.getFirst();
                }
                throw new SymbolAmbiguousException("Filename '%s' matches multiple tracked files: %s".formatted(filename, trackedMatches));
            }
        }

        // 4. Check all project files (case-insensitive basename match) - last resort
        var project = cm.getProject();
        if (project != null) {
            var projectFileMatches = project.getFiles().stream()
                    .filter(f -> f instanceof ProjectFile)
                    .map(f -> (ProjectFile) f)
                    .filter(f -> f.getFileName().equalsIgnoreCase(file.getFileName()))
                    .toList();
            if (projectFileMatches.size() == 1) {
                logger.debug("Resolved partial filename [{}] to project file [{}]", filename, projectFileMatches.getFirst());
                return projectFileMatches.getFirst();
            }
            if (projectFileMatches.size() > 1) {
                throw new SymbolAmbiguousException("Filename '%s' matches multiple project files: %s".formatted(filename, projectFileMatches));
            }
        }

        // 5. Not found anywhere
        throw new SymbolNotFoundException("Filename '%s' could not be resolved to an existing file.".formatted(filename));
    }
}
