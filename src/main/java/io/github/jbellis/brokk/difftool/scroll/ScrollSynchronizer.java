package io.github.jbellis.brokk.difftool.scroll;

import io.github.jbellis.brokk.difftool.diff.DiffUtil;
import io.github.jbellis.brokk.difftool.diff.JMChunk;
import io.github.jbellis.brokk.difftool.diff.JMDelta;
import io.github.jbellis.brokk.difftool.diff.JMRevision;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.util.List;

public class ScrollSynchronizer {
    private BufferDiffPanel diffPanel;
    private FilePanel filePanelLeft;
    private FilePanel filePanelRight;
    private AdjustmentListener horizontalAdjustmentListener;
    private AdjustmentListener verticalAdjustmentListener;

    public ScrollSynchronizer(BufferDiffPanel diffPanel, FilePanel filePanelLeft,
                              FilePanel filePanelRight) {
        this.diffPanel = diffPanel;
        this.filePanelLeft = filePanelLeft;
        this.filePanelRight = filePanelRight;

        init();
    }

    private void init() {
        JScrollBar o;
        JScrollBar r;

        // Synchronize the horizontal scrollbars:
        o = filePanelLeft.getScrollPane().getHorizontalScrollBar();
        r = filePanelRight.getScrollPane().getHorizontalScrollBar();
        r.addAdjustmentListener(getHorizontalAdjustmentListener());
        o.addAdjustmentListener(getHorizontalAdjustmentListener());

        // Synchronize the vertical scrollbars:
        o = filePanelLeft.getScrollPane().getVerticalScrollBar();
        r = filePanelRight.getScrollPane().getVerticalScrollBar();
        r.addAdjustmentListener(getVerticalAdjustmentListener());
        o.addAdjustmentListener(getVerticalAdjustmentListener());
    }

    private void scroll(boolean leftScrolled) {
        JMRevision revision;
        FilePanel fp1;
        FilePanel fp2;
        int line;

        revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        if (leftScrolled) {
            fp1 = filePanelLeft;
            fp2 = filePanelRight;
        } else {
            fp1 = filePanelRight;
            fp2 = filePanelLeft;
        }

        line = getCurrentLineCenter(fp1);

        if (leftScrolled) {
            line = DiffUtil.getRevisedLine(revision, line);
        } else {
            line = DiffUtil.getOriginalLine(revision, line);
        }

        scrollToLine(fp2, line);
    }

    void toNextDelta(boolean next) {
        int line;
        JMRevision revision;
        JMDelta previousDelta;
        JMDelta currentDelta;
        JMDelta nextDelta;
        JMDelta toDelta;
        JMChunk original;
        int currentIndex;
        int nextIndex;
        List<JMDelta> deltas;
        int i;

        revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        deltas = revision.getDeltas();

        line = getCurrentLineCenter(filePanelLeft);

        currentDelta = null;
        currentIndex = -1;

        i = 0;
        for (JMDelta delta : deltas) {
            original = delta.getOriginal();

            currentIndex = i;
            i++;

            if (line >= original.getAnchor()) {
                if (line <= original.getAnchor() + original.getSize()) {
                    currentDelta = delta;
                    break;
                }
            } else {
                break;
            }
        }

        previousDelta = null;
        nextDelta = null;
        if (currentIndex != -1) {
            if (currentIndex > 0) {
                previousDelta = deltas.get(currentIndex - 1);
            }

            nextIndex = currentIndex;
            if (currentDelta != null) {
                nextIndex++;
            }

            if (nextIndex < deltas.size()) {
                nextDelta = deltas.get(nextIndex);
            }
        }

        if (next) {
            toDelta = nextDelta;
        } else {
            toDelta = previousDelta;
        }

        if (toDelta != null) {
            scrollToLine(filePanelLeft, toDelta.getOriginal().getAnchor());
            scroll(true);
        }
    }

    public void showDelta(JMDelta delta) {
        scrollToLine(filePanelLeft, delta.getOriginal().getAnchor());
        scroll(false);
    }

    private int getCurrentLineCenter(FilePanel fp) {
        JScrollPane scrollPane;
        BufferDocumentIF bd;
        JTextComponent editor;
        JViewport viewport;
        int line;
        Rectangle rect;
        int offset;
        Point p;

        editor = fp.getEditor();
        scrollPane = fp.getScrollPane();
        viewport = scrollPane.getViewport();
        p = viewport.getViewPosition();
        offset = editor.viewToModel(p);

        // Scroll around the center of the editpane
        p.y += getHeightOffset(fp);

        offset = editor.viewToModel(p);
        bd = fp.getBufferDocument();
        if (bd == null) {
            return -1;
        }
        line = bd.getLineForOffset(offset);

        return line;
    }

