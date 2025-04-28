package io.github.jbellis.brokk.gui.mop.stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 * 
 * This initial skeleton simply displays plain text in a JEditorPane, with proper EDT-safe updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // The root panel that will contain all our content blocks
    private final JPanel root = new JPanel(new BorderLayout());
    // Initial implementation uses a simple text pane
    private final JEditorPane plain = new JEditorPane();
    
    /**
     * Creates a new incremental renderer with the given theme.
     * 
     * @param dark true for dark theme, false for light theme
     */
    public IncrementalBlockRenderer(boolean dark) {
        // Configure the plain text pane
        plain.setContentType("text/plain");
        plain.setEditable(false);
        // Prevent caret from moving during updates
        DefaultCaret caret = (DefaultCaret) plain.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        
        // Add the text pane to the root panel
        root.add(plain, BorderLayout.CENTER);
        root.setOpaque(false);
    }
    
    /**
     * Returns the root component that should be added to a container.
     * 
     * @return the root panel containing all rendered content
     */
    public JComponent getRoot() {
        return root;
    }
    
    /**
     * Updates the content with the given markdown text.
     * This initial implementation simply displays the raw text.
     * 
     * @param markdown the markdown text to display
     */
    public void update(String markdown) {
        // For now, just set the plain text content
        plain.setText(markdown);
        root.revalidate();
        root.repaint();
    }
}
