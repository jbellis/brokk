
package io.github.jbellis.brokk.diffTool.ui;

import io.github.jbellis.brokk.diffTool.diff.JMChunk;
import io.github.jbellis.brokk.diffTool.diff.JMDelta;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.diffTool.doc.JMDocumentEvent;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionListener;
import java.net.MalformedURLException;
import java.net.URL;

public class FilePanel implements BufferDocumentChangeListenerIF {
    private static final int MAXSIZE_CHANGE_DIFF = 1000;

    private BufferDiffPanel diffPanel;
    private final String name;
    private JScrollPane scrollPane;
    private JTextArea editor;
    private BufferDocumentIF bufferDocument;
    private JButton saveButton;
    private Timer timer;

    public FilePanel(BufferDiffPanel diffPanel, String name, int position) {
        this.diffPanel = diffPanel;
        this.name = name;
        init();
    }

    private void init() {

        // Initialize text editor with custom highlighting
        editor = new JTextArea();
        editor.setHighlighter(new JMHighlighter());
        // Wrap editor inside a scroll pane with optimized scrolling
        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);

        // If the document is "ORIGINAL", reposition the scrollbar to the left
        if (BufferDocumentIF.ORIGINAL.equals(name)) {
            LeftScrollPaneLayout layout = new LeftScrollPaneLayout();
            scrollPane.setLayout(layout);
            layout.syncWithScrollPane(scrollPane);
        }



        // Configure save button with an icon
        saveButton = new JButton();
        saveButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        saveButton.setContentAreaFilled(false);

        // Attempt to set an online icon for the save button
        try {
            saveButton.setIcon(new ImageIcon(new URL("https://img.icons8.com/?size=60&id=59875&format=png")));
        } catch (MalformedURLException e) {
            e.printStackTrace(); // Log the error if the URL is invalid
        }

        // Assign action listener to handle save button clicks
        saveButton.addActionListener(getSaveButtonAction());

