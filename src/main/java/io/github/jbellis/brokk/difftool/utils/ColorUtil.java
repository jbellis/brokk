package io.github.jbellis.brokk.difftool.utils;

import com.github.difflib.patch.AbstractDelta;

import java.awt.*;

public class ColorUtil {

    public static Color brighter(Color color) {
        return brighter(color, 0.05f);
    }

    public static Color darker(Color color) {
        return brighter(color, -0.05f);
    }

    /** Create a brighter color by changing the b component of a
     *    hsb-color (b=brightness, h=hue, s=saturation)
     */
    public static Color brighter(Color color, float factor) {
        float[] hsbvals;

        hsbvals = new float[3];
        Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsbvals);

        return setBrightness(color, hsbvals[2] + factor);
    }

    public static Color setBrightness(Color color, float brightness) {
        float[] hsbvals;

        hsbvals = new float[3];
        Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsbvals);
        hsbvals[2] = brightness;
        hsbvals[2] = Math.min(hsbvals[2], 1.0f);
        hsbvals[2] = Math.max(hsbvals[2], 0.0f);

        color = new Color(Color.HSBtoRGB(hsbvals[0], hsbvals[1], hsbvals[2]));

        return color;
    }

    /**
     * Calculates the perceived brightness of a color.
     * @param color The color.
     * @return A value between 0 (black) and 255 (white).
     */
    public static int getBrightness(Color color) {
        return (int) Math.sqrt(
                color.getRed() * color.getRed() * .241 +
                color.getGreen() * color.getGreen() * .691 +
                color.getBlue() * color.getBlue() * .068);
    }

    public static Color getColor(AbstractDelta<String> delta, boolean darkTheme) {
        return switch (delta.getType()) {
            case INSERT -> Colors.getAdded(darkTheme);
            case DELETE -> Colors.getDeleted(darkTheme);
            case CHANGE -> Colors.getChanged(darkTheme);
            case EQUAL -> throw new IllegalStateException();
        };
    }
}
