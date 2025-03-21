
package io.github.jbellis.brokk.diffTool.ui;

import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import io.github.jbellis.brokk.diffTool.diff.JMChunk;
import io.github.jbellis.brokk.diffTool.diff.JMDelta;
import io.github.jbellis.brokk.diffTool.diff.JMDiff;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.AbstractBufferDocument;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.diffTool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.diffTool.node.BufferNode;
import io.github.jbellis.brokk.diffTool.node.JMDiffNode;
import io.github.jbellis.brokk.diffTool.scroll.DiffScrollComponent;
import io.github.jbellis.brokk.diffTool.scroll.ScrollSynchronizer;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BufferDiffPanel extends JPanel {
    public static final int LEFT = 0;
    public static final int RIGHT = 2;
    public static final int NUMBER_OF_PANELS = 3;
    private final BrokkDiffPanel mainPanel;
    private FilePanel[] filePanels;
    private JMDiffNode diffNode;
    private JMRevision currentRevision;
    private JMDelta selectedDelta;
    private int selectedLine;
    private ScrollSynchronizer scrollSynchronizer;
    private JMDiff diff;
    private JSplitPane splitPane;

    static Color selectionColor = Color.BLUE;
    static Color newColor = Color.CYAN;
    static Color mixColor = Color.WHITE;

    static {
        selectionColor = new Color(selectionColor.getRed() * newColor.getRed() / mixColor.getRed()
                , selectionColor.getGreen() * newColor.getGreen() / mixColor.getGreen()
                , selectionColor.getBlue() * newColor.getBlue() / mixColor.getBlue());
    }

    public BufferDiffPanel(BrokkDiffPanel mainPanel) {
        this.mainPanel = mainPanel;
        diff = new JMDiff();

        init();

        setFocusable(true);
    }

    public void setDiffNode(JMDiffNode diffNode) {
        this.diffNode = diffNode;
        refreshDiffNode();
    }

    public JMDiffNode getDiffNode() {
        return diffNode;
    }

    private void refreshDiffNode() {
        BufferNode bnLeft = getDiffNode().getBufferNodeLeft();
        BufferNode bnRight = getDiffNode().getBufferNodeRight();

        BufferDocumentIF leftDocument = bnLeft == null ? null : bnLeft.getDocument();
        BufferDocumentIF rightDocument = bnRight == null ? null : bnRight.getDocument();

        setBufferDocuments(leftDocument, rightDocument, getDiffNode().getDiff(), getDiffNode().getRevision());
    }

    private void setBufferDocuments(BufferDocumentIF bd1, BufferDocumentIF bd2,
                                    JMDiff diff, JMRevision revision) {
        this.diff = diff;
        currentRevision = revision;

        if (bd1 != null) {
            filePanels[LEFT].setBufferDocument(bd1);
        }

        if (bd2 != null) {
            filePanels[RIGHT].setBufferDocument(bd2);
        }

        if (bd1 != null && bd2 != null) {
            reDisplay();
        }
    }

    private void reDisplay() {
        for (FilePanel fp : filePanels) {
            if (fp != null) {
                fp.reDisplay();
            }
        }
        mainPanel.repaint();
    }

    public String getTitle() {
        String title;
        List<String> titles = new ArrayList<>();
        for (FilePanel filePanel : filePanels) {
            if (filePanel == null) {
                continue;
            }

            BufferDocumentIF bd = filePanel.getBufferDocument();
            if (bd == null) {
                continue;
            }

            title = bd.getShortName();

            titles.add(title);
        }

        if (titles.size() == 1) {
            title = titles.getFirst();
        } else {
            if (titles.get(0).equals(titles.get(1))) {
                title = titles.getFirst();
            } else {
                title = titles.get(0) + "-" + titles.get(1);
            }
        }

        return title;
    }

    public boolean revisionChanged(JMDocumentEvent de) {
        FilePanel fp;
        BufferDocumentIF bd1;
        BufferDocumentIF bd2;

        if (currentRevision == null) {
            diff();
        } else {
            fp = getFilePanel(de.getDocument());
            if (fp == null) {
                return false;
            }

            bd1 = filePanels[LEFT].getBufferDocument();
            bd2 = filePanels[RIGHT].getBufferDocument();

            if (!currentRevision.update(bd1 != null ? bd1.getLines() : null,
                    bd2 != null ? bd2.getLines() : null, fp == filePanels[LEFT], de
                            .getStartLine(), de.getNumberOfLines())) {
                return false;
            }

            reDisplay();
        }

        return true;
    }

    private FilePanel getFilePanel(AbstractBufferDocument document) {
        for (FilePanel fp : filePanels) {
            if (fp == null) {
                continue;
            }

            if (fp.getBufferDocument() == document) {
                return fp;
            }
        }

        return null;
    }

    public void diff() {
        BufferDocumentIF bd1;
        BufferDocumentIF bd2;

        bd1 = filePanels[LEFT].getBufferDocument();
        bd2 = filePanels[RIGHT].getBufferDocument();

        if (bd1 != null && bd2 != null) {
            try {
                currentRevision = diff.diff(bd1.getLines(), bd2.getLines()
                        , getDiffNode().getIgnore());

                reDisplay();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void init() {
        String columns = "3px, pref, 3px, 0:grow, 5px, min, 60px, 0:grow, 25px, min, 3px, pref, 3px";
        String rows = "6px, pref, 3px, fill:0:grow, pref";

        setLayout(new BorderLayout());

        if (splitPane != null) {
            remove(splitPane);
        }
        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, buildFilePanel(columns, rows), null);
        add(splitPane);

        scrollSynchronizer = new ScrollSynchronizer(this, filePanels[LEFT], filePanels[RIGHT]);
    }

    private JPanel buildFilePanel(String columns, String rows) {
        FormLayout layout;
        CellConstraints cc;
        JPanel filePanel = new JPanel();
        layout = new FormLayout(columns, rows);
        cc = new CellConstraints();

        filePanel.setLayout(layout);

        filePanels = new FilePanel[NUMBER_OF_PANELS];

        filePanels[LEFT] = new FilePanel(this, BufferDocumentIF.ORIGINAL, LEFT);
        filePanels[RIGHT] = new FilePanel(this, BufferDocumentIF.REVISED, RIGHT);

        filePanel.add(filePanels[LEFT].getScrollPane(), cc.xyw(4, 4, 3));

        DiffScrollComponent diffScrollComponent = new DiffScrollComponent(this, LEFT, RIGHT);
        filePanel.add(diffScrollComponent, cc.xy(7, 4));

        filePanel.add(filePanels[RIGHT].getScrollPane(), cc.xyw(8, 4, 3));
        return filePanel;
    }

    public JMRevision getCurrentRevision() {
        return currentRevision;
    }


    public void runChange(int fromPanelIndex, int toPanelIndex, boolean shift) {
        JMDelta delta;
        BufferDocumentIF fromBufferDocument;
        BufferDocumentIF toBufferDocument;
        PlainDocument from;
        String s;
        int fromLine;
        int fromOffset;
        int toOffset;
        int size;
        JMChunk fromChunk;
        JMChunk toChunk;
        JTextComponent toEditor;

        delta = getSelectedDelta();
        if (delta == null) {
            return;
        }

        if (fromPanelIndex < 0 || fromPanelIndex >= filePanels.length) {
            return;
        }

        if (toPanelIndex < 0 || toPanelIndex >= filePanels.length) {
            return;
        }

        try {
            fromBufferDocument = filePanels[fromPanelIndex].getBufferDocument();
            toBufferDocument = filePanels[toPanelIndex].getBufferDocument();

            if (fromPanelIndex < toPanelIndex) {
                fromChunk = delta.getOriginal();
                toChunk = delta.getRevised();
            } else {
                fromChunk = delta.getRevised();
                toChunk = delta.getOriginal();
            }
            toEditor = filePanels[toPanelIndex].getEditor();

            if (fromBufferDocument == null || toBufferDocument == null) {
                return;
            }

            fromLine = fromChunk.getAnchor();
            size = fromChunk.getSize();
            fromOffset = fromBufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = fromBufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }

            from = fromBufferDocument.getDocument();
            s = from.getText(fromOffset, toOffset - fromOffset);

            fromLine = toChunk.getAnchor();
            size = toChunk.getSize();
            fromOffset = toBufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = toBufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }
            toEditor.setSelectionStart(fromOffset);
            toEditor.setSelectionEnd(toOffset);
            if (!shift) {
                toEditor.replaceSelection(s);
            } else {
                toEditor.getDocument().insertString(toOffset, s, null);
            }

            setSelectedDelta(null);
            setSelectedLine(delta.getOriginal().getAnchor());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void runDelete(int fromPanelIndex, int toPanelIndex) {
        JMDelta delta;
        BufferDocumentIF bufferDocument;

        int fromLine;
        int fromOffset;
        int toOffset;
        int size;
        JMChunk chunk;
        JTextComponent toEditor;

        try {
            delta = getSelectedDelta();
            if (delta == null) {
                return;
            }

            // Some sanity checks.
            if (fromPanelIndex < 0 || fromPanelIndex >= filePanels.length) {
                return;
            }

            if (toPanelIndex < 0 || toPanelIndex >= filePanels.length) {
                return;
            }

            bufferDocument = filePanels[fromPanelIndex].getBufferDocument();
            if (fromPanelIndex < toPanelIndex) {
                chunk = delta.getOriginal();
            } else {
                chunk = delta.getRevised();
            }
            toEditor = filePanels[fromPanelIndex].getEditor();

            if (bufferDocument == null) {
                return;
            }


            fromLine = chunk.getAnchor();
            size = chunk.getSize();
            fromOffset = bufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = bufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }

            toEditor.setSelectionStart(fromOffset);
            toEditor.setSelectionEnd(toOffset);
            toEditor.replaceSelection("");

            setSelectedDelta(null);
            setSelectedLine(delta.getOriginal().getAnchor());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void setSelectedDelta(JMDelta delta) {
        selectedDelta = delta;
        setSelectedLine(delta == null ? 0 : delta.getOriginal().getAnchor());
    }

    private void setSelectedLine(int line) {
        selectedLine = line;
    }


    public JMDelta getSelectedDelta() {
        List<JMDelta> deltas;

        if (currentRevision == null) {
            return null;
        }

        deltas = currentRevision.getDeltas();
        if (deltas.isEmpty()) {
            return null;
        }

        return selectedDelta;
    }

    public FilePanel getFilePanel(int index) {
        if (index < 0 || index > filePanels.length) {
            return null;
        }

        return filePanels[index];
    }
}
