package io.github.jbellis.brokk.difftool.utils;

import io.github.jbellis.brokk.difftool.diff.JMDelta;

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

    public static Color getColor(JMDelta delta, boolean isDark) {
        if (delta.isDelete()) {
            return Colors.getDeleted(isDark);
        }

        if (delta.isChange()) {
            return Colors.getChanged(isDark);
        }

        return Colors.getAdded(isDark);
    }

}
