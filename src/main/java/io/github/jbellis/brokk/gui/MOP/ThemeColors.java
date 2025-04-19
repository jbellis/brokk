package io.github.jbellis.brokk.gui.MOP;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper class for managing theme-specific colors throughout the application.
 */
public class ThemeColors {
    // Color constants for dark theme
    private static final Map<String, Color> DARK_COLORS = new HashMap<>();
    // Color constants for light theme
    private static final Map<String, Color> LIGHT_COLORS = new HashMap<>();
    
    static {
        // Initialize dark theme colors
        DARK_COLORS.put("chat_background", new Color(37, 37, 37));
        DARK_COLORS.put("message_background", new Color(64, 64, 64));
        DARK_COLORS.put("chat_text", new Color(212, 212, 212));
        DARK_COLORS.put("chat_header_text", new Color(114, 159, 207));
        DARK_COLORS.put("message_border_custom", new Color(46, 100, 55));
        DARK_COLORS.put("message_border_ai", new Color(139, 92, 246));
        DARK_COLORS.put("message_border_user", new Color(59, 130, 246));
        DARK_COLORS.put("ai_message_border", new Color(255, 215, 0)); // Gold for AI messages
        DARK_COLORS.put("system_message_border", new Color(30, 144, 255)); // Dodger blue for system messages
        DARK_COLORS.put("user_message_border", new Color(80, 80, 80)); // Gray for user messages

        // Initialize light theme colors
        LIGHT_COLORS.put("chat_background", new Color(240, 240, 240));
        LIGHT_COLORS.put("message_background", Color.GRAY);
        LIGHT_COLORS.put("chat_text", new Color(30, 30, 30));
        LIGHT_COLORS.put("chat_header_text", new Color(51, 103, 214));
        LIGHT_COLORS.put("message_border", Color.GRAY);
        LIGHT_COLORS.put("ai_message_border", new Color(218, 165, 32)); // Darker gold for AI messages
        LIGHT_COLORS.put("system_message_border", new Color(0, 0, 255)); // Blue for system messages
        LIGHT_COLORS.put("user_message_border", Color.GRAY); // Gray for user messages
    }
    
    /**
     * Gets a color for the specified theme and key.
     * 
     * @param isDarkTheme true for dark theme, false for light theme
     * @param key the color key
     * @return the Color for the specified theme and key
     * @throws IllegalArgumentException if the key doesn't exist
     */
    public static Color getColor(boolean isDarkTheme, String key) {
        Map<String, Color> colors = isDarkTheme ? DARK_COLORS : LIGHT_COLORS;
        Color color = colors.get(key);
        
        if (color == null) {
            throw new IllegalArgumentException("Color key not found: " + key);
        }
        
        return color;
    }
    
    /**
     * Adds or updates a color in the theme maps.
     * 
     * @param key the color key
     * @param darkColor the color for dark theme
     * @param lightColor the color for light theme
     */
    public static void setColor(String key, Color darkColor, Color lightColor) {
        DARK_COLORS.put(key, darkColor);
        LIGHT_COLORS.put(key, lightColor);
    }
}
