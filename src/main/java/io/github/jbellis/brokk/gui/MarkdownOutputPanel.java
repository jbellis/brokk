package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.TaskEntry;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Swing JPanel designed to display structured conversations as formatted text content which may include
 * standard Markdown, Markdown code fences (```), and Brokk-specific `SEARCH/REPLACE` edit blocks.
 * <p>
 * The panel internally maintains a list of {@link ChatMessage} objects, each representing a
 * message in the conversation (AI, User, System, etc.). Each message is rendered according to its type:
 *
 * <ul>
 *   <li>AI messages are parsed for edit blocks first, and if found, they are rendered with special formatting.
 *       Otherwise, they are rendered as Markdown with code syntax highlighting.</li>
 *   <li>User messages are rendered as plain text or simple Markdown.</li>
 *   <li>System and other message types are rendered as plain text.</li>
 * </ul>
 * <p>
 * The panel updates incrementally when messages are appended, only re-rendering the affected message
 * rather than the entire content, which prevents flickering during streaming updates.
 */
class MarkdownOutputPanel extends JPanel implements Scrollable {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    // Holds the structured messages that have been added to the panel
    private final List<ChatMessage> messages = new ArrayList<>();

    // Parallel list of UI components for each message (1:1 mapping with messages)
    private final List<Component> messageComponents = new ArrayList<>();

    // Listeners to notify whenever text changes
    private final List<Runnable> textChangeListeners = new ArrayList<>();

    // Flexmark parser and renderer for Markdown segments
    private final Parser parser;
    private final HtmlRenderer renderer;

    // Theme-related fields
    private boolean isDarkTheme = false;
    private Color textBackgroundColor = null;
    private Color codeBackgroundColor = null;
    private Color codeBorderColor = null;

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);

        // Build the Flexmark parser for normal text blocks
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark) {
        this.isDarkTheme = isDark;

        if (isDark) {
            textBackgroundColor = new Color(40, 40, 40);
            codeBackgroundColor = new Color(50, 50, 50);
            codeBorderColor = new Color(80, 80, 80);
        } else {
            textBackgroundColor = Color.WHITE;
            codeBackgroundColor = new Color(240, 240, 240);
            codeBorderColor = Color.GRAY;
        }

        setOpaque(true);
        setBackground(textBackgroundColor);

        var parent = getParent();
        if (parent instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(textBackgroundColor);
            var gp = vp.getParent();
            if (gp instanceof JScrollPane sp) {
                sp.setOpaque(true);
                sp.setBackground(textBackgroundColor);
            }
        }

        // Update spinner background if visible
        if (spinnerPanel != null) {
            spinnerPanel.updateBackgroundColor(textBackgroundColor);
        }

        // Re-render all components with new theme
        setText(new ArrayList<>(messages));

        revalidate();
        repaint();
    }

    /**
     * Clears all text and displayed components.
     */
    public void clear() {
        logger.debug("Clearing all content from MarkdownOutputPanel");
        internalClear();
        revalidate();
        repaint();
        textChangeListeners.forEach(Runnable::run); // Notify listeners about the change
    }

    /**
     * Internal helper to clear all state
     */
    private void internalClear() {
        messages.clear();
        messageComponents.clear();
        removeAll();
        spinnerPanel = null;
    }

    /**
     * Appends a new message or content to the last message if type matches.
     * This is the primary method for adding content during streaming.
     *
     * @param text The text content to append
     * @param type The type of message being appended
     */
    public void append(String text, ChatMessageType type) {
        assert text != null && type != null;
        if (text.isEmpty()) {
            return;
        }

        // Check if we're appending to an existing message of the same type
        if (!messages.isEmpty() && messages.getLast().type() == type) {
            // Append to existing message
            updateLastMessage(text);
        } else {
            // Create a new message
            ChatMessage newMessage = createChatMessage(text, type);
            addNewMessage(newMessage);
        }

        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Updates the last message by appending text to it
     */
    private void updateLastMessage(String additionalText) {
        if (messages.isEmpty()) return;

        var lastMessage = messages.getLast();
        var newText = Models.getRepr(lastMessage) + additionalText;
        var type = lastMessage.type();

        // Create a new message with the combined text
        ChatMessage updatedMessage = createChatMessage(newText, type);

        // Replace the last message
        messages.set(messages.size() - 1, updatedMessage);

        // Remove the last component
        Component lastComponent = messageComponents.getLast();
        remove(lastComponent);

        // If spinner is showing, remove it temporarily
        boolean spinnerWasVisible = false;
        if (spinnerPanel != null) {
            remove(spinnerPanel);
            spinnerWasVisible = true;
        }

        // Create new component and update the lists
        Component newComponent = renderMessageComponent(updatedMessage);
        messageComponents.set(messageComponents.size() - 1, newComponent);
        add(newComponent);

        // Re-add spinner if it was visible
        if (spinnerWasVisible) {
            add(spinnerPanel);
        }

        revalidate();
        repaint();
    }

    /**
     * Adds a new message to the display
     */
    private void addNewMessage(ChatMessage message) {
        // Add to our message list
        messages.add(message);

        // If spinner is showing, remove it temporarily
        boolean spinnerWasVisible = false;
        if (spinnerPanel != null) {
            remove(spinnerPanel);
            spinnerWasVisible = true;
        }

        // Create component for this message
        Component component = renderMessageComponent(message);
        messageComponents.add(component);
        add(component);

        // Re-add spinner if it was visible
        if (spinnerWasVisible) {
            add(spinnerPanel);
        }

        revalidate();
        repaint();
    }

    /**
     * Helper method to create a ChatMessage of the specified type
     */
    private ChatMessage createChatMessage(String text, ChatMessageType type) {
        return switch (type) {
            case USER -> new UserMessage(text);
            case AI -> new AiMessage(text);
            case CUSTOM -> new CustomMessage(Map.of("text", text));
            // Add other cases as needed with appropriate implementations
            default -> {
                logger.warn("Unsupported message type: {}, using AiMessage as fallback", type);
                yield new AiMessage(text);
            }
        };
    }

    /**
     * Sets the content from a list of ChatMessages
     */
    public void setText(List<ChatMessage> newMessages) {
        internalClear();

        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }

        for (var message : newMessages) {
            Component component = renderMessageComponent(message);
            messages.add(message);
            messageComponents.add(component);
            add(component);
        }

        revalidate();
        repaint();
        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Sets the content from a TaskEntry
     */
    public void setText(TaskEntry taskEntry) {
        if (taskEntry == null) {
            clear();
            return;
        }

        setText(taskEntry.log());
    }

    /**
     * Returns text representation of all messages.
     * For backward compatibility with code that expects a String.
     */
    public String getText() {
        return messages.stream()
                .map(Models::getRepr)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Returns the raw ChatMessage objects.
     * Similar to getMessages() but with a more descriptive name.
     *
     * @return An unmodifiable list of the current messages
     */
    public List<ChatMessage> getRawMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Let callers listen for changes in the text.
     */
    public void addTextChangeListener(Runnable listener) {
        textChangeListeners.add(listener);
    }

    /**
     * Renders a single message component based on its type
     */
    private Component renderMessageComponent(ChatMessage message) {
        // Create a container panel for this message
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(textBackgroundColor);
        messagePanel.setAlignmentX(LEFT_ALIGNMENT);

        // Based on message type, add appropriate styles and content
        switch (message.type()) {
            case AI -> {
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
                    blocksPanel.setAlignmentX(LEFT_ALIGNMENT);

                    for (var block : parseResult.blocks()) {
                        if (block.block() != null) {
                            // Edit block
                            blocksPanel.add(renderEditBlockComponent(block.block()));
                        } else if (!block.text().isBlank()) {
                            // Text between edit blocks - render as markdown
                            var textPanel = renderMarkdownContent(block.text());
                            blocksPanel.add(textPanel);
                        }
                    }
                    blocksPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
                    messagePanel.add(blocksPanel);
                } else {
                    // No edit blocks, render as markdown
                    var contentPanel = renderMarkdownContent(content);
                    contentPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
                    messagePanel.add(contentPanel);
                }
            }
            case USER -> {
                // For user messages, render as plain text with styling
                JPanel userPanel = new JPanel();
                userPanel.setLayout(new BoxLayout(userPanel, BoxLayout.Y_AXIS));
                userPanel.setBackground(isDarkTheme ? new Color(60, 60, 60) : new Color(245, 245, 245));
                // userPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                userPanel.setBorder(BorderFactory.createLineBorder(Color.red, 2));
                userPanel.setAlignmentX(LEFT_ALIGNMENT);
                var textPane = renderMarkdownContent(Models.getRepr(message));
                textPane.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));

                userPanel.add(textPane);
                messagePanel.add(userPanel);
            }
            case CUSTOM -> {
                // For custom/common messages, render as plain text with styling
                JPanel customPanel = new JPanel();
                customPanel.setLayout(new BoxLayout(customPanel, BoxLayout.Y_AXIS));
                customPanel.setBackground(isDarkTheme ? new Color(60, 60, 60) : new Color(245, 245, 245));
                // customPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                customPanel.setBorder(BorderFactory.createLineBorder(Color.blue, 2));
                customPanel.setAlignmentX(LEFT_ALIGNMENT);
                var textPane = renderMarkdownContent(Models.getRepr(message));
                textPane.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));

                customPanel.add(textPane);
                messagePanel.add(customPanel);
            }
            default -> {
                // Default case for other message types
                messagePanel.add(createPlainTextPane(Models.getRepr(message)));
            }
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
     * @return A JPanel containing the rendered content
     */
    private JPanel renderMarkdownContent(String markdownContent) {
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(textBackgroundColor);
        contentPanel.setAlignmentX(LEFT_ALIGNMENT);

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
                JEditorPane textPane = createHtmlPane();
                var html = renderer.render(parser.parse(textSegment));
                textPane.setText("<html><body>" + html + "</body></html>");
                contentPanel.add(textPane);
            }

            // 2. Render the code block
            String fenceInfo = matcher.group(1).toLowerCase();
            String codeContent = matcher.group(2);
            RSyntaxTextArea codeArea = createConfiguredCodeArea(fenceInfo, codeContent);
            contentPanel.add(codeAreaInPanel(codeArea));

            lastMatchEnd = matcher.end();
        }

        // Render any remaining text segment after the last code block
        String remainingText = markdownContent.substring(lastMatchEnd).trim(); // Trim whitespace
        if (!remainingText.isEmpty()) {
            JEditorPane textPane = createHtmlPane();
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
     * @return A JPanel containing components for the block.
     */
    private JPanel renderEditBlockComponent(EditBlock.SearchReplaceBlock block) {
        var blockPanel = new JPanel();
        blockPanel.setLayout(new BoxLayout(blockPanel, BoxLayout.Y_AXIS));
        blockPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0), // Outer margin
                BorderFactory.createLineBorder(isDarkTheme ? Color.DARK_GRAY : Color.LIGHT_GRAY, 1) // Border
        ));
        blockPanel.setBackground(textBackgroundColor); // Match overall background
        blockPanel.setAlignmentX(LEFT_ALIGNMENT); // Align components to the left

        // Header label (Filename)
        var headerLabel = new JLabel(String.format("File: %s", block.filename()));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); // Padding
        headerLabel.setAlignmentX(LEFT_ALIGNMENT);
        blockPanel.add(headerLabel);

        // Separator
        blockPanel.add(new JSeparator());

        // "SEARCH" section
        blockPanel.add(createEditBlockSectionLabel("SEARCH"));
        var searchArea = createConfiguredCodeArea("", block.beforeText()); // Use "none" syntax
        searchArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(searchArea, 1)); // Use thinner border for inner parts

        // Separator
        blockPanel.add(new JSeparator());

        // "REPLACE" section
        blockPanel.add(createEditBlockSectionLabel("REPLACE"));
        var replaceArea = createConfiguredCodeArea("", block.afterText()); // Use "none" syntax
        replaceArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(codeAreaInPanel(replaceArea, 1)); // Use thinner border for inner parts

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
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    /**
     * Creates a JEditorPane configured for plain text display.
     * Ensures background color matches the theme.
     */
    private JEditorPane createPlainTextPane(String text) {
        var plainPane = new JEditorPane();
        DefaultCaret caret = (DefaultCaret) plainPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        plainPane.setContentType("text/plain"); // Set content type to plain text
        plainPane.setText(text); // Set text directly
        plainPane.setEditable(false);
        plainPane.setAlignmentX(LEFT_ALIGNMENT);
        if (textBackgroundColor != null) {
            plainPane.setBackground(textBackgroundColor);
            // Set foreground based on theme for plain text
            plainPane.setForeground(isDarkTheme ? new Color(230, 230, 230) : Color.BLACK);
        }
        return plainPane;
    }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content) {
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
     * Wraps an RSyntaxTextArea in a panel with padding and border.
     * Allows specifying border thickness.
     */
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea, int borderThickness) {
        var panel = new JPanel(new BorderLayout());
        // Use code background for the outer padding panel
        panel.setBackground(codeBackgroundColor);
        panel.setAlignmentX(LEFT_ALIGNMENT);
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
     * Wraps an RSyntaxTextArea in a panel with default border thickness (3).
     */
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea) {
        return codeAreaInPanel(textArea, 3);
    }

    /**
     * Creates a JEditorPane for HTML content. We set base CSS to match the theme.
     */
    private JEditorPane createHtmlPane() {
        var htmlPane = new JEditorPane();
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(LEFT_ALIGNMENT);
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

    // --- Spinner Logic ---

    // We keep a reference to the spinner panel itself, so we can remove it later
    private SpinnerIndicatorPanel spinnerPanel = null;

    /**
     * Shows a small spinner (or message) at the bottom of the panel,
     * underneath whatever content the user just appended.
     * If already showing, does nothing.
     *
     * @param message The text to display next to the spinner (e.g. "Thinking...")
     */
    public void showSpinner(String message) {
        if (spinnerPanel != null) {
            // Already showing, update the message and return
            spinnerPanel.setMessage(message);
            return;
        }
        // Create a new spinner instance each time
        spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, textBackgroundColor);

        // Add to the end of this panel. Since we have a BoxLayout (Y_AXIS),
        // it shows up below the existing rendered content.
        add(spinnerPanel);

        revalidate();
        repaint();
    }

    /**
     * Hides the spinner panel if it is visible, removing it from the UI.
     */
    public void hideSpinner() {
        if (spinnerPanel == null) {
            return; // not showing
        }
        remove(spinnerPanel); // Remove from components
        spinnerPanel = null; // Release reference

        revalidate();
        repaint();
    }

    /**
     * Get the current messages in the panel.
     * This is useful for code that needs to access the structured message data.
     *
     * @return An unmodifiable list of the current messages
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    // --- Scrollable interface methods ---

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL
               ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
