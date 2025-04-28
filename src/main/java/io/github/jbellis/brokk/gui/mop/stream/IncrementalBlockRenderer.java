package io.github.jbellis.brokk.gui.mop.stream;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.Arrays;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 * 
 * This initial skeleton simply displays plain text in a JEditorPane, with proper EDT-safe updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // The root panel that will contain all our content blocks
    private final JPanel root = new JPanel(new BorderLayout());
    // Initial implementation uses a simple text pane
    private final JEditorPane plain = new JEditorPane();
    
    // Flexmark parser components
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final IdProvider idProvider;
    
    /**
     * Creates a new incremental renderer with the given theme.
     * 
     * @param dark true for dark theme, false for light theme
     */
    public IncrementalBlockRenderer(boolean dark) {
        // Configure the plain text pane
        plain.setContentType("text/plain");
        plain.setEditable(false);
        // Prevent caret from moving during updates
        DefaultCaret caret = (DefaultCaret) plain.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        
        // Add the text pane to the root panel
        root.add(plain, BorderLayout.CENTER);
        root.setOpaque(false);
        
        // Initialize Flexmark with our extensions
        idProvider = new IdProvider();
        MutableDataSet options = new MutableDataSet()
            .set(Parser.EXTENSIONS, Arrays.asList(
                    TablesExtension.create(),
                    BrokkMarkdownExtension.create()
            ))
            .set(IdProvider.ID_PROVIDER, idProvider)
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
            .set(HtmlRenderer.ESCAPE_HTML, true);
            
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
        
        logger.debug("Initialized IncrementalBlockRenderer with Flexmark parser and custom extensions");
    }
    
    /**
     * Returns the root component that should be added to a container.
     * 
     * @return the root panel containing all rendered content
     */
    public JComponent getRoot() {
        return root;
    }
    
    /**
     * Updates the content with the given markdown text.
     * This initial implementation simply displays the raw text, but internally
     * parses the markdown to prepare for proper incremental rendering.
     * 
     * @param markdown the markdown text to display
     */
    public void update(String markdown) {
        // Parse with Flexmark
        var document = parser.parse(markdown);
        var html = renderer.render(document);
        
        // Log the HTML with placeholders (for debugging)
        logger.debug("Parsed markdown to HTML with placeholders: {}", 
                    html.length() > 200 ? html.substring(0, 200) + "..." : html);
        
        // For now, still use the plain text pane until we implement the block rendering
        plain.setText(html);
        root.revalidate();
        root.repaint();
    }
}
