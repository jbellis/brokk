package io.github.jbellis.brokk.gui.MOP;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.Models;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for rendering Markdown content in Swing components.
 */
public class MarkdownRenderUtil {
    private static final Logger logger = LogManager.getLogger(MarkdownRenderUtil.class);
    private static final Parser parser = Parser.builder().build();
    private static final HtmlRenderer renderer = HtmlRenderer.builder().build();

    /**
     * Renders a string containing Markdown, handling ``` code fences.
     * Returns a panel containing the rendered components.
     *
     * @param markdownContent The Markdown content to render.
     * @param textBackgroundColor The background color for text components
     * @param isDarkTheme Whether dark theme is active
     * @return A JPanel containing the rendered content
     */
    public static JPanel renderMarkdownContent(String markdownContent, boolean isDarkTheme) {
        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(bgColor);
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Regex to find code blocks ```optional_info \n content ```
        // DOTALL allows . to match newline characters
        // reluctant quantifier *? ensures it finds the *next* ```
        Pattern codeBlockPattern = Pattern.compile("(?s)```(\\w*)[\\r\\n]?(.*?)```");
        Matcher matcher = codeBlockPattern.matcher(markdownContent);

        int lastMatchEnd = 0;

        while (matcher.find()) {
            // 1. Render the text segment before the code block
            String textSegment = markdownContent.substring(lastMatchEnd, matcher.start());
            if (!textSegment.isEmpty()) {
                JEditorPane textPane = createHtmlPane(isDarkTheme);
                var html = renderer.render(parser.parse(textSegment));
                textPane.setText("<html><body>" + html + "</body></html>");
                contentPanel.add(textPane);
            }

            // 2. Render the code block
            String fenceInfo = matcher.group(1).toLowerCase();
            String codeContent = matcher.group(2);
            RSyntaxTextArea codeArea = createConfiguredCodeArea(fenceInfo, codeContent, isDarkTheme);
            contentPanel.add(codeAreaInPanel(codeArea, 3, isDarkTheme));

            lastMatchEnd = matcher.end();
        }

        // Render any remaining text segment after the last code block
        String remainingText = markdownContent.substring(lastMatchEnd).trim(); // Trim whitespace
        if (!remainingText.isEmpty()) {
            JEditorPane textPane = createHtmlPane(isDarkTheme);
            // Render potentially trimmed segment as HTML
            var html = renderer.render(parser.parse(remainingText));
            textPane.setText("<html><body>" + html + "</body></html>");
            contentPanel.add(textPane);
        }

        return contentPanel;
    }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    public static RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content, boolean isDarkTheme) {
        var codeArea = new RSyntaxTextArea(content);
        codeArea.setEditable(false);
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);
        DefaultCaret caret = (DefaultCaret) codeArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        codeArea.setSyntaxEditingStyle(switch (fenceInfo) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        });
        codeArea.setHighlightCurrentLine(false);

        try {
            if (isDarkTheme) {
                var darkTheme = Theme.load(MarkdownRenderUtil.class.getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                var lightTheme = Theme.load(MarkdownRenderUtil.class.getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
                lightTheme.apply(codeArea);
            }
        } catch (IOException e) {
            if (isDarkTheme) {
                codeArea.setBackground(new Color(50, 50, 50));
                codeArea.setForeground(new Color(230, 230, 230));
            }
        }

        return codeArea;
    }

    /**
     * Wraps an RSyntaxTextArea in a panel with padding and border with custom colors.
     */
    public static JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness, boolean isDarkTheme, 
                                   Color codeBackgroundColor, Color codeBorderColor) {
        var panel = new JPanel(new BorderLayout());
        // Use code background for the outer padding panel
        panel.setBackground(codeBackgroundColor);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Padding outside the border
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        var textAreaPanel = new JPanel(new BorderLayout());
        // Use text area's actual background for the inner panel
        textAreaPanel.setBackground(textArea.getBackground());
        // Border around the text area
        textAreaPanel.setBorder(BorderFactory.createLineBorder(codeBorderColor, borderThickness, true));
        textAreaPanel.add(textArea);

        panel.add(textAreaPanel);
        // Adjust panel size
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    /**
     * Wraps an RSyntaxTextArea in a panel with default border thickness.
     */
    public static JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness, boolean isDarkTheme) {
        Color codeBackgroundColor = isDarkTheme ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color codeBorderColor = isDarkTheme ? new Color(80, 80, 80) : Color.GRAY;
        return codeAreaInPanel(textArea, borderThickness, isDarkTheme, codeBackgroundColor, codeBorderColor);
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    public static JEditorPane createHtmlPane(boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        htmlPane.setText("<html><body></body></html>");

        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");

        htmlPane.setBackground(bgColor);

        var kit = (HTMLEditorKit) htmlPane.getEditorKit();
        var ss = kit.getStyleSheet();

        // Base background and text color
        var bgColorHex = String.format("#%02x%02x%02x",
                                       bgColor.getRed(),
                                       bgColor.getGreen(),
                                       bgColor.getBlue());
        var textColor = ThemeColors.getColor(isDarkTheme, "chat_text");
        var linkColor = isDarkTheme ? "#88b3ff" : "#0366d6";

        ss.addRule("body { font-family: sans-serif; background-color: "
                + bgColorHex + "; color: " + textColor + "; }");
        ss.addRule("a { color: " + linkColor + "; }");
        ss.addRule("code { padding: 2px; background-color: "
                + (isDarkTheme ? "#3c3f41" : "#f6f8fa") + "; }");

        return htmlPane;
    }
    
    /**
     * Helper to create consistent labels for SEARCH/REPLACE sections
     */
    public static JLabel createEditBlockSectionLabel(String title) {
        var label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5)); // Padding
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }
    
    /**
     * Extract the content from a message for rendering.
     * This provides a consistent way to get the text content across all renderers.
     * 
     * @param message The chat message to extract content from
     * @return The string content to render
     */
    public static String getMessageContent(ChatMessage message) {
        if (message == null) {
            return "";
        }
        return Models.getRepr(message);
    }

}
