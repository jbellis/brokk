package io.github.jbellis.brokk.gui.mop.stream;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentDataFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownFactory;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // The root panel that will contain all our content blocks
    private final JPanel root;
    private final boolean isDarkTheme;
    
    // Flexmark parser components
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final IdProvider idProvider;

    // Component tracking
    private final Map<Integer, BlockEntry> registry = new LinkedHashMap<>();
    private String lastHtmlFingerprint = "";
    
    // Component factories
    private static final Map<String, ComponentDataFactory> FACTORIES = 
            ServiceLoader.load(ComponentDataFactory.class)
                         .stream()
                         .map(ServiceLoader.Provider::get)
                         .collect(Collectors.toMap(ComponentDataFactory::tagName, f -> f));
    
    // Fallback factory for markdown content
    private final MarkdownFactory markdownFactory = new MarkdownFactory();

    /**
     * Creates a new incremental renderer with the given theme.
     * 
     * @param dark true for dark theme, false for light theme
     */
    public IncrementalBlockRenderer(boolean dark) {
        this.isDarkTheme = dark;
        
        // Create root panel with vertical BoxLayout
        root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setOpaque(false);
        root.setBackground(ThemeColors.getColor(dark, "chat_background"));
        
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
     * Parses the markdown, extracts components, and updates the UI incrementally.
     * 
     * @param markdown the markdown text to display
     */
    public void update(String markdown) {
        // Parse with Flexmark
        var document = parser.parse(markdown);
        var html = renderer.render(document);
        
        // Skip if nothing changed
        String htmlFp = html.hashCode() + "";
        if (htmlFp.equals(lastHtmlFingerprint)) {
            logger.debug("Skipping update - content unchanged");
            return;
        }
        lastHtmlFingerprint = htmlFp;
        
        // Extract component data from HTML
        List<ComponentData> components = buildComponentData(html);
        
        // Update the UI with the reconciled components
        updateUI(components);
    }
    
    /**
     * Updates the UI with the given component data, reusing existing components when possible.
     * 
     * @param components The list of component data to render
     */
    private void updateUI(List<ComponentData> components) {
        Set<Integer> seen = new HashSet<>();
        
        // Process each component
        for (ComponentData cd : components) {
            BlockEntry entry = registry.get(cd.id());
            
            if (entry == null) {
                // Create new component
                JComponent comp = cd.createComponent(isDarkTheme);
                entry = new BlockEntry(comp, cd.fp());
                registry.put(cd.id(), entry);
                root.add(comp);
                logger.debug("Created new component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
            } else {
                logger.debug("cd.fp()={} vs. entry.fp={}", cd.fp(), entry.fp);
                if (!cd.fp().equals(entry.fp)) {
                    // Update existing component
                    cd.updateComponent(entry.comp);
                    entry.fp = cd.fp();
                    logger.debug("Updated component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
                }
            }
            
            seen.add(cd.id());
        }
        
        // Remove components that are no longer present
        registry.keySet().removeIf(id -> {
            if (!seen.contains(id)) {
                logger.debug("Removing component with id {}", id);
                root.remove(registry.get(id).comp);
                return true;
            }
            return false;
        });
        
        // Ensure components are in the correct order
        ensureComponentOrder(components);
        
        // Revalidate and repaint
        root.revalidate();
        root.repaint();
    }
    
    /**
     * Ensures all components are in the correct order according to the component data list.
     * 
     * @param components The ordered list of component data
     */
    private void ensureComponentOrder(List<ComponentData> components) {
        // Remove all components but don't dispose them
        root.removeAll();
        
        // Add them back in the correct order
        for (ComponentData data : components) {
            BlockEntry entry = registry.get(data.id());
            if (entry != null) {
                root.add(entry.comp);
            }
        }
    }
    
    /**
     * Builds a list of component data by parsing the HTML and extracting all placeholders
     * and intervening prose segments.
     * 
     * @param html The HTML string to parse
     * @return A list of ComponentData objects in document order
     */
    private List<ComponentData> buildComponentData(String html) {
        List<ComponentData> result = new ArrayList<>();
        
        Document doc = Jsoup.parse(html);
        var body = doc.body();
        
        // Track text nodes until we hit a special block
        StringBuilder currentText = new StringBuilder();
        // reset counter to create stable ids for markdown segments
        MarkdownFactory.resetCounter();
        // Process body as a flat sequence, handling text separately
        for (Node node : body.childNodes()) {
            if (node instanceof Element element) {
                String tagName = element.tagName();
                
                
                var factory = FACTORIES.get(tagName);
                logger.debug(html);
                if (factory != null) {
                    // We found a special block
                    
                    // If we have accumulated text, add it as a markdown component first
                    if (!currentText.isEmpty()) {
                        result.add(markdownFactory.fromText(currentText.toString()));
                        currentText.setLength(0);
                    }
                    
                    // Add the special block
                    result.add(factory.fromElement(element));
                    continue;
                }
            }
            
            // Regular HTML, append to current text
            currentText.append(node);
        }
        
        // Don't forget any trailing text
        if (!currentText.isEmpty()) {
            result.add(markdownFactory.fromText(currentText.toString()));
        }
        
        return result;
    }
    
    /**
     * Tracks a rendered component and its current fingerprint.
     */
    private static class BlockEntry {
        JComponent comp;
        String fp;
        
        BlockEntry(JComponent comp, String fp) {
            this.comp = comp;
            this.fp = fp;
        }
    }
}
