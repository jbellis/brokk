package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
public class MarkdownOutputPanel extends JPanel implements Scrollable {
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    // Holds the structured messages that have been added to the panel
    private final List<ChatMessage> messages = new ArrayList<>();

    // Parallel list of UI components for each message (1:1 mapping with messages)
    private final List<Component> messageComponents = new ArrayList<>();
    
    // Track renderers for each message (parallel to messageComponents)
    private final List<IncrementalBlockRenderer> messageRenderers = new ArrayList<>();

    // Listeners to notify whenever text changes
    private final List<Runnable> textChangeListeners = new ArrayList<>();

    // Theme-related fields
    private boolean isDarkTheme = false;
    private boolean blockClearAndReset = false;

    public MarkdownOutputPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark) {
        this.isDarkTheme = isDark;

        var backgroundColor = ThemeColors.getColor(isDark, "chat_background");
        setOpaque(true);
        setBackground(backgroundColor);

        var parent = getParent();
        if (parent instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(backgroundColor);
            var gp = vp.getParent();
            if (gp instanceof JScrollPane sp) {
                sp.setOpaque(true);
                sp.setBackground(backgroundColor);
            }
        }

        // Update spinner background if visible
        if (spinnerPanel != null) {
            spinnerPanel.updateBackgroundColor(backgroundColor);
        }

        // Re-render all components with new theme
        setText(messages);

        revalidate();
        repaint();
    }

    /**
     * Sets the blocking state that prevents clearing or resetting the panel contents.
     * When blocking is enabled, clear() and setText() methods will be ignored.
     * 
     * @param blocked true to prevent clear/reset operations, false to allow them
     */
    public void setBlocking(boolean blocked) {
        this.blockClearAndReset = blocked;
    }
    
    /**
     * Clears all text and displayed components.
     */
    public void clear() {
        if (blockClearAndReset) {
            logger.debug("Ignoring clear() request while blocking is enabled");
            return;
        }
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
        messageRenderers.clear();
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
            ChatMessage newMessage = Messages.create(text, type);
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
        var newText = Messages.getRepr(lastMessage) + additionalText;
        var type = lastMessage.type();
        
        // Get the renderer for the last message
        var lastRenderer = messageRenderers.getLast();
        lastRenderer.update(newText);
        
        // Create a new message with the combined text and update our model
        ChatMessage updatedMessage = Messages.create(newText, type);
        messages.set(messages.size() - 1, updatedMessage);

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

        // Determine styling based on message type
        String title = null;
        String iconText = null;
        Color highlightColor = null;
        
        switch (message.type()) {
            case AI:
                title = "Brokk";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_ai");
                break;
            case USER:
                title = "You";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_user");
                break;
            case CUSTOM:
            case SYSTEM:
                title = "System";
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
                break;
            default:
                title = message.type().toString();
                iconText = "\uD83D\uDCBB"; // Unicode for computer emoji
                highlightColor = ThemeColors.getColor(isDarkTheme, "message_border_custom");
        }
        
        // Create a new renderer for this message
        var renderer = new IncrementalBlockRenderer(isDarkTheme);
        messageRenderers.add(renderer);
        
        // Create the base panel with the renderer's root component
        var basePanel = new BaseChatMessagePanel(
            title, 
            iconText, 
            renderer.getRoot(), 
            isDarkTheme, 
            highlightColor
        );
        
        // Add the component to our UI
        messageComponents.add(basePanel);
        add(basePanel);
        
        // Update the renderer with the message content
        renderer.update(Messages.getText(message));

        // Re-add spinner if it was visible
        if (spinnerWasVisible) {
            add(spinnerPanel);
        }

        revalidate();
        repaint();
    }

    /**
     * Sets the content from a list of ChatMessages
     */
    public void setText(ContextFragment.TaskFragment newOutput) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText() request while blocking is enabled");
            return;
        }
        
        internalClear();

        if (newOutput == null) {
            return;
        }

        setText(newOutput.messages());
    }

    // private for changing theme -- parser doesn't need to change
    private void setText(List<ChatMessage> messages) {
        if (blockClearAndReset && !this.messages.isEmpty()) {
            logger.debug("Ignoring private setText() request while blocking is enabled");
            return;
        }
        
        for (var message : messages) {
            addNewMessage(message);
        }

        revalidate();
        repaint();
        textChangeListeners.forEach(Runnable::run);
    }

    /**
     * Sets the content from a TaskEntry
     */
    public void setText(TaskEntry taskEntry) {
        if (blockClearAndReset) {
            logger.debug("Ignoring setText(TaskEntry) request while blocking is enabled");
            return;
        }
        
        if (taskEntry == null) {
            clear();
            return;
        }

        if (taskEntry.isCompressed()) {
            setText(List.of(Messages.customSystem(taskEntry.summary())));
        } else {
            var taskFragment = taskEntry.log();
            setText(taskFragment.messages());
        }
    }

    /**
     * Returns text representation of all messages.
     * For backward compatibility with code that expects a String.
     */
    public String getText() {
        return messages.stream()
                .map(Messages::getRepr)
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
            spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, 
                                 ThemeColors.getColor(isDarkTheme, "chat_background"));

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
