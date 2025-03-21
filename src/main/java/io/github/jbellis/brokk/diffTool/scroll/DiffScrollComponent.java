package io.github.jbellis.brokk.diffTool.scroll;

import io.github.jbellis.brokk.diffTool.diff.JMChunk;
import io.github.jbellis.brokk.diffTool.diff.JMDelta;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.diffTool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.diffTool.ui.FilePanel;
import io.github.jbellis.brokk.diffTool.utils.Colors;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

public class DiffScrollComponent extends JComponent implements ChangeListener {
    private final BufferDiffPanel diffPanel;
    private final int fromPanelIndex;
    private final int toPanelIndex;
    private List<Command> commands;
    private Object antiAlias;
    private int curveType;

    private boolean drawCurves;

    public DiffScrollComponent(BufferDiffPanel diffPanel, int fromPanelIndex,
                               int toPanelIndex) {
        this.diffPanel = diffPanel;
        this.fromPanelIndex = fromPanelIndex;
        this.toPanelIndex = toPanelIndex;

        getFromPanel().getScrollPane().getViewport().addChangeListener(this);
        getToPanel().getScrollPane().getViewport().addChangeListener(this);

        addMouseListener(getMouseListener());
//        addKeyListener(getKeyListener());

        initSettings();
    }

    public void setCurveType(int curveType) {
        this.curveType = curveType;
    }

    public int getCurveType() {
        return curveType;
    }

    boolean shift;

    public boolean isDrawCurves() {
        return drawCurves;
    }

    public void setDrawCurves(boolean drawCurves) {
        this.drawCurves = drawCurves;
    }

    private void initSettings() {
        setDrawCurves(true);
        setCurveType(1);
    }

    public void stateChanged(ChangeEvent event) {
        repaint();
    }

