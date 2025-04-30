package io.github.jbellis.brokk.analyzer;

import java.util.Arrays;
import java.util.List;

public enum Language {
    JAVA("java"),
    PYTHON("py"),
    C_SHARP("cs"),
    NONE();  // no extensions

    private final List<String> extensions;

    /**
     * Associates this language with one or more file extensions.
     *
     * @param extensions the file extensions for this language
     */
    Language(String... extensions) {
        this.extensions = Arrays.asList(extensions);
    }

    public List<String> getExtensions() {
        return extensions;
    }
}

