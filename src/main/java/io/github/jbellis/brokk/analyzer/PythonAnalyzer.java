package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterPython; // Import the specific language class

import java.nio.file.Files;
import java.nio.file.Path; // Add import for Path
import java.util.Set;

public final class PythonAnalyzer extends TreeSitterAnalyzer {

    public PythonAnalyzer(IProject project) {
        super(project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return new TreeSitterPython(); // Instantiate the bonede language object
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/python.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName) {
        // Calculate package path based on directory structure relative to project root
        String packagePath = computePythonPackagePath(file); // e.g., "a" for "a/A.py"

        // Extract module name from filename using the inherited getFileName() method
        String moduleName = file.getFileName();
        if (moduleName.endsWith(".py")) {
            moduleName = moduleName.substring(0, moduleName.length() - 3); // e.g., "A"
        }

        try {
            return switch (captureName) {
                // e.g., file=a/A.py, packagePath=a, simpleName=A
                // -> CodeUnit.cls(file, "a", "A") => fqName="a.A"
                case "class.definition" -> CodeUnit.cls(file, packagePath, simpleName);

                // e.g., file=a/A.py, packagePath=a, simpleName=funcA (top-level function)
                // -> CodeUnit.fn(file, "a", "A.funcA") => fqName="a.A.funcA"
                // Note: We use ModuleName as the "class" context for top-level functions' shortName.
                case "function.definition" -> CodeUnit.fn(file, packagePath, moduleName + "." + simpleName);

                // TODO: Handle methods within classes correctly if the query captures them
                // TODO: Add case for "field.definition" if the query is updated to capture fields

                default -> {
                    // Log or handle unexpected captures if necessary
                    log.debug("Ignoring capture: {} with name: {}", captureName, simpleName);
                    yield null; // Returning null ignores the capture
                }
            };
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create CodeUnit for capture '{}', name '{}', file '{}': {}",
                     captureName, simpleName, file, e.getMessage());
            return null; // Return null on error to avoid breaking the analysis stream
        }
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // Python query uses "@obj (#eq? @obj \"self\")" predicate helper, ignore the @obj capture
        return Set.of("obj");
    }

    @Override
    protected String bodyPlaceholder() {
        return "…";
    }

    /**
     * Computes the Python package path based on directory structure relative to project root and __init__.py files.
     * Does *not* include the filename/module name itself.
     * @param file The file to get the package path for
     * @return The package path (e.g., "a" for "a/A.py", "" for top-level "B.py")
     */
    private String computePythonPackagePath(ProjectFile file) {
        var absPath = file.absPath();
        var projectRoot = getProject().getRoot();
        var parentDir = absPath.getParent();

        // If the file is directly in the project root, the package path is empty
        if (parentDir == null || parentDir.equals(projectRoot)) {
            return "";
        }

        // Find the effective package root by checking for __init__.py files upwards
        var current = parentDir;
        var packageRoot = projectRoot; // Default to project root if no __init__.py found above

        while (current != null && !current.equals(projectRoot)) {
            if (Files.exists(current.resolve("__init__.py"))) {
                // Found an __init__.py, this directory is part of the package path, continue checking parent
                 packageRoot = current; // Keep track of the highest dir with __init__.py that is *below* parentDir
            } else {
                 // No __init__.py here, the package path effectively stops *below* this directory
                 // If packageRoot is still projectRoot, it means no __init__.py was found at all in the hierarchy.
                 // If packageRoot points to a directory below 'current', that's our limit.
                 break; // Stop searching upwards
            }
             // Special case: If the immediate parent has __init__.py, its parent is the effective root.
             // Need to find the highest directory containing __init__.py *between* projectRoot and parentDir.
            current = current.getParent();
        }


        // Find the highest directory containing __init__.py between project root and the file's parent
        var effectivePackageRoot = projectRoot;
        current = parentDir;
        while (current != null && !current.equals(projectRoot)) {
            if (Files.exists(current.resolve("__init__.py"))) {
                effectivePackageRoot = current; // Found a potential root, keep checking higher
            }
            current = current.getParent();
        }


        // Calculate the relative path from the effective package root's PARENT
        // to the file's parent directory.
        Path rootForRelativize = effectivePackageRoot.equals(projectRoot) ? projectRoot : effectivePackageRoot.getParent();
        if (rootForRelativize == null) rootForRelativize = projectRoot; // Safety for top-level package roots


        // Calculate the path parts that form the package name
        var relPath = rootForRelativize.relativize(parentDir);

        // Convert path separators to dots for package name
        // Handle edge case where parentDir is the rootForRelativize (empty relative path)
        if (relPath.toString().isEmpty()) {
            return "";
        } else {
            return relPath.toString().replace('/', '.').replace('\\', '.');
        }
    }
}
