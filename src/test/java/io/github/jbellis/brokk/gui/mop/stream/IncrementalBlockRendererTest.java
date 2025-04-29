package io.github.jbellis.brokk.gui.mop.stream;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalBlockRendererTest {

    @Test
    void testBuildComponentData() throws Exception {
        // Create instance of IncrementalBlockRenderer
        IncrementalBlockRenderer renderer = new IncrementalBlockRenderer(false);

        // Use reflection to access private method
        Method buildComponentDataMethod = IncrementalBlockRenderer.class.getDeclaredMethod("buildComponentData", String.class);
        buildComponentDataMethod.setAccessible(true);

        // Test with mixed content
        String html = """
            <p>Regular markdown text</p>
            <code-fence data-id="1" data-lang="java">public class Test {}</code-fence>
            <p>More text</p>
            <edit-block data-id="2" data-adds="5" data-dels="3" data-file="Test.java"></edit-block>
            <p>Final text</p>
            """;

        @SuppressWarnings("unchecked")
        List<IncrementalBlockRenderer.ComponentData> result =
            (List<IncrementalBlockRenderer.ComponentData>) buildComponentDataMethod.invoke(renderer, html);

        // Verify results
        assertEquals(5, result.size(), "Should have 5 components (3 markdown, 1 code, 1 edit)");
        
        // Check component types and order
        assertTrue(result.get(0) instanceof IncrementalBlockRenderer.MarkdownComponentData);
        assertTrue(result.get(1) instanceof IncrementalBlockRenderer.CodeBlockComponentData);
        assertTrue(result.get(2) instanceof IncrementalBlockRenderer.MarkdownComponentData);
        assertTrue(result.get(3) instanceof IncrementalBlockRenderer.EditBlockComponentData);
        assertTrue(result.get(4) instanceof IncrementalBlockRenderer.MarkdownComponentData);
        
        // Verify content of components
        var codeBlock = (IncrementalBlockRenderer.CodeBlockComponentData) result.get(1);
        assertEquals(1, codeBlock.id());
        assertEquals("java", codeBlock.lang());
        assertEquals("public class Test {}", codeBlock.body());
        
        var editBlock = (IncrementalBlockRenderer.EditBlockComponentData) result.get(3);
        assertEquals(2, editBlock.id());
        assertEquals(5, editBlock.adds());
        assertEquals(3, editBlock.dels());
        assertEquals("Test.java", editBlock.file());
    }
}