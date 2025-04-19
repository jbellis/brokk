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
        // Create content panel
        String content = MarkdownRenderUtil.getMessageContent(message);
        var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
        contentPanel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_text"));
        
        // Create base panel with user message styling
            return new BaseChatMessagePanel(
                "You asked",
                "\uD83D\uDCBB", // Unicode for computer emoji
                contentPanel,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "message_border_user")
            );
    }
}
