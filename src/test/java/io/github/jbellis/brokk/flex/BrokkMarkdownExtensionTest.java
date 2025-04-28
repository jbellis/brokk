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
        assertTrue(html.contains("data-id=\"0\""), "first id should be 0");
        
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
}
