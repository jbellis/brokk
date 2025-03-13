package io.github.jbellis.brokk.gui;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.DeleteDelta;
import com.github.difflib.patch.InsertDelta;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.ChangeListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.GeneralPath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A Swing component that compares two files (left, right) using GumTree
 * and attempts to visualize diffs similarly to Meld:
 * - "Bands/ribbons" between changed lines
 * - Blocks coalesced together
 * - Partial line highlighting for single-line changes
 * - Move blocks shown in purple
 * - Insert blocks shrink to 1px on the side that has no lines
 * - Animated "apply arrow" in each block for partial merges
 */
public class DiffPanel extends JPanel {

    private final List<String> leftLines = new ArrayList<>();
    private final List<String> rightLines = new ArrayList<>();

    private final JTextArea leftTextArea = new JTextArea();
    private final JTextArea rightTextArea = new JTextArea();

    private final JTextField leftSearchField = new JTextField(12);
    private final JLabel leftSearchCountLabel = new JLabel(" ");
    private final JTextField rightSearchField = new JTextField(12);
    private final JLabel rightSearchCountLabel = new JLabel(" ");

    private final DiffArcPanel arcPanel = new DiffArcPanel();

    /**
     * Represents a coalesced block of changes, possibly from combining multiple
     * GumTree actions if they’re contiguous or logically grouped.
     */
    private static class DiffBlock {
        ActionType type;
        int startLeft, endLeft;
        int startRight, endRight;
        boolean singleLineChange;
        String changedSubstringLeft;
        String changedSubstringRight;
        Color color;
    }

    /**
     * Action types that matter for coloring and shape logic.
     */
    private enum ActionType { INSERT, DELETE, UPDATE, MOVE }

    /**
     * For display in the arc panel: the geometry of the "ribbon" band
     * plus an arrow for partial merges, plus bounding box for mouse hits.
     */
    private static class DiffBand {
        DiffBlock block;
        Shape shape;
        Rectangle bounds;
        int arrowX, arrowY;
        boolean hovered;
    }

    private final List<DiffBlock> diffBlocks = new ArrayList<>();

    /**
     * Constructs the panel from two files.
     */
    public DiffPanel(File leftFile, File rightFile) throws IOException {
        this(Files.readAllLines(leftFile.toPath()), Files.readAllLines(rightFile.toPath()));
    }

