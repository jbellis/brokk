
package io.github.jbellis.brokk.difftool.node;


import io.github.jbellis.brokk.difftool.doc.FileDocument;

import java.io.File;

public class FileNode implements Comparable<FileNode>, BufferNode {
    private final String name;
    private final File file;
    private final FileDocument document;

    public FileNode(String name, File file) {
        this.name = name;
        this.file = file;

        // Create the FileDocument up front, once. No repeated reads:
        this.document = new FileDocument(file, name);
    }

    @Override
    public FileDocument getDocument() {
        return document;  // Just return the one we have
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return (file != null) ? file.length() : 0;
    }

    @Override
    public int compareTo(FileNode other) {
        return name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof FileNode) && name.equals(((FileNode) o).getName());
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}

