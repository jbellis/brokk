package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.Map;

/**
 * Utility class to detect the appropriate RSyntaxTextArea syntax style based on file extension.
 */
public final class SyntaxDetector {
    // Map file extensions (lowercase) to RSyntaxTextArea SyntaxConstants
    private static final Map<String, String> EXT_TO_SYNTAX = Map.ofEntries(
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS),
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("gradle", SyntaxConstants.SYNTAX_STYLE_GROOVY), // Often Groovy syntax
            Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("hpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP)
    );

    // Private constructor to prevent instantiation
    private SyntaxDetector() {}

    /**
     * Detects the syntax style for a given BrokkFile based on its extension.
     * Defaults to {@link SyntaxConstants#SYNTAX_STYLE_NONE} if the extension is unknown.
     *
     * @param bf The BrokkFile.
     * @return The corresponding SyntaxConstants string, or SYNTAX_STYLE_NONE.
     */
    public static String detect(BrokkFile bf) {
        if (bf == null) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        // Extract extension from the absolute path's filename
        var filename = bf.absPath().getFileName().toString();
        var dotIndex = filename.lastIndexOf('.');
        // Handle no extension or file ending with "."
        var ext = (dotIndex == -1 || dotIndex == filename.length() - 1) ? "" : filename.substring(dotIndex + 1).toLowerCase();
        return EXT_TO_SYNTAX.getOrDefault(ext, SyntaxConstants.SYNTAX_STYLE_NONE);
    }
}