        // Setup a one-time timer to refresh the UI after 100ms
        timer = new Timer(100, refresh());
        timer.setRepeats(false);
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
        Document document;
        try {
            if (bufferDocument != null) {
                bufferDocument.removeChangeListener(this);

            }

            bufferDocument = bd;

            document = bufferDocument.getDocument();
            if (document != null) {
                editor.setDocument(document);
                editor.setTabSize(4);
                bufferDocument.addChangeListener(this);
            }

            initConfiguration();
        } catch (Exception ex) {
            ex.printStackTrace();

            JOptionPane.showMessageDialog(diffPanel, "Could not read file: "
                            + bufferDocument.getName()
                            + "\n" + ex.getMessage(),
                    "Error opening file", JOptionPane.ERROR_MESSAGE);
        }
    }


    public void reDisplay() {
        removeHighlights();
        paintRevisionHighlights();
        getHighlighter().repaint();
    }

    private void paintRevisionHighlights() {

        if (bufferDocument == null) {
            return;
        }

        JMRevision revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        for (JMDelta delta : revision.getDeltas()) {
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
    }

    abstract class AbstractHighlight {
        protected JMDelta delta;

        public AbstractHighlight(JMDelta delta) {
            this.delta = delta;
        }

        protected void highlight() {
            int fromOffset;
            int toOffset;
            JMRevision changeRev;
            JMChunk changeOriginal;
            int fromOffset2;
            int toOffset2;
            fromOffset = bufferDocument.getOffsetForLine(getPrimaryChunk().getAnchor());
            if (fromOffset < 0) {
                return;
            }

            toOffset = bufferDocument.getOffsetForLine(getPrimaryChunk().getAnchor() + getPrimaryChunk().getSize());
            if (toOffset < 0) {
                return;
            }

            boolean isEndAndIsLastNewLine = isEndAndIsLastNewLine(toOffset);

            JMHighlightPainter highlight = null;
            if (delta.isChange()) {
                if (delta.getOriginal().getSize() < MAXSIZE_CHANGE_DIFF
                        && delta.getRevised().getSize() < MAXSIZE_CHANGE_DIFF) {
                    changeRev = delta.getChangeRevision();
                    if (changeRev != null) {
                        for (JMDelta changeDelta : changeRev.getDeltas()) {
                            changeOriginal = getPrimaryChunk(changeDelta);
                            if (changeOriginal.getSize() <= 0) {
                                continue;
                            }

                            fromOffset2 = fromOffset + changeOriginal.getAnchor();
                            toOffset2 = fromOffset2 + changeOriginal.getSize();

                            setHighlight(JMHighlighter.LAYER1, fromOffset2, toOffset2,
                                    JMHighlightPainter.CHANGED_LIGHTER);
                        }
                    }
                }

                highlight = isEndAndIsLastNewLine ? JMHighlightPainter.CHANGED_NEWLINE : JMHighlightPainter.CHANGED;
            } else {
                if (isEmptyLine()) {
                    toOffset = fromOffset + 1;
                }
                if (delta.isAdd()) {
                    highlight = getAddedHighlightPainter(isOriginal(), isEndAndIsLastNewLine);
                } else if (delta.isDelete()) {
                    highlight = getDeleteHighlightPainter(!isOriginal(), isEndAndIsLastNewLine);
                }
            }

            setHighlight(fromOffset, toOffset, highlight);
        }


        private boolean isEndAndIsLastNewLine(int toOffset) {
            boolean isEndAndIsLastNewLine = false;
            try {
                PlainDocument document = bufferDocument.getDocument();
                int endOffset = toOffset - 1;
                boolean changeReachEnd = endOffset == document.getLength();
                boolean lastCharIsNewLine = "\n".equals(document.getText(endOffset, 1));
                isEndAndIsLastNewLine = changeReachEnd && lastCharIsNewLine;
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
            return isEndAndIsLastNewLine;
        }

        private JMChunk getPrimaryChunk() {
            return getPrimaryChunk(delta);
        }

        private boolean isOriginal() {
            return delta.getOriginal() == getPrimaryChunk();
        }

        private JMHighlightPainter getAddedHighlightPainter(boolean line, boolean isLastNewLine) {
            return line
                    ? JMHighlightPainter.ADDED_LINE
                    : isLastNewLine
                    ? JMHighlightPainter.ADDED_NEWLINE
                    : JMHighlightPainter.ADDED;
        }

        private JMHighlightPainter getDeleteHighlightPainter(boolean line, boolean isLastNewLine) {
            return line
                    ? JMHighlightPainter.DELETED_LINE
                    : isLastNewLine
                    ? JMHighlightPainter.DELETED_NEWLINE
                    : JMHighlightPainter.DELETED;
        }

        protected abstract JMChunk getPrimaryChunk(JMDelta changeDelta);

        public abstract boolean isEmptyLine();
    }

    class HighlightOriginal extends AbstractHighlight {

        public HighlightOriginal(JMDelta delta) {
            super(delta);
        }

        public boolean isEmptyLine() {
            return delta.isAdd();
        }

        protected JMChunk getPrimaryChunk(JMDelta changeDelta) {
            return changeDelta.getOriginal();
        }
    }

    class HighlightRevised extends AbstractHighlight {

        public HighlightRevised(JMDelta delta) {
            super(delta);
        }

        public boolean isEmptyLine() {
            return delta.isDelete();
        }

        protected JMChunk getPrimaryChunk(JMDelta changeDelta) {
            return changeDelta.getRevised();
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
            ex.printStackTrace();
        }
    }

    public ActionListener getSaveButtonAction() {
        return ae -> {
            try {
                bufferDocument.write();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SwingUtilities.getRoot(editor),
                        "Could not save file: " + bufferDocument.getName() + "\n"
                                + ex.getMessage(), "Error saving file",
                        JOptionPane.ERROR_MESSAGE);
            }
        };
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

    private void initConfiguration() {
        Font font = new Font("Arial", Font.PLAIN, 14);
        editor.setBorder(new LineNumberBorder(this));
        FontMetrics fm = editor.getFontMetrics(font);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(fm.getHeight());
        editor.setEditable(true);
    }


    public static class LeftScrollPaneLayout
            extends ScrollPaneLayout
    {
        public void layoutContainer(Container parent)
        {
            ComponentOrientation originalOrientation;

            // Dirty trick to get the vertical scrollbar to the left side of
            //  a scroll-pane.
            originalOrientation = parent.getComponentOrientation();
            parent.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            super.layoutContainer(parent);
            parent.setComponentOrientation(originalOrientation);
        }
    }
}
