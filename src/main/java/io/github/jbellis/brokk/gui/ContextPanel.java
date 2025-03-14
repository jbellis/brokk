package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.CodeUnit;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IAnalyzer;
import io.github.jbellis.brokk.RepoFile;
import io.github.jbellis.brokk.Models;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Option;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class ContextPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ContextPanel.class);

    // Columns
    private final int FILES_REFERENCED_COLUMN = 2;
    private final int FRAGMENT_COLUMN = 3;

    // Parent references
    private final Chrome chrome;
    private final ContextManager contextManager;

    private JTable contextTable;
    private JPanel locSummaryLabel;

    // Buttons
    private JButton editButton;
    private JButton readOnlyButton;
    private JButton summarizeButton;
    private JButton dropButton;
    private JButton copyButton;
    private JButton pasteButton;
    private JButton symbolButton;

    /**
     * Constructor for the context panel
     */
    public ContextPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        buildContextPanel();

        ((JLabel) locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");
    }

    /**
     * Build the context panel (unified table + action buttons).
     */
    private void buildContextPanel() {
        contextTable = new JTable(new DefaultTableModel(
                new Object[]{"LOC", "Description", "Files Referenced", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> Integer.class;
                    case 1 -> String.class;
                    case 2 -> List.class; // Our references column
                    case 3 -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Add custom cell renderer for the "Description" column
        contextTable.getColumnModel().getColumn(1)
                .setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    public Component getTableCellRendererComponent(
                            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                        var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                        if (value != null && value.toString().startsWith("✏️")) {
                            setFont(getFont().deriveFont(Font.ITALIC));
                        } else {
                            setFont(getFont().deriveFont(Font.PLAIN));
                        }
                        return c;
                    }
                });

        // Files Referenced column: use our FileReferencesTableCellRenderer
        var fileRenderer = new FileReferencesTableCellRenderer();
        contextTable.getColumnModel().getColumn(FILES_REFERENCED_COLUMN).setCellRenderer(fileRenderer);

        // Increase row height to accommodate file "badges"
        contextTable.setRowHeight(23);

        // Hide the FRAGMENT_COLUMN from view
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        // Column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(0).setMaxWidth(100);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(230);
        contextTable.getColumnModel().getColumn(2).setPreferredWidth(250);

        // Add mouse motion for tooltips
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == FILES_REFERENCED_COLUMN) {
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        // Show file references in a multiline tooltip
                        @SuppressWarnings("unchecked")
                        List<FileReferenceData> refs = (List<FileReferenceData>) value;
                        if (!refs.isEmpty()) {
                            var sb = new StringBuilder("<html>");
                            for (FileReferenceData r : refs) {
                                sb.append(r.getFullPath()).append("<br>");
                            }
                            sb.append("</html>");
                            contextTable.setToolTipText(sb.toString());
                            return;
                        }
                    }
                } else if (row >= 0 && col == 1) {
                    // Show full description
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        contextTable.setToolTipText(value.toString());
                        return;
                    }
                }
                contextTable.setToolTipText(null);
            }
        });

        // Add double-click to open fragment preview
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        var fragment = (ContextFragment) contextTable.getModel()
                                .getValueAt(row, FRAGMENT_COLUMN);
                        if (fragment != null) {
                            chrome.openFragmentPreview(fragment);
                        }
                    }
                }
            }
        });

        // Create a single JPopupMenu for the table
        JPopupMenu contextMenu = new JPopupMenu();
        // "View History" for the main row fragment, if it is a RepoPathFragment
        JMenuItem viewHistoryItem = new JMenuItem("View History");
        viewHistoryItem.addActionListener(ev -> {
            int row = contextTable.getSelectedRow();
            if (row >= 0) {
                var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                if (fragment instanceof ContextFragment.RepoPathFragment(RepoFile f)) {
                    chrome.getGitPanel().addFileHistoryTab(f);
                }
            }
        });

        // AutoContext menu items
        JMenuItem setAutoContext5Item = new JMenuItem("Set AutoContext to 5");
        setAutoContext5Item.addActionListener(e ->
                                                      chrome.contextManager.setAutoContextFilesAsync(5));
        JMenuItem setAutoContext10Item = new JMenuItem("Set AutoContext to 10");
        setAutoContext10Item.addActionListener(e ->
                                                       chrome.contextManager.setAutoContextFilesAsync(10));
        JMenuItem setAutoContext20Item = new JMenuItem("Set AutoContext to 20");
        setAutoContext20Item.addActionListener(e ->
                                                       chrome.contextManager.setAutoContextFilesAsync(20));
        JMenuItem setAutoContextCustomItem = new JMenuItem("Set AutoContext...");
        setAutoContextCustomItem.addActionListener(e ->
                                                           chrome.showSetAutoContextSizeDialog());

        // Create menu items for handling all references in a cell
        JMenuItem editAllRefsItem = new JMenuItem("Edit All References");
        editAllRefsItem.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.EDIT, selectedFragments);
        });
        
        JMenuItem readAllRefsItem = new JMenuItem("Read All References");
        readAllRefsItem.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.READ, selectedFragments);
        });
        
        JMenuItem summarizeAllRefsItem = new JMenuItem("Summarize All References");
        summarizeAllRefsItem.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.SUMMARIZE, selectedFragments);
        });

        // Add a mouse listener so we control exactly when the popup shows
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
        if (e.isPopupTrigger()) {
            int row = contextTable.rowAtPoint(e.getPoint());
            int col = contextTable.columnAtPoint(e.getPoint());

            // Clear the menu and rebuild according to row/column
            contextMenu.removeAll();

            if (row >= 0) {
                // Only select the row if nothing is currently selected
                if (contextTable.getSelectedRowCount() == 0) {
                    contextTable.setRowSelectionInterval(row, row);
                }
                var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);

                        // If this is the AutoContext row, show AutoContext items
                        if (fragment instanceof ContextFragment.AutoContext) {
                            contextMenu.add(setAutoContext5Item);
                            contextMenu.add(setAutoContext10Item);
                            contextMenu.add(setAutoContext20Item);
                            contextMenu.addSeparator();
                            contextMenu.add(setAutoContextCustomItem);

                        } else {
                            // Otherwise, show "View History" if it's a RepoPathFragment
                            contextMenu.add(viewHistoryItem);
                            viewHistoryItem.setEnabled(fragment instanceof ContextFragment.RepoPathFragment);
                        }

                        // If the user right-clicked on the references column, show reference options
                        var fileActionsAdded = false;
                        if (col == FILES_REFERENCED_COLUMN) {
                            @SuppressWarnings("unchecked")
                            List<FileReferenceData> references =
                                    (List<FileReferenceData>) contextTable.getValueAt(row, col);
                            if (references != null && !references.isEmpty()) {
                                FileReferenceData targetRef = findClickedReference(e.getPoint(), row, col, references);
                                if (targetRef != null) {
                                    // If clicking on a specific file reference, show specific file options
                                    contextMenu.addSeparator();
                                    contextMenu.add(buildAddMenuItem(targetRef));
                                    contextMenu.add(buildReadMenuItem(targetRef));
                                    contextMenu.add(buildSummarizeMenuItem(targetRef));
                                }
                            }
                            fileActionsAdded = true;
                        }

                        // If clicking in the row but not on a specific reference, show "all references" options
                        if (!fileActionsAdded) {
                            contextMenu.addSeparator();
                            contextMenu.add(editAllRefsItem);
                            contextMenu.add(readAllRefsItem);
                            contextMenu.add(summarizeAllRefsItem);
                        }

                        // Add Copy and Drop actions with a separator
                        contextMenu.addSeparator();
                        JMenuItem copySelectionItem = new JMenuItem("Copy");
                        copySelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = contextManager.performContextActionAsync(
                                    Chrome.ContextAction.COPY, selectedFragments);
                        });
                        contextMenu.add(copySelectionItem);

                        JMenuItem dropSelectionItem = new JMenuItem("Drop");
                        dropSelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = contextManager.performContextActionAsync(
                                    Chrome.ContextAction.DROP, selectedFragments);
                        });
                        contextMenu.add(dropSelectionItem);
                    } else {
                        // If no row is selected, we only show "View History" but disable it
                        contextMenu.add(viewHistoryItem);
                        viewHistoryItem.setEnabled(false);
                    }

                    contextMenu.show(contextTable, e.getX(), e.getY());
                }
            }
        });

        // Set selection mode to allow multiple selection
        contextTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Add a selection listener so we can update the context action buttons
        contextTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateContextButtons();
            }
        });

        // Build summary panel
        var contextSummaryPanel = new JPanel(new BorderLayout());
        locSummaryLabel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        innerLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        locSummaryLabel.add(innerLabel);
        locSummaryLabel.setBorder(BorderFactory.createEmptyBorder());
        contextSummaryPanel.add(locSummaryLabel, BorderLayout.NORTH);

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        var tableScrollPane = new JScrollPane(
                contextTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        tableScrollPane.setPreferredSize(new Dimension(600, 150));
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        // Context action buttons
        var buttonsPanel = createContextButtonsPanel();

        setLayout(new BorderLayout());
        add(tablePanel, BorderLayout.CENTER);
        add(buttonsPanel, BorderLayout.EAST);
        add(contextSummaryPanel, BorderLayout.SOUTH);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }

    /**
     * Creates the panel with context action buttons: edit/read/summarize/drop/copy/paste/symbol
     */
    private JPanel createContextButtonsPanel() {
        var buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
        buttonsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        editButton = new JButton("Edit Files");
        editButton.setMnemonic(KeyEvent.VK_D);
        editButton.setToolTipText("Add project files as editable context");
        editButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.EDIT, List.of());
        });

        readOnlyButton = new JButton("Read Files");
        readOnlyButton.setMnemonic(KeyEvent.VK_R);
        readOnlyButton.setToolTipText("Add project or external files as read-only context");
        readOnlyButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.READ, List.of());
        });

        summarizeButton = new JButton("Summarize Files");
        summarizeButton.setMnemonic(KeyEvent.VK_M);
        summarizeButton.setToolTipText("Summarize the classes in project files");
        summarizeButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.SUMMARIZE, List.of());
        });

        dropButton = new JButton("Drop All");
        dropButton.setMnemonic(KeyEvent.VK_P); // changed from VK_D
        dropButton.setToolTipText("Drop all or selected context entries");
        dropButton.addActionListener(e -> {
            chrome.disableContextActionButtons();
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.DROP, List.of());
        });

        copyButton = new JButton("Copy All");
        copyButton.setToolTipText("Copy all or selected context entries to clipboard");
        copyButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.COPY, List.of());
        });

        pasteButton = new JButton("Paste");
        pasteButton.setToolTipText("Paste the clipboard contents as a new context entry");
        pasteButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(
                    Chrome.ContextAction.PASTE, List.of());
        });

        symbolButton = new JButton("Symbol Usage");
        symbolButton.setMnemonic(KeyEvent.VK_Y);
        symbolButton.setToolTipText("Find uses of a class, method, or field");
        symbolButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.findSymbolUsageAsync();
        });

        // Use a prototype button to fix sizes
        var prototypeButton = new JButton("Summarize selected");
        var buttonSize = prototypeButton.getPreferredSize();
        var preferredSize = new Dimension(buttonSize.width, editButton.getPreferredSize().height);

        // Set sizes
        editButton.setPreferredSize(preferredSize);
        readOnlyButton.setPreferredSize(preferredSize);
        summarizeButton.setPreferredSize(preferredSize);
        dropButton.setPreferredSize(preferredSize);
        copyButton.setPreferredSize(preferredSize);
        pasteButton.setPreferredSize(preferredSize);
        symbolButton.setPreferredSize(preferredSize);

        editButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        readOnlyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        summarizeButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        dropButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        copyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        pasteButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        symbolButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        buttonsPanel.add(editButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(readOnlyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(summarizeButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(symbolButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(dropButton);
        buttonsPanel.add(Box.createVerticalGlue());
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(copyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(pasteButton);

        buttonsPanel.setMinimumSize(new Dimension(
                buttonsPanel.getPreferredSize().width,
                (int) (1.3 * buttonsPanel.getPreferredSize().height)
        ));

        return buttonsPanel;
    }

    /**
     * Whether any row(s) are selected in the context table
     */
    public boolean hasSelectedItems() {
        return contextTable.getSelectedRowCount() > 0;
    }

    /**
     * Gets the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        var fragments = new ArrayList<ContextFragment>();
        int[] selectedRows = contextTable.getSelectedRows();
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int row : selectedRows) {
            fragments.add((ContextFragment) tableModel.getValueAt(row, FRAGMENT_COLUMN));
        }
        return fragments;
    }

    /**
     * Updates the buttons based on context availability
     */
    public void updateContextButtons() {
        SwingUtilities.invokeLater(() -> {
            var ctx = (contextManager == null) ? null : contextManager.currentContext();
            boolean hasContext = (ctx != null && !ctx.isEmpty());
            dropButton.setEnabled(hasContext);
            copyButton.setEnabled(hasContext);
        });
    }

    /**
     * Disables the context action buttons
     */
    public void disableContextActionButtons() {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(false);
            readOnlyButton.setEnabled(false);
            summarizeButton.setEnabled(false);
            dropButton.setEnabled(false);
            copyButton.setEnabled(false);
            pasteButton.setEnabled(false);
            symbolButton.setEnabled(false);
        });
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons() {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(true);
            readOnlyButton.setEnabled(true);
            summarizeButton.setEnabled(true);
            dropButton.setEnabled(true);
            copyButton.setEnabled(true);
            pasteButton.setEnabled(true);
            symbolButton.setEnabled(true);
            updateContextButtons();
        });
    }

    /**
     * Populates the context table from a Context object.
     */
    public void populateContextTable(Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);
        updateContextButtons();

        if (ctx == null || ctx.isEmpty()) {
            ((JLabel) locSummaryLabel.getComponent(0)).setText(
                    "No context - use Edit or Read or Summarize to add content");
            revalidate();
            repaint();
            return;
        }

        var analyzer = contextManager.getAnalyzerNonBlocking();
        var allFragments = ctx.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        StringBuilder fullText = new StringBuilder();
        for (var frag : allFragments) {
            var text = getTextSafe(frag);
            fullText.append(text).append("\n");
            int loc = text.split("\\r?\\n", -1).length;
            totalLines += loc;
            var desc = frag.description();

            // Mark editable if it's in the editable streams
            boolean isEditable = ctx.editableFiles().anyMatch(e -> e == frag);
            if (isEditable) {
                desc = "✏️ " + desc;
            }

            // Build file references
            List<FileReferenceData> fileReferences = new ArrayList<>();
            if (analyzer != null && !(frag instanceof ContextFragment.RepoPathFragment)) {
                fileReferences = frag.sources(analyzer, contextManager.getProject().getRepo())
                        .stream()
                        .map(source -> {
                            var pathOpt = analyzer.pathOf(source);
                            return pathOpt.isDefined()
                                    ? new FileReferenceData(pathOpt.get().getFileName(),
                                                            pathOpt.get().toString(),
                                                            pathOpt.get(),
                                                            source)
                                    : null;
                        })
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted(Comparator.comparing(FileReferenceData::getFileName))
                        .collect(Collectors.toList());
            }

            tableModel.addRow(new Object[]{loc, desc, fileReferences, frag});
        }

        var approxTokens = Models.getApproximateTokens(fullText.toString());
        ((JLabel) locSummaryLabel.getComponent(0)).setText(
                "Total: %,d LOC, or about %,dk tokens".formatted(totalLines, approxTokens / 1000)
        );

        revalidate();
        repaint();
    }

    /**
     * Get the fragment text or remove it if it errors out
     */
    private String getTextSafe(ContextFragment fragment) {
        try {
            return fragment.text();
        } catch (IOException e) {
            chrome.systemOutput("Error reading fragment: " + e.getMessage());
            contextManager.removeBadFragment(fragment, e);
            return "";
        }
    }

    public JButton getEditButton() {
        return editButton;
    }

    /**
     * Called by Chrome to refresh the table if context changes
     */
    public void updateContextTable() {
        SwingUtilities.invokeLater(() -> {
            populateContextTable(contextManager.currentContext());
        });
    }

    /**
     * Updates the capture description area if needed
     */
    public void updateFilesDescriptionLabel(Set<? extends CodeUnit> sources, IAnalyzer analyzer) {
        if (chrome.captureDescriptionArea == null) {
            return;
        }

        if (sources.isEmpty()) {
            chrome.captureDescriptionArea.setText("Files referenced: None");
            return;
        }
        if (analyzer == null) {
            chrome.captureDescriptionArea.setText("Files referenced: ?");
            return;
        }

        var fileNames = sources.stream()
                .map(analyzer::pathOf)
                .filter(Option::isDefined)
                .map(Option::get)
                .map(RepoFile::getFileName)
                .collect(Collectors.toSet());

        String filesText = "Files referenced: " + String.join(", ", fileNames);
        chrome.captureDescriptionArea.setText(filesText);
    }

    /**
     * Determine which file reference was clicked based on mouse coordinates.
     * We replicate the FlowLayout calculations to find the badge at xInCell.
     */
    private FileReferenceData findClickedReference(Point pointInTableCoords,
                                                   int row, int col,
                                                   List<FileReferenceData> references) {
        // Get cell rectangle so we can convert to cell-local coordinates
        Rectangle cellRect = contextTable.getCellRect(row, col, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) {
            return null;
        }

        // We used a smaller font in FileReferenceList: about 0.85 of table's font size
        var baseFont = contextTable.getFont();
        float scaledSize = baseFont.getSize() * 0.85f;
        Font badgeFont = baseFont.deriveFont(Font.PLAIN, scaledSize);
        FontMetrics fm = contextTable.getFontMetrics(badgeFont);

        // FlowLayout left gap was 4, label has ~12px horizontal padding
        final int hgap = 4;
        final int horizontalPadding = 12; // total left+right
        // We drew a 1.5f stroke around the badge, but a small offset is enough
        final int borderThickness = 3; // extra width for border + rounding
        int currentX = 0;

        for (FileReferenceData ref : references) {
            String text = ref.getFileName();
            int textWidth = fm.stringWidth(text);
            // label’s total width = text + internal padding + border
            int labelWidth = textWidth + horizontalPadding + borderThickness;
            // Check if user clicked in [currentX .. currentX + labelWidth]
            if (xInCell >= currentX && xInCell <= currentX + labelWidth) {
                return ref;
            }
            currentX += (labelWidth + hgap);
        }
        return null;
    }

    /**
     * Build "Add file" menu item for a single file reference
     */
    private JMenuItem buildAddMenuItem(FileReferenceData fileRef) {
        JMenuItem addItem = new JMenuItem("Edit " + fileRef.getFullPath());
        addItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.EDIT,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot add file: " + fileRef.getFullPath() + " - no RepoFile available");
            }
        });
        return addItem;
    }

    /**
     * Build "Read file" menu item for a single file reference
     */
    private JMenuItem buildReadMenuItem(FileReferenceData fileRef) {
        JMenuItem readItem = new JMenuItem("Read " + fileRef.getFullPath());
        readItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.READ,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot read file: " + fileRef.getFullPath() + " - no RepoFile available");
            }
        });
        return readItem;
    }

    /**
     * Build "Summarize file" menu item for a single file reference
     */
    private JMenuItem buildSummarizeMenuItem(FileReferenceData fileRef) {
        JMenuItem summarizeItem = new JMenuItem("Summarize " + fileRef.getFullPath());
        summarizeItem.addActionListener(e -> {
            if (fileRef.getCodeUnit() != null && fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.SUMMARIZE,
                        List.of(new ContextFragment.RepoPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot summarize: " + fileRef.getFullPath() + " - file information not available");
            }
        });
        return summarizeItem;
    }
}
