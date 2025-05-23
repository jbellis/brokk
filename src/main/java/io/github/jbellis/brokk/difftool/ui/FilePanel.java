
package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.difftool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.difftool.search.SearchBarDialog;
import io.github.jbellis.brokk.difftool.search.SearchCommand;
import io.github.jbellis.brokk.difftool.search.SearchHit;
import io.github.jbellis.brokk.difftool.search.SearchHits;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

public class FilePanel implements BufferDocumentChangeListenerIF {
    private static final int MAXSIZE_CHANGE_DIFF = 1000;

    private final BufferDiffPanel diffPanel;
    private final String name;
    private JPanel visualComponentContainer; // Main container for editor or "new file" label
    private JScrollPane scrollPane;
    private JTextArea editor;
    private BufferDocumentIF bufferDocument;
    private Timer timer;
    private boolean selected;
    private SearchHits searchHits;
    private final SearchBarDialog bar;

    public FilePanel(BufferDiffPanel diffPanel, String name, SearchBarDialog bar) {
        this.diffPanel = diffPanel;
        this.name = name;
        this.bar = bar;
        init();
    }

    private void init() {
        visualComponentContainer = new JPanel(new BorderLayout());

        // Initialize text editor with custom highlighting
        editor = new JTextArea();
        editor.setHighlighter(new JMHighlighter());
        editor.addFocusListener(getFocusListener());
        bar.setFilePanel(this);
        // Undo listener will be added in setBufferDocument when editor is active

        // Wrap editor inside a scroll pane with optimized scrolling
        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);

        // If the document is "ORIGINAL", reposition the scrollbar to the left
        if (BufferDocumentIF.ORIGINAL.equals(name)) {
            LeftScrollPaneLayout layout = new LeftScrollPaneLayout();
            scrollPane.setLayout(layout);
            layout.syncWithScrollPane(scrollPane);
        }

        // Initially, add scrollPane to the visual container
        visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

