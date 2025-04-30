package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.CodeBlockComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CodeBlockFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentDataFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownFactory;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MiniParser class.
 */
public class MiniParserTest {
    
    private MiniParser parser;
    private MarkdownFactory mdFactory;
    private Map<String, ComponentDataFactory> factories;
    
    @BeforeEach
    void setUp() {
        parser = new MiniParser();
        mdFactory = new MarkdownFactory();
        factories = new HashMap<>();
        factories.put("code-fence", new CodeBlockFactory());
    }
    
    @Test
    void testParseSimpleParagraph() {
        var html = "<p>This is a simple paragraph.</p>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <p> element
        
        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single MarkdownComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof MarkdownComponentData);
        var md = (MarkdownComponentData) components.getFirst();
        assertEquals("<p>This is a simple paragraph.</p>", md.html());
    }
    
    @Test
    void testNestedCodeFence() {
        var html = "<ul><li>Here is a code block:\n" +
                  "<code-fence data-id=\"123\" data-lang=\"java\" data-content=\"System.out.println(&quot;test&quot;);\"/>\n" +
                  "</li></ul>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <ul> element
        
        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // The composite should have 3 children: HTML before, code fence, HTML after
        var composite = (CompositeComponentData) components.getFirst();
        assertEquals(3, composite.children().size());
        
        // First child is HTML up to the code fence
        assertTrue(composite.children().get(0) instanceof MarkdownComponentData);
        assertTrue(((MarkdownComponentData)composite.children().get(0)).html().contains("<ul><li>Here is a code block:"));
        
        // Second child is the code fence
        assertTrue(composite.children().get(1) instanceof CodeBlockComponentData);
        var codeBlock = (CodeBlockComponentData) composite.children().get(1);
        assertEquals("java", codeBlock.lang());
        assertEquals("System.out.println(\"test\");", codeBlock.body());
        
        // Third child is the closing HTML
        assertTrue(composite.children().get(2) instanceof MarkdownComponentData);
        assertTrue(((MarkdownComponentData)composite.children().get(2)).html().contains("</li></ul>"));
    }
    
    @Test
    void testMultipleNestedCustomTags() {
        var html = "<div>" +
                  "  <p>First paragraph</p>" +
                  "  <code-fence data-id=\"123\" data-lang=\"java\" data-content=\"code1();\"/>" +
                  "  <p>Second paragraph</p>" +
                  "  <code-fence data-id=\"124\" data-lang=\"python\" data-content=\"code2();\"/>" +
                  "  <p>Final paragraph</p>" +
                  "</div>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <div> element
        
        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // The composite should have 5 children
        var composite = (CompositeComponentData) components.getFirst();
        assertEquals(5, composite.children().size());
        
        // Check alternating pattern of markdown and code blocks
        assertTrue(composite.children().get(0) instanceof MarkdownComponentData);
        assertTrue(composite.children().get(1) instanceof CodeBlockComponentData);
        assertTrue(composite.children().get(2) instanceof MarkdownComponentData);
        assertTrue(composite.children().get(3) instanceof CodeBlockComponentData);
        assertTrue(composite.children().get(4) instanceof MarkdownComponentData);
        
        // Verify content of code blocks
        assertEquals("java", ((CodeBlockComponentData)composite.children().get(1)).lang());
        assertEquals("code1();", ((CodeBlockComponentData)composite.children().get(1)).body());
        assertEquals("python", ((CodeBlockComponentData)composite.children().get(3)).lang());
        assertEquals("code2();", ((CodeBlockComponentData)composite.children().get(3)).body());
    }
    
    @Test
    void testDeepNestedCustomTag() {
        var html = "<blockquote><ul><li>" +
                  "  <code-fence data-id=\"123\" data-lang=\"java\" data-content=\"deeplyNested();\"/>" +
                  "</li></ul></blockquote>";
        var doc = Jsoup.parse(html);
        var element = doc.body().child(0); // <blockquote> element
        
        var components = parser.parse(element, mdFactory, factories);
        
        // Should produce a single CompositeComponentData
        assertEquals(1, components.size());
        assertTrue(components.getFirst() instanceof CompositeComponentData);
        
        // Ensure the deeply nested tag was found
        var composite = (CompositeComponentData) components.getFirst();
        assertTrue(composite.children().stream().anyMatch(c -> c instanceof CodeBlockComponentData));
        
        // Find the code block and verify its content
        var codeBlock = composite.children().stream()
                .filter(c -> c instanceof CodeBlockComponentData)
                .map(c -> (CodeBlockComponentData)c)
                .findFirst()
                .orElse(null);
        
        assertNotNull(codeBlock);
        assertEquals("java", codeBlock.lang());
        assertEquals("deeplyNested();", codeBlock.body());
    }
}
