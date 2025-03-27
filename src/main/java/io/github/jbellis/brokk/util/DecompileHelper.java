package io.github.jbellis.brokk.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class DecompileHelper {
    private static final Logger logger = LogManager.getLogger(DecompileHelper.class);

    // extractJarToTemp method (no changes needed)
    public static void extractJarToTemp(Path jarPath, Path targetDir) throws IOException {
        // Ensure target directory exists and is a directory
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir); // Create if not exists
            // throw new IOException("Target path is not a directory: " + targetDir);
        }
        // Use try-with-resources to ensure the ZipFile is closed
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            var entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                // Resolve and normalize, then ensure it's within the target directory
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // --- Zip Slip Protection ---
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry is trying to escape the target directory: " + entry.getName());
                }
                // --- End Zip Slip Protection ---

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // Ensure parent directories exist for the file
                    Files.createDirectories(entryPath.getParent());
                    // Use try-with-resources for the input stream
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    // deleteDirectoryRecursive method (no changes needed)
    public static void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    logger.warn("Failed to delete file: {} ({})", file, e.getMessage());
                    // Attempt to force delete on Windows? Or just log and continue.
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    logger.warn("Error visiting directory contents for deletion: {} ({})", dir, exc.getMessage());
                    throw exc; // Propagate error
                }
                try {
                    Files.delete(dir);
                } catch (IOException e) {
                    logger.warn("Failed to delete directory: {} ({})", dir, e.getMessage());
                    // If directory is not empty, this might fail.
                    // Consider retries or more robust deletion if needed.
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                logger.warn("Failed to access file for deletion: {} ({})", file, exc.getMessage());
                // Decide if failure should stop the process
                return FileVisitResult.CONTINUE; // Continue deletion attempt
            }
        });
    }

    /**
     * Finds JAR files in common dependency cache locations.
     * This performs I/O and should not be called on the EDT.
     * @return A list of paths to discovered JAR files.
     */
    public static List<Path> findCommonDependencyJars() {
        long startTime = System.currentTimeMillis();
        List<Path> jarFiles = new ArrayList<>();
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory.");
            return jarFiles;
        }
        Path homePath = Path.of(userHome);

        List<Path> rootsToScan = List.of(
                homePath.resolve(".m2").resolve("repository"),
                homePath.resolve(".gradle").resolve("caches").resolve("modules-2").resolve("files-2.1"),
                homePath.resolve(".ivy2").resolve("cache"),
                homePath.resolve(".cache").resolve("coursier").resolve("v1").resolve("https"), // Adjust based on actual Coursier structure if needed
                homePath.resolve(".sbt") // SBT caches can be complex, start broad
                // Add other potential locations if known
        );

        int maxDepth = 15; // Limit recursion depth to avoid excessive scanning time or cycles

        for (Path root : rootsToScan) {
            if (Files.isDirectory(root)) {
                logger.debug("Scanning for JARs under: {}", root);
                try (Stream<Path> stream = Files.walk(root, maxDepth, FileVisitOption.FOLLOW_LINKS)) {
                    stream.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                            // Additional filter: exclude source and javadoc jars
                            .filter(path -> !path.getFileName().toString().toLowerCase().endsWith("-sources.jar"))
                            .filter(path -> !path.getFileName().toString().toLowerCase().endsWith("-javadoc.jar"))
                            .forEach(jarFiles::add);
                } catch (IOException e) {
                    logger.warn("Error walking directory {}: {}", root, e.getMessage());
                } catch (SecurityException e) {
                    logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                }
            } else {
                logger.debug("Dependency cache directory not found or not a directory: {}", root);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Found {} JAR files in common dependency locations in {} ms", jarFiles.size(), duration);
        return jarFiles;
    }
}
