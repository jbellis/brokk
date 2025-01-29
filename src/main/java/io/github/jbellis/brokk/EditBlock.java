package io.github.jbellis.brokk;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for extracting and applying before/after search-replace blocks in content
 */
public class EditBlock {
    private static final double FUZZY_THRESHOLD = 0.6;

    /**
     * Simple record storing the parts of a search-replace block.
     * If {@code filename} is non-null, then this block corresponds to a filename’s
     * search/replace. If {@code shellCommand} is non-null, then this block
     * corresponds to shell code that should be executed, not applied to a filename.
     */
    public record SearchReplaceBlock(String filename, String beforeText, String afterText, String shellCommand) { }

    public record ParseResult(List<SearchReplaceBlock> blocks, String parseError) { }

    private static final Pattern HEAD = Pattern.compile("^<{5,9} SEARCH\\s*$", Pattern.MULTILINE);
    private static final Pattern DIVIDER = Pattern.compile("^={5,9}\\s*$", Pattern.MULTILINE);
    private static final Pattern UPDATED = Pattern.compile("^>{5,9} REPLACE\\s*$", Pattern.MULTILINE);

    // Default fence to match triple-backtick usage, e.g. ``` ... ```
    static final String[] DEFAULT_FENCE = {"```", "```"};

    // For detecting shell fences
    private static final List<String> SHELL_STARTS = List.of(
            "```bash", "```sh", "```shell", "```cmd", "```batch", "```powershell",
            "```ps1", "```zsh", "```fish", "```ksh", "```csh", "```tcsh"
    );

    private EditBlock() {
        // utility class
    }

    /**
     * Parses the given content and yields either (filename, before, after, null)
     * or (null, null, null, shellCommand).
     *
     * Important! This does not *restrict* the blocks to `filesInContext`; this
     * parameter is only used to help find possible filename matches in poorly formed blocks.
     */
    public static ParseResult findOriginalUpdateBlocks(String content,
                                                       String[] fence,
                                                       Set<RepoFile> filesInContext) {
        List<SearchReplaceBlock> blocks = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        int i = 0;

        String currentFilename = null;

        while (i < lines.length) {
            String trimmed = lines[i].trim();

            // 1) Check if it's a shell fence block
            if (isShellFence(lines[i]) && !isNextLineHead(lines, i + 1)) {
                i++;
                StringBuilder shellContent = new StringBuilder();
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    shellContent.append(lines[i]).append("\n");
                    i++;
                }
                // skip the closing ```
                if (i < lines.length && lines[i].trim().startsWith("```")) {
                    i++;
                }
                blocks.add(new SearchReplaceBlock(null, null, null, shellContent.toString()));
                continue;
            }

            // 2) Check if it's a <<<<<<< SEARCH block
            if (HEAD.matcher(trimmed).matches()) {
                try {
                    // Attempt to find a filename in the preceding ~3 lines
                    currentFilename = findFileNameNearby(lines, i, fence, filesInContext, currentFilename);

                    // gather "before" lines until divider
                    i++;
                    List<String> beforeLines = new ArrayList<>();
                    while (i < lines.length && !DIVIDER.matcher(lines[i].trim()).matches()) {
                        beforeLines.add(lines[i]);
                        i++;
                    }
                    if (i >= lines.length) {
                        return new ParseResult(null, "Expected ======= divider after <<<<<<< SEARCH");
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
                        return new ParseResult(null, "Expected >>>>>>> REPLACE or =======");
                    }

                    var beforeJoined = stripQuotedWrapping(String.join("\n", beforeLines), currentFilename, fence);
                    var afterJoined = stripQuotedWrapping(String.join("\n", afterLines), currentFilename, fence);

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
                    String partial = String.join("\n", Arrays.copyOfRange(lines, 0, Math.min(lines.length, i + 1)));
                    return new ParseResult(null, partial + "\n^^^ " + e.getMessage());
                }
            }

            i++;
        }

