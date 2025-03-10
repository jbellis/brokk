package io.github.jbellis.brokk;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Completions {
    public static List<String> completeClassesAndMembers(String input, IAnalyzer analyzer, boolean returnFqn) {
        var allCodeUnits = analyzer.getAllClasses();
        var allClassnames = allCodeUnits.stream().map(CodeUnit::reference).toList();
        String partial = input.trim();
        Set<String> uniqueCompletions = new HashSet<>();
        
        // Handle empty input - return all classes
        if (partial.isEmpty()) {
            uniqueCompletions.addAll(allClassnames);
        } else {
            var matchingMembers = findClassesForMemberAccess(input, allClassnames).stream()
                    .flatMap(fqcn -> analyzer.getMembersInClass(fqcn).stream())
                    .map(CodeUnit::reference)
                    .toList();
            // Otherwise, we're completing class names with prioritized matching similar to getFileCompletions
            String partialLower = partial.toLowerCase();
            uniqueCompletions.addAll(matchingMembers);
        }

        // Return the class names in the requested format (FQN or short name)
        return uniqueCompletions.stream()
                .map(fqClass -> returnFqn ? fqClass : getShortClassName(fqClass))
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

    public static String extractCapitals(String base) {
        StringBuilder capitals = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isUpperCase(c)) {
                capitals.append(c);
            }
        }
        return capitals.toString();
    }

    /**
     * Expand paths that may contain wildcards (*, ?), returning all matches.
     */
    public static List<? extends BrokkFile> expandPath(GitRepo repo, String pattern) {
        // First check if this is a single file
        var file = maybeExternalFile(repo.getRoot(), pattern);
        if (file.exists()) {
            return List.of(file);
        }

        // Handle relative path
        var repoFile = (RepoFile)file;
        if (repoFile.exists()) {
            return List.of(repoFile);
        }

        // Handle glob patterns
        if (pattern.contains("*") || pattern.contains("?")) {
            var parent = Path.of(pattern).getParent();
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (var stream = Files.walk(parent)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .map(p -> maybeExternalFile(repo.getRoot(), p.toString()))
                        .toList();
            } catch (IOException e) {
                // part of the path doesn't exist
                return List.of();
            }
        }

        // If not a glob and doesn't exist directly, look for matches in git tracked files
        var filename = Path.of(pattern).getFileName().toString();
        var matches = repo.getTrackedFiles().stream()
                .filter(p -> p.getFileName().equals(filename))
                .toList();
        if (matches.size() != 1) {
            return List.of();
        }

        return matches;
    }

    public static BrokkFile maybeExternalFile(Path root, String pathStr) {
        Path p = Path.of(pathStr).toAbsolutePath();
        if (!p.startsWith(root)) {
            return new ExternalFile(p);
        }
        return new RepoFile(root, root.relativize(p));
    }

    @NotNull
    public static List<RepoFile> getFileCompletions(String input, Collection<RepoFile> repoFiles) {
        String partialLower = input.toLowerCase();
        Map<String, RepoFile> baseToFullPath = new HashMap<>();
        var uniqueCompletions = new HashSet<RepoFile>();

        for (RepoFile p : repoFiles) {
            baseToFullPath.put(p.getFileName(), p);
        }

        // Matching base filenames (priority 1)
        baseToFullPath.forEach((base, file) -> {
            if (base.toLowerCase().startsWith(partialLower)) {
                uniqueCompletions.add(file);
            }
        });

        // Camel-case completions (priority 2)
        baseToFullPath.forEach((base, file) -> {
            String capitals = extractCapitals(base);
            if (capitals.toLowerCase().startsWith(partialLower)) {
                uniqueCompletions.add(file);
            }
        });

        // Matching full paths (priority 3)
        for (RepoFile file : repoFiles) {
            if (file.toString().toLowerCase().startsWith(partialLower)) {
                uniqueCompletions.add(file);
            }
        }

        // Sort completions by filename, then by full path
        return uniqueCompletions.stream()
                .sorted((f1, f2) -> {
                    // Compare filenames first
                    int result = f1.getFileName().compareTo(f2.getFileName());
                    if (result == 0) {
                        // If filenames match, compare by full path
                        return f1.toString().compareTo(f2.toString());
                    }
                    return result;
                })
                .toList();
    }
}
