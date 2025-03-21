
package io.github.jbellis.brokk.diffTool.ui;

import io.github.jbellis.brokk.diffTool.utils.Colors;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;

public class JMHighlightPainter extends DefaultHighlighter.DefaultHighlightPainter {
    public static  JMHighlightPainter ADDED;
    public static JMHighlightPainter ADDED_LINE;
    /** Painter which adds a newline at end */
    public static JMHighlightPainter ADDED_NEWLINE;
    public static JMHighlightPainter CHANGED;
    /** Painter which adds a newline at end */
    public static JMHighlightPainter CHANGED_NEWLINE;
    public static JMHighlightPainter CHANGED_LIGHTER;
    public static JMHighlightPainter DELETED;
    public static JMHighlightPainter DELETED_LINE;
    /** Painter which adds a newline at end */
    public static JMHighlightPainter DELETED_NEWLINE;
    public static JMHighlightPainter CURRENT_SEARCH;
    public static JMHighlightPainter SEARCH;

    /**
     * Initializes highlight painters separately to avoid class loading deadlock.
     * This method **must** be called explicitly after class loading.
     */
    public static void initializePainters() {
        ADDED = new JMHighlightPainter(Colors.ADDED);
        ADDED.initConfiguration();
        ADDED_LINE = new JMHighlightLinePainter(Colors.ADDED);
        ADDED_LINE.initConfiguration();
        ADDED_NEWLINE = new JMHighlightNewLinePainter(Colors.ADDED);
        ADDED_NEWLINE.initConfiguration();
        CHANGED = new JMHighlightPainter(Colors.CHANGED);
        CHANGED.initConfiguration();
        CHANGED_NEWLINE = new JMHighlightNewLinePainter(Colors.CHANGED);
        CHANGED_NEWLINE.initConfiguration();
        CHANGED_LIGHTER = new JMHighlightPainter(Colors.CHANGED);
        CHANGED_LIGHTER.initConfiguration();
        DELETED = new JMHighlightPainter(Colors.DELETED);
        DELETED.initConfiguration();
        DELETED_LINE = new JMHighlightLinePainter(Colors.DELETED);
        DELETED_LINE.initConfiguration();
        DELETED_NEWLINE = new JMHighlightNewLinePainter(Colors.DELETED);
        DELETED_NEWLINE.initConfiguration();
        SEARCH = new JMHighlightPainter(Color.yellow);
        SEARCH.initConfiguration();
        CURRENT_SEARCH = new JMHighlightPainter(Color.yellow.darker());
        CURRENT_SEARCH.initConfiguration();
    }

    protected Color color;

    protected JMHighlightPainter(Color color) {
        super(color);
        this.color = color;
    }

    @Override
    public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
        Rectangle b;
        Rectangle r1;
        Rectangle r2;
        int x;
        int y;
        int width;
        int height;
        int count;

        b = shape.getBounds();

        try {
            r1 = comp.modelToView(p0);
            r2 = comp.modelToView(p1);

            g.setColor(color);
            if (isChangeLighter() || isSearch()) {
                if (r1.y == r2.y) {
                    g.fillRect(r1.x, r1.y, r2.x - r1.x, r1.height);
                } else {
                    count = ((r2.y - r1.y) / r1.height) + 1;
                    y = r1.y;
                    for (int i = 0; i < count; i++, y += r1.height) {
                        if (i == 0) {
                            // firstline:
                            x = r1.x;
                            width = b.width - b.x;
                        }
                        else if (i == count - 1) {
                            // lastline:
                            x = b.x;
                            width = r2.x - x;
                        } else {
                            // all lines in between the first and the lastline:
                            x = b.x;
                            width = b.width - b.x;
                        }

                        g.fillRect(x, y, width, r1.height);
                    }
                }
            } else {
                height = r2.y - r1.y;
                if(height == 0) {
                    height = r1.height;
                }
                g.fillRect(0, r1.y, b.x + b.width, height);
            }
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    private boolean isSearch() {
        return this == SEARCH || this == CURRENT_SEARCH;
    }

    private void initConfiguration() {
        if (isAdd()) {
            color = Colors.ADDED;
        } else if (isDeleted()) {
            color = Colors.DELETED;
        } else if (isChange()) {
            color = Colors.CHANGED;
        } else if (isChangeLighter()) {
            color = Colors.CHANGED;
        }
    }

    private boolean isChangeLighter() {
        return this == CHANGED_LIGHTER;
    }

    private boolean isChange() {
        return this == CHANGED;
    }

    private boolean isDeleted() {
        return this == DELETED || this == DELETED_LINE || this == DELETED_NEWLINE;
    }

    private boolean isAdd() {
        return this == ADDED || this == ADDED_LINE || this == ADDED_NEWLINE;
    }

    public static class JMHighlightNewLinePainter extends JMHighlightPainter {

        protected JMHighlightNewLinePainter(Color color) {
            super(color);
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
            Rectangle b;
            Rectangle r1;
            Rectangle r2;
            int x;
            int y;
            int width;
            int height;
            int count;

            b = shape.getBounds();

            try {
                r1 = comp.modelToView(p0);
                r2 = comp.modelToView(p1);

                g.setColor(color);
                if (this == CHANGED_LIGHTER || this == SEARCH || this == CURRENT_SEARCH) {
                    if (r1.y == r2.y) {
                        g.fillRect(r1.x, r1.y, r2.x - r1.x, r1.height);
                    } else {
                        count = ((r2.y - r1.y) / r1.height) + 1;
                        y = r1.y;
                        for (int i = 0; i < count; i++, y += r1.height) {
                            if (i == 0) {
                                // firstline:
                                x = r1.x;
                                width = b.width - b.x;
                            }
                            else if (i == count - 1) {
                                // lastline:
                                x = b.x;
                                width = r2.x - x;
                            } else {
                                // all lines in between the first and the lastline:
                                x = b.x;
                                width = b.width - b.x;
                            }

                            g.fillRect(x, y, width, r1.height);
                        }
                    }
                } else {
                    height = r2.y - r1.y;
                    //Add One line to print empty newline
                    height += r1.height;
                    g.fillRect(0, r1.y, b.x + b.width, height);
                }
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }
    }


    public static class JMHighlightLinePainter extends JMHighlightPainter {

        public JMHighlightLinePainter(Color color) {
            super(color);
        }

        @Override
        public void paint(Graphics g, int p0, int p1, Shape shape, JTextComponent comp) {
            Rectangle b;
            Rectangle r1;

            b = shape.getBounds();

            try {
                r1 = comp.modelToView(p0);

                g.setColor(color);
                int yLine = r1.y;
                g.drawLine(0, yLine, b.x + b.width, yLine);
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            }
        }
    }

}
