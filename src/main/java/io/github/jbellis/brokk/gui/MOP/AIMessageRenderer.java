package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.EditBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import java.awt.*;

/**
 * Renderer for AI messages, capable of handling both regular markdown and edit blocks.
 */
public class AIMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(AIMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, Color textBackgroundColor, boolean isDarkTheme) {
        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));
        messagePanel.setBackground(textBackgroundColor);
        messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        String content = MarkdownRenderUtil.getMessageContent(message);
            // For AI messages, try to parse edit blocks first
        var parseResult = EditBlock.parseAllBlocks(content);

        // If we have edit blocks, render them
        boolean hasEditBlocks = parseResult.blocks().stream()
                .anyMatch(block -> block.block() != null);

        if (hasEditBlocks) {
            // Create a container for edit blocks
            JPanel blocksPanel = new JPanel();
            blocksPanel.setLayout(new BoxLayout(blocksPanel, BoxLayout.Y_AXIS));
            blocksPanel.setBackground(textBackgroundColor);
            blocksPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            for (var block : parseResult.blocks()) {
                if (block.block() != null) {
                    // Edit block
                    blocksPanel.add(renderEditBlockComponent(block.block(), textBackgroundColor, isDarkTheme));
                } else if (!block.text().isBlank()) {
                    // Text between edit blocks - render as markdown
                    var textPanel = MarkdownRenderUtil.renderMarkdownContent(block.text(), textBackgroundColor, isDarkTheme);
                    blocksPanel.add(textPanel);
                }
            }
            blocksPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
            messagePanel.add(blocksPanel);
        } else {
            // No edit blocks, render as markdown
            var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, textBackgroundColor, isDarkTheme);
            contentPanel.setBorder(BorderFactory.createLineBorder(Color.yellow, 2));
            messagePanel.add(contentPanel);
        }
        
        // Set maximum width and return
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messagePanel.getPreferredSize().height));
        return messagePanel;
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
