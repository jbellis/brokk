package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A panel that stores Markdown text incrementally, supporting normal text blocks
 * and fenced code blocks. We maintain an "active" block of text or code until
 * we see a fence that switches modes. This avoids splitting Markdown headings
 * or lists when text arrives in multiple chunks.
 *
 * The state machine logic:
 * - TEXT mode: we accumulate normal lines (Markdown) in a single JEditorPane.
 *   If we encounter a code fence (``` + optional info), we finalize the text block,
 *   parse the fence info, switch to CODE mode, and create a new code block.
 * - CODE mode: we accumulate lines in an RSyntaxTextArea. If we encounter a code
 *   fence, we finalize the code block, switch to TEXT mode, and create a new text block.
 */
class MarkdownOutputPanel extends JPanel implements Scrollable
{
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPanel.class);

    // We track partial text so we can parse incrementally.
    private enum ParseState { TEXT, CODE }

    // Holds *all* the raw Markdown appended so far (for getText()).
    private final StringBuilder markdownBuffer = new StringBuilder();

    // Current parse mode
    private ParseState currentState = ParseState.TEXT;

    // Accumulates text lines in the current mode
    private final StringBuilder currentBlockContent = new StringBuilder();

    // Tracks code fence info (e.g. "java", "python") for the current code block
    private String currentFenceInfo = "";

    // The active text editor pane (if currentState = TEXT), or null if in CODE mode
    private JEditorPane activeTextPane = null;

    // The active code area (if currentState = CODE), or null if in TEXT mode
    private RSyntaxTextArea activeCodeArea = null;

    // Listeners to notify whenever text changes
    private final List<Runnable> textChangeListeners = new ArrayList<>();
    private String pendingBuffer = "";

    // Flexmark parser and renderer for normal Markdown blocks
    private final Parser parser;
    private final HtmlRenderer renderer;

    // Theme-related fields
    private boolean isDarkTheme = false;
    private Color textBackgroundColor = null;
    private Color codeBackgroundColor = null;
    private Color codeBorderColor = null;

    public MarkdownOutputPanel()
    {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(true);

        // Build the Flexmark parser for normal text blocks
        parser = Parser.builder().build();
        renderer = HtmlRenderer.builder().build();

        // Initially start a text block
        startTextBlock();
    }

    /**
     * Updates the theme colors used by this panel. Must be called before adding text,
     * or whenever you want to re-theme existing blocks.
     */
    public void updateTheme(boolean isDark)
    {
        this.isDarkTheme = isDark;

        if (isDark) {
            textBackgroundColor = new Color(40, 40, 40);
            codeBackgroundColor = new Color(50, 50, 50);
            codeBorderColor = new Color(80, 80, 80);
        } else {
            textBackgroundColor = Color.WHITE;
            codeBackgroundColor = new Color(240, 240, 240);
            codeBorderColor = Color.GRAY;
        }

        setOpaque(true);
        setBackground(textBackgroundColor);

        var parent = getParent();
        if (parent instanceof JViewport vp) {
            vp.setOpaque(true);
            vp.setBackground(textBackgroundColor);
            var gp = vp.getParent();
            if (gp instanceof JScrollPane sp) {
                sp.setOpaque(true);
                sp.setBackground(textBackgroundColor);
            }
        }

        // Update spinner background if visible
        if (spinnerPanel != null) {
            spinnerPanel.updateBackgroundColor(textBackgroundColor);
        }

        revalidate();
        repaint();
    }

    /**
     * Clears all text and displayed components. The parse state is reset.
     */
    public void clear()
    {
        logger.debug("Clearing all content from MarkdownOutputPanel");

        markdownBuffer.setLength(0);
        removeAll();

        currentState = ParseState.TEXT;
        currentBlockContent.setLength(0);
        currentFenceInfo = "";

        activeTextPane = null;
        activeCodeArea = null;

        // Start fresh in TEXT mode
        startTextBlock();

        revalidate();
        repaint();
    }

    /**
     * Appends more Markdown text. We parse only the newly appended segment
     * and update the active text/code block(s) as needed.
     */
    public void append(String text)
    {
        assert text != null;
        if (!text.isEmpty()) {
            markdownBuffer.append(text);
            parseIncremental(text);
            revalidate();
            repaint();
            textChangeListeners.forEach(Runnable::run);
        }
    }

    /**
     * Sets the entire text buffer to the given Markdown, re-parsing from scratch.
     */
    public void setText(String text)
    {
        assert text != null;
        clear();
        append(text);
    }

    /**
     * Returns the entire unrendered Markdown text so far.
     */
    public String getText()
    {
        return markdownBuffer.toString();
    }

    /**
     * Let callers listen for changes in the text.
     */
    public void addTextChangeListener(Runnable listener)
    {
        textChangeListeners.add(listener);
    }

    /**
     * Parses newly appended text incrementally.
     * We look for fences (```), and whenever we switch modes,
     * we finalize the old block and start a new one.
     */
    private void parseIncremental(String newText)
    {
        var textToParse = pendingBuffer + newText;
        pendingBuffer = "";
        var backtickCount = 0;
        for (int i = textToParse.length() - 1; i >= 0 && textToParse.charAt(i) == '`'; i--) {
            backtickCount++;
        }
        if (backtickCount > 0 && backtickCount < 3) {
            pendingBuffer = textToParse.substring(textToParse.length() - backtickCount);
            textToParse = textToParse.substring(0, textToParse.length() - backtickCount);
        }
        var lines = textToParse.split("\\r?\\n", -1);

        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            int startIdx = 0;
            while (true) {
                var fencePos = line.indexOf("```", startIdx);
                if (fencePos < 0) {
                    // No more fences in this line: append everything to current block
                    currentBlockContent.append(line.substring(startIdx));
                    // Only add a newline if this isn't the last line of the chunk
                    // (in the corner case where the last line ended with a newline, split() will give us an extra
                    // empty line, so we still don't want to add a newline in that case)
                    if (i < lines.length - 1) {
                        currentBlockContent.append("\n");
                    }
                    updateActiveBlock();
                    break;
                }

                // Append everything before the fence to the current block
                currentBlockContent.append(line, startIdx, fencePos);
                updateActiveBlock();

                // We found a fence, so finalize the current block
                finalizeActiveBlock();

                // Switch modes
                if (currentState == ParseState.TEXT) {
                    // We were in TEXT, so we must move to CODE
                    currentState = ParseState.CODE;
                    parseFenceInfo(line, fencePos + 3); // parse language
                    startCodeBlock();
                } else {
                    // We were in CODE, so switch to TEXT
                    currentState = ParseState.TEXT;
                    currentFenceInfo = "";
                    startTextBlock();
                }

                // Advance past the fence
                startIdx = fencePos + 3;
            }
        }
    }

    /**
     * Extracts a possible fence info (language id) that appears immediately
     * after the fence marker. Example: ```java
     */
    private void parseFenceInfo(String line, int fenceInfoStart)
    {
        var fenceRemainder = line.substring(fenceInfoStart).stripLeading();
        var parts = fenceRemainder.split("\\s+", 2);
        if (parts.length > 0 && !parts[0].isEmpty()) {
            currentFenceInfo = parts[0].toLowerCase();
            logger.debug("Parsed fence info: {}", currentFenceInfo);
        } else {
            currentFenceInfo = "";
        }
    }

    /**
     * Create a new text block and set it as activeTextPane. Clears currentBlockContent.
     */
    private void startTextBlock()
    {
        logger.debug("Starting new TEXT block");

        // Create a new JEditorPane
        activeTextPane = createHtmlPane();
        add(activeTextPane);

        currentBlockContent.setLength(0);
        activeCodeArea = null;
    }

    /**
     * Create a new code block and set it as activeCodeArea. Clears currentBlockContent.
     */
    private void startCodeBlock()
    {
        logger.debug("Starting new CODE block: {}", currentFenceInfo);

        activeCodeArea = createConfiguredCodeArea(currentFenceInfo, "");
        add(codeAreaInPanel(activeCodeArea));

        currentBlockContent.setLength(0);
        activeTextPane = null;
    }

    /**
     * Finalize the current block so it no longer receives appended text.
     * We do not remove it, just treat it as "closed".
     */
    private void finalizeActiveBlock()
    {
        if (currentState == ParseState.TEXT && activeTextPane != null) {
            // Last update before we freeze
            updateActiveBlock();
            activeTextPane = null;
            logger.debug("Finalized TEXT block");
        } else if (currentState == ParseState.CODE && activeCodeArea != null) {
            updateActiveBlock();
            activeCodeArea = null;
            logger.debug("Finalized CODE block");
        }
        currentBlockContent.setLength(0);
    }

    /**
     * Update the currently active block with the content in currentBlockContent.
     */
    private void updateActiveBlock()
    {
        if (currentState == ParseState.TEXT && activeTextPane != null) {
            var html = renderer.render(parser.parse(currentBlockContent.toString()));
            activeTextPane.setText("<html><body>" + html + "</body></html>");
        } else if (currentState == ParseState.CODE) {
            activeCodeArea.setText(currentBlockContent.toString());
        }
    }

    /**
     * Creates an RSyntaxTextArea for a code block, setting the syntax style and theme.
     */
    private RSyntaxTextArea createConfiguredCodeArea(String fenceInfo, String content)
    {
        var codeArea = new RSyntaxTextArea(content);
        codeArea.setEditable(false);
        codeArea.setLineWrap(true);
        codeArea.setWrapStyleWord(true);

        codeArea.setSyntaxEditingStyle(switch (fenceInfo) {
            case "java" -> SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python" -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript" -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        });
        codeArea.setHighlightCurrentLine(false);

        try {
            if (isDarkTheme) {
                var darkTheme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                darkTheme.apply(codeArea);
            } else {
                var lightTheme = Theme.load(getClass().getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/default.xml"));
                lightTheme.apply(codeArea);
            }
        } catch (IOException e) {
            if (isDarkTheme) {
                codeArea.setBackground(new Color(50, 50, 50));
                codeArea.setForeground(new Color(230, 230, 230));
            }
        }

        return codeArea;
    }

    /**
     * Wraps an RSyntaxTextArea in a panel with a border and vertical spacing.
     */
    private JPanel codeAreaInPanel(RSyntaxTextArea textArea)
    {
        var panel = new JPanel(new BorderLayout());
        panel.setBackground(codeBackgroundColor);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, textArea.getPreferredSize().height));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

        var textAreaPanel = new JPanel(new BorderLayout());
        textAreaPanel.setBackground(codeBackgroundColor);
        textAreaPanel.setBorder(BorderFactory.createLineBorder(codeBorderColor, 3, true));
        textAreaPanel.add(textArea);

        panel.add(textAreaPanel);
        return panel;
    }

    /**
     * Creates a JEditorPane for HTML content. We set base CSS to match the theme.
     */
    private JEditorPane createHtmlPane()
    {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(LEFT_ALIGNMENT);
        htmlPane.setText("<html><body></body></html>");

        if (textBackgroundColor != null) {
            htmlPane.setBackground(textBackgroundColor);

            var kit = (HTMLEditorKit) htmlPane.getEditorKit();
            var ss = kit.getStyleSheet();

            // Base background and text color
            var bgColorHex = String.format("#%02x%02x%02x",
                                           textBackgroundColor.getRed(),
                                           textBackgroundColor.getGreen(),
                                           textBackgroundColor.getBlue());
            var textColor = isDarkTheme ? "#e6e6e6" : "#000000";
            var linkColor = isDarkTheme ? "#88b3ff" : "#0366d6";

            ss.addRule("body { font-family: sans-serif; background-color: "
                               + bgColorHex + "; color: " + textColor + "; }");
            ss.addRule("a { color: " + linkColor + "; }");
            ss.addRule("code { padding: 2px; background-color: "
                               + (isDarkTheme ? "#3c3f41" : "#f6f8fa") + "; }");
        }

        return htmlPane;
    }

    // --- Spinner Logic ---

    // We keep a reference to the spinner panel itself, so we can remove it later
    private SpinnerIndicatorPanel spinnerPanel = null;

    /**
     * Shows a small spinner (or message) at the bottom of the panel,
     * underneath whatever content the user just appended.
     * If already showing, does nothing.
     *
     * @param message The text to display next to the spinner (e.g. "Thinking...")
     */
    public void showSpinner(String message)
    {
        if (spinnerPanel != null) {
            // Already showing, update the message and return
            spinnerPanel.setMessage(message);
            return;
        }
        // Create a new spinner instance each time
        spinnerPanel = new SpinnerIndicatorPanel(message, isDarkTheme, textBackgroundColor);

        // Add to the end of this panel. Since we have a BoxLayout (Y_AXIS),
        // it shows up below the existing text or code blocks.
        this.add(spinnerPanel);

        this.revalidate();
        this.repaint();
    }

    /**
     * Hides the spinner panel if it is visible, removing it from the UI.
     */
    public void hideSpinner()
    {
        if (spinnerPanel == null) {
            return; // not showing
        }
        this.remove(spinnerPanel);
        spinnerPanel = null;

        this.revalidate();
        this.repaint();
    }

    // --- Scrollable interface methods ---

    @Override
    public Dimension getPreferredScrollableViewportSize()
    {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
    {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
    {
        return orientation == SwingConstants.VERTICAL
                ? visibleRect.height : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth()
    {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight()
    {
        return false;
    }
}
