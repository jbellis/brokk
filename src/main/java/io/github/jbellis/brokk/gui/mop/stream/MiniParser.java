package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentDataFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A parser that processes a Jsoup Element tree and extracts custom tags (like code-fence, edit-block) 
 * even when they're nested inside regular HTML elements.
 * 
 * This creates a "mini-tree" representation per top-level element, allowing the incremental renderer
 * to properly handle nested custom blocks while maintaining its flat list architecture.
 */
public class MiniParser {
    private static final Logger logger = LogManager.getLogger(MiniParser.class);
    private static final AtomicInteger snippetIdGen = new AtomicInteger(1000);

    /**
     * Parses a top-level HTML element and extracts all custom tags within it.
     * 
     * @param topLevel The top-level HTML element to parse
     * @param mdFactory The factory for creating markdown components
     * @param factories Map of tag names to their component factories
     * @return A list of ComponentData objects (usually a single composite if nested tags are found)
     */
    public List<ComponentData> parse(Element topLevel, 
                                    MarkdownFactory mdFactory,
                                    Map<String, ComponentDataFactory> factories) {
        
        var childrenData = new ArrayList<ComponentData>();
        var sb = new StringBuilder();
        
        // Recursively walk the element tree
        walkElement(topLevel, sb, childrenData, mdFactory, factories);
        
        // Flush any remaining HTML text
        if (sb.length() > 0) {
            childrenData.add(mdFactory.fromText(sb.toString()));
        }
        
        // If we found any nested custom tags, wrap in a composite
        if (childrenData.size() > 1) {
            // At least one custom tag was found inside, so create a composite
            return List.of(new CompositeComponentData(generateIdForSnippet(), childrenData));
        } else if (childrenData.size() == 1) {
            // Just one component (either all text or a single custom tag)
            return childrenData;
        } else {
            // Empty element (shouldn't happen)
            return List.of();
        }
    }
    
    /**
     * Recursively walks an element tree, extracting custom tags and regular HTML.
     * 
     * @param node Current node being processed
     * @param sb StringBuilder accumulating regular HTML
     * @param childrenData List to collect ComponentData objects
     * @param mdFactory Factory for creating markdown components
     * @param factories Map of custom tag factories
     */
    private void walkElement(Node node, 
                            StringBuilder sb, 
                            List<ComponentData> childrenData,
                            MarkdownFactory mdFactory,
                            Map<String, ComponentDataFactory> factories) {
        
        if (node instanceof Element element) {
            String tagName = element.tagName();
            
            // Check if this is a registered custom tag
            if (factories.containsKey(tagName)) {
                // Flush any accumulated HTML first
                if (sb.length() > 0) {
                    childrenData.add(mdFactory.fromText(sb.toString()));
                    sb.setLength(0);
                }
                
                // Create component for this custom tag
                var factory = factories.get(tagName);
                childrenData.add(factory.fromElement(element));
                
            } else {
                // This is a regular HTML element - serialize opening tag
                sb.append("<").append(tagName);
                
                // Add attributes
                element.attributes().forEach(attr -> 
                    sb.append(" ").append(attr.getKey()).append("=\"").append(attr.getValue()).append("\"")
                );
                
                sb.append(">");
                
                // Recurse into children
                for (var child : element.childNodes()) {
                    walkElement(child, sb, childrenData, mdFactory, factories);
                }
                
                // Add closing tag
                sb.append("</").append(tagName).append(">");
            }
        } else if (node instanceof TextNode textNode) {
            // Just append the text
            sb.append(textNode.getWholeText());
        }
        // Other node types (comments, etc.) are ignored
    }
    
    /**
     * Generates a unique ID for a snippet or composite.
     * 
     * @return A unique integer ID
     */
    private int generateIdForSnippet() {
        return snippetIdGen.getAndIncrement();
    }
}
