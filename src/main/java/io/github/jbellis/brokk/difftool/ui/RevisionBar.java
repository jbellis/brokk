
package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.diff.JMChunk;
import io.github.jbellis.brokk.difftool.diff.JMDelta;
import io.github.jbellis.brokk.difftool.diff.JMRevision;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.difftool.utils.Colors;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class RevisionBar
        extends JComponent {
    private final BufferDiffPanel diffPanel;
    private final FilePanel filePanel;
    private final boolean original;

    public RevisionBar(BufferDiffPanel diffPanel, FilePanel filePanel,
                       boolean original) {
        this.diffPanel = diffPanel;
        this.filePanel = filePanel;
        this.original = original;

        setBorder(BorderFactory.createLineBorder(ColorUtil.darker(ColorUtil
                                                                          .darker(Colors.getPanelBackground()))));

        addMouseListener(getMouseListener());
        // Ensure a default size
        setPreferredSize(new Dimension(20, 200));
        setMinimumSize(new Dimension(10, 100));
    }

    private MouseListener getMouseListener() {
        return new MouseAdapter() {
            public void mouseClicked(MouseEvent me) {
                Rectangle r;
                int y;
                int line;
                int lineBefore;
                int lineAfter;
                JMRevision revision;
                Point p;
                BufferDocumentIF bd;
                int offset;
                JTextComponent editor;
                JViewport viewport;
                int numberOfLines;
                JMChunk original;

                r = getDrawableRectangle();

                if (r.height <= 0) {
                    return;
                }

                y = me.getY() - r.y;

                revision = diffPanel.getCurrentRevision();
                if (revision == null) {
                    return;
                }

                numberOfLines = getNumberOfLines(revision);
                line = (y * numberOfLines) / r.height;
                if (line > numberOfLines) {
                    line = numberOfLines;
                }

                if (line < 0) {
                    line = 0;
                }

                // If the files are very large the resolution of one pixel contains
                //   a lot of lines of the document. Check if there is a chunk in
                //   the revision between those lines and if there is position on
                //   that chunk.
                lineBefore = ((y - 3) * numberOfLines) / r.height;
                lineAfter = ((y + 3) * numberOfLines) / r.height;
                for (JMDelta delta : revision.getDeltas()) {
                    original = delta.getOriginal();

                    // The chunk starts within the bounds of the line-resolution.
                    if (original.getAnchor() > lineBefore
                            && original.getAnchor() < lineAfter) {
                        diffPanel.doGotoDelta(delta);
                        return;
                    }
                }

                diffPanel.doGotoLine(line);
            }
        };
    }

    /** Calculate the rectangle that can be used to draw the diffs.
     *    It is essentially the size of the scrollbar minus its buttons.
     */
    private Rectangle getDrawableRectangle() {
        JScrollBar sb;
        Rectangle r;
        int buttonHeight;

        sb = filePanel.getScrollPane().getVerticalScrollBar();
        r = sb.getBounds();
        r.x = 0;
        r.y = 0;

        for (Component c : sb.getComponents()) {
            if (c instanceof AbstractButton) {
                r.y += c.getHeight();
                r.height -= (2 * c.getHeight());
                break;
            }
        }

        return r;
    }

    public void paintComponent(Graphics g) {
        Rectangle r;
        Graphics2D g2;
        JMRevision revision;
        JMChunk chunk;
        int y;
        int height;
        int numberOfLines;
        Rectangle clipBounds;

        g2 = (Graphics2D) g;

        clipBounds = g.getClipBounds();

        r = getDrawableRectangle();

        r.x = clipBounds.x;
        r.width = clipBounds.width;

        g2.setColor(Color.white);
        g2.fill(r);

        revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        numberOfLines = getNumberOfLines(revision);
        if (numberOfLines <= 0) {
            return;
        }

        for (JMDelta delta : revision.getDeltas()) {
            chunk = original ? delta.getOriginal() : delta.getRevised();

            g.setColor(ColorUtil.getColor(delta, diffPanel.isDarkTheme()));
            y = r.y + (r.height * chunk.getAnchor()) / numberOfLines;
            height = (r.height * chunk.getSize()) / numberOfLines;
            if (height <= 0) {
                height = 1;
            }

            g.fillRect(0, y, r.width, height);
        }
    }

    private int getNumberOfLines(JMRevision revision) {
        return original ? revision.getOrgSize() : revision.getRevSize();
    }
}
