package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.EditBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Renderer for AI messages, capable of handling both regular markdown and edit blocks.
 */
public class AIMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(AIMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        String content = MarkdownRenderUtil.getMessageContent(message);
        // For AI messages, try to parse edit blocks first
        var parseResult = EditBlock.parseAllBlocks(content);

        // Create content panel for AI message
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(textBackgroundColor);
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // If we have edit blocks, render them
        boolean hasEditBlocks = parseResult.blocks().stream()
                .anyMatch(block -> block.block() != null);

        if (hasEditBlocks) {
            // Process each block part
            for (var block : parseResult.blocks()) {
                if (block.block() != null) {
                    // Edit block
                    contentPanel.add(renderEditBlockComponent(block.block(), textBackgroundColor, isDarkTheme));
                } else if (!block.text().isBlank()) {
                    // Text between edit blocks - render as markdown
                    var textPanel = MarkdownRenderUtil.renderMarkdownContent(block.text(), isDarkTheme);
                    contentPanel.add(textPanel);
                }
            }
        } else {
            // No edit blocks, render as markdown
            var markdownPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
            contentPanel.add(markdownPanel);
        }
        
        // Create base panel with AI message styling
            return new BaseChatMessagePanel(
                "Brokk",
                "\uD83D\uDCBB", // Unicode for computer emoji
                contentPanel,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "message_border_ai")
            );
    }

    /**
     * Creates a JPanel visually representing a single SEARCH/REPLACE block.
     *
     * @param block The SearchReplaceBlock to render.
     * @param textBackgroundColor The background color for text
     * @param isDarkTheme Whether dark theme is active
     * @return A JPanel containing components for the block.
     */
    private JPanel renderEditBlockComponent(EditBlock.SearchReplaceBlock block, Color textBackgroundColor, boolean isDarkTheme) {
        Color codeBackgroundColor = isDarkTheme ? new Color(50, 50, 50) : new Color(240, 240, 240);
        Color codeBorderColor = isDarkTheme ? new Color(80, 80, 80) : Color.GRAY;
        
        var blockPanel = new JPanel();
        blockPanel.setLayout(new BoxLayout(blockPanel, BoxLayout.Y_AXIS));
        blockPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(5, 0, 5, 0), // Outer margin
                BorderFactory.createLineBorder(isDarkTheme ? Color.DARK_GRAY : Color.LIGHT_GRAY, 1) // Border
        ));
        blockPanel.setBackground(textBackgroundColor); // Match overall background
        blockPanel.setAlignmentX(Component.LEFT_ALIGNMENT); // Align components to the left

        // Header label (Filename)
        var headerLabel = new JLabel(String.format("File: %s", block.filename()));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5)); // Padding
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        blockPanel.add(headerLabel);

        // Separator
        blockPanel.add(new JSeparator());

        // "SEARCH" section
        blockPanel.add(MarkdownRenderUtil.createEditBlockSectionLabel("SEARCH"));
        var searchArea = MarkdownRenderUtil.createConfiguredCodeArea("", block.beforeText(), isDarkTheme); // Use "none" syntax
        searchArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(MarkdownRenderUtil.codeAreaInPanel(searchArea, 1, isDarkTheme, codeBackgroundColor, codeBorderColor)); // Use thinner border for inner parts

        // Separator
        blockPanel.add(new JSeparator());

        // "REPLACE" section
        blockPanel.add(MarkdownRenderUtil.createEditBlockSectionLabel("REPLACE"));
        var replaceArea = MarkdownRenderUtil.createConfiguredCodeArea("", block.afterText(), isDarkTheme); // Use "none" syntax
        replaceArea.setBackground(isDarkTheme ? new Color(55, 55, 55) : new Color(245, 245, 245)); // Slightly different background
        blockPanel.add(MarkdownRenderUtil.codeAreaInPanel(replaceArea, 1, isDarkTheme, codeBackgroundColor, codeBorderColor)); // Use thinner border for inner parts

        // Adjust panel size
        blockPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, blockPanel.getPreferredSize().height));

        return blockPanel;
    }
}
