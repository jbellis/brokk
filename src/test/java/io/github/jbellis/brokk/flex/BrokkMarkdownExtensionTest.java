package io.github.jbellis.brokk.flex;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the BrokkMarkdownExtension which converts SEARCH/REPLACE blocks to edit-block elements.
 */
class BrokkMarkdownExtensionTest {
    private Parser parser;
    private HtmlRenderer renderer;
    private IdProvider idProvider;

    @BeforeEach
    void setUp() {
        idProvider = new IdProvider();  // Reset counter for each test
        MutableDataSet options = new MutableDataSet()
                .set(Parser.EXTENSIONS, List.of(BrokkMarkdownExtension.create()))
                .set(IdProvider.ID_PROVIDER, idProvider)
                // Keep html straightforward for string-comparison
                .set(HtmlRenderer.SOFT_BREAK, "\n")
                .set(HtmlRenderer.ESCAPE_HTML, true);

        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
    }

    @Test
    void regularCodeFenceGetsRenderedAsCodeFenceElement() {
        var md = """
                ```java
                public class Test {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                """;

        String html = renderer.render(parser.parse(md));

        // 1) We get exactly one code-fence element
        assertTrue(html.contains("<code-fence"), "expected a <code-fence> placeholder");
        System.out.println(html);
        assertEquals(1, html.split("<code-fence").length - 1,
                     "should create exactly one <code-fence>");

        // 2) Data attributes are present and escaped properly
        assertTrue(html.contains("data-lang=\"java\""), "language attribute missing");
        assertTrue(html.contains("data-id=\""), "id attribute missing");
        assertTrue(html.contains("data-content=\""), "content attribute missing");

        // 3) Code content is properly included and escaped
        assertTrue(html.contains("System.out.println"), "code content should be included");
        assertTrue(html.contains("&quot;Hello&quot;"), "string quotes should be escaped");

        // 4) The original markdown fence markers should not appear in output
        assertFalse(html.contains("```java"), "opening fence marker should not appear in output");
        assertFalse(html.contains("```\n"), "closing fence marker should not appear in output");
    }

    @Test
    void fencedBlockGetsRenderedAsEditBlock() {
        var md = """
                ```
                foo.txt
                <<<<<<< SEARCH
                a
                =======
                b
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));

        // 1) We get exactly one edit-block element

        assertTrue(html.contains("<edit-block"), "expected an <edit-block> placeholder");
        System.out.println(html);
        assertEquals(1, html.split("<edit-block").length - 1,
                     "should create exactly one <edit-block>");

        // 2) Data attributes are present and escaped properly
        assertTrue(html.contains("data-file=\"foo.txt\""), "filename attribute missing");
        assertTrue(html.contains("data-adds=\"1\""), "adds attribute incorrect");
        assertTrue(html.contains("data-dels=\"1\""), "dels attribute incorrect");

        // 3) Raw conflict markers must NOT appear in the rendered html
        assertFalse(html.contains("<<<<<<<"), "raw conflict marker leaked into html");
        assertFalse(html.contains("======="), "raw conflict marker leaked into html");
        assertFalse(html.contains(">>>>>>>"), "raw conflict marker leaked into html");
    }

    @Test
    void unfencedBlockIsRecognisedEarly() {
        var md = """
                <<<<<<< SEARCH example.txt
                lineA
                =======
                lineB
                >>>>>>> REPLACE
                """;

        String html = renderer.render(parser.parse(md));

        // Should still recognise the block and render a placeholder
        assertTrue(html.contains("<edit-block"), 
                  "unfenced block should still become an <edit-block>");
        assertTrue(html.contains("data-file=\"example.txt\""),
                  "filename extracted from SEARCH line");
    }

    @Test
    void multipleBlocksReceiveDistinctIds() {
        var md = """
                ```
                file1.txt
                <<<<<<< SEARCH
                one
                =======
                two
                >>>>>>> REPLACE
                ```

                ```
                file2.txt
                <<<<<<< SEARCH
                three
                =======
                four
                >>>>>>> REPLACE
                ```
                """;

        String html = renderer.render(parser.parse(md));

        System.out.println(html);
        // Two edit-blocks with different ids
        assertEquals(2, html.split("<edit-block").length - 1, "expected two blocks");
        
        // Check that we have one data-id="0" (for the first block)
        assertTrue(html.contains("data-id=\"1187388123\""), "first id should be 0");
        
        // Get the second ID
        int firstIdPos = html.indexOf("data-id=\"");
        int secondIdPos = html.indexOf("data-id=\"", firstIdPos + 1);
        
        // Make sure there is a second ID
        assertTrue(secondIdPos > 0, "should find a second ID");
        
        // Extract the second ID
        int idStart = secondIdPos + 9; // length of 'data-id="'
        int idEnd = html.indexOf("\"", idStart);
        String secondId = html.substring(idStart, idEnd);
        
        // Make sure second ID is not 0
        assertNotEquals("0", secondId, "second id should be different from first");
    }

    @Test
    void mixedCodeFenceAndEditBlocksAreRenderedCorrectly() {
        var md = """
                Here's a code example:
                
                ```java
                public class Example {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                ```
                
                And here's an edit block:
                
                ```
                example.txt
                <<<<<<< SEARCH
                old content
                =======
                new content
                >>>>>>> REPLACE
                ```
                
                Another code block:
                
                ```python
                def hello():
                    print("Hello, Python!")
                ```
                """;

        String html = renderer.render(parser.parse(md));
        System.out.println(html);

        // 1) We get exactly two code-fence elements and one edit-block
        assertEquals(2, html.split("<code-fence").length - 1,
                     "should create exactly two <code-fence> elements");
        assertEquals(1, html.split("<edit-block").length - 1,
                     "should create exactly one <edit-block> element");

        // 2) Code fence attributes are present
        assertTrue(html.contains("data-lang=\"java\""), "Java language attribute missing");
        assertTrue(html.contains("data-lang=\"python\""), "Python language attribute missing");

        // 3) Edit block attributes are present
        assertTrue(html.contains("data-file=\"example.txt\""), "filename attribute missing");
        assertTrue(html.contains("data-adds=\"1\""), "adds attribute incorrect");
        assertTrue(html.contains("data-dels=\"1\""), "dels attribute incorrect");

        // 4) Content is properly included and escaped
        assertTrue(html.contains("System.out.println"), "Java code content missing");
        assertTrue(html.contains("print(&quot;Hello, Python!&quot;)"), "Python code content missing");

        // 5) Raw markers must not appear in the rendered html
        assertFalse(html.contains("<<<<<<<"), "raw conflict marker leaked into html");
        assertFalse(html.contains("======="), "raw conflict marker leaked into html");
        assertFalse(html.contains(">>>>>>>"), "raw conflict marker leaked into html");
        assertFalse(html.contains("```java"), "opening fence marker should not appear in output");
        assertFalse(html.contains("```python"), "opening fence marker should not appear in output");
    }
}
