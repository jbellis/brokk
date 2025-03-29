package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

public class LocalFileRepo implements IGitRepo {
    private static final Logger logger = LogManager.getLogger(LocalFileRepo.class);
    private final Path root;

    public LocalFileRepo(Path root) {
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Root path must be an existing directory");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Set<ProjectFile> getTrackedFiles() {
        try (var pathStream = Files.walk(root)) {
            return pathStream
                .filter(Files::isRegularFile)
                .map(path -> {
                    var relPath = root.relativize(path);
                    return new ProjectFile(root, relPath);
                })
                .collect(Collectors.toSet());
        } catch (IOException e) {
            logger.error("Error walking directory tree", e);
            return Set.of();
        }
    }
}
