package io.github.jbellis.brokk.gui.components;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * A JLabel that displays a clickable hyperlink, opening it in the default browser.
 * Handles common errors like browser unavailability.
 */
public class BrowserLabel extends JLabel {
    private static final Logger logger = LogManager.getLogger(BrowserLabel.class);
    private final String url;

    /**
     * Creates a new BrowserLabel for the specified URL.
     *
     * @param url The URL to link to. The label text will be the same as the URL.
     */
    public BrowserLabel(String url) {
        this(url, url); // Default text is the URL itself
    }

    /**
     * Creates a new BrowserLabel for the specified URL with custom display text.
     *
     * @param url  The URL to link to.
     * @param text The text to display on the label (will be wrapped in HTML link tags).
     */
    public BrowserLabel(String url, String text) {
        super("<html><a href=\"" + url + "\">" + text + "</a></html>");
        this.url = url;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openBrowser();
            }
        });
    }

    private void openBrowser() {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (UnsupportedOperationException ex) {
            logger.error("Browser not supported: {}", url, ex);
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this), // Get parent window
                    "Sorry, unable to open browser automatically. This is a known problem on WSL.\nPlease visit: " + url,
                    "Browser Unsupported",
                    JOptionPane.WARNING_MESSAGE
            );
        } catch (Exception ex) {
            logger.error("Failed to open URL: {}", url, ex);
            JOptionPane.showMessageDialog(
                    SwingUtilities.getWindowAncestor(this), // Get parent window
                    "Failed to open the browser. Please visit:\n" + url,
                    "Browser Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }
}
