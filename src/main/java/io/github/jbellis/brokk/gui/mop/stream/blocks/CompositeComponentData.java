package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a composite component that contains multiple child components.
 * Used for handling nested elements like code fences inside list items.
 */
public record CompositeComponentData(
    int id,
    List<ComponentData> children
) implements ComponentData {

    @Override
    public String fp() {
        // Combine child fingerprints to create a composite fingerprint
        return children.stream()
               .map(ComponentData::fp)
               .collect(Collectors.joining("-"));
    }

    private static final String THEME_FLAG = "brokk.darkTheme";

    @Override
    public JComponent createComponent(boolean darkTheme) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        var bgColor = ThemeColors.getColor(darkTheme, "message_background");
        panel.setBackground(bgColor);
        
        // Store the theme flag for later use in updateComponent
        panel.putClientProperty(THEME_FLAG, darkTheme);
        
        // Create and add each child component in order
        for (ComponentData child : children) {
            var childComp = child.createComponent(darkTheme);
            childComp.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(childComp);
        }
        
        return panel;
    }

    @Override
    public void updateComponent(JComponent component) {
        if (!(component instanceof JPanel panel)) {
            return;
        }
        
        Boolean darkTheme = (Boolean) panel.getClientProperty("THEME_FLAG");

        // Check if number of children matches number of components
        var components = panel.getComponents();
        if (components.length != children.size()) {
            // Child count mismatch, rebuild all components
            panel.removeAll();
            for (ComponentData child : children) {
                var childComp = child.createComponent(darkTheme);
                childComp.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(childComp);
            }
        } else {
            // Update existing components without rebuilding
            for (int i = 0; i < children.size(); i++) {
                if (components[i] instanceof JComponent jcomp) {
                    children.get(i).updateComponent(jcomp);
                }
            }
        }

        panel.revalidate();
        panel.repaint();
    }
}
