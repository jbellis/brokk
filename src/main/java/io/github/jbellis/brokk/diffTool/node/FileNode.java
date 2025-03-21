
package io.github.jbellis.brokk.diffTool.node;


import io.github.jbellis.brokk.diffTool.doc.FileDocument;

import java.io.File;

public class FileNode implements Comparable<FileNode> , BufferNode {
  private final String name;
  private final File file;
  private long fileLastModified;
  private FileDocument document;

  public FileNode(String name, File file) {
    this.name = name;
    this.file = file;
  }

  public String getName() {
    return name;
  }

  public FileDocument getDocument() {
    if (document == null || file.lastModified() != fileLastModified) {
      document = new FileDocument(file);
      fileLastModified = file.lastModified();
    }
    return document;
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

