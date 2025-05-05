package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * Flexmark extension for Brokk-specific Markdown syntax.
 * Registers custom block parsers for edit blocks and code fences.
 */
public class BrokkMarkdownExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    
    private BrokkMarkdownExtension() {
    }
    
    public static BrokkMarkdownExtension create() {
        return new BrokkMarkdownExtension();
    }

    @Override
    public void extend(Parser.Builder parserBuilder) {
        parserBuilder.customBlockParserFactory(new EditBlockParser.Factory());
    }

    @Override
    public void extend(HtmlRenderer.Builder rendererBuilder, String rendererType) {
        rendererBuilder.nodeRendererFactory(new EditBlockRenderer.Factory());
        rendererBuilder.nodeRendererFactory(new CodeFenceRenderer.Factory());
        rendererBuilder.nodeRendererFactory(new RawHtmlWhitelistRenderer.Factory());
    }

    @Override
    public void parserOptions(MutableDataHolder options) {
        // No parser options to configure
    }

    @Override
    public void rendererOptions(MutableDataHolder options) {
        // No renderer options to configure
    }
}
