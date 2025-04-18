package io.github.jbellis.brokk.gui.MOP;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.Models;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;

/**
 * Renderer for user messages, with styling specific to user input.
 */
public class UserMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(UserMessageRenderer.class);

    private final Parser parser;
    private final HtmlRenderer renderer;
    
    public UserMessageRenderer() {
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();
    }

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
        
        var markdownHelper = new AIMessageRenderer(); // Reuse the markdown rendering
        var textPane = markdownHelper.renderComponent(message, textBackgroundColor, isDarkTheme);
        textPane.setForeground(isDarkTheme ? new Color(220, 220, 220) : new Color(30, 30, 30));

        userPanel.add(textPane);
        messagePanel.add(userPanel);
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
    }
}
