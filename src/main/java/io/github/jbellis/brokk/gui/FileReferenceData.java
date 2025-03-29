package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.ProjectFile;

/**
 * Represents a file reference with metadata for context menu usage.
 */
public class FileReferenceData {
    private final String fileName;
    private final String fullPath;
    private final ProjectFile projectFile; // Optional, if available

    public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
        this.fileName = fileName;
        this.fullPath = fullPath;
        this.projectFile = projectFile;
    }

    // Getters
    public String getFileName() { return fileName; }
    public String getFullPath() { return fullPath; }
    public ProjectFile getRepoFile() { return projectFile; }

    @Override
    public String toString() {
        return fileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileReferenceData that = (FileReferenceData) o;
        return fullPath.equals(that.fullPath);
    }

    @Override
    public int hashCode() {
        return fullPath.hashCode();
    }
}
