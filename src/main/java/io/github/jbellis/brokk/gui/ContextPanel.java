package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Analyzer;
import io.github.jbellis.brokk.CodeUnit;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.RepoFile;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ContextPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ContextPanel.class);
    private final int FILES_REFERENCED_COLUMN = 2;
    private final int FRAGMENT_COLUMN = 3;

    // Parent reference
    private final Chrome chrome;
    private final ContextManager contextManager;

    // Context Panel components
    private JTable contextTable;
    private JPanel locSummaryLabel;
    private JTable uncommittedFilesTable;
    private JButton suggestCommitButton;

    // Context action buttons
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
        
        // Build the panel components
        buildContextPanel();
        
        // Initialize with empty summary
        ((JLabel)locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");
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
                    case 2 -> String.class;
                    case 3 -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Add custom cell renderer for the description column to show italics for editable files
        contextTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                if (value != null && value.toString().startsWith("✏️")) {
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }

                return c;
            }
        });

        // Use default renderer for Files Referenced column - files are joined with commas
        contextTable.setRowHeight(18);

        // Set up table header with custom column headers
        var tableHeader = contextTable.getTableHeader();
        tableHeader.setReorderingAllowed(false);
        tableHeader.setResizingAllowed(true);
        tableHeader.setFont(new Font(Font.DIALOG, Font.BOLD, 12));

        // Hide the header for the "Fragment" column
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        contextTable.setIntercellSpacing(new Dimension(10, 1));

        // column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(0).setMaxWidth(100);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(230);
        contextTable.getColumnModel().getColumn(FILES_REFERENCED_COLUMN).setPreferredWidth(250);

        // Add tooltip for files referenced column
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());

                if (row >= 0 && col == FILES_REFERENCED_COLUMN) {
                    var value = contextTable.getValueAt(row, col);
                    if (value != null && !value.toString().isEmpty()) {
                        // Format files as a multiline list by replacing commas with newlines
                        String formattedTooltip = "<html>" +
                            value.toString().replace(", ", "<br>") +
                            "</html>";
                        contextTable.setToolTipText(formattedTooltip);
                        return;
                    }
                }

                // Clear tooltip when not over files column
                contextTable.setToolTipText(null);
            }
        });

        // Add double-click listener to open fragment preview
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                        if (fragment != null) {
                            chrome.openFragmentPreview(fragment);
                        }
                    }
                }
            }
        });
        // Set selection mode to allow multiple selection
        contextTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Add a selection listener to update the context buttons
        contextTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateContextButtons();
            }
        });

        // Panel for context summary information at bottom
        var contextSummaryPanel = new JPanel();
        contextSummaryPanel.setLayout(new BorderLayout());

        locSummaryLabel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        innerLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        locSummaryLabel.add(innerLabel);
        locSummaryLabel.setBorder(BorderFactory.createEmptyBorder());

        // Create panel for uncommitted changes with a suggest commit button to the right
        var uncommittedPanel = new JPanel(new BorderLayout(10, 0));
        uncommittedPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Uncommitted Changes",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table for uncommitted files - fixed to 3 rows with scrollbar
        uncommittedFilesTable = new JTable(new DefaultTableModel(
                new Object[]{"Filename", "Path"}, 0));
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);

        // Set column widths
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);

        // Create a scroll pane with fixed height of 3 rows plus header and scrollbar
        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        int tableRowHeight = uncommittedFilesTable.getRowHeight();
        int headerHeight = 22; // Approximate header height
        int scrollbarHeight = 3; // Extra padding for scrollbar
        uncommittedScrollPane.setPreferredSize(new Dimension(600, (tableRowHeight * 3) + headerHeight + scrollbarHeight));

        // Create the suggest commit button panel on the right
        var commitButtonPanel = new JPanel(new BorderLayout());
        suggestCommitButton = new JButton("Suggest Commit");
        suggestCommitButton.setEnabled(false);
        suggestCommitButton.setMnemonic(KeyEvent.VK_C);
        suggestCommitButton.setToolTipText("Suggest a commit message for the uncommitted changes");
        suggestCommitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.currentUserTask = contextManager.performCommitActionAsync();
        });

        // Add button to panel (width will be set later)
        commitButtonPanel.add(suggestCommitButton, BorderLayout.NORTH);

        // Add table and button to the panel
        uncommittedPanel.add(uncommittedScrollPane, BorderLayout.CENTER);
        uncommittedPanel.add(commitButtonPanel, BorderLayout.EAST);

        contextSummaryPanel.add(locSummaryLabel, BorderLayout.NORTH);
        contextSummaryPanel.add(uncommittedPanel, BorderLayout.CENTER);

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        JScrollPane tableScrollPane = new JScrollPane(contextTable,
                                                      JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                      JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Set a preferred size to maintain height even when empty (almost works)
        tableScrollPane.setPreferredSize(new Dimension(600, 150));
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        // Buttons panel
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
     * Creates the panel with context action buttons: edit/read/summarize/drop/copy
     */
    private JPanel createContextButtonsPanel() {
        var buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
        buttonsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        editButton = new JButton("Edit All");
        editButton.setMnemonic(KeyEvent.VK_D);
        editButton.setToolTipText("Add project files as editable context");
        editButton.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.EDIT, selectedFragments);
        });

        readOnlyButton = new JButton("Read All");
        readOnlyButton.setMnemonic(KeyEvent.VK_R);
        readOnlyButton.setToolTipText("Add project or external files as read-only context");
        readOnlyButton.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.READ, selectedFragments);
        });

        summarizeButton = new JButton("Summarize All");
        summarizeButton.setMnemonic(KeyEvent.VK_M);
        summarizeButton.setToolTipText("Summarize the classes in project files");
        summarizeButton.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.SUMMARIZE, selectedFragments);
        });

        dropButton = new JButton("Drop All");
        dropButton.setMnemonic(KeyEvent.VK_P);  // Changed from VK_D to VK_P
        dropButton.setToolTipText("Drop all or selected context entries");
        dropButton.addActionListener(e -> {
            chrome.disableContextActionButtons();
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.DROP, selectedFragments);
        });

        copyButton = new JButton("Copy All");
        copyButton.setToolTipText("Copy all or selected context entries to clipboard");
        copyButton.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.COPY, selectedFragments);
        });

        pasteButton = new JButton("Paste");
        pasteButton.setToolTipText("Paste the clipboard contents as a new context entry");
        pasteButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.PASTE, List.of());
        });

        symbolButton = new JButton("Symbol Usage");
        symbolButton.setMnemonic(KeyEvent.VK_Y);
        symbolButton.setToolTipText("Find uses of a class, method, or field");
        symbolButton.addActionListener(e -> {
            chrome.currentUserTask = contextManager.findSymbolUsageAsync();
        });

        // Create a prototype button to measure width
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
        buttonsPanel.add(Box.createVerticalGlue());  // Push remaining buttons to bottom
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(copyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(pasteButton);

        // Set the suggestCommitButton to match the width of these context buttons
        if (suggestCommitButton != null) {
            suggestCommitButton.setPreferredSize(preferredSize);
            suggestCommitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        }

        // Force the panel to keep at least enough vertical space for all buttons.
        buttonsPanel.setMinimumSize(new Dimension(buttonsPanel.getPreferredSize().width, (int) (1.3 * buttonsPanel.getPreferredSize().height)));

        return buttonsPanel;
    }
    
    /**
     * Check if any items are selected
     */
    public boolean hasSelectedItems() {
        return contextTable.getSelectedRowCount() > 0;
    }

    /**
     * Get the list of selected fragments
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
     * Update context action button labels
     */
    public void updateContextButtons() {
        SwingUtilities.invokeLater(() -> {
            var hasSelection = hasSelectedItems();
            editButton.setText(hasSelection ? "Edit Selected" : "Edit Files");
            readOnlyButton.setText(hasSelection ? "Read Selected" : "Read Files");
            summarizeButton.setText(hasSelection ? "Summarize Selected" : "Summarize Files");
            dropButton.setText(hasSelection ? "Drop Selected" : "Drop All");
            copyButton.setText(hasSelection ? "Copy Selected" : "Copy All");

            var ctx = contextManager.currentContext();
            var hasContext = (ctx != null && !ctx.isEmpty());
            dropButton.setEnabled(hasContext);
            copyButton.setEnabled(hasContext);
        });
    }

    /**
     * Disables the context action buttons while an action is in progress
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
     *
     * @param ctx The context to display in the table
     */
    public void populateContextTable(Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        assert ctx != null;

        // Clear the existing table rows
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);

        updateContextButtons();

        if (ctx.isEmpty()) {
            ((JLabel)locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");
            revalidate();
            repaint();
            return;
        }

        // Fill the table with new data
        var analyzer = contextManager.getAnalyzerNonBlocking();
        var allFragments = ctx.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        var fullText = "";  // no large merges needed
        for (var frag : allFragments) {
            var text = getTextSafe(frag);
            fullText += text + "\n";
            int loc = text.split("\\r?\\n", -1).length;
            totalLines += loc;
            var desc = frag.description();

            var isEditable = (frag instanceof ContextFragment.RepoPathFragment)
                    && ctx.editableFiles().anyMatch(e -> e == frag);

            if (isEditable) {
                desc = "✏️ " + desc;  // Add pencil icon to editable files
            }

            String referencedFiles = "";
            // Get referenced files for non-RepoPathFragment instances
            if (analyzer != null && !(frag instanceof ContextFragment.RepoPathFragment)) {
                Set<CodeUnit> sources = frag.sources(analyzer);
                if (!sources.isEmpty()) {
                    referencedFiles = sources.stream()
                        .map(analyzer::pathOf)
                        .filter(Option::isDefined)
                        .map(Option::get)
                        .map(RepoFile::getFileName)
                        .distinct()  // Remove duplicates while keeping the stream
                        .collect(Collectors.joining(", "));
                }
            }

            tableModel.addRow(new Object[]{loc, desc, referencedFiles, frag});
        }

        var approxTokens = io.github.jbellis.brokk.Models.getApproximateTokens(fullText);

        ((JLabel)locSummaryLabel.getComponent(0)).setText(
                "Total: %,d LOC, or about %,dk tokens".formatted(totalLines, approxTokens / 1000)
        );

        // revalidate/repaint the panel to reflect the new rows
        revalidate();
        repaint();
    }

    private String getTextSafe(ContextFragment fragment) {
        try {
            return fragment.text();
        } catch (IOException e) {
            chrome.toolErrorRaw("Error reading fragment: " + e.getMessage());
            contextManager.removeBadFragment(fragment, e);
            return "";
        }
    }

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    public void updateSuggestCommitButton() {
        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            List<String> uncommittedFiles = io.github.jbellis.brokk.GitRepo.instance.getUncommittedFileNames();
            SwingUtilities.invokeLater(() -> {
                DefaultTableModel model = (DefaultTableModel) uncommittedFilesTable.getModel();
                model.setRowCount(0);

                if (uncommittedFiles.isEmpty()) {
                    suggestCommitButton.setEnabled(false);
                } else {
                    for (String filePath : uncommittedFiles) {
                        // Split into filename and path
                        int lastSlash = filePath.lastIndexOf('/');
                        String filename = (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
                        String path = (lastSlash >= 0) ? filePath.substring(0, lastSlash) : "";

                        model.addRow(new Object[]{filename, path});
                    }
                    suggestCommitButton.setEnabled(true);
                }
            });
            return null;
        });
    }
    

    // Getters for components that might be needed by Chrome
    public JButton getEditButton() {
        return editButton;
    }

    public void updateContextTable() {
        SwingUtilities.invokeLater(() -> {
            populateContextTable(contextManager.currentContext());
        });
    }
    
    /**
     * Updates the description label with file names
     */
    public void updateFilesDescriptionLabel(Set<? extends CodeUnit> sources, Analyzer analyzer) {
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

        Set<String> fileNames = sources.stream()
            .map(analyzer::pathOf)
            .filter(Option::isDefined)
            .map(Option::get)
            .map(RepoFile::getFileName)
            .collect(Collectors.toSet());

        String filesText = "Files referenced: " + String.join(", ", fileNames);
        chrome.captureDescriptionArea.setText(filesText);
    }
}
