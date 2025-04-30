package io.github.jbellis.brokk.gui.mop.stream.blocks;

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

    @Override
    public JComponent createComponent(boolean darkTheme) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
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
        
        // Simple first implementation - rebuild all children when anything changes
        // A more sophisticated implementation could do per-child diffing
        panel.removeAll();
        
        for (ComponentData child : children) {
            var childComp = child.createComponent(panel.getBackground().equals(Color.BLACK));
            childComp.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(childComp);
        }
        
        panel.revalidate();
        panel.repaint();
    }
}
