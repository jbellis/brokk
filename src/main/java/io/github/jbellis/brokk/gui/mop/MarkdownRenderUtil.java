package io.github.jbellis.brokk.gui.mop;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for rendering Markdown content in Swing components.
 */
public class MarkdownRenderUtil {
    private static final Logger logger = LogManager.getLogger(MarkdownRenderUtil.class);
    private static final Parser parser = Parser.builder()
            .extensions(Collections.singletonList(TablesExtension.create()))
            .build();
    private static final HtmlRenderer renderer = HtmlRenderer.builder()
            .softBreak("<br />\n")  // Convert single newlines to HTML line breaks
            .extensions(Collections.singletonList(TablesExtension.create()))
            .build();

    /**
     * Renders a string containing Markdown, handling ``` code fences.
     * Returns a panel containing the rendered components.
     *
     * @param markdownContent The Markdown content to render.
     * @param isDarkTheme     Whether dark theme is active
     * @return A JPanel containing the rendered content
     */
    public static JPanel renderMarkdownContent(String markdownContent, boolean isDarkTheme) {
        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(bgColor);
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Regex to find code blocks ```optional_info \n content ```
        // (?m) makes ^ and $ match start/end of lines
        // (?s) DOTALL allows . to match newline characters
        // ^\s* ensures the fence starts at the beginning of a line (with optional whitespace)
        // reluctant quantifier *? ensures it finds the *next* ```
        Pattern codeBlockPattern = Pattern.compile("(?ms)^\\s*```(\\w*)[\\r\\n](.*?)^\\s*```\\s*$");
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
            contentPanel.add(createCodeBlockPanel(codeArea, fenceInfo, isDarkTheme));

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
                                         Color codeBackgroundColor, Color codeBorderColor)
    {
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
        Color codeBackgroundColor = ThemeColors.getColor(isDarkTheme, "code_block_background");
        Color codeBorderColor = ThemeColors.getColor(isDarkTheme, "code_block_border");
        return codeAreaInPanel(textArea, borderThickness, isDarkTheme, codeBackgroundColor, codeBorderColor);
    }

    /**
     * Creates a code block panel using BaseChatMessagePanel.
     * This provides consistent styling with other message components.
     *
     * @param textArea    The RSyntaxTextArea containing the code
     * @param fenceInfo   The language identifier from the code fence
     * @param isDarkTheme Whether dark theme is active
     * @return A panel containing the styled code block
     */
    public static JPanel createCodeBlockPanel(RSyntaxTextArea textArea, String fenceInfo, boolean isDarkTheme) {
        // Format the title based on fence info
        String title = fenceInfo.isEmpty() ? "Code" :
                       fenceInfo.substring(0, 1).toUpperCase() + fenceInfo.substring(1);

        // Use code icon
        String iconText = "\uD83D\uDCDD"; // Unicode for memo/pencil emoji

        // Create the panel using BaseChatMessagePanel
        return new BaseChatMessagePanel(
                title,
                iconText,
                textArea,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "codeHighlight"),
                ThemeColors.getColor(isDarkTheme, "rsyntax_background"),
                ThemeColors.getColor(isDarkTheme, "message_background")
        );
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    public static JEditorPane createHtmlPane(boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
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
        var textColorHex = String.format("#%02x%02x%02x",
                                         textColor.getRed(),
                                         textColor.getGreen(),
                                         textColor.getBlue());
        var linkColor = ThemeColors.getColorHex(isDarkTheme, "link_color_hex");

        // Define theme-specific colors
        var borderColor = ThemeColors.getColorHex(isDarkTheme, "border_color_hex");
        // Base typography
        ss.addRule("body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5; " +
                           "background-color: " + bgColorHex + "; color: " + textColorHex + "; margin: 0; padding: 8px; }");

        // Headings
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin-top: 18px; margin-bottom: 12px; " +
                           "font-weight: 600; line-height: 1.25; color: " + textColorHex + "; }");
        ss.addRule("h1 { font-size: 1.5em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h2 { font-size: 1.3em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h3 { font-size: 1.1em; }");
        ss.addRule("h4 { font-size: 1em; }");

        // Links
        ss.addRule("a { color: " + linkColor + "; text-decoration: none; }");
        ss.addRule("a:hover { text-decoration: underline; }");

        // Paragraphs and lists
        ss.addRule("p, ul, ol { margin-top: 0; margin-bottom: 12px; }");
        ss.addRule("ul, ol { padding-left: 2em; }");
        ss.addRule("li { margin: 0.25em 0; }");
        ss.addRule("li > p { margin-top: 12px; }");

        // Code styling
        ss.addRule("code { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace; " +
                           "padding: 0.2em 0.4em; margin: 0; font-size: 85%; border-radius: 3px; " +
                           "color: " + linkColor + "; }");

        // Table styling
        ss.addRule("table { border-collapse: collapse; margin: 15px 0; width: 100%; }");
        ss.addRule("table, th, td { border: 1px solid " + borderColor + "; }");
        ss.addRule("th { background-color: " + ThemeColors.getColorHex(isDarkTheme, "code_block_background") + "; " +
                  "padding: 8px; text-align: left; font-weight: 600; }");
        ss.addRule("td { padding: 8px; }");
        ss.addRule("tr:nth-child(even) { background-color: " + ThemeColors.getColorHex(isDarkTheme, "message_background") + "; }");
        ss.addRule("tr:hover { background-color: " + ThemeColors.getColorHex(isDarkTheme, "chat_background") + "; }");

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
        return Messages.getRepr(message);
    }

}
