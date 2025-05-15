package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Decompiler {
    private static final Logger logger = LogManager.getLogger(Decompiler.class);

    public record MavenCoordinates(String groupId, String artifactId, String version) {}

    /**
     * Imports the specified JAR file. If it's a sources JAR, it's extracted.
     * If it's a binary JAR, attempts to find and download its sources.
     * If sources are found/downloaded, they are extracted. Otherwise, the binary JAR is decompiled.
     *
     * @param io The Chrome instance for UI feedback.
     * @param jarPath Path to the JAR file to import.
     * @param runner TaskRunner to run background tasks on.
     */
    public static void decompileJar(Chrome io, Path jarPath, ContextManager.TaskRunner runner) {
        try {
            String originalJarName = jarPath.getFileName().toString();
            Path originalProjectRoot = io.getContextManager().getRoot();
            Path brokkDir = originalProjectRoot.resolve(".brokk");
            Path depsDir = brokkDir.resolve("dependencies");
            Files.createDirectories(depsDir);

            String baseName = originalJarName;
            boolean isSourceJar = originalJarName.endsWith("-sources.jar");
            if (isSourceJar) {
                baseName = originalJarName.substring(0, originalJarName.length() - "-sources.jar".length());
            } else if (originalJarName.endsWith(".jar")) {
                baseName = originalJarName.substring(0, originalJarName.length() - ".jar".length());
            }
            Path outputDir = depsDir.resolve(baseName); // Target directory for extracted sources or decompiled code

            logger.info("Processing JAR: {}, Output directory: {}", originalJarName, outputDir);

            if (handleExistingOutputDirectory(io, outputDir, originalJarName)) {
                return; // User chose "No" or deletion failed
            }

            if (isSourceJar) {
                io.systemOutput("Extracting sources from " + originalJarName + "...");
                runner.submit("Extracting " + originalJarName, () -> {
                    try {
                        extractArchiveTo(jarPath, outputDir);
                        io.systemOutput("Successfully extracted sources from " + originalJarName + " to " + outputDir.getFileName() +
                                        ". Reopen project to incorporate the new source files.");
                        logDirectoryContents(outputDir);
                    } catch (IOException e) {
                        logger.error("Error extracting source JAR {}: {}", jarPath, e.getMessage(), e);
                        io.toolErrorRaw("Error extracting " + originalJarName + ": " + e.getMessage());
                    }
                    return null;
                });
            } else {
                // Binary JAR: try to find sources, then decompile if not found
                runner.submit("Processing " + originalJarName, () -> {
                    Optional<MavenCoordinates> coordsOpt = findMavenCoordinates(jarPath);
                    if (coordsOpt.isPresent()) {
                        MavenCoordinates coords = coordsOpt.get();
                        io.systemOutput("Found Maven coordinates for " + originalJarName + ": " +
                                        coords.groupId() + ":" + coords.artifactId() + ":" + coords.version());
                        io.systemOutput("Attempting to download sources...");
                        Optional<Path> sourcesJarOpt = resolveAndDownloadSourcesJar(coords, io);

                        if (sourcesJarOpt.isPresent()) {
                            Path downloadedSourcesJar = sourcesJarOpt.get();
                            io.systemOutput("Successfully downloaded sources JAR: " + downloadedSourcesJar.getFileName());
                            try {
                                extractArchiveTo(downloadedSourcesJar, outputDir);
                                io.systemOutput("Successfully extracted downloaded sources for " + originalJarName + " to " + outputDir.getFileName() +
                                                ". Reopen project to incorporate the new source files.");
                                logDirectoryContents(outputDir);
                                try { Files.deleteIfExists(downloadedSourcesJar); } // Clean up temp downloaded JAR
                                catch (IOException e) { logger.warn("Could not delete temporary sources JAR: {}", downloadedSourcesJar, e); }
                                return null; // Successfully processed sources
                            } catch (IOException e) {
                                logger.error("Error extracting downloaded sources JAR {}: {}", downloadedSourcesJar, e.getMessage(), e);
                                io.toolErrorRaw("Error extracting downloaded sources for " + originalJarName + ": " + e.getMessage() +
                                                ". Will attempt to decompile the original JAR.");
                            }
                        } else {
                            io.systemOutput("Could not download sources for " + originalJarName + ". Proceeding with decompilation.");
                        }
                    } else {
                        io.systemOutput("Maven coordinates not found in " + originalJarName + ". Proceeding with decompilation.");
                    }
                    // Fallback to decompilation
                    performFernflowerDecompilation(io, jarPath, outputDir);
                    return null;
                });
            }
        } catch (IOException e) {
            io.toolErrorRaw("Error preparing for import of " + jarPath.getFileName() + ": " + e.getMessage());
            logger.error("Error preparing for import of {}: {}", jarPath, e.getMessage(), e);
        }
    }

    private static boolean handleExistingOutputDirectory(Chrome io, Path outputDir, String jarName) throws IOException {
        if (Files.exists(outputDir)) {
            int choice = JOptionPane.showConfirmDialog(
                    io.getFrame(),
                    String.format("""
                        Output directory for %s already exists:
                        %s

                        Delete this directory and proceed with import/decompilation?
                        (Choosing 'No' will leave the existing files unchanged and cancel the operation.)
                        """, jarName, outputDir.toString()),
                    "Output Directory Exists",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );

            if (choice == JOptionPane.YES_OPTION) {
                logger.debug("User chose to delete existing output directory: {}", outputDir);
                try {
                    deleteDirectoryRecursive(outputDir);
                    Files.createDirectories(outputDir); // Recreate after deletion
                } catch (IOException e) {
                    logger.error("Failed to delete existing directory: {}", outputDir, e);
                    io.toolErrorRaw("Error deleting existing directory: " + e.getMessage());
                    return true; // Indicate failure/stop
                }
            } else {
                logger.debug("User chose not to overwrite existing directory: {}. Import cancelled.", outputDir);
                io.systemOutput("Import of " + jarName + " cancelled by user (directory exists).");
                return true; // Indicate cancellation
            }
        } else {
            Files.createDirectories(outputDir); // Create if it didn't exist
        }
        return false; // Proceed
    }


    private static void performFernflowerDecompilation(Chrome io, Path binaryJarPath, Path finalOutputDir) {
        logger.debug("Starting Fernflower decompilation for {} into {}", binaryJarPath, finalOutputDir);
        io.systemOutput("Decompiling " + binaryJarPath.getFileName() + "...");
        Path tempExtractDir = null;
        try {
            tempExtractDir = Files.createTempDirectory("fernflower-extracted-");
            logger.debug("Created temporary directory for extraction: {}", tempExtractDir);

            extractArchiveTo(binaryJarPath, tempExtractDir); // Extracts JAR contents to temp dir
            logger.debug("Extracted JAR contents to temporary directory for decompilation.");

            Map<String, Object> options = Map.of(
                    "hes", "1", // hide empty super
                    "hdc", "1", // hide default constructor
                    "dgs", "1", // decompile generic signature
                    "ren", "1"  // rename ambiguous
            );
            ConsoleDecompiler decompiler = new ConsoleDecompiler(
                    finalOutputDir.toFile(), // Decompile into the final output directory
                    options,
                    new FernflowerLoggerAdapter()
            );

            decompiler.addSource(tempExtractDir.toFile()); // Source for decompiler is the extracted content
            logger.info("Starting Fernflower decompilation process...");
            decompiler.decompileContext();
            logger.info("Fernflower decompilation process finished for {}.", binaryJarPath.getFileName());

            io.systemOutput("Decompilation of " + binaryJarPath.getFileName() + " completed. " +
                            "Reopen project to incorporate the new source files.");
            logDirectoryContents(finalOutputDir);

        } catch (Exception e) {
            io.toolErrorRaw("Error during decompilation of " + binaryJarPath.getFileName() + ": " + e.getMessage());
            logger.error("Error during Fernflower decompilation task for {}", binaryJarPath, e);
        } finally {
            if (tempExtractDir != null) {
                try {
                    logger.debug("Cleaning up temporary extraction directory: {}", tempExtractDir);
                    deleteDirectoryRecursive(tempExtractDir);
                    logger.debug("Temporary extraction directory deleted: {}", tempExtractDir);
                } catch (IOException e) {
                    logger.error("Failed to delete temporary extraction directory: {}", tempExtractDir, e);
                }
            }
        }
    }

    private static Optional<MavenCoordinates> findMavenCoordinates(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith("META-INF/maven/") && entryName.endsWith("/pom.properties") && !entry.isDirectory()) {
                    logger.debug("Found pom.properties: {} in {}", entryName, jarPath);
                    try (InputStream is = zipFile.getInputStream(entry);
                         InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        Properties props = new Properties();
                        props.load(reader);
                        String groupId = props.getProperty("groupId");
                        String artifactId = props.getProperty("artifactId");
                        String version = props.getProperty("version");
                        if (groupId != null && artifactId != null && version != null) {
                            logger.info("Parsed Maven coordinates from {}: {}:{}:{}", entryName, groupId, artifactId, version);
                            return Optional.of(new MavenCoordinates(groupId, artifactId, version));
                        } else {
                            logger.warn("pom.properties found ({}) but missing GAV coordinates.", entryName);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Error reading JAR file {} to find Maven coordinates: {}", jarPath, e.getMessage(), e);
        }
        logger.debug("No valid pom.properties with GAV found in {}", jarPath);
        return Optional.empty();
    }

    private static RepositorySystem getMavenRepositorySystem() {
        var locator = MavenRepositorySystemUtils.newServiceLocator();
        // Standard factories are auto-discovered by newServiceLocator() based on classpath.
        // This includes BasicRepositoryConnectorFactory, FileTransporterFactory,
        // and JdkHttpTransporterFactory if aether-transport-jdk-http.jar is present.
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override
            public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
                logger.error("Maven Service creation failed for type {} with impl {}: {}", type, impl, exception.getMessage(), exception);
            }
        });
        return locator.getService(RepositorySystem.class);
    }

    private static RepositorySystemSession newMavenSession(RepositorySystem system, Path localRepoPath) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        return session;
    }

    private static Optional<Path> resolveAndDownloadSourcesJar(MavenCoordinates coords, Chrome io) {
        RepositorySystem system = getMavenRepositorySystem();
        if (system == null) {
            logger.error("Failed to initialize Maven RepositorySystem.");
            io.toolErrorRaw("Failed to initialize Maven subsystem for source download.");
            return Optional.empty();
        }

        Path tempLocalRepoPath;
        try {
            tempLocalRepoPath = Files.createTempDirectory("brokk-maven-local-repo-");
        } catch (IOException e) {
            logger.error("Failed to create temporary local Maven repository directory", e);
            io.toolErrorRaw("Failed to create temp directory for Maven downloads.");
            return Optional.empty();
        }

        RepositorySystemSession session = newMavenSession(system, tempLocalRepoPath);

        Artifact sourcesArtifact = new DefaultArtifact(coords.groupId(), coords.artifactId(), "sources", "jar", coords.version());
        RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build();
        ArtifactRequest artifactRequest = new ArtifactRequest(sourcesArtifact, Collections.singletonList(central), null);

        logger.info("Attempting to resolve sources artifact: {}", sourcesArtifact);
        try {
            ArtifactResult artifactResult = system.resolveArtifact(session, artifactRequest);
            Artifact resolved = artifactResult.getArtifact();
            logger.info("Successfully resolved sources artifact {} to {}", sourcesArtifact, resolved.getFile());
            // The downloaded file is in tempLocalRepoPath. We might want to copy it out
            // or use it directly and rely on temp dir cleanup later.
            // For simplicity, let's assume it can be used from there or copy it to another temp location if needed.
            // For now, just return the path. The caller can decide to move/delete.
            return Optional.of(resolved.getFile().toPath());
        } catch (ArtifactResolutionException e) {
            logger.warn("Failed to resolve sources artifact {}: {}", sourcesArtifact, e.getMessage());
            // It's common for sources not to be available, so this might not be a severe error for the user.
            io.systemOutput("Could not find or download sources for " + coords.artifactId() + ": " + e.getMessage());
        } finally {
            // Clean up the temporary local repository
            try {
                deleteDirectoryRecursive(tempLocalRepoPath);
            } catch (IOException e) {
                logger.warn("Failed to delete temporary local Maven repository: {}", tempLocalRepoPath, e);
            }
        }
        return Optional.empty();
    }


    /**
     * Extracts all entries from an archive (JAR/ZIP) to a target directory, preserving structure.
     * @param archivePath Path to the archive file.
     * @param targetDir Path to the directory where contents will be extracted.
     * @throws IOException if an I/O error occurs.
     */
    public static void extractArchiveTo(Path archivePath, Path targetDir) throws IOException {
        if (!Files.isDirectory(targetDir)) {
            Files.createDirectories(targetDir);
        }
        try (ZipFile zipFile = new ZipFile(archivePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                if (!entryPath.startsWith(targetDir)) { // Zip Slip check
                    throw new IOException("Zip entry is trying to escape the target directory: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent()); // Ensure parent dir exists
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public static void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    logger.warn("Failed to delete file: {} ({})", file, e.getMessage());
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
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to access file for deletion: {} ({})", file, exc.getMessage());
                // Decide if failure should stop the process
                return FileVisitResult.CONTINUE; // Continue deletion attempt
            }
        });
    }

    private static void logDirectoryContents(Path dir) {
        logger.debug("Final contents of {} after operation:", dir);
        try (var pathStream = Files.walk(dir, 1)) { // Walk only one level deep for brevity
            pathStream.forEach(path -> logger.debug("   {}", path.getFileName()));
        } catch (IOException e) {
            logger.warn("Error listing output directory contents for {}", dir, e);
        }
    }

    // Fernflower Logger Adapter
    private static class FernflowerLoggerAdapter extends org.jetbrains.java.decompiler.main.extern.IFernflowerLogger {
        @Override
        public void writeMessage(String message, Severity severity) {
            switch (severity) {
                case ERROR -> logger.error("Fernflower: {}", message);
                case WARN  -> logger.warn("Fernflower: {}", message);
                case INFO  -> logger.info("Fernflower: {}", message);
                case TRACE -> logger.trace("Fernflower: {}", message);
                default    -> logger.debug("Fernflower: {}", message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable t) {
            switch (severity) {
                case ERROR -> logger.error("Fernflower: {}", message, t);
                case WARN  -> logger.warn("Fernflower: {}", message, t);
                case INFO  -> logger.info("Fernflower: {}", message, t);
                case TRACE -> logger.trace("Fernflower: {}", message, t);
                default   -> logger.debug("Fernflower: {}", message, t);
            }
        }
    }


    /**
     * Scans the user-level caches of common JVM build tools (Maven, Gradle, Ivy, Coursier, SBT)
     * on both Unix-like and Windows machines and returns every regular *.jar found,
     * including source JARs but excluding javadoc-only archives.
     */
    public static List<Path> findCommonDependencyJars() {
        long startTime = System.currentTimeMillis();

        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            logger.warn("Could not determine user home directory.");
            return List.of();
        }
        Path homePath = Path.of(userHome);

        List<Path> rootsToScan = new ArrayList<>();

        /* ---------- default locations that exist on all OSes ---------- */
        rootsToScan.add(homePath.resolve(".m2").resolve("repository"));
        rootsToScan.add(homePath.resolve(".gradle").resolve("caches")
                                .resolve("modules-2").resolve("files-2.1"));
        rootsToScan.add(homePath.resolve(".ivy2").resolve("cache"));
        rootsToScan.add(homePath.resolve(".cache").resolve("coursier")
                                .resolve("v1").resolve("https")); // For Coursier CLI
        rootsToScan.add(homePath.resolve(".sbt")); // SBT uses Ivy cache by default but can have its own structures

        /* ---------- honour user-supplied overrides ---------- */
        Optional.ofNullable(System.getenv("MAVEN_REPO"))
                .map(Path::of)
                .ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getProperty("maven.repo.local")) // Another way M2_REPO can be set
                .map(Path::of)
                .ifPresent(rootsToScan::add);

        Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                .map(Path::of)
                .map(p -> p.resolve("caches").resolve("modules-2").resolve("files-2.1"))
                .ifPresent(rootsToScan::add);
        
        Optional.ofNullable(System.getenv("IVY_HOME")) // Ivy user home
                .map(Path::of)
                .map(p -> p.resolve("cache"))
                .ifPresent(rootsToScan::add);

        /* ---------- OS-specific cache roots ---------- */
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH);
        if (osName.contains("win")) { // Windows
            Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                Path lad = Path.of(localAppData);
                rootsToScan.add(lad.resolve("Coursier").resolve("cache").resolve("v1").resolve("https"));
                rootsToScan.add(lad.resolve("sbt").resolve("boot")); // sbt boot jars
                // Gradle might also use LOCALAPPDATA if GRADLE_USER_HOME is not set, but its default is user.home
            });
        } else if (osName.contains("mac")) { // macOS
            rootsToScan.add(homePath.resolve("Library").resolve("Caches").resolve("Coursier").resolve("v1").resolve("https"));
            rootsToScan.add(homePath.resolve("Library").resolve("Caches").resolve("sbt")); // sbt cache on macOS
        } else { // Linux and other Unix-like
            rootsToScan.add(homePath.resolve(".cache").resolve("coursier").resolve("v1").resolve("https")); // Already added but good to be explicit
             // sbt default is ~/.sbt and ~/.ivy2 which are already covered
        }


        /* ---------- de-duplicate & scan ---------- */
        List<Path> uniqueRoots = rootsToScan.stream().distinct().filter(Files::isDirectory).toList();

        var jarFiles = uniqueRoots.parallelStream()
                                  .peek(root -> logger.debug("Scanning for JARs under: {}", root))
                                  .flatMap(root -> {
                                      try {
                                          // Limit depth to avoid extremely deep scans in unexpected places, e.g. node_modules within a cache
                                          return Files.walk(root, 15, FileVisitOption.FOLLOW_LINKS);
                                      } catch (IOException e) {
                                          logger.warn("Error walking directory {}: {}", root, e.getMessage());
                                          return Stream.empty();
                                      } catch (SecurityException e) {
                                          logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                                          return Stream.empty();
                                      }
                                  })
                                  .filter(Files::isRegularFile)
                                  .filter(path -> {
                                      String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
                                      // Include .jar and -sources.jar, exclude -javadoc.jar and other non-code archives
                                      return name.endsWith(".jar")
                                             && !name.endsWith("-javadoc.jar")
                                             && !name.endsWith("-doc.jar")
                                             && !name.endsWith("-docs.jar")
                                             && !name.endsWith("-manual.jar")
                                             && !name.endsWith("-tests.jar"); // Usually not primary dependency sources
                                  })
                                  .distinct() // Paths can be duplicated if symlinks create cycles or multiple roots overlap
                                  .toList();

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Found {} JAR files in common dependency locations in {} ms",
                    jarFiles.size(), duration);

        return jarFiles;
    }
}
