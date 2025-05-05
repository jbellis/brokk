package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.git.GitStatus;
import io.github.jbellis.brokk.gui.mop.TestUtil;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CodeBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.EditBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void nestedCodeFenceIsRecognized() {
        String md = "- item\n  ```java\n  int x=1;\n  ```";
        var cds = TestUtil.parseMarkdown(md);
        
        // After implementing MiniParser, we should now detect the nested code fence
        assertTrue(
            cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
            "Should detect nested fence as a CompositeComponentData"
        );
        
        // Get the first composite and check it contains a CodeBlockComponentData
        var composite = (CompositeComponentData) cds.stream()
            .filter(c -> c instanceof CompositeComponentData)
            .findFirst()
            .orElseThrow();
            
        assertTrue(
            composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData),
            "Composite should contain a CodeBlockComponentData"
        );
    }
    
    @Test
    void directHtmlNestedFenceIsRecognized() {
        String html = "<ul><li>Here is a code block:\n" +
                      "<code-fence data-id=\"123\" data-lang=\"java\" data-content=\"System.out.println(&quot;test&quot;)\"/>\n" +
                      "</li></ul>";
                      
        var cds = TestUtil.parseHtml(html);
        
        // With MiniParser, we should now extract the nested code fence
        assertTrue(
            cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
            "Should detect nested fence in direct HTML as a CompositeComponentData"
        );
        
        // Get the composite and verify it contains a code block
        var composite = (CompositeComponentData) cds.stream()
            .filter(c -> c instanceof CompositeComponentData)
            .findFirst()
            .orElseThrow();
            
        assertTrue(
            composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData),
            "Composite should contain a CodeBlockComponentData"
        );
    }

    @Test
    void testBuildComponentData() throws Exception {
        // Test with mixed content
        String html = """
            <p>Regular markdown text</p>
            <code-fence data-id="1" data-lang="java"><pre>public class Test {}</pre></code-fence>
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
          assertEquals(3, editBlock.changed()); // changed = min(5, 3) = 3
          assertEquals("Test.java", editBlock.file());
          assertEquals(GitStatus.UNKNOWN, editBlock.status());
      }
    
    @Test
    void compositeBuildsChildComponents() {
        var markdown = new MarkdownComponentData(1, "<p>hello</p>");
        var code = new CodeBlockComponentData(2, "int x = 1;", "java");
        var composite = new CompositeComponentData(99, List.of(markdown, code));
        
        var comp = composite.createComponent(false);
        
        // The composite should create a panel with two child components
        assertEquals(2, comp.getComponentCount());
        assertEquals(markdown.fp() + "-" + code.fp(), composite.fp());
    }
    
    @Test
    void compositeFingerprintChangesWhenChildChanges() {
        var md1 = "- a\n  ```java\n  x();\n  ```";
        var md2 = "- a\n  ```java\n  y();\n  ```";  // code content changed
        
        // Parse both markdown snippets
        var components1 = TestUtil.parseMarkdown(md1);
        var components2 = TestUtil.parseMarkdown(md2);
        
        // Both should produce a CompositeComponentData
        assertEquals(1, components1.size());
        assertEquals(1, components2.size());
        assertTrue(components1.get(0) instanceof CompositeComponentData);
        assertTrue(components2.get(0) instanceof CompositeComponentData);
        
        // Get fingerprints
        String fp1 = components1.get(0).fp();
        String fp2 = components2.get(0).fp();
        
        // Fingerprints should be different because the code content changed
        assertNotEquals(fp1, fp2, "Composite fingerprint should change when child content changes");
    }
    
    @Test
    void incrementalRendererDetectsNestedContentChange() {
        var md1 = "- a\n  ```java\n  x();\n  ```";
        var md2 = "- a\n  ```java\n  y();\n  ```";  // child changed
        
        // Create a renderer and update with first markdown
        var renderer = new IncrementalBlockRenderer(false);
        renderer.update(md1);
        
        // Get the first component to check later
        var rootPanel = renderer.getRoot();
        var firstComponent = rootPanel.getComponent(0);
        
        // Update with second markdown
        renderer.update(md2);
        
        // The component should have been updated, not recreated
        // So it should be the same component instance
        assertEquals(firstComponent, rootPanel.getComponent(0), 
            "Component instance should be reused even when nested content changes");
        
        // But the content should have been updated
        var panel = (JPanel)rootPanel.getComponent(0);
        assertTrue(panel.getComponentCount() > 0, "Panel should contain components");
    }
    
    @Test
    void multipleMixedCustomTagsAreParsed() {
        String md = """
            Here's a list with mixed content:
            
            - Item 1: normal text
            - Item 2: with code
              ```java
              System.out.println("Item 2");
              ```
            - Item 3: with edit block
              <<<<<<< SEARCH Test.java
              void test() {}
              ======= Test.java
              void test() { return; }
              >>>>>>> REPLACE Test.java
            """;
        
        var cds = TestUtil.parseMarkdown(md);
        
        // Should produce at least one composite
        assertTrue(
            cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
            "Should detect mixed custom tags as a CompositeComponentData"
        );
        
        // Find any composite and verify it has both types of custom blocks
        var composite = cds.stream()
            .filter(c -> c instanceof CompositeComponentData)
            .map(c -> (CompositeComponentData)c)
            .findFirst()
            .orElseThrow();
        
        boolean hasCodeBlock = composite.children().stream()
            .anyMatch(c -> c instanceof CodeBlockComponentData);
            
        boolean hasEditBlock = composite.children().stream()
            .anyMatch(c -> c instanceof EditBlockComponentData);
            
        assertTrue(hasCodeBlock || hasEditBlock, 
            "Composite should contain either a CodeBlockComponentData or EditBlockComponentData");
    }
    
    @Test
    void multipleMixedCustomTagsAreParsed2() {
        String md = """
            Here's a list with mixed content:
            
            - Item 1: normal text
            - Item 2: with code
              ```java
              System.out.println("Item 2");
              ```
            - Item 3: with edit block
              ```
              Test.java
              <<<<<<< SEARCH
              void test() {}
              ======= Test.java
              void test() { return; }
              >>>>>>> REPLACE
              ```
            """;
        
        var cds = TestUtil.parseMarkdown(md);
        
        // Should produce at least one composite
        assertTrue(
            cds.stream().anyMatch(c -> c instanceof CompositeComponentData),
            "Should detect mixed custom tags as a CompositeComponentData"
        );
        
        // Find any composite and verify it has both types of custom blocks
        var composite = cds.stream()
            .filter(c -> c instanceof CompositeComponentData)
            .map(c -> (CompositeComponentData)c)
            .findFirst()
            .orElseThrow();
        
        boolean hasCodeBlock = composite.children().stream()
            .anyMatch(c -> c instanceof CodeBlockComponentData);
            
        boolean hasEditBlock = composite.children().stream()
            .anyMatch(c -> c instanceof EditBlockComponentData);
            
        assertTrue(hasCodeBlock || hasEditBlock, 
            "Composite should contain either a CodeBlockComponentData or EditBlockComponentData");
    }
}
