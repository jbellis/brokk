package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * Renderer for user messages, with styling specific to user input.
 */
public class UserMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(UserMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // For user messages, render as markdown with styling
        JPanel userPanel = new JPanel();
        userPanel.setLayout(new BoxLayout(userPanel, BoxLayout.Y_AXIS));
        userPanel.setBackground(ThemeColors.getColor(isDarkTheme, "chat_background"));
        userPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        userPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Add a header row with icon and label (like "Code" in the reference image)
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerPanel.setBackground(userPanel.getBackground());
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Create icon (a code symbol)
        JLabel iconLabel = new JLabel("\uD83D\uDCBB"); // Unicode for computer emoji
        iconLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 20f));
        headerPanel.add(iconLabel);
        
        // Create title label
        JLabel titleLabel = new JLabel("Ask");
        titleLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel);
        
        userPanel.add(headerPanel);
        
        // Add a small gap between header and content
        userPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        
        String content = MarkdownRenderUtil.getMessageContent(message);
        var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
        contentPanel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_text"));
        Color borderColor = ThemeColors.getColor(isDarkTheme, "message_border");
        contentPanel.setBorder(MarkdownRenderUtil.createRoundedBorder(borderColor, 2, 15));

        userPanel.add(contentPanel);
        messagePanel.add(userPanel);
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
    }
}
