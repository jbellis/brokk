package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.MarkdownRenderUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import java.util.List;

/**
 * Represents a code fence block with syntax highlighting.
 */
public record CodeBlockComponentData(int id, String body, String lang) implements ComponentData {
    @Override
    public String fp() {
        return body.length() + ":" + body.hashCode();
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        var textArea = MarkdownRenderUtil.createConfiguredCodeArea(lang, body, darkTheme);
        return MarkdownRenderUtil.createCodeBlockPanel(textArea, lang, darkTheme);
    }
    
    @Override
    public void updateComponent(JComponent component) {
        // Find the RSyntaxTextArea within the panel
        var textAreas = findComponentsOfType(component, org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.class);
        if (!textAreas.isEmpty()) {
            var textArea = textAreas.getFirst();
            // Record caret position
            int caretPos = textArea.getCaretPosition();
            // Update text
            textArea.setText(body);
            // Set syntax style if changed
            textArea.setSyntaxEditingStyle(getSyntaxStyle(lang));
            // Restore caret if in valid range
            if (caretPos >= 0 && caretPos <= textArea.getDocument().getLength()) {
                textArea.setCaretPosition(caretPos);
            }
        }
    }
    
    /**
     * Gets the appropriate syntax style constant for a language.
     */
    private String getSyntaxStyle(String lang) {
        if (lang == null || lang.isEmpty()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        
        return switch(lang.toLowerCase()) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python", "py" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript", "js" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "json" -> SyntaxConstants.SYNTAX_STYLE_JSON;
            case "html" -> SyntaxConstants.SYNTAX_STYLE_HTML;
            case "xml" -> SyntaxConstants.SYNTAX_STYLE_XML;
            case "css" -> SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sql" -> SyntaxConstants.SYNTAX_STYLE_SQL;
            case "bash", "sh" -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }
    
    /**
     * Finds all components of a specific type within a container.
     */
    private <T extends java.awt.Component> List<T> findComponentsOfType(java.awt.Container container, Class<T> type) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (java.awt.Component comp : container.getComponents()) {
            if (type.isInstance(comp)) {
                result.add(type.cast(comp));
            }
            if (comp instanceof java.awt.Container) {
                result.addAll(findComponentsOfType((java.awt.Container) comp, type));
            }
        }
        return result;
    }
}
