package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.gui.MOP.AIMessageRenderer;
import io.github.jbellis.brokk.gui.MOP.CustomMessageRenderer;
import io.github.jbellis.brokk.gui.MOP.UserMessageRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);

        // Build the Flexmark parser for normal text blocks
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
        
        // Initialize message renderers
        aiRenderer = new io.github.jbellis.brokk.gui.MOP.AIMessageRenderer();
        userRenderer = new io.github.jbellis.brokk.gui.MOP.UserMessageRenderer();
        customRenderer = new io.github.jbellis.brokk.gui.MOP.CustomMessageRenderer();
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark) {
        this.isDarkTheme = isDark;

        if (isDark) {
            textBackgroundColor = new Color(40, 40, 40);
        } else {
            textBackgroundColor = Color.WHITE;
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

    // Message renderers for different message types
    private final AIMessageRenderer aiRenderer;
    private final UserMessageRenderer userRenderer;
    private final CustomMessageRenderer customRenderer;
    
    /**
     * Renders a single message component based on its type
     */
    private Component renderMessageComponent(ChatMessage message) {
        return switch (message.type()) {
            case AI -> aiRenderer.renderComponent(message, textBackgroundColor, isDarkTheme);
            case USER -> userRenderer.renderComponent(message, textBackgroundColor, isDarkTheme);
            case CUSTOM -> customRenderer.renderComponent(message, textBackgroundColor, isDarkTheme);
            default -> {
                // Default case for other message types
                JPanel messagePanel = new JPanel();
                messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
                messagePanel.setBackground(textBackgroundColor);
                messagePanel.setAlignmentX(LEFT_ALIGNMENT);
                messagePanel.add(createPlainTextPane(Models.getRepr(message)));
                messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
                yield messagePanel;
            }
        };
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
            
            // Configure text wrapping correctly
                        plainPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
                        plainPane.putClientProperty("caretWidth", 0); // Hide caret
                        // Don't constrain height, let component determine its own preferred size based on content
                        plainPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            
            return plainPane;
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
