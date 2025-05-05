package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;

import java.util.HashSet;
import java.util.Set;

/**
 * A custom renderer for HTML nodes that selectively passes through whitelisted tags
 * while still escaping all other HTML for security.
 */
public class RawHtmlWhitelistRenderer implements NodeRenderer {

    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
        handlers.add(new NodeRenderingHandler<>(HtmlInline.class, this::render));
        handlers.add(new NodeRenderingHandler<>(HtmlBlock.class, this::render));
        return handlers;
    }

    /**
     * Renders an HTML node, passing it through raw if it matches our whitelist,
     * or escaping it otherwise.
     */
    private void render(Node node, NodeRendererContext context, HtmlWriter html) {
        String raw = node.getChars().toString();
        String trimmedLower = raw.trim().toLowerCase();
        
        boolean allowed = EditBlockUtils.WHITELISTED_HTML_TAGS.stream()
                .anyMatch(trimmedLower::startsWith);
                
        if (allowed) {
            html.raw(raw); // Pass through unchanged for whitelisted tags
        } else {
            html.text(raw); // Escape anything else
        }
    }

    /**
     * Factory for creating RawHtmlWhitelistRenderer instances.
     */
    public static class Factory implements com.vladsch.flexmark.html.renderer.NodeRendererFactory {
        @Override
        public NodeRenderer apply(DataHolder options) {
            return new RawHtmlWhitelistRenderer();
        }
    }
}
