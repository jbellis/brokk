package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides stable, deterministic IDs for markdown blocks.
 * 
 * These IDs are used to track components across re-renders, allowing the 
 * incremental renderer to reuse existing Swing components when their 
 * content hasn't changed.
 */
public class IdProvider {
    private static final Logger logger = LogManager.getLogger(IdProvider.class);
    
    /**
     * DataKey for storing/retrieving the IdProvider from Flexmark's parser context.
     */
    public static final DataKey<IdProvider> ID_PROVIDER = new DataKey<>("ID_PROVIDER", new IdProvider());
    
    /**
     * Generates a stable ID for a node based on its source position.
     * This ensures that the same block of text will always get the same ID,
     * even if surrounding content changes.
     * 
     * @param node the Flexmark node to generate an ID for
     * @return a deterministic integer ID
     */
    public int getId(Node node) {
        // Use the start offset as a basis for the ID
        // This ensures the same physical block gets the same ID even if content above it changes
        int startOffset = node.getStartOffset();
        
        // Include node type name in the hash calculation
        String nodeType = node.getClass().getSimpleName();
        
        // Combine node type and position for a more unique ID
        int typeHash = nodeType.hashCode();
        int id = Math.abs(31 * startOffset + typeHash);
        
        logger.debug("Generated ID {} for {} node at offset {}", id, nodeType, startOffset);
        return id;
    }
}