        // Setup a one-time timer to refresh the UI after 100ms
        timer = new Timer(100, refresh());
        timer.setRepeats(false);

//        diffPanel.getCaseSensitiveCheckBox().addActionListener(e -> {
//            doSearch()
//        });
    }

    public JComponent getVisualComponent() {
        return visualComponentContainer;
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public JTextArea getEditor() {
        return editor;
    }

    public BufferDocumentIF getBufferDocument() {
        return bufferDocument;
    }


    public void setBufferDocument(BufferDocumentIF bd) {
        Document previousDocument;
        Document newDocument;

        try {
            if (this.bufferDocument != null) {
                this.bufferDocument.removeChangeListener(this);
                previousDocument = this.bufferDocument.getDocument();
                if (previousDocument != null) {
                    previousDocument.removeUndoableEditListener(diffPanel.getUndoHandler());
                }
            }

            this.bufferDocument = bd;
            visualComponentContainer.removeAll(); // Clear previous content

            // Always add the scrollPane (which contains the editor)
            visualComponentContainer.add(scrollPane, BorderLayout.CENTER);

            if (bd != null) {
                newDocument = bd.getDocument();
                if (newDocument != null) {
                    editor.setDocument(newDocument);
                    editor.setTabSize(4); // TODO: Make configurable
                    bd.addChangeListener(this);
                    newDocument.addUndoableEditListener(diffPanel.getUndoHandler());
                }
                editor.setEditable(!bd.isReadonly());
            } else {
                // If BufferDocumentIF is null, clear the editor and make it non-editable
                editor.setDocument(new JTextArea().getDocument()); // Set a new empty document
                editor.setText("");
                editor.setEditable(false);
            }

            visualComponentContainer.revalidate();
            visualComponentContainer.repaint();

            // Initialize configuration - this sets border etc.
            initConfiguration();

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(diffPanel, "Could not read file or set document: "
                                                  + (bd != null ? bd.getName() : "Unknown")
                                                  + "\n" + ex.getMessage(),
                                          "Error processing file", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void reDisplay() {
        removeHighlights();
        paintSearchHighlights();
        paintRevisionHighlights();
        getHighlighter().repaint();
    }

    /**
     * Repaint highlights: we get the patch from BufferDiffPanel, then highlight
     * each delta's relevant lines in *this* panel (ORIGINAL or REVISED).
     */
    private void paintRevisionHighlights()
    {
        var doc = bufferDocument;
        if (doc == null) return;

        // Access the shared patch from the parent BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null) return;

        for (var delta : patch.getDeltas()) {
            // Are we the "original" side or the "revised" side?
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
    }

    abstract class AbstractHighlight {
        protected final AbstractDelta<String> delta;

        AbstractHighlight(AbstractDelta<String> delta) {
            this.delta = delta;
        }

        public void highlight() {
            // Retrieve the chunk relevant to this side
            var chunk = getChunk(delta);
            var fromOffset = bufferDocument.getOffsetForLine(chunk.getPosition());
            if (fromOffset < 0) return;
            var toOffset = bufferDocument.getOffsetForLine(chunk.getPosition() + chunk.size());
            if (toOffset < 0) return;

            // Check if chunk is effectively "empty line" in the old code
            boolean isEmpty = (chunk.size() == 0);

            // End offset might be the doc length; check trailing newline logic:
            boolean isEndAndNewline = isEndAndLastNewline(toOffset);

            // Decide color. For Insert vs Delete vs Change we do:
            var isDark = diffPanel.isDarkTheme();
            var type = delta.getType(); // DeltaType.INSERT, DELETE, CHANGE
            var painter = switch (type) {
                case INSERT ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getAdded(isDark))
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getAdded(isDark))
                                : new JMHighlightPainter(Colors.getAdded(isDark));

                case DELETE ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getDeleted(isDark))
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getDeleted(isDark))
                                : new JMHighlightPainter(Colors.getDeleted(isDark));

                case CHANGE ->
                        isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getChanged(isDark))
                                : new JMHighlightPainter(Colors.getChanged(isDark));
                case EQUAL -> throw new IllegalStateException();
            };
            setHighlight(fromOffset, toOffset, painter);
        }

        // Check if the last char is a newline *and* if offset is doc length
        private boolean isEndAndLastNewline(int toOffset) {
            try {
                var docLen = bufferDocument.getDocument().getLength();
                int endOffset = toOffset - 1;
                if (endOffset < 0 || endOffset >= docLen) {
                    return false;
                }
                // If the final character is a newline & chunk touches doc-end
                boolean lastCharIsNL = "\n".equals(bufferDocument.getDocument().getText(endOffset, 1));
                return (endOffset == docLen - 1) && lastCharIsNL;
            } catch (BadLocationException e) {
                // This exception indicates an issue with offsets, likely a bug
                throw new RuntimeException("Bad location accessing document text", e);
            }
        }

        protected abstract Chunk<String> getChunk(AbstractDelta<String> d);
    }

    class HighlightOriginal extends AbstractHighlight {
        HighlightOriginal(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getSource(); // For the original side
        }
    }

    class HighlightRevised extends AbstractHighlight {
        HighlightRevised(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getTarget(); // For the revised side
        }
    }


    private JMHighlighter getHighlighter() {
        return (JMHighlighter) editor.getHighlighter();
    }

    private void removeHighlights() {
        JMHighlighter jmhl = getHighlighter();
        jmhl.removeHighlights(JMHighlighter.LAYER0);
        jmhl.removeHighlights(JMHighlighter.LAYER1);
        jmhl.removeHighlights(JMHighlighter.LAYER2);
    }

    private void setHighlight(int offset, int size,
                              Highlighter.HighlightPainter highlight) {

        setHighlight(JMHighlighter.LAYER0, offset, size, highlight);
    }

    private void setHighlight(Integer layer, int offset, int size,
                              Highlighter.HighlightPainter highlight) {
        try {
            getHighlighter().addHighlight(layer, offset, size, highlight);
        } catch (BadLocationException ex) {
            // This usually indicates a logic error in calculating offsets/sizes
            throw new RuntimeException("Error adding highlight at offset " + offset + " size " + size, ex);
        }
    }

    boolean isDocumentChanged() {
        return bufferDocument != null && bufferDocument.isChanged();
    }

    public void documentChanged(JMDocumentEvent de) {
        if (de.getStartLine() == -1 && de.getDocumentEvent() == null) {
            // Refresh the diff of whole document.
            timer.restart();
        } else {
//             Try to update the revision instead of doing a full diff.
            if (!diffPanel.revisionChanged(de)) {
                timer.restart();
            }
        }
    }


    private ActionListener refresh() {
        return ae -> diffPanel.diff();
    }

    public FocusListener getFocusListener() {
        return new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent fe) {
                diffPanel.setSelectedPanel(FilePanel.this);
            }
        };
    }


    private void initConfiguration() {
        Font font = new Font("Arial", Font.PLAIN, 14);
        editor.setBorder(new LineNumberBorder(this));
        FontMetrics fm = editor.getFontMetrics(font);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(fm.getHeight());
        editor.setEditable(true);
    }


    public static class LeftScrollPaneLayout
            extends ScrollPaneLayout {
        public void layoutContainer(Container parent) {
            ComponentOrientation originalOrientation;

            // Dirty trick to get the vertical scrollbar to the left side of
            //  a scroll-pane.
            originalOrientation = parent.getComponentOrientation();
            parent.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            super.layoutContainer(parent);
            parent.setComponentOrientation(originalOrientation);
        }
    }

    public void doStopSearch() {
        searchHits = null;
        reDisplay();
    }

    SearchCommand getSearchCommand() {
        return bar.getCommand();
    }

    public SearchHits doSearch() {
        int numberOfLines;
        BufferDocumentIF doc;
        String text;
        int index, fromIndex;
        boolean caseSensitive;
        String searchText, searchTextToCompare, textToSearch;
        SearchHit searchHit;
        int offset;
        SearchCommand searchCommand;

        searchCommand = getSearchCommand();
        if (searchCommand == null) {
            return null;
        }

        searchText = searchCommand.searchText();
        caseSensitive = searchCommand.isCaseSensitive(); // Get case-sensitive flag

        doc = getBufferDocument();
        if (doc == null) { // Should not happen if isDisplayingEditor is true and doc set
            searchHits = new SearchHits();
            return searchHits;
        }
        numberOfLines = doc.getNumberOfLines();

        searchHits = new SearchHits();

        if (!searchText.isEmpty()) {
            for (int line = 0; line < numberOfLines; line++) {
                text = doc.getLineText(line);

                // Adjust case based on case-sensitive flag
                if (!caseSensitive) {
                    textToSearch = text.toLowerCase();
                    searchTextToCompare = searchText.toLowerCase();
                } else {
                    textToSearch = text;
                    searchTextToCompare = searchText;
                }

                fromIndex = 0;
                while ((index = textToSearch.indexOf(searchTextToCompare, fromIndex)) != -1) {
                    offset = bufferDocument.getOffsetForLine(line);
                    if (offset < 0) {
                        continue;
                    }

                    searchHit = new SearchHit(line, offset + index, searchText.length());
                    searchHits.add(searchHit);

                    fromIndex = index + searchHit.getSize();
                }
            }
        }

        reDisplay(); // This will also check isDisplayingEditor
        scrollToSearch(this, searchHits);
        return getSearchHits();
    }


    SearchHits getSearchHits() {
        return searchHits;
        }

        private void paintSearchHighlights() {
            if (searchHits == null) {
                return;
            }
            for (SearchHit sh : searchHits.getSearchHits()) {
            setHighlight(JMHighlighter.LAYER2, sh.getFromOffset(),
                         sh.getToOffset(),
                         searchHits.isCurrent(sh)
                                 ? JMHighlightPainter.CURRENT_SEARCH : JMHighlightPainter.SEARCH);
        }
    }


    public void doPreviousSearch() {
        SearchHits sh = getSearchHits();
        if (sh == null) {
            return;
        }
        sh.previous();
        reDisplay();
        scrollToSearch(this, sh);
    }

    private void scrollToSearch(FilePanel fp, SearchHits searchHitsToScroll) {
        if (searchHitsToScroll == null) {
            return;
        }

        SearchHit currentHit = searchHitsToScroll.getCurrent();
        if (currentHit != null) {
            int line = currentHit.getLine();
            diffPanel.getScrollSynchronizer().scrollToLine(fp, line);
            diffPanel.setSelectedLine(line);
        }
    }

    public void doNextSearch() {
        SearchHits sh = getSearchHits();
        if (sh == null) {
            return;
        }
        sh.next();
        reDisplay();
        scrollToSearch(this, sh);
    }
}
