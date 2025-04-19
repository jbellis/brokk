package io.github.jbellis.brokk.gui.MOP;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Base component for chat message panels with common styling and structure.
 * Provides a standardized layout with header (icon + title) and content area.
 */
public class BaseChatMessagePanel extends JPanel {

    /**
         * A panel that draws a rounded background and a highlight bar on the left.
         * It contains the actual content component.
         */
        private static class RoundedHighlightPanel extends JPanel {
            private final Color backgroundColor;
            private final Color highlightColor;
            private final int arcSize;
            private final int highlightThickness;

            public RoundedHighlightPanel(Component content, Color backgroundColor, Color highlightColor,
                                           int arcSize, int highlightThickness, int padding) {
                super(new BorderLayout()); // Use BorderLayout to manage the content
                this.backgroundColor = backgroundColor;
                this.highlightColor = highlightColor;
                this.arcSize = arcSize;
                this.highlightThickness = highlightThickness;

                setOpaque(false); // We paint our own background + highlight

                // Set border to create padding *inside* the highlight bar and around content
                setBorder(BorderFactory.createEmptyBorder(padding, padding + highlightThickness, padding, padding));
                add(content, BorderLayout.CENTER); // Add original content
            }

            @Override
            protected void paintComponent(Graphics g) {
                // Don't call super.paintComponent() because we are opaque=false and paint everything.
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int width = getWidth();
                int height = getHeight();
                var roundRect = new RoundRectangle2D.Float(0, 0, width, height, arcSize, arcSize);

                // 1. Paint the main background color, clipped to the rounded shape
                g2d.setColor(backgroundColor);
                g2d.fill(roundRect);

                // 2. Paint the left highlight bar, also clipped
                g2d.setColor(highlightColor);
                // Use clip for safety at corners, though fillRect should be within bounds
                Shape clip = g2d.getClip();
                g2d.clip(roundRect);
                g2d.fillRect(0, 0, highlightThickness, height);
                g2d.setClip(clip);

                g2d.dispose();

                // Children are painted after this method returns by the Swing painting mechanism
            }
        }

        /**
         * Creates a new base chat message panel with the given title, icon, content, and custom highlight color.
         *
         * @param title The title text to display in the header
         * @param iconText Unicode icon text to display
         * @param contentComponent The main content component to display
         * @param isDarkTheme Whether dark theme is active
         * @param highlightColor The color to use for the left highlight bar
         */
        public BaseChatMessagePanel(String title, String iconText, Component contentComponent,
                                   boolean isDarkTheme, Color highlightColor) {
            initialize(title, iconText, contentComponent, isDarkTheme, highlightColor);
        }

    /**
         * Common initialization method for all constructors.
         */
    private void initialize(String title, String iconText, Component contentComponent,
                            boolean isDarkTheme, Color highlightColor)
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(ThemeColors.getColor(isDarkTheme, "chat_background"));
        // Overall padding for the entire message panel (header + content area)
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        setAlignmentX(Component.LEFT_ALIGNMENT);  // Also ensure *this* panel is left-aligned in its parent
        setAlignmentY(Component.TOP_ALIGNMENT);

        // Get theme colors
        Color messageBgColor = ThemeColors.getColor(isDarkTheme, "message_background");

        // Add a header row with icon and label
                JPanel headerPanel = new JPanel();
                headerPanel.setLayout(new GridBagLayout());  // Use GridBagLayout for precise control
                headerPanel.setBackground(getBackground());
                headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                
                // Create constraints that anchor content to the left
                GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = 0;
                gbc.weightx = 0.0;  // Don't give extra space to the component
                gbc.fill = GridBagConstraints.NONE;
                gbc.anchor = GridBagConstraints.WEST;  // Force anchor to the left (WEST)
                
                // Put icon and title directly in the header panel with proper constraints
                JPanel iconTitlePanel = new JPanel();
                iconTitlePanel.setLayout(new BoxLayout(iconTitlePanel, BoxLayout.X_AXIS));
                iconTitlePanel.setOpaque(false);
                
                // Icon
                JLabel iconLabel = new JLabel(iconText);
                iconLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
                iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 16f));
                iconTitlePanel.add(iconLabel);
                
                // Title
                JLabel titleLabel = new JLabel(" " + title);  // Add a space after the icon
                titleLabel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_header_text"));
                titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
                iconTitlePanel.add(titleLabel);
                
                // Add the panel with icon+title to the header with left alignment
                headerPanel.add(iconTitlePanel, gbc);
                
                // Add a "filler" component that takes up the rest of the space
                gbc.gridx = 1;
                gbc.weightx = 1.0;  // This component gets all extra horizontal space
                gbc.fill = GridBagConstraints.HORIZONTAL;
                headerPanel.add(Box.createHorizontalGlue(), gbc);
                
                // For debugging only - remove in production
                // headerPanel.setBorder(BorderFactory.createLineBorder(Color.RED));

        add(headerPanel);

        // Add a small gap between header and content
        add(Box.createRigidArea(new Dimension(0, 5)));

        // Wrap the content component in our custom panel for rounded background + highlight
        int arcSize = 15;
        int highlightThickness = 4;
        int padding = 8;
        var contentWrapper = new RoundedHighlightPanel(
                contentComponent,
                messageBgColor,
                highlightColor,
                arcSize,
                highlightThickness,
                padding
        );
        add(contentWrapper);

        // Let this entire panel grow in width if space is available
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
    }

}
