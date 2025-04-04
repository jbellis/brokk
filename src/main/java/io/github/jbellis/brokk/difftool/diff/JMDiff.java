
package io.github.jbellis.brokk.difftool.diff;


import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import io.github.jbellis.brokk.difftool.doc.AbstractBufferDocument;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JMDiff {
    public static int BUFFER_SIZE = 100000;
    // Class variables:
    // Allocate a charBuffer once for performance. The charbuffer is used to
    //   store a 'line' without it's ignored characters.
    static final private CharBuffer inputLine = CharBuffer.allocate(BUFFER_SIZE);
    static final private CharBuffer outputLine = CharBuffer.allocate(BUFFER_SIZE);

    public JMDiff() {
        // Constructor remains, but no longer initializes algorithms
    }

    public JMRevision diff(List<String> a, List<String> b, Ignore ignore)
            throws Exception {
        if (a == null) {
            a = Collections.emptyList();
        }
        if (b == null) {
            b = Collections.emptyList();
        }
        // Note: Ignore object is not used in this path currently, but kept for API compatibility
        return diff(a.toArray(), b.toArray(), ignore);
    }

    public JMRevision diff(Object[] a, Object[] b, Ignore ignore) {
        Object[] orgFiltered;
        Object[] revFiltered;

        Object[] orgRaw = a;
        Object[] revRaw = b;

        if (orgRaw == null) {
            orgRaw = new Object[]{};
        }
        if (revRaw == null) {
            revRaw = new Object[]{};
        }

        boolean filtered = orgRaw instanceof AbstractBufferDocument.Line[]
                && revRaw instanceof AbstractBufferDocument.Line[];

        if (filtered) {
            orgFiltered = filter(ignore, orgRaw);
            revFiltered = filter(ignore, revRaw);
        } else {
            // If not Line[], assume they are already filtered or don't need filtering
            // This might need adjustment depending on how non-Line[] inputs are handled.
            // For now, treat them as pre-filtered for the purpose of diffing.
            orgFiltered = orgRaw;
            revFiltered = revRaw;
        }

        // Convert filtered arrays to List<String> for java-diff-utils
        List<String> listA = Stream.of(orgFiltered)
                                   .map(Object::toString)
                                   .collect(Collectors.toList());
        List<String> listB = Stream.of(revFiltered)
                                   .map(Object::toString)
                                   .collect(Collectors.toList());

        // Perform diff using java-diff-utils
        Patch<String> patch = DiffUtils.diff(listA, listB);

        // Convert the result back to JMRevision
        JMRevision revision = convertPatchToJMRevision(patch, orgRaw, revRaw);
        revision.setIgnore(ignore);

        // Adjust line numbers if filtering was applied
        if (filtered) {
            adjustRevision(revision, orgRaw, (JMString[]) orgFiltered, revRaw, (JMString[]) revFiltered);
        }

        return revision;
    }

    /**
     * Converts a Patch object from java-diff-utils to the internal JMRevision format.
     *
     * @param patch         The patch generated by java-diff-utils.
     * @param originalRaw   The original raw (unfiltered) array of objects.
     * @param revisedRaw    The revised raw (unfiltered) array of objects.
     * @return A JMRevision representing the diff.
     */
    private JMRevision convertPatchToJMRevision(Patch<String> patch, Object[] originalRaw, Object[] revisedRaw) {
        JMRevision jmRevision = new JMRevision(originalRaw, revisedRaw);

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            var sourceChunk = delta.getSource();
            var targetChunk = delta.getTarget();

            // Create JMChunk instances using position and size from java-diff-utils Chunk
            JMChunk originalJMChunk = new JMChunk(sourceChunk.getPosition(), sourceChunk.size());
            JMChunk revisedJMChunk = new JMChunk(targetChunk.getPosition(), targetChunk.size());

            // Create and add JMDelta
            JMDelta jmDelta = new JMDelta(originalJMChunk, revisedJMChunk);
            jmRevision.add(jmDelta);
        }

        return jmRevision;
    }


    private void adjustRevision(JMRevision revision, Object[] orgArray, JMString[] orgArrayFiltered, Object[] revArray, JMString[] revArrayFiltered) {
        JMChunk chunk;
        int anchor;
        int size;
        int index;
        int adjustedAnchor;
        int adjustedSize;

        for (JMDelta delta : revision.getDeltas()) {
            // Adjust Original Chunk
            chunk = delta.getOriginal();
            index = chunk.getAnchor(); // Index in the filtered array
            if (index < orgArrayFiltered.length) {
                adjustedAnchor = orgArrayFiltered[index].lineNumber; // Map back to original line number
            } else {
                // If the anchor is beyond the filtered array, map it to the end of the original array
                adjustedAnchor = orgArray.length;
            }

            size = chunk.getSize(); // Size in the filtered array
            if (size > 0) {
                int endIndex = index + size - 1;
                if (endIndex < orgArrayFiltered.length) {
                    // Calculate size based on original line numbers
                    adjustedSize = orgArrayFiltered[endIndex].lineNumber - adjustedAnchor + 1;
                } else if (index < orgArrayFiltered.length) {
                    // If end is out but start is in, size goes to the end of the original array
                    adjustedSize = orgArray.length - adjustedAnchor;
                } else {
                     // If start is also out, size is 0 in the original context
                    adjustedSize = 0;
                }
            } else {
                adjustedSize = 0;
            }
            chunk.setAnchor(adjustedAnchor);
            chunk.setSize(adjustedSize);

            // Adjust Revised Chunk
            chunk = delta.getRevised();
            index = chunk.getAnchor(); // Index in the filtered array
            if (index < revArrayFiltered.length) {
                adjustedAnchor = revArrayFiltered[index].lineNumber; // Map back to original line number
            } else {
                 // If the anchor is beyond the filtered array, map it to the end of the revised array
                adjustedAnchor = revArray.length;
            }

            size = chunk.getSize(); // Size in the filtered array
             if (size > 0) {
                int endIndex = index + size - 1;
                if (endIndex < revArrayFiltered.length) {
                    // Calculate size based on original line numbers
                    adjustedSize = revArrayFiltered[endIndex].lineNumber - adjustedAnchor + 1;
                 } else if (index < revArrayFiltered.length) {
                    // If end is out but start is in, size goes to the end of the revised array
                     adjustedSize = revArray.length - adjustedAnchor;
                 } else {
                    // If start is also out, size is 0 in the revised context
                     adjustedSize = 0;
                 }
            } else {
                 adjustedSize = 0;
             }
            chunk.setAnchor(adjustedAnchor);
            chunk.setSize(adjustedSize);
        }
    }

    private JMString[] filter(Ignore ignore, Object[] array) {
        List<JMString> result;
        JMString jms;
        int lineNumber;

        synchronized (inputLine) {
            result = new ArrayList<>(array.length);
            lineNumber = -1;
            for (Object o : array) {
                lineNumber++;

                inputLine.clear();
                inputLine.put(o.toString());
                removeIgnoredChars(inputLine, ignore, outputLine);
                // Skip entirely blank lines if ignoreBlankLines is true AFTER filtering whitespace/EOL
                if (outputLine.remaining() == 0 && ignore.ignoreBlankLines) {
                   continue;
                }
                 // Represent originally blank lines (that weren't ignored) with a newline if ignoreBlankLines is false
                if (outputLine.remaining() == 0 && !ignore.ignoreBlankLines) {
                     outputLine.clear();
                     outputLine.put('\n');
                     outputLine.flip();
                 }

                jms = new JMString();
                jms.s = outputLine.toString();
                jms.lineNumber = lineNumber;
                result.add(jms);
            }
        }

        return result.toArray(new JMString[result.size()]);
    }

    static class JMString {
        String s;
        int lineNumber;

        @Override
        public int hashCode() {
            return s.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            JMString jmString = (JMString) o;
            return s.equals(jmString.s);
        }

        @Override
        public String toString() {
           // Don't include line number here, it interferes with DiffUtils equality
           return s;
        }
    }


    public static boolean isEOL(int character) {
        return character == '\n' || character == '\r';
    }

    /** Remove all characters from the 'line' that can be ignored.
     *  @param  inputLine char[] representing a line.
     *  @param  ignore an object with the ignore options.
     *  @param  outputLine return value which contains all characters from line that cannot be
     *          ignored. It is a parameter that can be reused (which is important for
     *          performance)
     */
    public static void removeIgnoredChars(CharBuffer inputLine, Ignore ignore, CharBuffer outputLine) {
        int length;
        int contentEndIndex = -1; // Index after the last non-EOL char
        int contentStartIndex = -1; // Index of the first non-whitespace char
        boolean containsNonWhitespace = false;

        inputLine.flip();
        outputLine.clear();
        length = inputLine.remaining();

        // Find the effective content range, ignoring trailing EOLs
        for (int i = length - 1; i >= 0; i--) {
            if (!isEOL(inputLine.charAt(i))) {
                contentEndIndex = i;
                break;
            }
        }

        // If the line is all EOLs or empty
        if (contentEndIndex == -1) {
            if (!ignore.ignoreEOL && length > 0) {
                // Keep the first EOL if not ignored
                outputLine.put(inputLine.get(0));
            } // else: output remains empty if EOLs are ignored or line is empty
            outputLine.flip();
            return;
        }

        // Find the start of content, ignoring leading whitespace
        for (int i = 0; i <= contentEndIndex; i++) {
            if (!Character.isWhitespace(inputLine.charAt(i))) {
                contentStartIndex = i;
                containsNonWhitespace = true;
                break;
            }
        }

        // If the line consists only of whitespace (and potentially ignored EOLs)
        if (!containsNonWhitespace) {
             if (!ignore.ignoreWhitespaceAtBegin && !ignore.ignoreWhitespaceAtEnd && !ignore.ignoreWhitespaceInBetween) {
                 // Keep the whitespace if no whitespace ignore flags are set
                 for (int i = 0; i <= contentEndIndex; i++) {
                     outputLine.put(inputLine.get(i));
                 }
             } // else: output remains empty if any whitespace ignore is active
             outputLine.flip();
             return;
        }

        boolean lastWrittenWasSpace = false;
        for (int i = 0; i <= contentEndIndex; i++) {
            char c = inputLine.get(i);
            boolean isWhitespace = Character.isWhitespace(c);

            // Leading whitespace check
            if (i < contentStartIndex) {
                if (ignore.ignoreWhitespaceAtBegin) {
                    continue;
                }
                // else: fall through to handle as normal whitespace
            }

            // Trailing whitespace check (before EOLs, which were already handled)
            boolean isTrailingWhitespace = true;
            for (int j = i; j <= contentEndIndex; j++) {
                if (!Character.isWhitespace(inputLine.get(j))) {
                    isTrailingWhitespace = false;
                    break;
                }
            }
            if (isTrailingWhitespace && isWhitespace) {
                if (ignore.ignoreWhitespaceAtEnd) {
                    continue;
                }
                // else: fall through to handle as normal whitespace
            }

            // In-between whitespace check
            if (isWhitespace) {
                if (ignore.ignoreWhitespaceInBetween) {
                    if (!lastWrittenWasSpace) { // Write only one space for multiple ignored spaces
                       // We don't actually write the space here, we let the next non-space character handle it if needed
                       lastWrittenWasSpace = true; // Mark that we encountered whitespace
                    }
                     continue; // Skip writing this whitespace character
                } else {
                    // Keep whitespace if not ignoring in-between
                    outputLine.put(c);
                    lastWrittenWasSpace = true; // Track that a space was written
                }
            } else {
                 // Non-whitespace character
                 if (lastWrittenWasSpace && ignore.ignoreWhitespaceInBetween) {
                    // If we skipped spaces due to ignoreWhitespaceInBetween, add a single space delimiter now
                     // outputLine.put(' '); // Decided against adding synthetic space
                 }
                char charToWrite = ignore.ignoreCase ? Character.toLowerCase(c) : c;
                outputLine.put(charToWrite);
                lastWrittenWasSpace = false;
            }
        }

        // Append EOL if not ignored (only if original line had one)
        if (!ignore.ignoreEOL && length > contentEndIndex + 1) {
            // Add the first EOL char encountered after content
             for (int i = contentEndIndex + 1; i < length; i++) {
                 if (isEOL(inputLine.charAt(i))) {
                     outputLine.put(inputLine.get(i));
                     // Handle CRLF specifically if needed, assuming we only keep one EOL char type
                     if (inputLine.get(i) == '\r' && i + 1 < length && inputLine.get(i + 1) == '\n') {
                          // outputLine.put('\n'); // Optional: Normalize to LF or keep original pair
                     }
                     break; // Only add one EOL marker
                 }
             }
        }

        outputLine.flip();
    }

}
