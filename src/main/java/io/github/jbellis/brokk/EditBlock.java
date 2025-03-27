package io.github.jbellis.brokk;

import com.google.common.annotations.VisibleForTesting;
import io.github.jbellis.brokk.analyzer.RepoFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.min;

/**
 * Utility for extracting and applying before/after search-replace blocks in content.
 */
public class EditBlock {
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

    public record EditResult(Map<RepoFile, String> originalContents, List<FailedBlock> failedBlocks) { }

    public record FailedBlock(SearchReplaceBlock block, EditBlockFailureReason reason) { }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks (or shell blocks, etc.) and apply them.
     */
    public static EditResult applyEditBlocks(IContextManager contextManager, IConsoleIO io, Collection<SearchReplaceBlock> blocks) {
        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        List<SearchReplaceBlock> succeeded = new ArrayList<>();

        // Track original file contents before any changes
        Map<RepoFile, String> changedFiles = new HashMap<>();

        for (SearchReplaceBlock block : blocks) {
            // Shell commands remain unchanged
            if (block.shellCommand() != null) {
                io.systemOutput("Shell command from LLM:\n" + block.shellCommand());
                continue;
            }

            // Attempt to apply to the specified file
            RepoFile file = block.filename() == null ? null : contextManager.toFile(block.filename());
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
                for (RepoFile altFile : contextManager.getEditableFiles()) {
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
    public record SearchReplaceBlock(String filename, String beforeText, String afterText, String shellCommand) {
        @Override
        public String toString() {
            if (shellCommand != null) {
                return "```shell\n" + shellCommand + "\n```";
            }
            
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

    public record ParseResult(List<SearchReplaceBlock> blocks, String parseError) { }

    private static final Pattern HEAD = Pattern.compile("^<{5,9} SEARCH\\W*$", Pattern.MULTILINE);
    private static final Pattern DIVIDER = Pattern.compile("^={5,9}\\s*$", Pattern.MULTILINE);
    private static final Pattern UPDATED = Pattern.compile("^>{5,9} REPLACE\\W*$", Pattern.MULTILINE);

    // Default fence to match triple-backtick usage, e.g. ``` ... ```
    static final String[] DEFAULT_FENCE = {"```", "```"};

    private EditBlock() {
        // utility class
    }

    static ParseResult findOriginalUpdateBlocks(String content,
                                                       Set<RepoFile> filesInContext)
    {
        return findOriginalUpdateBlocks(content, filesInContext, new IGitRepo() {
            @Override
            public Path getRoot() {
                return Path.of("");
            }

            @Override
            public List<RepoFile> getTrackedFiles() {
                return List.of();
            }
        });
    }
    
    /**
     * Parses the given content and yields either (filename, before, after, null)
     * or (null, null, null, shellCommand).
     *
     * Important! This does not *restrict* the blocks to `filesInContext`; this
     * parameter is only used to help find possible filename matches in poorly formed blocks.
     */
    public static ParseResult findOriginalUpdateBlocks(String content,
                                                       Set<RepoFile> filesInContext,
                                                       IGitRepo repo) 
    {
        List<SearchReplaceBlock> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int i = 0;

        String currentFilename = null;

        while (i < lines.length) {
            String trimmed = lines[i].trim();

            // Check if it's a <<<<<<< SEARCH block
            if (HEAD.matcher(trimmed).matches()) {
                try {
                    // Attempt to find a filename in the preceding ~3 lines
                    currentFilename = findFileNameNearby(lines, i, DEFAULT_FENCE, filesInContext, currentFilename, (GitRepo) repo);

                    // gather "before" lines until divider
                    i++;
                    List<String> beforeLines = new ArrayList<>();
                    while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                        beforeLines.add(lines[i]);
                        i++;
                    }
                    if (i >= lines.length) {
                        return new ParseResult(blocks, "Expected ======= divider after <<<<<<< SEARCH");
                    }

                    // gather "after" lines until >>>>>>> REPLACE or another divider
                    i++; // skip the =======
                    List<String> afterLines = new ArrayList<>();
                    while (i < lines.length
                            && !UPDATED.matcher(lines[i].trim()).matches()
                            && !DIVIDER.matcher(lines[i].trim()).matches()) {
                        afterLines.add(lines[i]);
                        i++;
                    }
                    if (i >= lines.length) {
                        return new ParseResult(blocks, "Expected >>>>>>> REPLACE or =======");
                    }

                    var beforeJoined = stripQuotedWrapping(String.join("\n", beforeLines), currentFilename, DEFAULT_FENCE);
                    var afterJoined = stripQuotedWrapping(String.join("\n", afterLines), currentFilename, DEFAULT_FENCE);

                    // Append trailing newline if not present
                    if (!beforeJoined.isEmpty() && !beforeJoined.endsWith("\n")) {
                        beforeJoined += "\n";
                    }
                    if (!afterJoined.isEmpty() && !afterJoined.endsWith("\n")) {
                        afterJoined += "\n";
                    }

                    blocks.add(new SearchReplaceBlock(currentFilename, beforeJoined, afterJoined, null));

                } catch (Exception e) {
                    // Provide partial context in the error
                    String partial = String.join("\n", Arrays.copyOfRange(lines, 0, min(lines.length, i + 1)));
                    return new ParseResult(null, partial + "\n^^^ " + e.getMessage());
                }
            }

            i++;
        }

        return new ParseResult(blocks, null);
    }
    
    /**
     * Attempt to locate beforeText in content and replace it with afterText.
     * If beforeText is empty, just append afterText. If no match found, return null.
     */
    private static String doReplace(RepoFile file,
                                    String content,
                                    String beforeText,
                                    String afterText,
                                    String[] fence) {
        if (file != null && !file.exists() && beforeText.trim().isEmpty()) {
            // Treat as a brand-new filename with empty original content
            content = "";
        }

        assert content != null;

        // Strip any surrounding triple-backticks, optional filename line, etc.
        beforeText = stripQuotedWrapping(beforeText, file == null ? null : file.toString(), fence);
        afterText = stripQuotedWrapping(afterText, file == null ? null : file.toString(), fence);

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
    public static String doReplace(RepoFile file, String beforeText, String afterText) throws IOException {
        var content = file.exists() ? file.read() : "";
        return doReplace(file, content, beforeText, afterText, DEFAULT_FENCE);
    }

    /**
     * RepoFile-free overload for testing simplicity
     */
    public static String doReplace(String original, String beforeText, String afterText) {
        return doReplace(null, original, beforeText, afterText, DEFAULT_FENCE);
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

    /**
     * Scanning for a filename up to 3 lines above the HEAD block index. If none found, fallback to
     * currentFilename if it's not null.
     */
    @VisibleForTesting
    static String findFileNameNearby(String[] lines,
                                     int headIndex,
                                     String[] fence,
                                     Set<RepoFile> validFiles,
                                     String currentPath, 
                                     GitRepo repo) 
    {
        // Search up to 3 lines above headIndex
        int start = Math.max(0, headIndex - 3);
        var candidates = new ArrayList<String>();
        for (int i = headIndex - 1; i >= start; i--) {
            String s = lines[i].trim();
            String possible = stripFilename(s, fence);
            if (possible != null && !possible.isBlank()) {
                candidates.add(possible);
            }
            // If not a fence line, break.
            if (!s.startsWith("```")) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            return currentPath;
        }

        // 1) Exact match in validFilenames
        for (var c : candidates) {
            if (validFiles.stream().anyMatch(f -> f.toString().equals(c))) {
                return c;
            }
        }

        // 2) Case-insensitive match by basename against validFiles
        var matches = candidates.stream()
                .map(c -> Path.of(c).getFileName().toString().toLowerCase())
                .flatMap(cLower -> validFiles.stream()
                        .filter(f -> f.getFileName().toLowerCase().equals(cLower))
                        .findFirst()
                        .stream())
                .map(RepoFile::toString)
                .toList();
        if (!matches.isEmpty()) {
            return matches.getFirst();
        }
        
        // 3) substring match vs repo
        matches = candidates.stream()
                .flatMap(c -> repo.getTrackedFiles().stream()
                        .filter(f -> f.toString().contains(c))
                        .findFirst()
                        .stream())
                .map(RepoFile::toString)
                .toList();
        if (!matches.isEmpty()) {
            return matches.getFirst();
        }
        
        // 4) If the candidate has an extension and no better match found, just return that.
        for (var c : candidates) {
            if (c.contains(".")) {
                return c;
            }
        }

        // 5) Fallback to the first raw candidate
        return candidates.getFirst();
    }

    /**
     * Ignores lines that are just ``` or ...
     */
    private static String stripFilename(String line, String[] fence) {
        String s = line.trim();
        if (s.equals("...") || s.equals(fence[0])) {
            return null;
        }
        // remove trailing colons, leading #, etc.
        s = s.replaceAll(":$", "").replaceFirst("^#", "").trim();
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^\\*+|\\*+$", "");
        return s.isBlank() ? null : s;
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

    /**
     * Return a snippet of the content that best matches the search block, or "" if none.
     */
    public static String findSimilarLines(String search, String content, double threshold) {
        String[] searchLines = search.split("\n", -1);
        String[] contentLines = content.split("\n", -1);

        double bestRatio = 0.0;
        int bestIdx = -1;

        int searchLen = searchLines.length;
        for (int i = 0; i <= contentLines.length - searchLen; i++) {
            String[] chunk = Arrays.copyOfRange(contentLines, i, i + searchLen);
            double ratio = sequenceMatcherRatio(String.join("\n", chunk), search);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestIdx = i;
            }
        }
        if (bestRatio < threshold || bestIdx < 0) {
            return "";
        }

        // return the chunk plus a bit of surrounding context
        int start = Math.max(0, bestIdx - 3);
        int end = min(contentLines.length, bestIdx + searchLen + 3);
        String[] snippet = Arrays.copyOfRange(contentLines, start, end);
        return String.join("\n", snippet);
    }

    /**
     * Collects suggestions for failed blocks by examining file contents
     */
    public static Map<FailedBlock, String> collectSuggestions(List<FailedBlock> failedBlocks, IContextManager cm) {
        Map<FailedBlock, String> suggestions = new HashMap<>();

        for (var failedBlock : failedBlocks) {
            if (failedBlock.block().filename() == null) continue;

            try {
                String fileContent = cm.toFile(failedBlock.block().filename()).read();
                String snippet = findSimilarLines(failedBlock.block().beforeText(), fileContent, 0.6);
                String suggestion = "";
                if (fileContent.contains(failedBlock.block().afterText().trim())) {
                    suggestion = """
                    Note: The replacement text is already present in the file. If we no longer need to apply
                    this block, omit it from your reply.
                    """.stripIndent();
                } else if (!snippet.isEmpty()) {
                    suggestion = """
                    Did you mean:
                    ```
                    %s
                    ```
                    """.stripIndent().formatted(snippet);
                }
                if (!suggestion.isEmpty()) {
                    suggestions.put(failedBlock, suggestion);
                }
            } catch (IOException ignored) {
                // Skip suggestions if we can't read the file
            }
        }
        return suggestions;
    }

    /**
     * Parses blocks and attempts to apply them, reporting errors to stdout.
     */
    public static void main2(String[] args) throws IOException {
        // Create a simple console IO for output
        var io = new IConsoleIO() {
            @Override
            public void systemOutput(String message) {
                System.out.println(message);
            }

            @Override
            public void llmOutput(String message) {
                System.out.println(message);
            }

            @Override
            public void actionOutput(String message) {
                System.out.println(message);
            }

            @Override
            public void toolError(String message) {
                System.err.println("ERROR: " + message);
            }

            @Override
            public void toolErrorRaw(String message) {
                System.err.println(message);
            }
        };

        // Read input from testblocks.txt
        Path testBlocksPath = Path.of("testblocks.txt");
        var content = new StringBuilder();
        Path cwd = Path.of("").toAbsolutePath();
        Set<RepoFile> potentialFiles = new HashSet<>();

        // Collect lines while scanning for potential file paths
        try (var scanner = new Scanner(testBlocksPath)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                content.append(line).append("\n");

                // Identify lines that look like file paths starting with src/
                if (line.trim().startsWith("src/")) {
                    potentialFiles.add(new RepoFile(cwd, line.trim()));
                }
            }
        } catch (IOException e) {
            io.toolError("Failed to read testblocks.txt: " + e.getMessage());
            System.exit(1);
        }
        System.out.println("Potential files are " + potentialFiles);

        // Get the context manager from Environment if available, or create one with potential files
        IContextManager contextManager = new IContextManager() {
                @Override
                public Set<RepoFile> getEditableFiles() {
                    return potentialFiles;
                }

                @Override
                public RepoFile toFile(String path) {
                    return new RepoFile(cwd, path);
                }
            };

        // Parse the input for SEARCH/REPLACE blocks
        var parseResult = findOriginalUpdateBlocks(content.toString(), contextManager.getEditableFiles());
        if (parseResult.parseError() != null) {
            io.toolErrorRaw(parseResult.parseError());
            System.exit(1);
        }

        var blocks = parseResult.blocks();
        if (blocks.isEmpty()) {
            io.systemOutput("No SEARCH/REPLACE blocks found in input");
            System.exit(0);
        }

        io.systemOutput("Found " + blocks.size() + " SEARCH/REPLACE blocks");

        // Apply the edit blocks
        var editResult = applyEditBlocks(contextManager, io, blocks);

        // Report any failures
        if (!editResult.failedBlocks().isEmpty()) {
            io.systemOutput(editResult.failedBlocks().size() + " blocks failed to apply:");
            var suggestions = collectSuggestions(editResult.failedBlocks(), contextManager);

            for (var failed : editResult.failedBlocks()) {
                io.systemOutput("Failed to apply block for file: " +
                                     (failed.block().filename() == null ? "(none)" : failed.block().filename()) +
                                     " Reason: " + failed.reason());

                if (suggestions.containsKey(failed)) {
                    io.systemOutput(suggestions.get(failed));
                }
            }
        }
    }
}