        return new ParseResult(blocks, null);
    }

    /**
     * Overload that uses DEFAULT_FENCE and no fence parameter in the signature, for convenience.
     */
    public static ParseResult findOriginalUpdateBlocks(String content, Set<RepoFile> filesInContext) {
        return findOriginalUpdateBlocks(content, DEFAULT_FENCE, filesInContext);
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

        if (content == null) {
            return null;
        }

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
     */
    static String replaceMostSimilarChunk(String whole, String part, String replace) {
        // 1) prep for line-based matching
        ContentLines wholeCL = prep(whole);
        ContentLines partCL = prep(part);
        ContentLines replaceCL = prep(replace);

        // 2) perfect or whitespace approach
        String attempt = perfectOrWhitespace(wholeCL.lines, partCL.lines, replaceCL.lines);
        if (attempt != null) {
            return attempt;
        }

        // Count how many leading blank lines in the "search" block
        int partLeadingBlanks = countLeadingBlankLines(partCL.lines);

        // For 1..partLeadingBlanks: remove exactly i blank lines from search
        for (int i = 1; i <= partLeadingBlanks; i++) {
            // slice i leading blank lines from 'partCL'
            String[] truncatedPart = Arrays.copyOfRange(partCL.lines, i, partCL.lines.length);

            // also slice up to i blank lines from 'replaceCL'
            int replaceLeadingBlanks = countLeadingBlankLines(replaceCL.lines);
            int limit = Math.min(i, replaceLeadingBlanks);
            String[] truncatedReplace = Arrays.copyOfRange(replaceCL.lines, limit, replaceCL.lines.length);

            attempt = perfectOrWhitespace(wholeCL.lines, truncatedPart, truncatedReplace);
            if (attempt != null) {
                return attempt;
            }
        }

        // 3) handle triple-dot expansions
        try {
            attempt = tryDotdotdots(whole, part, replace);
            if (attempt != null) {
                return attempt;
            }
        } catch (IllegalArgumentException e) {
            // ignore if it fails
        }

        // 3a) If that failed, attempt dropping a spurious leading blank line from the "search" block:
        if (partCL.lines.length > 2 && partCL.lines[0].trim().isEmpty()) {
            // drop the first line from partCL
            String[] splicedPart = Arrays.copyOfRange(partCL.lines, 1, partCL.lines.length);
            String[] splicedReplace = Arrays.copyOfRange(replaceCL.lines, 1, replaceCL.lines.length);

            attempt = perfectOrWhitespace(wholeCL.lines, splicedPart, splicedReplace);
            if (attempt != null) {
                return attempt;
            }
            // try triple-dot expansions on the spliced block if needed.
            attempt = tryDotdotdots(whole, String.join("", splicedPart), String.join("", splicedReplace));
            if (attempt != null) {
                return attempt;
            }
        }

        // 5) fuzzy approach
        return replaceClosestEditDistance(wholeCL.lines, partCL.lines, replaceCL.lines);
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
    public static String tryDotdotdots(String whole, String part, String replace) {
        // If there's no "..." in part or whole, skip
        if (!part.contains("...") && !whole.contains("...")) {
            return null;
        }
        // splits on lines of "..."
        Pattern dotsRe = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s*$");

        String[] partPieces = dotsRe.split(part);
        String[] replacePieces = dotsRe.split(replace);

        if (partPieces.length != replacePieces.length) {
            throw new IllegalArgumentException("Unpaired ... usage in search/replace");
        }

        String result = whole;
        for (int i = 0; i < partPieces.length; i++) {
            String pp = partPieces[i];
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
                // part piece empty, but replace piece is not -> just append
                result += rp;
            }
        }
        return result;
    }

    /**
     * Tries perfect replace first, then leading-whitespace-insensitive.
     */
    public static String perfectOrWhitespace(String[] wholeLines,
                                             String[] partLines,
                                             String[] replaceLines) {
        String perfect = perfectReplace(wholeLines, partLines, replaceLines);
        if (perfect != null) {
            return perfect;
        }
        return replacePartWithMissingLeadingWhitespace(wholeLines, partLines, replaceLines);
    }

    /**
     * Tries exact line-by-line match.
     */
    public static String perfectReplace(String[] wholeLines,
                                        String[] partLines,
                                        String[] replaceLines) {
        if (partLines.length == 0) {
            return null;
        }
        outer:
        for (int i = 0; i <= wholeLines.length - partLines.length; i++) {
            for (int j = 0; j < partLines.length; j++) {
                if (!Objects.equals(wholeLines[i + j], partLines[j])) {
                    continue outer;
                }
            }
            // found match
            List<String> newFile = new ArrayList<>();
            // everything before the match
            newFile.addAll(Arrays.asList(wholeLines).subList(0, i));
            // add replacement
            newFile.addAll(Arrays.asList(replaceLines));
            // everything after the match
            newFile.addAll(Arrays.asList(wholeLines).subList(i + partLines.length, wholeLines.length));
            return String.join("", newFile);
        }
        return null;
    }

    /**
     * Attempt a line-for-line match ignoring leading whitespace. If found, replace that
     * slice by adjusting each replacement line's indentation to preserve the relative
     * indentation from the 'search' lines.
     */
    static String replacePartWithMissingLeadingWhitespace(String[] wholeLines,
                                                          String[] partLines,
                                                          String[] replaceLines) {
        // Skip leading blank lines in the 'search'
        int pStart = 0;
        while (pStart < partLines.length && partLines[pStart].trim().isEmpty()) {
            pStart++;
        }
        // Skip trailing blank lines in the search block
        int pEnd = partLines.length;
        while (pEnd > pStart && partLines[pEnd - 1].trim().isEmpty()) {
            pEnd--;
        }
        String[] truncatedPart = Arrays.copyOfRange(partLines, pStart, pEnd);

        // Do the same for the 'replace'
        int rStart = 0;
        while (rStart < replaceLines.length && replaceLines[rStart].trim().isEmpty()) {
            rStart++;
        }
        int rEnd = replaceLines.length;
        while (rEnd > rStart && replaceLines[rEnd - 1].trim().isEmpty()) {
            rEnd--;
        }
        String[] truncatedReplace = Arrays.copyOfRange(replaceLines, rStart, rEnd);

        if (truncatedPart.length == 0) {
            // No actual lines to match
            return null;
        }

        // Attempt to find a slice in wholeLines that matches ignoring leading spaces.
        int needed = truncatedPart.length;
        for (int start = 0; start <= wholeLines.length - needed; start++) {
            if (!matchesIgnoringLeading(wholeLines, start, truncatedPart)) {
                continue;
            }

            // Found a match - rebuild the filename around it
            // everything before the match
            List<String> newFile = new ArrayList<>(Arrays.asList(wholeLines).subList(0, start));
            // add replacement lines with adjusted indentation
            for (int i = 0; i < needed && i < truncatedReplace.length; i++) {
                int sliceLeading = countLeadingSpaces(wholeLines[start + i]);
                int partLeading  = countLeadingSpaces(truncatedPart[i]);
                int difference = partLeading - sliceLeading;

                String adjusted = adjustIndentation(truncatedReplace[i], difference);
                newFile.add(adjusted);
            }
            // if the replacement is longer, add leftover lines
            newFile.addAll(Arrays.asList(truncatedReplace).subList(needed, truncatedReplace.length));
            // everything after the match
            newFile.addAll(Arrays.asList(wholeLines).subList(start + needed, wholeLines.length));
            return String.join("", newFile);
        }
        return null;
    }

    static boolean matchesIgnoringLeading(String[] wholeLines, int start, String[] partLines) {
        if (start + partLines.length > wholeLines.length) {
            return false;
        }
        for (int i = 0; i < partLines.length; i++) {
            if (!wholeLines[start + i].stripLeading().equals(partLines[i].stripLeading())) {
                return false;
            }
        }
        return true;
    }

    static int countLeadingSpaces(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    static String adjustIndentation(String line, int delta) {
        if (line.isBlank() || delta == 0) {
            return line;
        }
        int current = countLeadingSpaces(line);

        int newCount;
        if (delta > 0) {
            // remove up to `delta` spaces
            int remove = Math.min(current, delta);
            newCount = current - remove;
        } else {
            // delta < 0 => add -delta
            newCount = current + (-delta);
        }

        // never go below zero
        if (newCount < 0) {
            newCount = 0;
        }

        String stripped = line.stripLeading();
        return " ".repeat(newCount) + stripped;
    }

    /**
     * Fallback fuzzy approach with optimized checks to skip unnecessary LCS calls.
     */
    static String replaceClosestEditDistance(String[] wholeLines,
                                             String[] partLines,
                                             String[] replaceLines) {
        // Quick re-try perfect/whitespace
        String attempt = perfectOrWhitespace(wholeLines, partLines, replaceLines);
        if (attempt != null) {
            return attempt;
        }

        // Trim leading blank lines from part & replace, so we
        // don't insert that extra blank line in the fuzzy approach.
        int pIndex = 0;
        while (pIndex < partLines.length && partLines[pIndex].trim().isEmpty()) {
            pIndex++;
        }
        int rIndex = 0;
        while (rIndex < replaceLines.length && replaceLines[rIndex].trim().isEmpty()) {
            rIndex++;
        }
        String[] truncatedPart = Arrays.copyOfRange(partLines, pIndex, partLines.length);
        String[] truncatedReplace = Arrays.copyOfRange(replaceLines, rIndex, replaceLines.length);
        if (truncatedPart.length == 0) {
            // no real lines to match, just return null
            return null;
        }

        // We'll do a prefix array for the content lines so we can build chunk strings quickly
        String[] prefixWhole = new String[wholeLines.length + 1];
        prefixWhole[0] = "";
        for (int i = 0; i < wholeLines.length; i++) {
            prefixWhole[i + 1] = prefixWhole[i] + wholeLines[i];
        }

        // Pre-join the truncatedPart once
        String joinedPart = String.join("", truncatedPart);

        // Find the best fuzzy match in wholeLines vs truncatedPart
        double bestSim = 0.0;
        int bestStart = -1;
        int bestLen = -1;

        // near ~size of truncatedPart
        int baseLen = truncatedPart.length;
        int minLen = Math.max(1, (int) Math.floor(baseLen * 0.9));
        int maxLen = Math.min(wholeLines.length, (int) Math.ceil(baseLen * 1.1));

        for (int length = minLen; length <= maxLen; length++) {
            for (int start = 0; start + length <= wholeLines.length; start++) {
                // Get the chunk from prefix
                String chunk = prefixWhole[start + length].substring(prefixWhole[start].length());

                // Early skip if max possible ratio can’t exceed bestSim
                // Max possible LCS == min(chunk.length(), joinedPart.length())
                int aLen = chunk.length();
                int bLen = joinedPart.length();
                int minLenAB = Math.min(aLen, bLen);
                double maxPossibleRatio = (2.0 * minLenAB) / (aLen + bLen);
                if (maxPossibleRatio <= bestSim) {
                    continue;
                }

                double sim = sequenceMatcherRatio(chunk, joinedPart);
                if (sim > bestSim) {
                    bestSim = sim;
                    bestStart = start;
                    bestLen = length;
                }
            }
        }

        // If best match is below threshold, no replacement
        if (bestSim < FUZZY_THRESHOLD || bestStart < 0) {
            return null;
        }

        // Build new file with reindentation
        List<String> newFile = new ArrayList<>(Arrays.asList(wholeLines).subList(0, bestStart));
        // Indent replacement lines to match the region we are overwriting
        int linesToIndent = Math.min(bestLen, truncatedPart.length);
        for (int i = 0; i < truncatedReplace.length; i++) {
            if (i < linesToIndent) {
                int chunkLeading = countLeadingSpaces(wholeLines[bestStart + i]);
                int partLeading  = countLeadingSpaces(truncatedPart[i]);
                int delta = partLeading - chunkLeading;
                newFile.add(adjustIndentation(truncatedReplace[i], delta));
            } else {
                // If the replacement is longer, we can keep the same indent as the last matched line
                int lastIndent = (linesToIndent > 0)
                        ? countLeadingSpaces(wholeLines[bestStart + linesToIndent - 1])
                        : 0;
                newFile.add(adjustIndentation(truncatedReplace[i], -lastIndent));
            }
        }
        newFile.addAll(Arrays.asList(wholeLines).subList(bestStart + bestLen, wholeLines.length));
        return String.join("", newFile);
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
                                     String currentPath) {
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

        // 2) Case-insensitive match by basename
        for (var c : candidates) {
            String cLower = Path.of(c).getFileName().toString().toLowerCase();
            var matched = validFiles.stream()
                    .filter(f -> f.getFileName().toLowerCase().equals(cLower))
                    .toList();
            if (!matched.isEmpty()) {
                // we don't have a good way to tell which is better if there are multiple options that differ only by case
                return matched.getFirst().toString();
            }
        }

        // 3) If the candidate has an extension and no better match found, just return that.
        for (var c : candidates) {
            if (c.contains(".")) {
                return c;
            }
        }

        // 4) Fallback to the first candidate
        return candidates.getFirst();
    }

    /**
     * Ignores lines that are just ``` or ...
     */
    private static String stripFilename(String line, String[] fence) {
        String s = line.trim();
        if (s.equals("...")) {
            return null;
        }
        if (s.startsWith(fence[0])) {
            return null;
        }
        // remove trailing colons, leading #, etc.
        s = s.replaceAll(":$", "").replaceFirst("^#", "").trim();
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^\\*+|\\*+$", "");
        return s.isBlank() ? null : s;
    }

    private static ContentLines prep(String content) {
        if (content == null) {
            content = "";
        }
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
        int end = Math.min(contentLines.length, bestIdx + searchLen + 3);
        String[] snippet = Arrays.copyOfRange(contentLines, start, end);
        return String.join("\n", snippet);
    }

    /**
     * Convenience overload using threshold=0.6
     */
    public static String findSimilarLines(String search, String content) {
        return findSimilarLines(search, content, 0.6);
    }

    private static boolean isShellFence(String line) {
        if (!line.trim().startsWith("```")) {
            return false;
        }
        String rest = line.trim().substring(3).toLowerCase(Locale.ROOT);
        // If it matches known shell starts or line is just triple backticks
        if (SHELL_STARTS.stream().anyMatch(line.trim()::startsWith)) {
            return true;
        }
        // fallback check
        return rest.startsWith("bash")
                || rest.startsWith("sh")
                || rest.startsWith("shell")
                || rest.startsWith("cmd")
                || rest.startsWith("batch")
                || rest.startsWith("powershell")
                || rest.startsWith("ps1")
                || rest.startsWith("zsh")
                || rest.startsWith("fish")
                || rest.startsWith("ksh")
                || rest.startsWith("csh")
                || rest.startsWith("tcsh");
    }

    /**
     * Checks if the next line is a HEAD pattern (<<<< SEARCH). This helps to skip
     * incorrectly grouping shell fences that happen to appear right before a HEAD block.
     */
    private static boolean isNextLineHead(String[] lines, int idx) {
        if (idx < 0 || idx >= lines.length) {
            return false;
        }
        return HEAD.matcher(lines[idx].trim()).matches();
    }
}
