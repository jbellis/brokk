package io.github.jbellis.brokk;

import com.google.common.annotations.VisibleForTesting;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.min;

/**
 * Utility for extracting and applying before/after search-replace blocks in content.
 */
public class EditBlock {
    private static final Logger logger = LogManager.getLogger(EditBlock.class);

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
        NO_FILENAME,
        IO_ERROR
    }

    public record EditResult(Map<ProjectFile, String> originalContents, List<FailedBlock> failedBlocks) { }

    public record FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) { }

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
            // Attempt to apply to the specified file
            ProjectFile file = block.filename() == null ? null : contextManager.toFile(block.filename());
            boolean isCreateNew = block.beforeText().trim().isEmpty();

            String finalUpdated = null;
            if (file != null) {
                // if the user gave a valid file name, try to apply it there first
                try {
                    finalUpdated = doReplace(file, block.beforeText(), block.afterText());
                } catch (IOException e) {
                    io.toolError("Failed reading/writing " + file + ": " + e.getMessage());
                }
            }

            // Fallback: if finalUpdated is still null and 'before' is not empty, try each known file
            if (finalUpdated == null && !isCreateNew) {
                for (ProjectFile altFile : contextManager.getEditableFiles()) {
                    try {
                        String updatedContent = doReplace(altFile.read(), block.beforeText(), block.afterText());
                        if (updatedContent != null) {
                            file = altFile; // Found a match
                            finalUpdated = updatedContent;
                            break;
                        }
                    } catch (IOException ignored) {
                        // keep trying
                    }
                }
            }

            // if we still haven't found a matching file, we have to give up
            if (file == null) {
                failed.add(new FailedBlock(block, EditBlockFailureReason.NO_MATCH));
                continue;
            }

            if (finalUpdated == null) {
                var failedBlock = new FailedBlock(block, EditBlockFailureReason.NO_MATCH);
                failed.add(failedBlock);
            } else {
                // Save original content before first change
                if (!changedFiles.containsKey(file)) {
                    try {
                        changedFiles.put(file, file.exists() ? file.read() : "");
                    } catch (IOException e) {
                        io.toolError("Failed reading " + file + ": " + e.getMessage());
                    }
                }

                // Actually write the file if it changed
                var error = false;
                try {
                    file.write(finalUpdated);
                } catch (IOException e) {
                    io.toolError("Failed writing " + file + ": " + e.getMessage());
                    failed.add(new FailedBlock(block, EditBlockFailureReason.IO_ERROR));
                    error = true;
                }
                if (!error) {
                    succeeded.add(block);
                    if (isCreateNew) {
                        try {
                            contextManager.addToGit(file.toString());
                            io.systemOutput("Added to git " + file);
                        } catch (IOException e) {
                            io.systemOutput("Failed to git add " + file + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

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
    public record SearchReplaceBlock(String filename, String beforeText, String afterText) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("```");
            if (filename != null) {
                sb.append("\n").append(filename);
            }
            sb.append("\n<<<<<<< SEARCH\n");
            sb.append(beforeText);
            if (!beforeText.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("=======\n");
            sb.append(afterText);
            if (!afterText.endsWith("\n")) {
                sb.append("\n");
            }
            sb.append(">>>>>>> REPLACE\n```");
            
            return sb.toString();
        }
    }

    // Default fence to match triple-backtick usage, e.g. ``` ... ```
    static final String[] DEFAULT_FENCE = {"```", "```"};

    private EditBlock() {
        // utility class
    }

    /**
     * Attempt to locate beforeText in content and replace it with afterText.
     * If beforeText is empty, just append afterText. If no match found, return null.
     */
    private static String doReplace(ProjectFile file,
                                    String content,
                                    String beforeText,
                                    String afterText) {
        if (file != null && !file.exists() && beforeText.trim().isEmpty()) {
            // Treat as a brand-new filename with empty original content
            content = "";
        }

        assert content != null;

        // Strip any surrounding triple-backticks, optional filename line, etc.
        beforeText = stripQuotedWrapping(beforeText, file == null ? null : file.toString(), EditBlock.DEFAULT_FENCE);
        afterText = stripQuotedWrapping(afterText, file == null ? null : file.toString(), EditBlock.DEFAULT_FENCE);

        // If there's no "before" text, just append the after-text
        if (beforeText.trim().isEmpty()) {
            return content + afterText;
        }

        // Attempt the chunk replacement
        return replaceMostSimilarChunk(content, beforeText, afterText);
    }

    /**
     * Called by Coder
     */
    public static String doReplace(ProjectFile file, String beforeText, String afterText) throws IOException {
        var content = file.exists() ? file.read() : "";
        return doReplace(file, content, beforeText, afterText);
    }

    /**
     * RepoFile-free overload for testing simplicity
     */
    public static String doReplace(String original, String beforeText, String afterText) {
        return doReplace(null, original, beforeText, afterText);
    }

    /**
     * Attempts perfect/whitespace replacements, then tries "...", then fuzzy.
     * Returns null if no match found.
     */
    static String replaceMostSimilarChunk(String content, String target, String replace) {
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
            // drop the first line from targetCl
            String[] splicedTarget = Arrays.copyOfRange(targetCl.lines, 1, targetCl.lines.length);
            String[] splicedReplace = Arrays.copyOfRange(replaceCL.lines, 1, replaceCL.lines.length);

            attempt = perfectOrWhitespace(originalCL.lines, splicedTarget, splicedReplace);
            if (attempt != null) {
                return attempt;
            }

            // try triple-dot expansions on the spliced block if needed.
            return tryDotdotdots(content, String.join("", splicedTarget), String.join("", splicedReplace));
        }

        return null;
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
    public static String tryDotdotdots(String whole, String target, String replace) {
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
                    return null; // can't do partial replacement
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
     */
    public static String perfectOrWhitespace(String[] originalLines,
                                             String[] targetLines,
                                             String[] replaceLines) {
        String perfect = perfectReplace(originalLines, targetLines, replaceLines);
        if (perfect != null) {
            return perfect;
        }
        return replaceIgnoringWhitespace(originalLines, targetLines, replaceLines);
    }

    /**
     * Tries exact line-by-line match.
     */
    public static String perfectReplace(String[] originalLines,
                                        String[] targetLines,
                                        String[] replaceLines) {
        if (targetLines.length == 0) {
            return null;
        }
        outer:
        for (int i = 0; i <= originalLines.length - targetLines.length; i++) {
            for (int j = 0; j < targetLines.length; j++) {
                if (!Objects.equals(originalLines[i + j], targetLines[j])) {
                    continue outer;
                }
            }
            // found match
            List<String> newLines = new ArrayList<>();
            // everything before the match
            newLines.addAll(Arrays.asList(originalLines).subList(0, i));
            // add replacement
            newLines.addAll(Arrays.asList(replaceLines));
            // everything after the match
            newLines.addAll(Arrays.asList(originalLines).subList(i + targetLines.length, originalLines.length));
            return String.join("", newLines);
        }
        return null;
    }

    /**
     * Attempt a line-for-line match ignoring whitespace. If found, replace that
     * slice by adjusting each replacement line's indentation to preserve the relative
     * indentation from the 'search' lines.
     */
    static String replaceIgnoringWhitespace(String[] originalLines, String[] targetLines, String[] replaceLines) {
        // Skip leading blank lines in the target and replacement
        var truncatedTarget = removeLeadingTrailingEmptyLines(targetLines);
        var truncatedReplace = removeLeadingTrailingEmptyLines(replaceLines);

        if (truncatedTarget.length == 0) {
            // No actual lines to match
            return null;
        }

        // Attempt to find a slice in originalLines that matches ignoring whitespace.
        int needed = truncatedTarget.length;
        for (int start = 0; start <= originalLines.length - needed; start++) {
            if (!matchesIgnoringWhitespace(originalLines, start, truncatedTarget)) {
                continue;
            }

            // Found a match - rebuild the file around it
            // everything before the match
            List<String> newLines = new ArrayList<>(Arrays.asList(originalLines).subList(0, start));
            if (truncatedReplace.length > 0) {
                // for the very first replacement line, handle the case where the LLM omitted whitespace in its target, e.g.
                // Original:
                //   L1
                //   L2
                // Target:
                // L1
                //   L2
                var adjusted = getLeadingWhitespace(originalLines[start]) + truncatedReplace[0].trim() + "\n";
                newLines.add(adjusted);
                // add the rest of the replacement lines assuming that whitespace is correct
                newLines.addAll(Arrays.asList(truncatedReplace).subList(1, truncatedReplace.length));
            }
            // everything after the match
            newLines.addAll(Arrays.asList(originalLines).subList(start + needed, originalLines.length));
            return String.join("", newLines);
        }
        return null;
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
        if (block == null || block.isEmpty()) {
            return block;
        }
        String[] lines = block.split("\n", -1);

        // If first line ends with the filename’s filename
        if (fname != null && lines.length > 0) {
            String fn = new File(fname).getName();
            if (lines[0].trim().endsWith(fn)) {
                lines = Arrays.copyOfRange(lines, 1, lines.length);
            }
        }
        // If triple-backtick block
        if (lines.length >= 2
                && lines[0].startsWith(fence[0])
                && lines[lines.length - 1].startsWith(fence[1])) {
            lines = Arrays.copyOfRange(lines, 1, lines.length - 1);
        }
        String result = String.join("\n", lines);
        if (!result.isEmpty() && !result.endsWith("\n")) {
            result += "\n";
        }
        return result;
    }

    private static ContentLines prep(String content) {
        assert content != null;
        // ensure it ends with newline
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }
        String[] lines = content.split("\n", -1);
        // preserve trailing newline by re-adding "\n" to all but last element
        for (int i = 0; i < lines.length - 1; i++) {
            lines[i] = lines[i] + "\n";
        }
        return new ContentLines(content, lines);
    }

    private record ContentLines(String original, String[] lines) { }
}
