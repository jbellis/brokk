package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class Completions {
    public static List<CodeUnit> completeClassesAndMembers(String input, IAnalyzer analyzer) {
        String partial = input.trim();

        // Gather matching classes
        if (partial.isEmpty()) {
            return analyzer.getAllClasses().stream().toList();
        }

        var matches = new HashSet<>(analyzer.getDefinitions(".*" + input + ".*"));
        if (partial.toUpperCase().equals(partial)) {
            // split the characters apart with .* between for camel case matches
            String camelCasePattern = ".*%s.*".formatted(String.join(".*", partial.split("")));
            matches.addAll(analyzer.getDefinitions(camelCasePattern));
        }

        // sort by whether the name starts with the input string (ignoring case), then alphabetically
        return matches.stream()
                .sorted(Comparator.<CodeUnit, Boolean>comparing(codeUnit -> 
                    codeUnit.name().toLowerCase().startsWith(partial.toLowerCase())
                ).reversed()
                .thenComparing(CodeUnit::toString))
                .toList();
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
    public static List<? extends BrokkFile> expandPath(IProject project, String pattern) {
        // First check if this is a single file
        var file = maybeExternalFile(project.getRoot(), pattern);
        if (file.exists()) {
            return List.of(file);
        }

        // Handle glob patterns [only in the last part of the path]
        if (pattern.contains("*") || pattern.contains("?")) {
            Path parent = file.absPath().getParent();
            while (parent.toString().contains("*") || parent.toString().contains("?")) {
                parent = parent.getParent();
            }
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + file.absPath());
            try (var stream = Files.walk(parent)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .map(p -> maybeExternalFile(project.getRoot(), p.toString()))
                        .toList();
            } catch (IOException e) {
                // part of the path doesn't exist
                return List.of();
            }
        }

        // If not a glob and doesn't exist directly, look for matches in git tracked files
        var filename = Path.of(pattern).getFileName().toString();
        var matches = project.getFiles().stream()
                .filter(p -> p.getFileName().equals(filename))
                .toList();
        if (matches.size() != 1) {
            return List.of();
        }

        return matches;
    }

    public static BrokkFile maybeExternalFile(Path root, String pathStr) {
        Path p = Path.of(pathStr);
        if (!p.isAbsolute()) {
            return new ProjectFile(root, p);
        }
        if (!p.startsWith(root)) {
            return new ExternalFile(p);
        }
        // we have an absolute path that's part of the project
        return new ProjectFile(root, root.relativize(p));
    }

    @NotNull
    public static List<ProjectFile> getFileCompletions(String input, Collection<ProjectFile> projectFiles) {
        String partialLower = input.toLowerCase();
        Map<String, ProjectFile> baseToFullPath = new HashMap<>();
        var uniqueCompletions = new HashSet<ProjectFile>();

        for (ProjectFile p : projectFiles) {
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
        for (ProjectFile file : projectFiles) {
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
