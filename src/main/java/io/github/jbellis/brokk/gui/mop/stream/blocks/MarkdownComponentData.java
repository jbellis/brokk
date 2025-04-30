package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.MarkdownRenderUtil;

import javax.swing.*;
import java.awt.*;

/**
 * Represents a Markdown prose segment between placeholders.
 */
public record MarkdownComponentData(int id, String html) implements ComponentData {
    @Override
    public String fp() {
        return html.hashCode() + "";
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        JEditorPane editor = MarkdownRenderUtil.createHtmlPane(darkTheme);
        
        // Update content
        editor.setText("<html><body>" + html + "</body></html>");
        
        // Configure for left alignment and proper sizing
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        
        return editor;
    }
    
    @Override
    public void updateComponent(JComponent component) {
        if (component instanceof JEditorPane editor) {
            // Record current scroll position
            var viewport = SwingUtilities.getAncestorOfClass(JViewport.class, editor);
            Point viewPosition = viewport instanceof JViewport ? ((JViewport)viewport).getViewPosition() : null;
            
            // Update content
            editor.setText("<html><body>" + html + "</body></html>");
            
            // Restore scroll position if possible
            if (viewport instanceof JViewport && viewPosition != null) {
                ((JViewport)viewport).setViewPosition(viewPosition);
            }
        }
    }
}
