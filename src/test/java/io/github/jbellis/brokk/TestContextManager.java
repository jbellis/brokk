package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

class TestContextManager implements IContextManager {
    private final Path root;
    private final Set<ProjectFile> validFiles;

    public TestContextManager(Path root, Set<String> validFiles) {
        this.root = root;
        this.validFiles = validFiles.stream().map(f -> new ProjectFile(root, Path.of(f))).collect(Collectors.toSet());
    }

    @Override
    public ProjectFile toFile(String relName) {
        return new ProjectFile(root, Path.of(relName));
    }

    @Override
    public Set<ProjectFile> getEditableFiles() {
        return validFiles;
    }
}
