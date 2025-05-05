package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom renderer for edit blocks that produces placeholder HTML elements
 * with data attributes instead of actual HTML.
 */
public class EditBlockRenderer implements NodeRenderer {
    private static final Logger logger = LogManager.getLogger(EditBlockRenderer.class);
    
    private final IdProvider idProvider;
    
    public EditBlockRenderer(DataHolder options) {
        this.idProvider = options.get(IdProvider.ID_PROVIDER);
    }
    
    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
        handlers.add(new NodeRenderingHandler<>(EditBlockNode.class, this::render));
        return handlers;
    }
    
    /**
     * Renders an edit block as a placeholder HTML element.
     */
    private void render(EditBlockNode node, NodeRendererContext context, HtmlWriter html) {
        int id = idProvider.getId(node);
        String filename = node.getFilename().toString().trim();
        int adds = node.getAdds();
        int dels = node.getDels();
        int changed = node.getChangedLines();
        String status = node.getStatus().name().toLowerCase();
        
        logger.debug("Rendering edit block with id={}, file={}, adds={}, dels={}, changed={}, status={}", 
                     id, filename, adds, dels, changed, status);
        
        // Output a self-closing placeholder tag with data attributes
        html.line();
        html.raw("<edit-block");
        html.raw(" data-id=\"" + id + "\"");
        html.raw(" data-file=\"" + escapeHtml(filename) + "\"");
        html.raw(" data-adds=\"" + adds + "\"");
          html.raw(" data-dels=\"" + dels + "\"");
          html.raw(" data-changed=\"" + changed + "\"");
          html.raw(" data-status=\"" + status + "\"");
          html.raw("></edit-block>");
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
     * Factory for creating EditBlockRenderer instances.
     */
    public static class Factory implements com.vladsch.flexmark.html.renderer.NodeRendererFactory {
        @Override
        public NodeRenderer apply(DataHolder options) {
            return new EditBlockRenderer(options);
        }
    }
}
