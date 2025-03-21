package io.github.jbellis.brokk.diffTool.node;

import io.github.jbellis.brokk.diffTool.doc.StringDocument;

public class StringNode implements Comparable<StringNode>, BufferNode {
    private final String name;
    private String content;
    private StringDocument document;

    public StringNode(String name, String content) {
        this.name = name;
        this.content = (content != null) ? content : "";
    }

    public String getName() {
        return name;
    }

    public StringDocument getDocument() {
        if (document == null) {
            document = new StringDocument(content);
        }
        return document;
    }

    public long getSize() {
        return content.length();
    }

    @Override
    public int compareTo(StringNode other) {
        return name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof StringNode) && name.equals(((StringNode) o).getName());
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "StringNode[name=" + name + "]";
    }
}
