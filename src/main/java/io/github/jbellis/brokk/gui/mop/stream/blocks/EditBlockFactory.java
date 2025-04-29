package io.github.jbellis.brokk.gui.mop.stream.blocks;

import org.jsoup.nodes.Element;

/**
 * Factory for creating EditBlockComponentData instances from HTML elements.
 */
public class EditBlockFactory implements ComponentDataFactory {
    @Override
    public String tagName() {
        return "edit-block";
    }
    
    @Override
    public ComponentData fromElement(Element element) {
        int id = Integer.parseInt(element.attr("data-id"));
        int adds = Integer.parseInt(element.attr("data-adds"));
        int dels = Integer.parseInt(element.attr("data-dels"));
        String file = element.attr("data-file");
        
        return new EditBlockComponentData(id, adds, dels, file);
    }
}
