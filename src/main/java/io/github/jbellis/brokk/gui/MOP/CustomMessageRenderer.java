package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;

/**
 * Renderer for custom/system messages.
 */
public class CustomMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(CustomMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(textBackgroundColor);
        messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // For custom/common messages, render as markdown with styling
        JPanel customPanel = new JPanel();
        customPanel.setLayout(new BoxLayout(customPanel, BoxLayout.Y_AXIS));
        customPanel.setBackground(isDarkTheme ? new Color(60, 60, 60) : new Color(245, 245, 245));
        customPanel.setBorder(BorderFactory.createLineBorder(Color.blue, 2));
        customPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        String content = MarkdownRenderUtil.getMessageContent(message);
            var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
        contentPanel.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));

        customPanel.add(contentPanel);
        messagePanel.add(customPanel);
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
    }
}