    public void scrollToLine(FilePanel fp, int line) {
        JScrollPane scrollPane;
        FilePanel fp2;
        BufferDocumentIF bd;
        JTextComponent editor;
        JViewport viewport;
        Rectangle rect;
        int offset;
        Point p;
        Rectangle viewRect;
        Dimension viewSize;
        Dimension extentSize;
        int x;

        fp2 = fp == filePanelLeft ? filePanelRight : filePanelLeft;

        bd = fp.getBufferDocument();
        if (bd == null) {
            return;
        }

        offset = bd.getOffsetForLine(line);
        if (offset < 0) {
            return;
        }

        viewport = fp.getScrollPane().getViewport();
        editor = fp.getEditor();

        try {
            rect = editor.modelToView(offset);
            if (rect == null) {
                return;
            }

            p = rect.getLocation();
            p.y -= getHeightOffset(fp);
            p.y += getCorrectionOffset(fp2);

            // Do not allow scrolling before the begin.
            if (p.y < 0) {
                p.y = 0;
            }

            // Do not allow scrolling after the end.
            viewSize = viewport.getViewSize();
            viewRect = viewport.getViewRect();
            extentSize = viewport.getExtentSize();
            if (p.y > viewSize.height - extentSize.height) {
                p.y = viewSize.height - extentSize.height;
            }

            p.x = viewRect.x;

            viewport.setViewPosition(p);


        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private int getHeightOffset(FilePanel fp) {
        JScrollPane scrollPane;
        JViewport viewport;
        int offset;
        int unitIncrement;

        scrollPane = fp.getScrollPane();
        viewport = scrollPane.getViewport();

        offset = viewport.getSize().height / 2;
        unitIncrement = scrollPane.getHorizontalScrollBar().getUnitIncrement();
        offset = offset - (offset % unitIncrement);

        return offset;
    }

    private int getCorrectionOffset(FilePanel fp) {
        JTextComponent editor;
        int offset;
        Rectangle rect;
        Point p;
        JViewport viewport;

        editor = fp.getEditor();
        viewport = fp.getScrollPane().getViewport();
        p = viewport.getViewPosition();
        offset = editor.viewToModel(p);

        try {
            // This happens when you scroll to the bottom. The upper line won't
            //   start at the right position (You can see half of the line)
            // Correct this offset with the pane next to it to keep in sync.
            rect = editor.modelToView(offset);
            if (rect != null) {
                return p.y - rect.getLocation().y;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return 0;
    }

    private AdjustmentListener getHorizontalAdjustmentListener() {
        if (horizontalAdjustmentListener == null) {
            horizontalAdjustmentListener = new AdjustmentListener() {
                private boolean insideScroll;

                public void adjustmentValueChanged(AdjustmentEvent e) {
                    JScrollBar scFrom;
                    JScrollBar scTo;

                    if (insideScroll) {
                        return;
                    }

                    if (filePanelLeft.getScrollPane().getHorizontalScrollBar() == e
                            .getSource()) {
                        scFrom = filePanelLeft.getScrollPane().getHorizontalScrollBar();
                        scTo = filePanelRight.getScrollPane().getHorizontalScrollBar();
                    } else {
                        scFrom = filePanelRight.getScrollPane().getHorizontalScrollBar();
                        scTo = filePanelLeft.getScrollPane().getHorizontalScrollBar();
                    }

                    // Stop possible recursion!
                    // An left scroll will have a right scroll as
                    //   a result. That revised scroll could have a orginal
                    //   scroll as result. etc...
                    insideScroll = true;
                    insideScroll = true;
                    scTo.setValue(scFrom.getValue());
                    insideScroll = false;
                }
            };
        }

        return horizontalAdjustmentListener;
    }

    private AdjustmentListener getVerticalAdjustmentListener() {
        if (verticalAdjustmentListener == null) {
            verticalAdjustmentListener = new AdjustmentListener() {
                private boolean insideScroll;
                private int counter;

                public void adjustmentValueChanged(AdjustmentEvent e) {
                    boolean leftScrolled;

                    if (insideScroll) {
                        return;
                    }

                    if (filePanelLeft.getScrollPane().getVerticalScrollBar() == e
                            .getSource()) {
                        leftScrolled = true;
                    } else {
                        leftScrolled = false;
                    }

                    // Stop possible recursion!
                    // An left scroll will have a right scroll as
                    //   a result. That revised scroll could have a orginal
                    //   scroll as result. etc...
                    insideScroll = true;
                    scroll(leftScrolled);
                    insideScroll = false;
                }
            };
        }

        return verticalAdjustmentListener;
    }
}
