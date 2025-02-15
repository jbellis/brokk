package io.github.jbellis.brokk;

import org.jline.reader.Candidate;
import org.msgpack.core.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Completions {
    @VisibleForTesting
    static List<Candidate> completeClassesAndMembers(String input, IAnalyzer analyzer, boolean returnFqn) {
        var allClasses = analyzer.getAllClasses();
        String partial = input.trim();

        var matchingClasses = findClassesForMemberAccess(input, allClasses);
        if (matchingClasses.size() == 1) {
            // find matching members
            List<Candidate> results = new ArrayList<>();
            for (var matchedClass : matchingClasses) {
                String memberPrefix = partial.substring(partial.lastIndexOf(".") + 1);
                // Add members
                var trueMembers = analyzer.getMembersInClass(matchedClass).stream().filter(m -> !m.contains("$")).toList();
                for (String fqMember : trueMembers) {
                    String shortMember = fqMember.substring(fqMember.lastIndexOf('.') + 1);
                    if (shortMember.startsWith(memberPrefix)) {
                        String display = returnFqn ? fqMember : getShortClassName(matchedClass) + "." + shortMember;
                        results.add(new Candidate(display, display, null, null, null, null, true));
                    }
                }
            }
            return results;
        }

        // Otherwise, we're completing class names
        String partialLower = partial.toLowerCase();
        Set<String> matchedClasses = new TreeSet<>();

        // Gather matching classes
        if (partial.isEmpty()) {
            matchedClasses.addAll(allClasses);
        } else {
            var st = returnFqn ? allClasses.stream() : allClasses.stream().map(Completions::getShortClassName);
            st.forEach(name -> {
                if (name.toLowerCase().startsWith(partialLower)
                        || getShortClassName(name).toLowerCase().startsWith(partialLower)) {
                    matchedClasses.add(name);
                }
            });

            matchedClasses.addAll(getClassnameMatches(partial, allClasses));
        }

        // Return just the class names
        return matchedClasses.stream()
                .map(fqClass -> {
                    String display = returnFqn ? fqClass : getShortClassName(fqClass);
                    return new Candidate(display, display, null, null, null, null, false);
                })
                .collect(Collectors.toList());
    }

    /**
     * Return the FQCNs corresponding to input if it identifies an unambiguous class in [the FQ] allClasses
     */
    static Set<String> findClassesForMemberAccess(String input, List<String> allClasses) {
        // suppose allClasses = [a.b.Do, a.b.Do$Re, d.Do, a.b.Do$Re$Sub]
        // then we want
        // a -> []
        // a.b -> []
        // a.b.Do -> []
        // a.b.Do. -> [a.b.Do]
        // Do -> []
        // Do. -> [a.b.Do, d.Do]
        // Do.foo -> [a.b.Do, d.Do]
        // foo -> []
        // Do$Re -> []
        // Do$Re. -> [a.b.Do$Re]
        // Do$Re$Sub -> [a.b.Do$ReSub]

        // Handle empty or null inputs
        if (input == null || input.isEmpty() || allClasses == null) {
            return Set.of();
        }

        // first look for an unambiguous match to the entire input
        var lowerCase = input.toLowerCase();
        var prefixMatches = allClasses.stream()
                .filter(className -> className.toLowerCase().startsWith(lowerCase)
                        || getShortClassName(className).toLowerCase().startsWith(lowerCase))
                .collect(Collectors.toSet());
        if (prefixMatches.size() == 1) {
            return prefixMatches;
        }

        if (input.lastIndexOf(".") < 0) {
            return Set.of();
        }

        // see if the input-before-dot is a classname
        String possibleClassname = input.substring(0, input.lastIndexOf("."));
        return allClasses.stream()
                .filter(className -> className.equalsIgnoreCase(possibleClassname)
                        || getShortClassName(className).equalsIgnoreCase(possibleClassname))
                .collect(Collectors.toSet());
    }

    /**
     * This only does syntactic parsing, if you need to verify whether the parsed element
     * is actually a class, getUniqueClass() may be what you want
     */
    static String getShortClassName(String fqClass) {
        // a.b.C -> C
        // a.b.C. -> C
        // C -> C
        // a.b.C$D -> C$D
        // a.b.C$D. -> C$D.

        int lastDot = fqClass.lastIndexOf('.');
        if (lastDot == -1) {
            return fqClass;
        }

        // Handle trailing dot
        if (lastDot == fqClass.length() - 1) {
            int nextToLastDot = fqClass.lastIndexOf('.', lastDot - 1);
            return fqClass.substring(nextToLastDot + 1, lastDot);
        }

        return fqClass.substring(lastDot + 1);
    }

    /**
     * Given a non-fully qualified classname, complete it with camel case or prefix matching
     * to a FQCN
     */
    static Set<String> getClassnameMatches(String partial, List<String> allClasses) {
        var partialLower = partial.toLowerCase();
        var nameMatches = new HashSet<String>();
        for (String fqClass : allClasses) {
            // fqClass = a.b.c.FooBar$LedZep

            // Extract the portion after the last '.' and the last '$' if present
            // simpleName = FooBar$LedZep
            String simpleName = fqClass;
            int lastDot = fqClass.lastIndexOf('.');
            if (lastDot >= 0) {
                simpleName = fqClass.substring(lastDot + 1);
            }

            // Now also strip off nested classes for simpler matching
            // simpleName = LedZep
            int lastDollar = simpleName.lastIndexOf('$');
            if (lastDollar >= 0) {
                simpleName = simpleName.substring(lastDollar + 1);
            }

            // Check for simple prefix match
            if (simpleName.toLowerCase().startsWith(partialLower)) {
                nameMatches.add(fqClass);
            } else {
                var capitals = extractCapitals(simpleName);
                if (capitals.toLowerCase().startsWith(partialLower)) {
                    nameMatches.add(fqClass);
                }
            }
        }
        return nameMatches;
    }

    static String extractCapitals(String base) {
        StringBuilder capitals = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isUpperCase(c)) {
                capitals.append(c);
            }
        }
        return capitals.toString();
    }

    /**
     * Splits the given string by quoted or unquoted segments.
     */
    static List<String> parseQuotedFilenames(String args) {
        var pattern = Pattern.compile("\"([^\"]+)\"|\\S+");
        return pattern.matcher(args)
                .results()
                .map(m -> m.group(1) != null ? m.group(1) : m.group())
                .toList();
    }

    /**
     * Expand paths that may contain wildcards (*, ?), returning all matches.
     */
    static List<RepoFile> expandPath(Path root, String pattern) {
        // First check if it exists as a relative filename from root
        var file = new RepoFile(root, pattern);
        if (file.exists()) {
            return List.of(file);
        }

        // Handle glob patterns
        if (pattern.contains("*") || pattern.contains("?")) {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (var stream = Files.walk(root)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(root.relativize(p)))
                        .map(p -> new RepoFile(root, root.relativize(p)))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // If not a glob and doesn't exist directly, look for matches in git tracked files
        var filename = Path.of(pattern).getFileName().toString();
        var matches = ContextManager.getTrackedFiles().stream()
                .filter(p -> p.getFileName().equals(filename))
                .toList();

        if (matches.isEmpty()) {
            return List.of();
        }
        if (matches.size() > 1) {
            return List.of();
        }

        return matches;
    }
}
