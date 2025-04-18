package io.github.jbellis.brokk.gui.MOP;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.Models;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderer for AI messages, capable of handling both regular markdown and edit blocks.
 */
public class AIMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(AIMessageRenderer.class);

    private final Parser parser;
    private final HtmlRenderer renderer;
    
    public AIMessageRenderer() {
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
    }

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(textBackgroundColor);
        messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        String content = Models.getRepr(message);
        // For AI messages, try to parse edit blocks first
        var parseResult = EditBlock.parseAllBlocks(content);

        // If we have edit blocks, render them
        boolean hasEditBlocks = parseResult.blocks().stream()
                .anyMatch(block -> block.block() != null);

        if (hasEditBlocks) {
            // Create a container for edit blocks
            JPanel blocksPanel = new JPanel();
            blocksPanel.setLayout(new BoxLayout(blocksPanel, BoxLayout.Y_AXIS));
            blocksPanel.setBackground(textBackgroundColor);
            blocksPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            for (var block : parseResult.blocks()) {
                if (block.block() != null) {
                    // Edit block
                    blocksPanel.add(renderEditBlockComponent(block.block(), textBackgroundColor, isDarkTheme));
                } else if (!block.text().isBlank()) {
                    // Text between edit blocks - render as markdown
                    var textPanel = renderMarkdownContent(block.text(), textBackgroundColor, isDarkTheme);
                    blocksPanel.add(textPanel);
                }
            }
            blocksPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
            messagePanel.add(blocksPanel);
        } else {
            // No edit blocks, render as markdown
            var contentPanel = renderMarkdownContent(content, textBackgroundColor, isDarkTheme);
            contentPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
            messagePanel.add(contentPanel);
        }
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
    }

    /**
     * Renders a string containing Markdown, handling ``` code fences.
     * Returns a panel containing the rendered components.
     *
     * @param markdownContent The Markdown content to render.
     * @param textBackgroundColor The background color for text components
     * @param isDarkTheme Whether dark theme is active
     * @return A JPanel containing the rendered content
     */
    private JPanel renderMarkdownContent(String markdownContent, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(textBackgroundColor);
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
                JEditorPane textPane = createHtmlPane(textBackgroundColor, isDarkTheme);
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
            JEditorPane textPane = createHtmlPane(textBackgroundColor, isDarkTheme);
            // Render potentially trimmed segment as HTML
            var html = renderer.render(parser.parse(remainingText));
            textPane.setText("<html><body>" + html + "</body></html>");
            contentPanel.add(textPane);
        }

        return contentPanel;
    }

    /**
     * Creates a JPanel visually representing a single SEARCH/REPLACE block.
     *
     * @param block The SearchReplaceBlock to render.
     * @param textBackgroundColor The background color for text
     * @param isDarkTheme Whether dark theme is active
     * @return A JPanel containing components for the block.
     */
    private JPanel renderEditBlockComponent(EditBlock.SearchReplaceBlock block, Color textBackgroundColor, boolean isDarkTheme) {
        Color codeBackgroundColor = isDarkTheme ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color codeBorderColor = isDarkTheme ? new Color(80, 80, 80) : Color.GRAY;
        
        var blockPanel = new JPanel();
        blockPanel.setLayout(new BoxLayout(blockPanel, BoxLayout.Y_AXIS));
        blockPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0), // Outer margin
                BorderFactory.createLineBorder(isDarkTheme ? Color.DARK_GRAY : Color.LIGHT_GRAY, 1) // Border
        ));
        blockPanel.setBackground(textBackgroundColor); // Match overall background
        blockPanel.setAlignmentX(Component.LEFT_ALIGNMENT); // Align components to the left

        // Header label (Filename)
        var headerLabel = new JLabel(String.format("File: %s", block.filename()));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); // Padding
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        blockPanel.add(headerLabel);

        // Separator
        blockPanel.add(new JSeparator());

        // "SEARCH" section
        blockPanel.add(createEditBlockSectionLabel("SEARCH"));
        var searchArea = createConfiguredCodeArea("", block.beforeText(), isDarkTheme); // Use "none" syntax
        searchArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(searchArea, 1, isDarkTheme, codeBackgroundColor, codeBorderColor)); // Use thinner border for inner parts

        // Separator
        blockPanel.add(new JSeparator());

        // "REPLACE" section
        blockPanel.add(createEditBlockSectionLabel("REPLACE"));
        var replaceArea = createConfiguredCodeArea("", block.afterText(), isDarkTheme); // Use "none" syntax
        replaceArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(replaceArea, 1, isDarkTheme, codeBackgroundColor, codeBorderColor)); // Use thinner border for inner parts

        // Adjust panel size
        blockPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, blockPanel.getPreferredSize().height));

        return blockPanel;
    }

    /**
     * Helper to create consistent labels for SEARCH/REPLACE sections
     */
    private JLabel createEditBlockSectionLabel(String title) {
        var label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        label.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5)); // Padding
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content, boolean isDarkTheme) {
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
                var darkTheme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                var lightTheme = Theme.load(getClass().getResourceAsStream(
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
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness, boolean isDarkTheme, 
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
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness, boolean isDarkTheme) {
        Color codeBackgroundColor = isDarkTheme ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color codeBorderColor = isDarkTheme ? new Color(80, 80, 80) : Color.GRAY;
        return codeAreaInPanel(textArea, borderThickness, isDarkTheme, codeBackgroundColor, codeBorderColor);
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    private JEditorPane createHtmlPane(Color textBackgroundColor, boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        htmlPane.setText("<html><body></body></html>");

        if (textBackgroundColor != null) {
            htmlPane.setBackground(textBackgroundColor);

            var kit = (HTMLEditorKit) htmlPane.getEditorKit();
            var ss = kit.getStyleSheet();

            // Base background and text color
            var bgColorHex = String.format("#%02x%02x%02x",
                    textBackgroundColor.getRed(),
                    textBackgroundColor.getGreen(),
                    textBackgroundColor.getBlue());
            var textColor = isDarkTheme ? "#e6e6e6" : "#000000";
            var linkColor = isDarkTheme ? "#88b3ff" : "#0366d6";

            ss.addRule("body { font-family: sans-serif; background-color: "
                    + bgColorHex + "; color: " + textColor + "; }");
            ss.addRule("a { color: " + linkColor + "; }");
            ss.addRule("code { padding: 2px; background-color: "
                    + (isDarkTheme ? "#3c3f41" : "#f6f8fa") + "; }");
        }

        return htmlPane;
    }
}
