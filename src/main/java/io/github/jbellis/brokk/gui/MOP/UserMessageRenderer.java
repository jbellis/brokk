package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;

/**
 * Renderer for user messages, with styling specific to user input.
 */
public class UserMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(UserMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(textBackgroundColor);
        messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // For user messages, render as markdown with styling
        JPanel userPanel = new JPanel();
        userPanel.setLayout(new BoxLayout(userPanel, BoxLayout.Y_AXIS));
        userPanel.setBackground(isDarkTheme ? new Color(60, 60, 60) : new Color(245, 245, 245));
        userPanel.setBorder(BorderFactory.createLineBorder(Color.red, 2));
        userPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        String content = MarkdownRenderUtil.getMessageContent(message);
            var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, textBackgroundColor, isDarkTheme);
        contentPanel.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));

        userPanel.add(contentPanel);
        messagePanel.add(userPanel);
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
    }
}
