package io.github.jbellis.brokk.difftool.diff;

public enum TypeDiff {
    ADD("Add"),
    DELETE("Del"),
    CHANGE("Change");

    final String description;

    TypeDiff(String description) {
        this.description = description;
    }

    public String toString() {
        return description;
    }
}