    private MouseListener getMouseListener() {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent me) {
                requestFocus();
                executeCommand(me.getX(), me.getY());
            }
        };
    }

    public void executeCommand(double x, double y) {
        if (commands == null) {
            return;
        }

        for (Command command : commands) {
            if (command.contains(x, y)) {
                command.execute();
                return;
            }
        }

    }

    @Override
    public void paintComponent(Graphics g) {
        Rectangle r;
        Graphics2D g2;

        g2 = (Graphics2D) g;

        r = g.getClipBounds();
        g2.setColor(getBackground());
        g2.fill(r);
        g2.setColor(Color.LIGHT_GRAY);

        paintDiffs(g2);
    }

    private void paintDiffs(Graphics2D g2) {
        JViewport viewportFrom;
        JViewport viewportTo;
        JTextComponent editorFrom;
        JTextComponent editorTo;
        JMRevision revision;
        BufferDocumentIF bdFrom;
        BufferDocumentIF bdTo;
        int firstLineFrom;
        int lastLineFrom;
        int firstLineTo;
        int lastLineTo;
        int offset;
        Rectangle r;
        Point p;
        JMChunk original;
        JMChunk revised;
        Rectangle viewportRect;
        Rectangle fromRect;
        Rectangle toRect;
        int fromLine;
        int toLine;
        int x;
        int y;
        int width;
        int height;
        Rectangle bounds;
        int x0;
        int y0;
        int x1;
        int y1;
        Color color;
        Color darkerColor;
        Polygon shape;
        Rectangle rect;
        boolean selected;
        int selectionWidth;
        FilePanel fromPanel;
        FilePanel toPanel;

        bounds = g2.getClipBounds();
        g2.setClip(null);

        revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        commands = new ArrayList<>();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        antiAlias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        // From side:
        fromPanel = getFromPanel();
        viewportFrom = fromPanel.getScrollPane().getViewport();
        editorFrom = fromPanel.getEditor();
        bdFrom = fromPanel.getBufferDocument();
        if (bdFrom == null) {
            return;
        }

        r = viewportFrom.getViewRect();

        // Calculate firstLine shown of the first document.
        p = new Point(r.x, r.y);
        offset = editorFrom.viewToModel2D(p);
        firstLineFrom = bdFrom.getLineForOffset(offset) + 1;

        // Calculate lastLine shown of the first document.
        p = new Point(r.x, r.y + r.height);
        offset = editorFrom.viewToModel2D(p);
        bdFrom = fromPanel.getBufferDocument();
        lastLineFrom = bdFrom.getLineForOffset(offset) + 1;

        // To side:
        toPanel = getToPanel();
        viewportTo = toPanel.getScrollPane().getViewport();
        editorTo = toPanel.getEditor();
        bdTo = toPanel.getBufferDocument();
        if (bdTo == null) {
            return;
        }

        r = viewportTo.getViewRect();

        // Calculate firstLine shown of the second document.
        p = new Point(r.x, r.y);
        offset = editorTo.viewToModel2D(p);
        firstLineTo = bdTo.getLineForOffset(offset) + 1;

        // Calculate lastLine shown of the second document.
        p = new Point(r.x, r.y + r.height);
        offset = editorTo.viewToModel2D(p);
        lastLineTo = bdTo.getLineForOffset(offset) + 1;

        try {
            // Draw only the delta's that have some line's drawn in one of the viewports.
            for (JMDelta delta : revision.getDeltas()) {
                original = delta.getOriginal();
                revised = delta.getRevised();

                // This delta is before the firstLine of the screen: Keep on searching!
                if (original.getAnchor() + original.getSize() < firstLineFrom
                        && revised.getAnchor() + revised.getSize() < firstLineTo) {
                    continue;
                }

                // This delta is after the lastLine of the screen: stop!
                if (original.getAnchor() > lastLineFrom
                        && revised.getAnchor() > lastLineTo) {
                    break;
                }

                selected = (delta == diffPanel.getSelectedDelta());

                // OK, this delta has some visible lines. Now draw it!
                darkerColor = Color.gray;

                if (delta.isChange()) {
                    color = Colors.CHANGED;
                } else if (delta.isDelete()) {
                    color = Colors.DELETED;
                } else {
                    color = Colors.ADDED;
                }
                g2.setColor(color);
                g2.setColor(color);

                // Draw original chunk:
                fromLine = original.getAnchor();
                toLine = original.getAnchor() + original.getSize();
                viewportRect = viewportFrom.getViewRect();
                offset = bdFrom.getOffsetForLine(fromLine);
                if (offset < 0) {
                    continue;
                }

                fromRect = editorFrom.modelToView(offset);
                offset = bdFrom.getOffsetForLine(toLine);
                if (offset < 0) {
                    continue;
                }
                toRect = editorFrom.modelToView(offset);

                x = 0;
                y = fromRect.y - viewportRect.y + 1;
                y = Math.max(y, 0);
                width = 10;
                height = 0;

                // start of diff is before the first visible line.
                // end   of diff is before the last visible line.
                // (The first part of diff should not be visible)
                if (fromRect.y <= viewportRect.y
                        && toRect.y <= viewportRect.y + viewportRect.height) {
                    height = original.getSize() * fromRect.height;
                    height -= viewportRect.y - fromRect.y;
                }
                // start of diff is after the first visible line.
                // end   of diff is after the last visible line.
                // (The last part of diff should not be visible)
                else if (fromRect.y > viewportRect.y
                        && toRect.y > viewportRect.y + viewportRect.height) {
                    height = original.getSize() * fromRect.height;
                    height -= viewportRect.y + viewportRect.height - toRect.y;
                }
                // start of diff is after the first visible line.
                // end   of diff is before the last visible line.
                // (The diff is completely visible)
                else if (fromRect.y > viewportRect.y
                        && toRect.y <= viewportRect.y + viewportRect.height) {
                    height = original.getSize() * fromRect.height;
                }
                // start of diff is before the first visible line.
                // end   of diff is after the last visible line.
                // (The first part of diff should not be visible)
                // (The last part of diff should not be visible)
                else if (fromRect.y <= viewportRect.y
                        && toRect.y > viewportRect.y + viewportRect.height) {
                    height = viewportRect.height;
                }

                x0 = x + width;
                y0 = y;

                int curveX1 = x;
                int curveY1 = y;
                int curveX4 = x;
                int curveY4 = y + (Math.max(height, 0));

                int xSelFrom = x;
                int ySelFrom = y;
                int heightSelFrom = height;


                // Draw revised chunk:
                fromLine = revised.getAnchor();
                toLine = revised.getAnchor() + revised.getSize();
                viewportRect = viewportTo.getViewRect();
                offset = bdTo.getOffsetForLine(fromLine);
                if (offset < 0 ) {
                    continue;
                }

                fromRect = editorTo.modelToView(offset);
                offset = bdTo.getOffsetForLine(toLine);
                if (offset < 0) {
                    continue;
                }
                toRect = editorTo.modelToView(offset);

                x = bounds.x + bounds.width - 10;
                y = fromRect.y - viewportRect.y + 1;
                y = Math.max(y, 0);
                width = 10;
                height = 0;
                if (fromRect.y <= viewportRect.y
                        && toRect.y <= viewportRect.y + viewportRect.height) {
                    height = revised.getSize() * fromRect.height;
                    height -= viewportRect.y - fromRect.y;
                } else if (fromRect.y > viewportRect.y
                        && toRect.y > viewportRect.y + viewportRect.height) {
                    height = revised.getSize() * fromRect.height;
                    height -= viewportRect.y + viewportRect.height - toRect.y;
                } else if (fromRect.y > viewportRect.y
                        && toRect.y <= viewportRect.y + viewportRect.height) {
                    height = revised.getSize() * fromRect.height;
                } else if (fromRect.y <= viewportRect.y
                        && toRect.y > viewportRect.y + viewportRect.height) {
                    height = viewportRect.height;
                }

                x1 = x;
                y1 = y;

                if (isDrawCurves()) {
                    int curveX2 = x + width;
                    int curveX3 = x + width;
                    int curveY3 = y + (Math.max(height, 0));

                    GeneralPath curve = new GeneralPath();
                    if (getCurveType() == 0) {
                        curve.append(new Line2D.Float(curveX4, curveY4, curveX1, curveY1),
                                true);
                        curve.append(new Line2D.Float(curveX2, y, curveX3, curveY3),
                                true);
                    } else if (getCurveType() == 1) {
                        int posyOrg = original.getSize() > 0 ? 0 : 1;
                        int posyRev = revised.getSize() > 0 ? 0 : 1;
                        curve.append(new CubicCurve2D.Float(curveX1, curveY1 - posyOrg
                                        , curveX1 + ((float) (curveX2 - curveX1) / 2) + 5, curveY1
                                        , curveX1 + ((float) (curveX2 - curveX1) / 2) + 5, y
                                        , curveX2, y - posyRev)
                                , true);
                        curve.append(new CubicCurve2D.Float(curveX3, curveY3 + posyRev/* - addHeightCorrection*/
                                        , curveX3 + ((float) (curveX4 - curveX3) / 2) - 5, curveY3 /*- addHeightCorrection*/
                                        , curveX3 + ((float) (curveX4 - curveX3) / 2) - 5, curveY4
                                        , curveX4, curveY4 + posyOrg)
                                , true);
                    } else if (getCurveType() == 2) {
                        curve.append(new CubicCurve2D.Float(curveX1, curveY1 - 2
                                        , curveX2 + 10, curveY1
                                        , curveX1 + 10, y
                                        , curveX2, y - 2)
                                , true);
                        curve.append(new CubicCurve2D.Float(curveX3, curveY3 + 2
                                        , curveX4 - 10, curveY3
                                        , curveX3 - 10, curveY4
                                        , curveX4, curveY4 + 2)
                                , true);
                    }
                    g2.setColor(color);
                    g2.fill(curve);
                    g2.setColor(darkerColor);
                    setAntiAlias(g2);
                    g2.draw(curve);
                    resetAntiAlias(g2);
                } else {
                    if (height > 0) {
                        g2.setColor(color);
                        g2.fillRect(x, y, width, height);
                    }

                    g2.setColor(darkerColor);
                    g2.drawLine(x, y, x + width, y);
                    if (height > 0) {
                        g2.drawLine(x, y + height, x + width, y + height);
                        g2.drawLine(x, y, x, y + height);
                    }

                    g2.setColor(darkerColor);
                    g2.drawLine(x0, y0, x0 + 15, y0);
                    setAntiAlias(g2);
                    g2.drawLine(x0 + 15, y0, x1 - 15, y1);
                    resetAntiAlias(g2);
                    g2.drawLine(x1 - 15, y1, x1, y1);
                }

                if (selected) {
                    if (heightSelFrom > 0) {
                        selectionWidth = 5;
                        g2.setColor(Color.yellow);
                        g2.fillRect(xSelFrom, ySelFrom, selectionWidth, heightSelFrom);
                        g2.setColor(Color.yellow.darker());
                        g2.drawLine(xSelFrom, ySelFrom, xSelFrom + selectionWidth, ySelFrom);
                        g2.drawLine(xSelFrom + selectionWidth, ySelFrom, xSelFrom + selectionWidth, ySelFrom + heightSelFrom);
                        g2.drawLine(xSelFrom, ySelFrom + heightSelFrom, xSelFrom + selectionWidth, ySelFrom + heightSelFrom);
                    }

                    if (height > 0) {
                        selectionWidth = 5;
                        x += selectionWidth;
                        g2.setColor(Color.yellow);
                        g2.fillRect(x, y, selectionWidth, height);
                        g2.setColor(Color.yellow.darker());
                        g2.drawLine(x, y, x + selectionWidth, y);
                        g2.drawLine(x, y, x, y + height);
                        g2.drawLine(x, y + height, x + selectionWidth, y + height);
                        g2.setColor(color);
                    }
                }

                // Draw merge right->left command.

                if (bdFrom.isReadonly()) {
                    if (!shift || revised.getSize() > 0) {
                        shape = createTriangle(x0, y0, true);
                        setAntiAlias(g2);
                        g2.setColor(Color.gray);
                        g2.fill(shape);
                        g2.setColor(Color.black);
                        g2.draw(shape);
                        resetAntiAlias(g2);
                        commands.add(new DiffChangeCommand(shape, delta, toPanelIndex,
                                fromPanelIndex));
                    }

                    // Draw delete right command
                    if (original.getSize() > 0 && !shift) {
                        g2.setColor(Color.red);
                        g2.drawLine(x0 + 3 - width, y0 + 3, x0 + 7 - width, y0 + 7);
                        g2.drawLine(x0 + 7 - width, y0 + 3, x0 + 3 - width, y0 + 7);
                        rect = new Rectangle(x0 + 2 - width, y0 + 2, 6, 6);
                        commands.add(new DiffDeleteCommand(rect, delta, fromPanelIndex,
                                toPanelIndex));
                    }
                }

                // Draw merge left->right command.
                if (bdTo.isReadonly()) {
                    if (!shift || original.getSize() > 0) {
                        shape = createTriangle(x1, y1, false);
                        setAntiAlias(g2);
                        g2.setColor(Color.gray);
                        g2.fill(shape);
                        g2.setColor(Color.black);
                        g2.drawPolygon(shape);
                        resetAntiAlias(g2);
                        commands.add(new DiffChangeCommand(shape, delta, fromPanelIndex,
                                toPanelIndex));
                    }

                    // Draw delete right command
                    if (revised.getSize() > 0 && !shift) {
                        g2.setColor(Color.red);
                        g2.drawLine(x1 + 3, y1 + 3, x1 + 7, y1 + 7);
                        g2.drawLine(x1 + 7, y1 + 3, x1 + 3, y1 + 7);
                        rect = new Rectangle(x1 + 2, y1 + 2, 6, 6);
                        commands.add(new DiffDeleteCommand(rect, delta, toPanelIndex,
                                fromPanelIndex));
                    }
                }
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }

        resetAntiAlias(g2);
    }

    private Polygon createTriangle(int x, int y, boolean toLeft) {
        Polygon shape;
        shape = new Polygon();
        int posx = 10;
        shape.addPoint(x + (toLeft ? -posx : posx), y);
        posx -= 11;
        shape.addPoint(x + (toLeft ? -posx : posx), y + -4);
        shape.addPoint(x + (toLeft ? -posx : posx), y + +4);
        return shape;
    }

    class DiffChangeCommand
            extends Command {
        DiffChangeCommand(Shape shape, JMDelta delta, int fromIndex, int toIndex) {
            super(shape, delta, fromIndex, toIndex);
        }

        @Override
        public void execute() {
            diffPanel.setSelectedDelta(delta);
            diffPanel.runChange(fromIndex, toIndex, shift);
        }
    }

    class DiffDeleteCommand
            extends Command {
        DiffDeleteCommand(Shape shape, JMDelta delta, int fromIndex, int toIndex) {
            super(shape, delta, fromIndex, toIndex);
        }

        @Override
        public void execute() {
            diffPanel.setSelectedDelta(delta);
            if (!shift) {
                diffPanel.runDelete(fromIndex, toIndex);
            }
        }
    }

    abstract static class Command {
        Rectangle bounds;
        JMDelta delta;
        int fromIndex;
        int toIndex;

        Command(Shape shape, JMDelta delta, int fromIndex, int toIndex) {
            this.bounds = shape.getBounds();
            this.delta = delta;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }

        boolean contains(double x, double y) {
            return bounds.contains(x, y);
        }

        public abstract void execute();
    }

    private void setAntiAlias(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private void resetAntiAlias(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias);
    }

    private FilePanel getFromPanel() {
        return diffPanel.getFilePanel(fromPanelIndex);
    }

    private FilePanel getToPanel() {
        return diffPanel.getFilePanel(toPanelIndex);
    }
}
