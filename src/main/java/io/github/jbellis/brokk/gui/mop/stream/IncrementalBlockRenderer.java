package io.github.jbellis.brokk.gui.mop.stream;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.MarkdownRenderUtil;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // The root panel that will contain all our content blocks
    private final JPanel root;
    private final boolean isDarkTheme;
    
    // Flexmark parser components
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final IdProvider idProvider;

    // Component tracking
    private final Map<Integer, BlockEntry> registry = new LinkedHashMap<>();
    private String lastHtmlFingerprint = "";

    /**
     * Creates a new incremental renderer with the given theme.
     * 
     * @param dark true for dark theme, false for light theme
     */
    public IncrementalBlockRenderer(boolean dark) {
        this.isDarkTheme = dark;
        
        // Create root panel with vertical BoxLayout
        root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setOpaque(false);
        root.setBackground(ThemeColors.getColor(dark, "chat_background"));
        
        // Initialize Flexmark with our extensions
        idProvider = new IdProvider();
        MutableDataSet options = new MutableDataSet()
            .set(Parser.EXTENSIONS, Arrays.asList(
                    TablesExtension.create(),
                    BrokkMarkdownExtension.create()
            ))
            .set(IdProvider.ID_PROVIDER, idProvider)
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
            .set(HtmlRenderer.ESCAPE_HTML, true);
            
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
        
        logger.debug("Initialized IncrementalBlockRenderer with Flexmark parser and custom extensions");
    }
    
    /**
     * Returns the root component that should be added to a container.
     * 
     * @return the root panel containing all rendered content
     */
    public JComponent getRoot() {
        return root;
    }
    
    /**
     * Updates the content with the given markdown text.
     * Parses the markdown, extracts components, and updates the UI incrementally.
     * 
     * @param markdown the markdown text to display
     */
    public void update(String markdown) {
        // Parse with Flexmark
        var document = parser.parse(markdown);
        var html = renderer.render(document);
        
        // Skip if nothing changed
        String htmlFp = html.hashCode() + "";
        if (htmlFp.equals(lastHtmlFingerprint)) {
            logger.debug("Skipping update - content unchanged");
            return;
        }
        lastHtmlFingerprint = htmlFp;
        
        // Extract component data from HTML
        List<ComponentData> components = buildComponentData(html);
        
        // Update the UI with the reconciled components
        updateUI(components);
    }
    
    /**
     * Updates the UI with the given component data, reusing existing components when possible.
     * 
     * @param components The list of component data to render
     */
    private void updateUI(List<ComponentData> components) {
        Set<Integer> seen = new HashSet<>();
        
        // Process each component
        for (ComponentData cd : components) {
            BlockEntry entry = registry.get(cd.id());
            
            if (entry == null) {
                // Create new component
                JComponent comp = createComponent(cd);
                entry = new BlockEntry(comp, cd.fp());
                registry.put(cd.id(), entry);
                root.add(comp);
                logger.debug("Created new component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
            } else {
                logger.debug("cd.fp()={} vs. entry.fp={}", cd.fp(), entry.fp);
                if (!cd.fp().equals(entry.fp)) {
                    // Update existing component
                    updateComponent(entry.comp, cd);
                    entry.fp = cd.fp();
                    logger.debug("Updated component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
                }
            }
            
            seen.add(cd.id());
        }
        
        // Remove components that are no longer present
        registry.keySet().removeIf(id -> {
            if (!seen.contains(id)) {
                logger.debug("Removing component with id {}", id);
                root.remove(registry.get(id).comp);
                return true;
            }
            return false;
        });
        
        // Ensure components are in the correct order
        ensureComponentOrder(components);
        
        // Revalidate and repaint
        root.revalidate();
        root.repaint();
    }
    
    /**
     * Creates a Swing component for the given ComponentData.
     * 
     * @param data The component data to create a Swing component for
     * @return The created Swing component
     */
    private JComponent createComponent(ComponentData data) {
        return switch (data) {
            case MarkdownComponentData md -> createMarkdownComponent(md);
            case CodeBlockComponentData code -> createCodeComponent(code);
            case EditBlockComponentData edit -> createEditBlockComponent(edit);
        };
    }
    
    /**
     * Updates an existing Swing component with new data.
     * 
     * @param component The component to update
     * @param data The new data for the component
     */
    private void updateComponent(JComponent component, ComponentData data) {
        switch (data) {
            case MarkdownComponentData md -> updateMarkdownComponent(component, md);
            case CodeBlockComponentData code -> updateCodeComponent(component, code);
            case EditBlockComponentData edit -> updateEditBlockComponent(component, edit);
        }
    }
    
    /**
     * Creates a component for displaying markdown content.
     * 
     * @param data The markdown component data
     * @return A JEditorPane configured for HTML display
     */
    private JComponent createMarkdownComponent(MarkdownComponentData data) {
        JEditorPane editor = MarkdownRenderUtil.createHtmlPane(isDarkTheme);
        updateMarkdownComponent(editor, data);
        
        // Configure for left alignment and proper sizing
        editor.setAlignmentX(Component.LEFT_ALIGNMENT);
        editor.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        
        return editor;
    }
    
    /**
     * Updates a markdown component with new content.
     * 
     * @param component The component to update
     * @param data The new markdown data
     */
    private void updateMarkdownComponent(JComponent component, MarkdownComponentData data) {
        if (component instanceof JEditorPane editor) {
            // Record current scroll position
            var viewport = SwingUtilities.getAncestorOfClass(JViewport.class, editor);
            Point viewPosition = viewport instanceof JViewport ? ((JViewport)viewport).getViewPosition() : null;
            
            // Update content
            editor.setText("<html><body>" + data.html() + "</body></html>");
            
            // Restore scroll position if possible
            if (viewport instanceof JViewport && viewPosition != null) {
                ((JViewport)viewport).setViewPosition(viewPosition);
            }
        }
    }
    
    /**
     * Creates a component for displaying code blocks.
     * 
     * @param data The code block component data
     * @return A syntax-highlighted code component
     */
    private JComponent createCodeComponent(CodeBlockComponentData data) {
        var textArea = MarkdownRenderUtil.createConfiguredCodeArea(data.lang(), data.body(), isDarkTheme);
        return MarkdownRenderUtil.createCodeBlockPanel(textArea, data.lang(), isDarkTheme);
    }
    
    /**
     * Updates a code component with new content.
     * 
     * @param component The component to update
     * @param data The new code data
     */
    private void updateCodeComponent(JComponent component, CodeBlockComponentData data) {
        // Find the RSyntaxTextArea within the panel
        var textAreas = findComponentsOfType(component, org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.class);
        if (!textAreas.isEmpty()) {
            var textArea = textAreas.getFirst();
            // Record caret position
            int caretPos = textArea.getCaretPosition();
            // Update text
            textArea.setText(data.body());
            // Set syntax style if changed
            textArea.setSyntaxEditingStyle(getSyntaxStyle(data.lang()));
            // Restore caret if in valid range
            if (caretPos >= 0 && caretPos <= textArea.getDocument().getLength()) {
                textArea.setCaretPosition(caretPos);
            }
        }
    }
    
    /**
     * Gets the appropriate syntax style constant for a language.
     * 
     * @param lang The programming language
     * @return The RSyntaxTextArea syntax style constant
     */
    private String getSyntaxStyle(String lang) {
        if (lang == null || lang.isEmpty()) {
            return org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        
        return switch(lang.toLowerCase()) {
            case "java" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_JAVA;
            case "python", "py" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_PYTHON;
            case "javascript", "js" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            case "json" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_JSON;
            case "html" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_HTML;
            case "xml" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_XML;
            case "css" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_CSS;
            case "sql" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_SQL;
            case "bash", "sh" -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            default -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_NONE;
        };
    }
    
    /**
     * Creates a component for displaying edit blocks.
     * 
     * @param data The edit block component data
     * @return A component displaying the edit block
     */
    private JComponent createEditBlockComponent(EditBlockComponentData data) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        // Create a label with the edit block information
        JLabel label = new JLabel(String.format("<html><b>Edit Block:</b> %s (adds: %d, dels: %d)</html>", 
                                              data.file(), data.adds(), data.dels()));
        label.setForeground(ThemeColors.getColor(isDarkTheme, "chat_text"));
        
        panel.add(label, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        return panel;
    }
    
    /**
     * Updates an edit block component with new data.
     * 
     * @param component The component to update
     * @param data The new edit block data
     */
    private void updateEditBlockComponent(JComponent component, EditBlockComponentData data) {
        if (component instanceof JPanel panel) {
            // Find the label within the panel
            var labels = findComponentsOfType(panel, JLabel.class);
            if (!labels.isEmpty()) {
                labels.getFirst().setText(String.format("<html><b>Edit Block:</b> %s (adds: %d, dels: %d)</html>", 
                                                   data.file(), data.adds(), data.dels()));
            }
        }
    }
    
    /**
     * Finds all components of a specific type within a container.
     * 
     * @param <T> The component type to find
     * @param container The container to search in
     * @param type The class of the component type
     * @return A list of found components of the specified type
     */
    private <T extends Component> List<T> findComponentsOfType(Container container, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component comp : container.getComponents()) {
            if (type.isInstance(comp)) {
                result.add(type.cast(comp));
            }
            if (comp instanceof Container) {
                result.addAll(findComponentsOfType((Container) comp, type));
            }
        }
        return result;
    }
    
    /**
     * Ensures all components are in the correct order according to the component data list.
     * 
     * @param components The ordered list of component data
     */
    private void ensureComponentOrder(List<ComponentData> components) {
        // Remove all components but don't dispose them
        root.removeAll();
        
        // Add them back in the correct order
        for (ComponentData data : components) {
            BlockEntry entry = registry.get(data.id());
            if (entry != null) {
                root.add(entry.comp);
            }
        }
    }
    
    /**
     * Builds a list of component data by parsing the HTML and extracting all placeholders
     * and intervening prose segments.
     * 
     * @param html The HTML string to parse
     * @return A list of ComponentData objects in document order
     */
    private List<ComponentData> buildComponentData(String html) {
        List<ComponentData> result = new ArrayList<>();
        
        Document doc = Jsoup.parse(html);
        var body = doc.body();
        
        // Track text nodes until we hit a special block
        StringBuilder currentText = new StringBuilder();
        
        // Process body as a flat sequence, handling text separately
        for (Node node : body.childNodes()) {
            if (node instanceof Element element) {
                String tagName = element.tagName();
                
                if (tagName.equals("code-fence") || tagName.equals("edit-block")) {
                    // We found a special block
                    
                    // If we have accumulated text, add it as a markdown component first
                    if (!currentText.isEmpty()) {
                        // Create a temporary Flexmark node to generate ID
                        com.vladsch.flexmark.util.ast.Node tempNode = 
                            parser.parse(currentText.toString()).getFirstChild();
                        int textId = idProvider.getId(tempNode);
                        result.add(new MarkdownComponentData(textId, currentText.toString()));
                        currentText.setLength(0);
                    }
                    
                    // Then add the special block
                    if (tagName.equals("code-fence")) {
                        int id = Integer.parseInt(element.attr("data-id"));
                        String lang = element.attr("data-lang");
                        result.add(new CodeBlockComponentData(id, element.attr("data-content"), lang));
                    } else { // edit-block
                        int id = Integer.parseInt(element.attr("data-id"));
                        int adds = Integer.parseInt(element.attr("data-adds"));
                        int dels = Integer.parseInt(element.attr("data-dels"));
                        String file = element.attr("data-file");
                        result.add(new EditBlockComponentData(id, adds, dels, file));
                    }
                    
                    continue;
                }
            }
            
            // Regular HTML, append to current text
            currentText.append(node);
        }
        
        // Don't forget any trailing text
        if (!currentText.isEmpty()) {
            // Create a temporary Flexmark node to generate ID
            com.vladsch.flexmark.util.ast.Node tempNode = 
                parser.parse(currentText.toString()).getFirstChild();
            int textId = idProvider.getId(tempNode);
            result.add(new MarkdownComponentData(textId, currentText.toString()));
        }
        
        return result;
    }
    
    /**
     * Represents a component that can be rendered in the UI.
     * Each component has a stable ID and a fingerprint for change detection.
     */
    sealed interface ComponentData {
        int id();
        String fp();
    }
    
    /**
     * Represents a Markdown prose segment between placeholders.
     */
    record MarkdownComponentData(int id, String html) implements ComponentData {
        @Override
        public String fp() {
            return html.hashCode() + "";
        }
    }
    
    /**
     * Represents a code fence block with syntax highlighting.
     */
    record CodeBlockComponentData(int id, String body, String lang) implements ComponentData {
        @Override
        public String fp() {
            return body.length() + ":" + body.hashCode();
        }
    }
    
    /**
     * Represents an edit block for file diffs.
     */
    record EditBlockComponentData(int id, int adds, int dels, String file) implements ComponentData {
        @Override
        public String fp() {
            return adds + "|" + dels;
        }
    }
    
    /**
     * Tracks a rendered component and its current fingerprint.
     */
    private static class BlockEntry {
        JComponent comp;
        String fp;
        
        BlockEntry(JComponent comp, String fp) {
            this.comp = comp;
            this.fp = fp;
        }
    }
}
