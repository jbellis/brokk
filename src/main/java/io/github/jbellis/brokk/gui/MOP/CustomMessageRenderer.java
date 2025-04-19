package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Renderer for custom/system messages.
 */
public class CustomMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(CustomMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        // Create content panel
        String content = MarkdownRenderUtil.getMessageContent(message);
        var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
        
        // Apply special styling for system messages
            JPanel customPanel = new JPanel();
            customPanel.setLayout(new BoxLayout(customPanel, BoxLayout.Y_AXIS));
            customPanel.setBackground(isDarkTheme ? new Color(60, 60, 60) : new Color(245, 245, 245));
            customPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            contentPanel.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));
            
            // Allow content to dynamically resize both width and height
                contentPanel.setPreferredSize(null);
                contentPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
                contentPanel.setMinimumSize(new Dimension(10, 10));
                customPanel.add(contentPanel);
        
        // Create base panel with system message styling
            return new BaseChatMessagePanel(
                "System",
                "\uD83D\uDCBB", // Unicode for computer emoji
                customPanel,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "message_border_custom")
            );
    }
}
