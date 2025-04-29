package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.ast.Document;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom renderer for fenced code blocks that produces placeholder HTML elements
 * with data attributes instead of actual HTML code blocks.
 */
public class CodeFenceRenderer implements NodeRenderer {
    private static final Logger logger = LogManager.getLogger(CodeFenceRenderer.class);
    
    private final IdProvider idProvider;
    
    public CodeFenceRenderer(DataHolder options) {
        this.idProvider = options.get(IdProvider.ID_PROVIDER);
    }
    
    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
        handlers.add(new NodeRenderingHandler<>(FencedCodeBlock.class, this::render));
        return handlers;
    }
    
    /**
     * Renders a fenced code block as a placeholder HTML element.
     */
    private void render(FencedCodeBlock node, NodeRendererContext context, HtmlWriter html) {
        // Only convert *direct* document children
        if (!(node.getParent() instanceof Document)) {
            // Nested -> let Flexmark's standard renderer handle it
            context.delegateRender();
            return;
        }
        
        int id = idProvider.getId(node);
        String language = node.getInfo().toString();
        String content = node.getContentChars().toString();
        
        logger.debug("Rendering code fence with id={}, language={}, content length={}", 
                     id, language, content.length());
        
        // Output placeholder tag with content both as text and as attribute (for test compatibility)
        html.line();
        html.raw("<code-fence");
        html.raw(" data-id=\"" + id + "\"");
        html.raw(" data-lang=\"" + escapeHtml(language) + "\"");
        html.raw(" data-content=\"" + escapeHtml(content) + "\"");
        html.raw(">");
        html.text(content); // HtmlWriter.text() automatically handles HTML escaping
        html.raw("</code-fence>");
        html.line();
    }
    
    /**
     * Basic HTML escaping for attribute values.
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;");
    }
    
    /**
     * Factory for creating CodeFenceRenderer instances.
     */
    public static class Factory implements com.vladsch.flexmark.html.renderer.NodeRendererFactory {
        @Override
        public NodeRenderer apply(DataHolder options) {
            return new CodeFenceRenderer(options);
        }
    }
}
