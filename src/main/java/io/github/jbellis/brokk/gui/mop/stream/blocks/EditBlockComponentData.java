package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Represents an edit block for file diffs.
 */
public record EditBlockComponentData(int id, int adds, int dels, String file) implements ComponentData {
    @Override
    public String fp() {
        return adds + "|" + dels;
    }
    
    @Override
    public JComponent createComponent(boolean darkTheme) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Create a label with the edit block information
        JLabel label = new JLabel(String.format("<html><b>Edit Block:</b> %s (adds: %d, dels: %d)</html>", 
                                              file(), adds(), dels()));
        label.setForeground(ThemeColors.getColor(darkTheme, "chat_text"));
        
        panel.add(label, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        return panel;
    }
    
    @Override
    public void updateComponent(JComponent component) {
        if (component instanceof JPanel panel) {
            // Find the label within the panel
            var labels = findComponentsOfType(panel, JLabel.class);
            if (!labels.isEmpty()) {
                labels.getFirst().setText(String.format("<html><b>Edit Block:</b> %s (adds: %d, dels: %d)</html>", 
                                                   file(), adds(), dels()));
            }
        }
    }
    
    /**
     * Finds all components of a specific type within a container.
     */
    private <T extends java.awt.Component> List<T> findComponentsOfType(java.awt.Container container, Class<T> type) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (java.awt.Component comp : container.getComponents()) {
            if (type.isInstance(comp)) {
                result.add(type.cast(comp));
            }
            if (comp instanceof java.awt.Container) {
                result.addAll(findComponentsOfType((java.awt.Container) comp, type));
            }
        }
        return result;
    }
}
