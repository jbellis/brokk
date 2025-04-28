package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flexmark extension for Brokk edit blocks.
 * 
 * This extension registers both parsers and renderers for edit blocks.
 */
public class BrokkMarkdownExtension implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    private static final Logger logger = LogManager.getLogger(BrokkMarkdownExtension.class);
    
    /**
     * Creates a new EditBlockExtension.
     */
    public BrokkMarkdownExtension() {
        logger.debug("Initializing EditBlockExtension");
    }
    
    /**
     * Extension factory method.
     */
    public static BrokkMarkdownExtension create() {
        return new BrokkMarkdownExtension();
    }
    
    /**
     * Register the extension in parser options.
     */
    @Override
    public void extend(Parser.Builder parserBuilder) {
        parserBuilder.customBlockParserFactory(new EditBlockParser.Factory());
    }
    
    /**
     * Register the extension in renderer options.
     */
    @Override
    public void extend(HtmlRenderer.Builder rendererBuilder, String rendererType) {
        rendererBuilder.nodeRendererFactory(new EditBlockRenderer.Factory());
        rendererBuilder.nodeRendererFactory(new CodeFenceRenderer.Factory());
    }
    
    /**
     * Configure the options for this extension.
     */
    @Override
    public void parserOptions(MutableDataHolder options) {
        // Ensure we have an IdProvider in the options
        if (!options.contains(IdProvider.ID_PROVIDER)) {
            options.set(IdProvider.ID_PROVIDER, new IdProvider());
        }
    }
    
    /**
     * Configure the options for this extension.
     */
    @Override
    public void rendererOptions(MutableDataHolder options) {
        // No extra renderer options needed
    }
}
