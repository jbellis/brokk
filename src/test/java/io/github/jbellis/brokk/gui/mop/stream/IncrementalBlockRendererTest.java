package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.TestUtil;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CodeBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.EditBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the IncrementalBlockRenderer.
 */
public class IncrementalBlockRendererTest {

    @Test
    void topLevelCodeFenceIsRecognized() {
        var md = """
                ```java
                public class Test {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                """;
        var cds = TestUtil.parseMarkdown(md);
        
        // Should produce one component that is a CodeBlockComponentData
        assertEquals(1, cds.size());
        assertTrue(cds.get(0) instanceof CodeBlockComponentData);
    }
    
    @Test
    void nestedCodeFenceNotRecognisedYet() {
        String md = "- item\n  ```java\n  int x=1;\n  ```";
        var cds = TestUtil.parseMarkdown(md);
        
        // Current implementation treats the entire list as one markdown component
        // and doesn't detect the nested code fence
        assertTrue(
            cds.stream().noneMatch(c -> c instanceof CodeBlockComponentData),
            "Current code should NOT detect nested fence"
        );
        assertEquals(1, cds.size());
        assertTrue(cds.get(0) instanceof MarkdownComponentData);
    }
    
    @Test
    void directHtmlNestedFenceNotRecognized() {
        String html = "<ul><li>Here is a code block:\n" +
                      "<code-fence data-id=\"123\" data-lang=\"java\" data-content=\"System.out.println(&quot;test&quot;)\"/>\n" +
                      "</li></ul>";
                      
        var cds = TestUtil.parseHtml(html);
        
        assertTrue(
            cds.stream().noneMatch(c -> c instanceof CodeBlockComponentData),
            "Current code should NOT detect nested fence in direct HTML"
        );
        assertEquals(1, cds.size());
        assertTrue(cds.get(0) instanceof MarkdownComponentData);
    }

    @Test
    void testBuildComponentData() throws Exception {
        // Test with mixed content
        String html = """
            <p>Regular markdown text</p>
            <code-fence data-id="1" data-lang="java" data-content="public class Test {}"></code-fence>
            <p>More text</p>
            <edit-block data-id="2" data-adds="5" data-dels="3" data-file="Test.java"></edit-block>
            <p>Final text</p>
            """;

        var cds = TestUtil.parseHtml(html);

        // Verify results
        assertEquals(5, cds.size(), "Should have 5 components (3 markdown, 1 code, 1 edit)");

        // Check component types and order
        assertTrue(cds.get(0) instanceof MarkdownComponentData);
        assertTrue(cds.get(1) instanceof CodeBlockComponentData);
        assertTrue(cds.get(2) instanceof MarkdownComponentData);
        assertTrue(cds.get(3) instanceof EditBlockComponentData);
        assertTrue(cds.get(4) instanceof MarkdownComponentData);

        // Verify content of components
        var codeBlock = (CodeBlockComponentData) cds.get(1);
        assertEquals(1, codeBlock.id());
        assertEquals("java", codeBlock.lang());
        assertEquals("public class Test {}", codeBlock.body());

        var editBlock = (EditBlockComponentData) cds.get(3);
        assertEquals(2, editBlock.id());
        assertEquals(5, editBlock.adds());
        assertEquals(3, editBlock.dels());
        assertEquals("Test.java", editBlock.file());
    }
}
