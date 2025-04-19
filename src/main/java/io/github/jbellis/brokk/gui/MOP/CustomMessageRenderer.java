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
            // Add debugging border to content panel
            contentPanel.setBorder(BorderFactory.createLineBorder(Color.GREEN, 1));
            customPanel.add(contentPanel);
            
            // Set debugging border on the custom panel too
            customPanel.setBorder(BorderFactory.createLineBorder(Color.MAGENTA, 1));
        
        // Create base panel with system message styling
            return new BaseChatMessagePanel(
                "System", 
                "\u2139\uFE0F", // Information symbol
                customPanel,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "message_border_custom")
            );
    }
}
