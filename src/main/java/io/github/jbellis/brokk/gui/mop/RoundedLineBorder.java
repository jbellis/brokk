package io.github.jbellis.brokk.gui.mop;

import javax.swing.border.AbstractBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * A border with rounded corners and a customizable thickness and color.
 * Used for badges and other UI elements that need rounded edges.
 */
public class RoundedLineBorder extends AbstractBorder {
    private final Color color;
    private final int thickness;
    private final int radius;
    private final boolean fillInside;
    private final Color fillColor;

    /**
     * Creates a rounded line border with the specified color and thickness.
     *
     * @param color Border color
     * @param thickness Border thickness
     * @param radius Corner radius
     */
    public RoundedLineBorder(Color color, int thickness, int radius) {
        this(color, thickness, radius, false, null);
    }

    /**
     * Creates a rounded border that can optionally fill the inside with a color.
     *
     * @param color Border color
     * @param thickness Border thickness
     * @param radius Corner radius
     * @param fillInside Whether to fill the inside of the border
     * @param fillColor Color to fill the inside (ignored if fillInside is false)
     */
    public RoundedLineBorder(Color color, int thickness, int radius, boolean fillInside, Color fillColor) {
        this.color = color;
        this.thickness = thickness;
        this.radius = radius;
        this.fillInside = fillInside;
        this.fillColor = fillColor;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Adjust for thickness
        int adjustedThickness = Math.max(1, thickness);
        int halfThickness = adjustedThickness / 2;
        
        // Draw the fill first if needed
        if (fillInside && fillColor != null) {
            g2.setColor(fillColor);
            g2.fill(new RoundRectangle2D.Float(
                    x, y, width - 1, height - 1, radius, radius));
        }
        
        // Draw the border
        g2.setColor(color);
        g2.setStroke(new BasicStroke(adjustedThickness));
        g2.draw(new RoundRectangle2D.Float(
                x + halfThickness, y + halfThickness, 
                width - adjustedThickness, height - adjustedThickness, 
                radius, radius));
        
        g2.dispose();
    }

    @Override
    public Insets getBorderInsets(Component c) {
        int margin = Math.max(thickness, radius / 2);
        return new Insets(margin, margin, margin, margin);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        int margin = Math.max(thickness, radius / 2);
        insets.left = insets.right = insets.top = insets.bottom = margin;
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return fillInside;
    }
}
