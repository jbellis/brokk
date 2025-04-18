package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;

import javax.swing.*;
import java.awt.*;

/**
 * Interface for rendering chat message components.
 * Each implementation handles the rendering of a specific message type.
 */
public interface MessageComponentRenderer {
    /**
     * Renders a chat message as a Swing component.
     *
     * @param message The message to render
     * @param textBackgroundColor The background color for text components
     * @param isDarkTheme Whether the dark theme is active
     * @return A Component representing the rendered message
     */
    Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme);
}
