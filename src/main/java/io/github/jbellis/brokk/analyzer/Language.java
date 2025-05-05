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

    /**
     * Returns the Language enum constant corresponding to the given file extension.
     * Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    public static Language fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return NONE;
        }
        String lowerExt = extension.toLowerCase();
        for (Language lang : Language.values()) {
            // Check if the lowercase extension is in the language's known extensions
            if (lang.extensions.contains(lowerExt)) {
                return lang;
            }
        }
        // No matching language found for the extension
        return NONE;
    }
}