    /**
     * Alternatively, constructs from two lists of lines.
     */
    public DiffPanel(List<String> leftLines, List<String> rightLines) {
        super(new BorderLayout());
        this.leftLines.addAll(leftLines);
        this.rightLines.addAll(rightLines);

        JPanel leftSearchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftSearchPanel.add(new JLabel("Left search:"));
        leftSearchPanel.add(leftSearchField);
        leftSearchPanel.add(leftSearchCountLabel);

        JPanel rightSearchPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightSearchPanel.add(new JLabel("Right search:"));
        rightSearchPanel.add(rightSearchField);
        rightSearchPanel.add(rightSearchCountLabel);

        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.add(leftSearchPanel, BorderLayout.WEST);
        searchRow.add(rightSearchPanel, BorderLayout.EAST);
        add(searchRow, BorderLayout.NORTH);

        leftTextArea.setEditable(false);
        rightTextArea.setEditable(false);
        Font font = new Font("Monospaced", Font.PLAIN, 13);
        leftTextArea.setFont(font);
        rightTextArea.setFont(font);

        leftTextArea.setBorder(new LineNumberBorder(leftTextArea));
        rightTextArea.setBorder(new LineNumberBorder(rightTextArea));

        JScrollPane leftScroll = new JScrollPane(leftTextArea);
        JScrollPane rightScroll = new JScrollPane(rightTextArea);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(arcPanel, BorderLayout.CENTER);
        arcPanel.setPreferredSize(new Dimension(70, 100));

        JSplitPane leftSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, centerPanel);
        leftSplit.setResizeWeight(0.5);
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, rightScroll);
        mainSplit.setResizeWeight(0.0);

        add(mainSplit, BorderLayout.CENTER);
        updateLeftText();
        updateRightText();
        computeDiffBlocks();
        highlightChangedLines();
        arcPanel.recomputeBands();
        arcPanel.repaint();

        ChangeListener repainter = e -> arcPanel.repaint();
        leftScroll.getViewport().addChangeListener(repainter);
        rightScroll.getViewport().addChangeListener(repainter);

        arcPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                arcPanel.handleClick(e.getPoint());
            }
        });

        arcPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                arcPanel.handleHover(e.getPoint());
            }
        });

        leftSearchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                doSearchLeft();
            }
        });
        rightSearchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                doSearchRight();
            }
        });

        installCtrlFBinding(leftTextArea, leftSearchField);
        installCtrlFBinding(rightTextArea, rightSearchField);
    }

    /**
     * Installs a Ctrl+F handler on the given text area to focus the search field.
     */
    private void installCtrlFBinding(JTextArea textArea, JTextField searchField) {
        InputMap im = textArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = textArea.getActionMap();
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
        im.put(ctrlF, "showSearch");
        am.put("showSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
    }

    /**
     * Sets or refreshes the text in the left area.
     */
    private void updateLeftText() {
        leftTextArea.setText(String.join("\n", leftLines));
        leftTextArea.setCaretPosition(0);
    }

    /**
     * Sets or refreshes the text in the right area.
     */
    private void updateRightText() {
        rightTextArea.setText(String.join("\n", rightLines));
        rightTextArea.setCaretPosition(0);
    }

    /**
     * Runs java-diff-utils, collects deltas, merges them into blocks, and stores them in diffBlocks.
     * Also adds some debug printlns for troubleshooting.
     */
    private void computeDiffBlocks()
    {
        System.out.println("computeDiffBlocks: begin");
        try {
            diffBlocks.clear();

            // Use java-diff-utils to get a patch representing the line-by-line differences
            var patch = DiffUtils.diff(leftLines, rightLines);

            // Build raw DiffBlocks from each delta
            List<DiffBlock> rawBlocks = new ArrayList<>();
            for (AbstractDelta<String> delta : patch.getDeltas()) {
                var block = buildBlock(delta);
                rawBlocks.add(block);
            }
            System.out.println("computeDiffBlocks: raw blocks=" + rawBlocks.size());

            // Coalesce adjacent or overlapping blocks
            List<DiffBlock> merged = coalesceBlocks(rawBlocks);
            diffBlocks.addAll(merged);
            System.out.println("computeDiffBlocks: after coalesce=" + diffBlocks.size());
        }
        catch (Exception ex) {
            ex.printStackTrace();
            diffBlocks.clear();
        }
    }

    /**
     * Builds a single DiffBlock from the given diff-utils AbstractDelta.
     */
    private DiffBlock buildBlock(AbstractDelta<String> delta)
    {
        DiffBlock b = new DiffBlock();
        b.startLeft = -1;
        b.endLeft = -1;
        b.startRight = -1;
        b.endRight = -1;
        b.singleLineChange = false;

        // Positions and lines on the old (left) side:
        var srcChunk = delta.getSource();
        // Positions and lines on the new (right) side:
        var tgtChunk = delta.getTarget();

        // For line-based diffs, getPosition() is a 0-based line index in each file.
        // The chunk's lines() method returns the actual lines that were changed,
        // so the size tells us how many lines are involved on each side.
        int leftPos = srcChunk.getPosition();
        int leftSize = srcChunk.getLines().size();
        int rightPos = tgtChunk.getPosition();
        int rightSize = tgtChunk.getLines().size();

        // If the delta is a DeleteDelta, the right side lines are empty (in the patch).
        // If the delta is an InsertDelta, the left side lines are empty (in the patch).
        // If it's a ChangeDelta, both sides have lines.

        if (delta instanceof InsertDelta) {
            b.type = ActionType.INSERT;
            b.color = Color.GREEN;

            // Insert means no real left range
            b.startLeft = -1;
            b.endLeft = -1;

            // The new lines are at [rightPos .. rightPos + rightSize - 1]
            b.startRight = rightPos;
            b.endRight = rightPos + rightSize - 1;
        }
        else if (delta instanceof DeleteDelta) {
            b.type = ActionType.DELETE;
            b.color = Color.PINK;

            // Delete means no real right range
            b.startRight = -1;
            b.endRight = -1;

            // The old lines are [leftPos .. leftPos + leftSize - 1]
            b.startLeft = leftPos;
            b.endLeft = leftPos + leftSize - 1;
        }
        else if (delta instanceof ChangeDelta) {
            b.type = ActionType.UPDATE;
            b.color = Color.ORANGE;

            // Both sides have lines:
            b.startLeft = leftPos;
            b.endLeft = leftPos + leftSize - 1;
            b.startRight = rightPos;
            b.endRight = rightPos + rightSize - 1;
        }
        else {
            // Fallback
            b.type = ActionType.UPDATE;
            b.color = Color.CYAN;
            b.startLeft = leftPos;
            b.endLeft = leftPos + leftSize - 1;
            b.startRight = rightPos;
            b.endRight = rightPos + rightSize - 1;
        }

        // If exactly 1 line changed on each side, we do single-line substring highlighting
        if (b.type == ActionType.UPDATE
                && (b.endLeft == b.startLeft)
                && (b.endRight == b.startRight)
                && b.startLeft >= 0
                && b.startLeft < leftLines.size()
                && b.startRight >= 0
                && b.startRight < rightLines.size())
        {
            b.singleLineChange = true;
            computeSublineChange(b, String.join("\n", leftLines),
                                 String.join("\n", rightLines));
        }

        return b;
    }

    /**
     * Performs a naive sub-string diff for single-line changes, storing changed regions.
     */
    private void computeSublineChange(DiffBlock b, String leftFull, String rightFull) {
        if (b.startLeft < 0 || b.startLeft >= leftLines.size()) return;
        if (b.startRight < 0 || b.startRight >= rightLines.size()) return;
        String leftLine = leftLines.get(b.startLeft);
        String rightLine = rightLines.get(b.startRight);

        int prefix = 0;
        while (prefix < leftLine.length() && prefix < rightLine.length()
                && leftLine.charAt(prefix) == rightLine.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < leftLine.length() - prefix
                && suffix < rightLine.length() - prefix
                && leftLine.charAt(leftLine.length() - 1 - suffix)
                == rightLine.charAt(rightLine.length() - 1 - suffix)) {
            suffix++;
        }

        if (prefix + suffix > leftLine.length()) suffix = leftLine.length() - prefix;
        if (prefix + suffix > rightLine.length()) suffix = rightLine.length() - prefix;

        b.changedSubstringLeft = leftLine.substring(prefix, leftLine.length() - suffix);
        b.changedSubstringRight = rightLine.substring(prefix, rightLine.length() - suffix);
    }

    /**
     * Helper to ensure we have valid 0-based line ranges.
     */
    private void fixNegativeRanges(DiffBlock b) {
        if (b.startLeft < 0)  b.startLeft = -1;
        if (b.endLeft < 0)    b.endLeft = -1;
        if (b.startRight < 0) b.startRight = -1;
        if (b.endRight < 0)   b.endRight = -1;
    }

    /**
     * Coalesces contiguous or overlapping blocks of the same ActionType
     * and merges them into a single DiffBlock with the min start and max end lines.
     * Also coalesces adjacent Moves if they are consecutive lines.
     */
    private List<DiffBlock> coalesceBlocks(List<DiffBlock> raw) {
        raw.sort(Comparator.comparingInt(a -> a.startLeft < 0 ? Integer.MAX_VALUE : a.startLeft));
        List<DiffBlock> result = new ArrayList<>();
        for (DiffBlock b : raw) {
            if (result.isEmpty()) {
                result.add(b);
                continue;
            }
            DiffBlock last = result.get(result.size() - 1);
            if (canMerge(last, b)) {
                last.startLeft = Math.min(last.startLeft, b.startLeft < 0 ? last.startLeft : b.startLeft);
                last.endLeft   = Math.max(last.endLeft, b.endLeft);
                last.startRight= Math.min(last.startRight, b.startRight < 0 ? last.startRight : b.startRight);
                last.endRight  = Math.max(last.endRight, b.endRight);
            } else {
                result.add(b);
            }
        }
        return result;
    }

    /**
     * Checks whether two blocks should be merged based on type and adjacency.
     */
    private boolean canMerge(DiffBlock a, DiffBlock b) {
        if (a.type != b.type) return false;
        // For MOVEs, we especially want to coalesce if they're contiguous lines in both sides.
        if (a.type == ActionType.MOVE) {
            boolean leftContig = (b.startLeft <= a.endLeft + 1);
            boolean rightContig = (b.startRight <= a.endRight + 1);
            return leftContig && rightContig;
        }
        // For other types, coalesce if they overlap or are immediately adjacent.
        boolean leftContig = (b.startLeft <= a.endLeft + 1) || a.startLeft < 0 || b.startLeft < 0;
        boolean rightContig = (b.startRight <= a.endRight + 1) || a.startRight < 0 || b.startRight < 0;
        return leftContig && rightContig;
    }

    /**
     * Converts a character offset into a 0-based line number.
     */
    private int getLineNumberForOffset(String fullText, int offset) {
        if (offset < 0) return -1;
        if (offset > fullText.length()) offset = fullText.length();
        int line = 0;
        for (int i = 0; i < offset; i++) {
            if (fullText.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Highlights changed lines in the two text areas. For single-line updates,
     * we highlight only the changed substring.
     */
    private void highlightChangedLines() {
        clearHighlights(leftTextArea);
        clearHighlights(rightTextArea);
        for (DiffBlock block : diffBlocks) {
            if (block.type == ActionType.INSERT && block.startLeft < 0) {
                // Insert block: highlight only the right side
                highlightLines(rightTextArea, block.startRight, block.endRight, block.color);
            } else if (block.type == ActionType.DELETE && block.startRight < 0) {
                // Delete block: highlight only the left side
                highlightLines(leftTextArea, block.startLeft, block.endLeft, block.color);
            } else {
                // Typical block with lines on both sides
                highlightLines(leftTextArea, block.startLeft, block.endLeft, block.color);
                highlightLines(rightTextArea, block.startRight, block.endRight, block.color);

                // Single-line sub-string highlight
                if (block.singleLineChange && block.changedSubstringLeft != null) {
                    highlightSubstring(leftTextArea, block.startLeft,
                                       block.changedSubstringLeft, Color.RED);
                    highlightSubstring(rightTextArea, block.startRight,
                                       block.changedSubstringRight, Color.RED);
                }
            }
        }
    }

    /**
     * Removes all highlights from a JTextComponent.
     */
    private static void clearHighlights(JTextComponent comp) {
        comp.getHighlighter().removeAllHighlights();
    }

    /**
     * Highlights an entire range of lines with the given color.
     */
    private void highlightLines(JTextArea area, int startLine, int endLine, Color color) {
        if (startLine < 0 || endLine < 0) return;
        int startOffset = getOffsetForLine(area, startLine);
        int endOffset = getOffsetForLine(area, endLine + 1);
        if (startOffset < 0 || endOffset < 0) return;
        Highlighter.HighlightPainter painter =
                new DefaultHighlighter.DefaultHighlightPainter(adjustAlpha(color, 0.3f));
        try {
            area.getHighlighter().addHighlight(startOffset, endOffset, painter);
        } catch (BadLocationException e) {
            System.out.println("Error highlighting lines: " + e.getMessage());
        }
    }

    /**
     * Highlights a substring in a single line with a stronger color.
     */
    private void highlightSubstring(JTextArea area, int lineIndex, String substring, Color color) {
        if (lineIndex < 0 || lineIndex >= area.getLineCount()) return;
        if (substring == null || substring.isEmpty()) return;
        int lineStart = getOffsetForLine(area, lineIndex);
        int lineEnd = getOffsetForLine(area, lineIndex + 1);
        if (lineStart < 0 || lineEnd < 0) return;
        try {
            String lineText = area.getText(lineStart, lineEnd - lineStart);
            int idx = lineText.indexOf(substring);
            if (idx >= 0) {
                Highlighter.HighlightPainter painter =
                        new DefaultHighlighter.DefaultHighlightPainter(adjustAlpha(color, 0.5f));
                area.getHighlighter().addHighlight(lineStart + idx, lineStart + idx + substring.length(), painter);
            }
        } catch (BadLocationException e) {
            System.out.println("Error highlighting substring: " + e.getMessage());
        }
    }

    /**
     * Adjusts the alpha of the given color.
     */
    private Color adjustAlpha(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(alpha * 255));
    }

    /**
     * Returns the document offset for the start of the given line index in the area.
     */
    private int getOffsetForLine(JTextArea area, int lineIndex) {
        if (lineIndex < 0) return -1;
        if (lineIndex >= area.getLineCount()) return -1;
        try {
            return area.getLineStartOffset(lineIndex);
        } catch (BadLocationException e) {
            return -1;
        }
    }

    /**
     * Left-side search.
     */
    private void doSearchLeft() {
        String query = leftSearchField.getText();
        if (query == null || query.isEmpty()) {
            clearHighlights(leftTextArea);
            highlightChangedLines();
            leftSearchCountLabel.setText(" ");
            return;
        }
        clearHighlights(leftTextArea);
        highlightChangedLines();
        int count = highlightAll(leftTextArea, query);
        leftSearchCountLabel.setText(count + " match" + (count == 1 ? "" : "es"));
    }

    /**
     * Right-side search.
     */
    private void doSearchRight() {
        String query = rightSearchField.getText();
        if (query == null || query.isEmpty()) {
            clearHighlights(rightTextArea);
            highlightChangedLines();
            rightSearchCountLabel.setText(" ");
            return;
        }
        clearHighlights(rightTextArea);
        highlightChangedLines();
        int count = highlightAll(rightTextArea, query);
        rightSearchCountLabel.setText(count + " match" + (count == 1 ? "" : "es"));
    }

    /**
     * Highlights all occurrences of 'text' in 'comp' with yellow.
     * Returns the number of matches found.
     */
    private static int highlightAll(JTextComponent comp, String text) {
        Highlighter h = comp.getHighlighter();
        String full = comp.getText();
        int count = 0;
        int idx = 0;
        while (true) {
            idx = full.indexOf(text, idx);
            if (idx < 0) break;
            try {
                h.addHighlight(idx, idx + text.length(),
                               new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));
            } catch (BadLocationException e) {
                // ignore
            }
            idx += text.length();
            count++;
        }
        return count;
    }

    /**
     * The center panel that draws "ribbons" between changed blocks (left to right),
     * plus an "apply arrow" near the top for partial merges.
     */
    private class DiffArcPanel extends JPanel {
        private final List<DiffBand> bands = new ArrayList<>();
        private Timer hoverTimer;
        private int arrowSize = 8;
        private boolean arrowGrowing = false;

        public DiffArcPanel() {
            setOpaque(true);
            setBackground(Color.WHITE);
            setToolTipText("Click a band or arrow to apply partial merge");
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    System.out.println("DiffArcPanel resized: " + getWidth() + "x" + getHeight());
                    recomputeBands();
                    repaint();
                }
            });
            hoverTimer = new Timer(30, e -> animateArrowHover());
            hoverTimer.setRepeats(true);
            hoverTimer.start();
        }

        /**
         * Recreates the band shapes for all diffBlocks.
         */
        public void recomputeBands() {
            System.out.println("recomputeBands: start");
            bands.clear();
            for (DiffBlock block : diffBlocks) {
                DiffBand band = new DiffBand();
                band.block = block;

                int topLeftY = getLineBaselineY(leftTextArea, block.startLeft, true, block.type == ActionType.INSERT);
                int botLeftY = getLineBaselineY(leftTextArea, block.endLeft, false, block.type == ActionType.INSERT);
                int topRightY = getLineBaselineY(rightTextArea, block.startRight, true, block.type == ActionType.DELETE);
                int botRightY = getLineBaselineY(rightTextArea, block.endRight, false, block.type == ActionType.DELETE);

                GeneralPath gp = buildRibbon(topLeftY, botLeftY, topRightY, botRightY,
                                             getWidth(), adjustAlpha(block.color, 0.25f));
                band.shape = gp;
                band.bounds = gp.getBounds();
                band.block = block;

                band.arrowX = 5; // put arrow near left edge
                band.arrowY = topLeftY;
                bands.add(band);
            }
        }

        /**
         * Determines the Y coordinate for a given line index in the specified text area.
         * If the block is an insertion with no actual lines on the left side, we narrow it to 1 px.
         */
        private int getLineBaselineY(JTextArea area, int line, boolean top, boolean narrow) {
            if (line < 0 || line >= area.getLineCount()) {
                // For pure insert or delete, force a 1px line region
                if (narrow) {
                    return top ? 0 : 1;
                }
                return top ? 0 : 0;
            }
            FontMetrics fm = area.getFontMetrics(area.getFont());
            int lineHeight = fm.getHeight();
            int y = line * lineHeight + (top ? 0 : lineHeight);
            Point p = SwingUtilities.convertPoint(area, new Point(0, y), DiffArcPanel.this);
            return p.y;
        }

        /**
         * Creates a shape that looks like a filled "ribbon" from
         * left top/bottom to right top/bottom using cubic curves.
         */
        private GeneralPath buildRibbon(int topLeft, int botLeft, int topRight, int botRight,
                                        int panelWidth, Color fill) {
            GeneralPath path = new GeneralPath();
            int leftX = 0;
            int rightX = panelWidth;
            path.moveTo(leftX, topLeft);

            // Top curve left->right
            int c1x = leftX + (rightX - leftX) / 3;
            int c1y = topLeft;
            int c2x = leftX + 2 * (rightX - leftX) / 3;
            int c2y = topRight;
            path.curveTo(c1x, c1y, c2x, c2y, rightX, topRight);

            // Move down to bottom right
            path.lineTo(rightX, botRight);

            // Bottom curve right->left
            c1x = leftX + 2 * (rightX - leftX) / 3;
            c1y = botRight;
            c2x = leftX + (rightX - leftX) / 3;
            c2y = botLeft;
            path.curveTo(c1x, c1y, c2x, c2y, leftX, botLeft);

            path.closePath();
            return path;
        }

        /**
         * Paints each ribbon and arrow. Moves remain purple. Others are tinted by block color.
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());

            for (DiffBand band : bands) {
                g2.setColor(adjustAlpha(band.block.color, 0.25f));
                g2.fill(band.shape);
                g2.setColor(band.block.color);
                g2.draw(band.shape);

                int ax = band.arrowX + arrowSize;
                int ay = band.arrowY + arrowSize;
                drawArrow(g2, ax, ay, band.block.color.darker(), band.hovered);
            }
        }

        /**
         * Draws the arrow near the top-left corner of the ribbon block,
         * scaled up to 175% if hovered.
         */
        private void drawArrow(Graphics2D g2, int x, int y, Color color, boolean hovered) {
            g2.setColor(color);
            int size = hovered ? (int)(arrowSize * 1.75) : arrowSize;
            Polygon arrow = new Polygon();
            arrow.addPoint(x, y);
            arrow.addPoint(x - size, y - size/2);
            arrow.addPoint(x - size, y + size/2);
            g2.fillPolygon(arrow);
        }

        /**
         * Called when the user clicks on the arc panel. We check if they clicked
         * inside the bounding box of one of the bands (or near the arrow).
         */
        public void handleClick(Point p) {
            for (DiffBand band : bands) {
                if (band.bounds.contains(p)) {
                    System.out.println("Clicked block: " + band.block.type);
                    doPartialMerge(band.block);
                    break;
                }
            }
        }

        /**
         * Called when the mouse moves. We detect whether we are over an arrow
         * so we can animate the arrow size.
         */
        public void handleHover(Point p)
        {
            boolean anyHovered = false;
            for (DiffBand band : bands) {
                // We use the same coordinate logic that drawArrow uses:
                int size = arrowGrowing ? (int) (arrowSize * 1.75) : arrowSize;
                int ax = band.arrowX + arrowSize;  // where the arrow is drawn in drawArrow
                int ay = band.arrowY + arrowSize;

                // The arrow is drawn from (ax, ay) leftwards, so define bounding box accordingly
                int rectX = ax - size;
                int rectY = ay - size / 2;
                // Guarantee non-negative width/height:
                Rectangle arrowRect = new Rectangle(rectX, rectY, size, size);

                band.hovered = arrowRect.contains(p);
                if (band.hovered) {
                    anyHovered = true;
                }
            }

            // Trigger arrow growing or shrinking based on hover
            if (anyHovered && !arrowGrowing) {
                arrowGrowing = true;
            } else if (!anyHovered && arrowGrowing) {
                arrowGrowing = false;
            }
        }

        /**
         * Animates the arrow growth or shrink on hover.
         */
        private void animateArrowHover() {
            if (arrowGrowing) {
                arrowSize = Math.min(arrowSize + 1, 12);
            } else {
                arrowSize = Math.max(arrowSize - 1, 8);
            }
            repaint();
        }

        /**
         * Partially merges the left side lines into the right side for the block.
         * For an insert, attempts to find a better anchor if possible.
         */
        private void doPartialMerge(DiffBlock block) {
            // If there's no real content on left, do nothing
            if (block.startLeft < 0 || block.endLeft < 0) {
                System.out.println("No left lines to copy for partial merge.");
                return;
            }
            List<String> newContent = new ArrayList<>(rightLines);

            int insertionPoint = block.startRight;
            if (insertionPoint < 0 || insertionPoint > newContent.size()) {
                // Attempt a best-effort anchor if we can
                insertionPoint = findAnchor(block);
            }

            int removeCount = Math.max(0, (block.endRight - block.startRight + 1));
            if (insertionPoint < 0) insertionPoint = 0;
            if (insertionPoint > newContent.size()) insertionPoint = newContent.size();
            for (int i = 0; i < removeCount && insertionPoint < newContent.size(); i++) {
                newContent.remove(insertionPoint);
            }

            for (int i = block.startLeft; i <= block.endLeft && i < leftLines.size(); i++) {
                newContent.add(insertionPoint, leftLines.get(i));
                insertionPoint++;
            }

            rightLines.clear();
            rightLines.addAll(newContent);
            updateRightText();
            computeDiffBlocks();
            highlightChangedLines();
            recomputeBands();
            repaint();
        }

        /**
         * Finds a suitable anchor point in rightLines to insert, based on the lines
         * in block.startLeft - 1 or similar. Tries to find a matching line in rightLines.
         */
        private int findAnchor(DiffBlock block) {
            System.out.println("Attempting to find anchor for insertion");
            if (block.startLeft <= 0) return 0;
            String anchorLine = leftLines.get(block.startLeft - 1);
            int best = 0;
            for (int i = 0; i < rightLines.size(); i++) {
                if (rightLines.get(i).equals(anchorLine)) {
                    best = i + 1;
                }
            }
            return best;
        }
    }

    /**
     * A border that shows line numbers in a gray strip, similar to an editor gutter.
     */
    private static class LineNumberBorder extends AbstractBorder {
        private static final int MARGIN = 50;
        private final JTextArea textArea;
        private final Font lineNumberFont = new Font("Monospaced", Font.PLAIN, 12);
        private final Color bgColor = new Color(230, 230, 230);
        private final Color fgColor = Color.GRAY;

        public LineNumberBorder(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(0, MARGIN, 0, 0);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(bgColor);
            g2.fillRect(x, y, MARGIN, height);

            FontMetrics fm = g2.getFontMetrics(lineNumberFont);
            int lineHeight = fm.getHeight();
            Rectangle clip = g2.getClipBounds();
            int startLine = clip.y / lineHeight;
            if (startLine < 0) startLine = 0;
            int endLine = startLine + (height / lineHeight) + 1;
            int totalLines = textArea.getLineCount();

            JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, textArea);
            int yoffset = 0;
            if (sp != null) {
                yoffset = sp.getViewport().getViewPosition().y;
            }

            g2.setColor(fgColor);
            g2.setFont(lineNumberFont);
            for (int line = startLine; line < endLine; line++) {
                if (line >= totalLines) break;
                String label = String.valueOf(line + 1);
                int yy = line * lineHeight - yoffset + fm.getAscent();
                g2.drawString(label, x + MARGIN - 5 - fm.stringWidth(label), yy);
            }
            g2.dispose();
        }
    }

    /**
     * Standalone main for testing.
     */
    public static void main(String[] args) throws Exception {
        com.formdev.flatlaf.FlatLightLaf.setup();

        com.github.gumtreediff.client.Run.initGenerators();

        JFrame frame = new JFrame("DiffPanel Example");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        var leftSource = """
                public class AccountManager {
                    private String name;
                    private double outstanding;
                    private int unused;

                    void printOwing() {
                        // Print banner
                        System.out.println("Details of account");
                        System.out.println("-----");
                        System.out.println("");

                        // Print details
                        System.out.println("name: " + name);
                        System.out.println("amount: " + outstanding);
                    }
                }
                """.stripIndent();

        var rightSource = """
                public class AccountManager {
                    private String name;
                    private double outstanding;

                    void printOwing() {
                        // Print banner
                        System.out.println("***");
                        System.out.println("");

                        // Print details
                        printDetails();
                    }

                    void printDetails() {
                        System.out.println("name: " + name);
                        System.out.println("amount: " + outstanding);
                    }
                }
                """.stripIndent();

        DiffPanel panel;
        if (args.length == 2) {
            panel = new DiffPanel(new File(args[0]), new File(args[1]));
        } else {
            panel = new DiffPanel(
                    List.of(leftSource.split("\n")),
                    List.of(rightSource.split("\n")));
        }

        frame.setContentPane(panel);
        frame.setSize(1200, 600);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
