package io.github.jbellis.brokk.gui.mop.stream.blocks;

import org.jsoup.nodes.Element;

/**
 * Factory for creating CodeBlockComponentData instances from HTML elements.
 */
public class CodeBlockFactory implements ComponentDataFactory {
    @Override
    public String tagName() {
        return "code-fence";
    }
    
    @Override
    public ComponentData fromElement(Element element) {
        int id = Integer.parseInt(element.attr("data-id"));
        String lang = element.attr("data-lang");
        String content = element.attr("data-content");
        
        return new CodeBlockComponentData(id, content, lang);
    }
}
