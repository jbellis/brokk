package io.github.jbellis.brokk.gui.MOP;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Base component for chat message panels with common styling and structure.
 * Provides a standardized layout with header (icon + title) and content area.
 */
public class BaseChatMessagePanel extends JPanel {
    
    /**
         * Creates a new base chat message panel with the given title, icon, and content.
         * Uses a default rounded border.
         *
         * @param title The title text to display in the header
         * @param iconText Unicode icon text to display
         * @param contentComponent The main content component to display
         * @param isDarkTheme Whether dark theme is active
         */
        public BaseChatMessagePanel(String title, String iconText, Component contentComponent, boolean isDarkTheme) {
            initialize(title, iconText, contentComponent, isDarkTheme);
        }
    
    /**
         * Common initialization method for all constructors.
         */
        private void initialize(String title, String iconText, Component contentComponent, 
                               boolean isDarkTheme) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ThemeColors.getColor(isDarkTheme, "chat_background"));
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Add a header row with icon and label
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerPanel.setBackground(getBackground());
        headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Create icon
        JLabel iconLabel = new JLabel(iconText);
        iconLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 26f));
        headerPanel.add(iconLabel);
        
        // Create title label
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 26f));
        headerPanel.add(titleLabel);
        
        add(headerPanel);
        
        // Add a small gap between header and content
        add(Box.createRigidArea(new Dimension(0, 5)));
        
        // Apply rounded border to content if it's a JComponent
            if (contentComponent instanceof JComponent) {
                Color borderColor = ThemeColors.getColor(isDarkTheme, "message_border");
                Border roundedBorder = MarkdownRenderUtil.createRoundedBorder(borderColor, 1, 15);
                ((JComponent) contentComponent).setBorder(roundedBorder);
            }
        
        // Add the content component
        add(contentComponent);
        
        // Set maximum width
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }
}
