package io.github.jbellis.brokk.gui.mop.stream.blocks;

import org.jsoup.nodes.Element;

/**
 * Factory for creating MarkdownComponentData instances.
 * Handles both explicit "markdown" tags and plain text nodes.
 */
public class MarkdownFactory implements ComponentDataFactory {
    // Counter for generating unique IDs for markdown segments
    private static int markdownCounter = 0;
    
    @Override
    public String tagName() {
        return "markdown";
    }
    
    @Override
    public ComponentData fromElement(Element element) {
        return new MarkdownComponentData(++markdownCounter, element.html());
    }
    
    /**
     * Creates a MarkdownComponentData from plain text.
     */
    public ComponentData fromText(String html) {
        return new MarkdownComponentData(++markdownCounter, html);
    }
}
