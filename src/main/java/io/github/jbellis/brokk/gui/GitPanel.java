package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel for showing Git-related information and actions, excluding the "Log" tab
 * (which is handled by GitLogPanel).
 */
public class GitPanel extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    // Commit tab UI
    private JTable uncommittedFilesTable;
    private JButton suggestMessageButton;
    private RSyntaxTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;

    // History tabs
    private JTabbedPane tabbedPane;
    private final Map<String, JTable> fileHistoryTables = new HashMap<>();
    private Map<String, String> fileStatusMap = new HashMap<>();
    
    

    // Reference to the extracted Log tab
    private GitLogPanel gitLogPanel;  // The separate class

    /**
     * Constructor for the Git panel
     */
    public GitPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Git ▼",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        int rows = 15;
        int rowHeight = 18;
        int overhead = 100;
        int totalHeight = rows * rowHeight + overhead;
        int preferredWidth = 1000;
        Dimension panelSize = new Dimension(preferredWidth, totalHeight);
        setPreferredSize(panelSize);

        // Add a mouse listener to the panel to handle clicks on the title
        this.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                // Check if the click is in the top region where the title resides
                // This is a simple approach assuming the title is in the top area
                if (e.getY() < 20) {  // Approximate height of the title area
                    chrome.toggleGitPanel();
                }
            }
        });

        // Tabbed pane
        tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // 1) Commit tab
        JPanel commitTab = buildCommitTab();
        tabbedPane.addTab("Commit", commitTab);

        // 2) Log tab (moved into GitLogPanel)
        gitLogPanel = new GitLogPanel(chrome, contextManager);
        tabbedPane.addTab("Log", gitLogPanel);
    }

    /**
     * Updates repository data in the UI
     */
    public void updateRepo() {
        SwingUtilities.invokeLater(() -> {
            gitLogPanel.update();
        });
    }

    /**
     * Builds the Commit tab.
     */
    private JPanel buildCommitTab() {
        JPanel commitTab = new JPanel(new BorderLayout());

        // Table for uncommitted files
        DefaultTableModel model = new DefaultTableModel(new Object[]{"Filename", "Path"}, 0) {
            @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        uncommittedFilesTable = new JTable(model);
        uncommittedFilesTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                                   boolean isSelected, boolean hasFocus,
                                                                   int row, int column) {
                var cell = (javax.swing.table.DefaultTableCellRenderer) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String filename = (String) table.getModel().getValueAt(row, 0);
                String path = (String) table.getModel().getValueAt(row, 1);
                String fullPath = path.isEmpty() ? filename : path + "/" + filename;
                String status = fileStatusMap.get(fullPath);
                if (!isSelected) {
                    if ("new".equals(status)) {
                        cell.setForeground(java.awt.Color.GREEN);
                    } else if ("deleted".equals(status)) {
                        cell.setForeground(java.awt.Color.RED);
                    } else if ("modified".equals(status)) {
                        cell.setForeground(java.awt.Color.BLUE);
                    } else {
                        cell.setForeground(java.awt.Color.BLACK);
                    }
                } else {
                    cell.setForeground(table.getSelectionForeground());
                }
                return cell;
            }
        });
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Add double-click handler to show diff for uncommitted files
        uncommittedFilesTable.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    int row = uncommittedFilesTable.rowAtPoint(e.getPoint());
                    if (row >= 0)
                    {
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                        viewDiffForUncommittedRow(row);
                    }
                }
            }
        });

        // Popup menu for uncommitted files
        var uncommittedContextMenu = new JPopupMenu();
        uncommittedFilesTable.setComponentPopupMenu(uncommittedContextMenu);

        var captureDiffItem = new JMenuItem("Capture Diff");
        uncommittedContextMenu.add(captureDiffItem);
        var viewDiffItem = new JMenuItem("View Diff");
        uncommittedContextMenu.add(viewDiffItem);
        var editFileItem = new JMenuItem("Edit File(s)");
        uncommittedContextMenu.add(editFileItem);

        // When the menu appears, select the row under the cursor so the right-click target is highlighted
        uncommittedContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
        {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
            {
                SwingUtilities.invokeLater(() -> {
                    var point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, uncommittedFilesTable);
                    int row = uncommittedFilesTable.rowAtPoint(point);
                    if (row >= 0 && !uncommittedFilesTable.isRowSelected(row))
                    {
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                    }
                    // Update menu item states when popup becomes visible
                    updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem);
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        // Hook up "Show Diff" action
        viewDiffItem.addActionListener(e -> {
            int row = uncommittedFilesTable.getSelectedRow();
            if (row >= 0) {
                viewDiffForUncommittedRow(row);
            }
        });

        // Hook up "Capture Diff" action
        captureDiffItem.addActionListener(e -> {
            captureUncommittedDiff();
        });

        // Hook up "Edit File" action
        editFileItem.addActionListener(e -> {
            int row = uncommittedFilesTable.getSelectedRow();
            if (row >= 0) {
                String filename = (String) uncommittedFilesTable.getValueAt(row, 0);
                String path = (String) uncommittedFilesTable.getValueAt(row, 1);
                String filePath = path.isEmpty() ? filename : path + "/" + filename;
                editFile(filePath);
            }
        });
        
        // Update context menu item states based on selection
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateUncommittedContextMenuState(captureDiffItem, viewDiffItem, editFileItem);
            }
        });

        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        commitTab.add(uncommittedScrollPane, BorderLayout.CENTER);

        // Commit message + buttons at bottom
        JPanel commitBottomPanel = new JPanel(new BorderLayout());
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(new JLabel("Commit/Stash Description:"), BorderLayout.NORTH);

        commitMessageArea = new RSyntaxTextArea(2, 50);
        commitMessageArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        commitMessageArea.setHighlightCurrentLine(false);
        messagePanel.add(new JScrollPane(commitMessageArea), BorderLayout.CENTER);

        commitBottomPanel.add(messagePanel, BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // "Suggest Message" button
        suggestMessageButton = new JButton("Suggest Message");
        suggestMessageButton.setToolTipText("Suggest a commit message for the selected files");
        suggestMessageButton.setEnabled(false);
        suggestMessageButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
            contextManager.submitBackgroundTask("Suggesting commit message", () -> {
                try {
                    var diff = selectedFiles.isEmpty()
                            ? getRepo().diff()
                            : getRepo().diffFiles(selectedFiles);
                    if (diff.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            chrome.actionOutput("No changes to commit");
                            chrome.enableUserActionButtons();
                        });
                        return null;
                    }
                    // Trigger LLM-based commit message generation
                    contextManager.inferCommitMessageAsync(diff);
                    SwingUtilities.invokeLater(chrome::enableUserActionButtons);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error suggesting commit message: " + ex.getMessage());
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });
        buttonPanel.add(suggestMessageButton);

        // "Stash" button
        stashButton = new JButton("Stash All");
        stashButton.setToolTipText("Save your changes to the stash");
        stashButton.setEnabled(false);
        stashButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            String message = commitMessageArea.getText().trim();
            if (message.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            // Filter out comment lines
            String stashDescription = Arrays.stream(message.split("\n"))
                    .filter(line -> !line.trim().startsWith("#"))
                    .collect(Collectors.joining("\n"))
                    .trim();
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();

            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        // If no files selected, stash all changes
                        getRepo().createStash(stashDescription.isEmpty() ? "Stash created by Brokk" : stashDescription);
                    } else {
                        // Create a partial stash with only the selected files
                        getRepo().createPartialStash(
                            stashDescription.isEmpty() ? "Partial stash created by Brokk" : stashDescription, 
                            selectedFiles);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (selectedFiles.isEmpty()) {
                            chrome.systemOutput("All changes stashed successfully");
                        } else {
                            String fileList = selectedFiles.size() <= 3
                                    ? selectedFiles.stream().map(Object::toString).collect(Collectors.joining(", "))
                                    : selectedFiles.size() + " files";
                            chrome.systemOutput("Stashed " + fileList);
                        }
                        commitMessageArea.setText("");
                        updateCommitPanel();
                        gitLogPanel.update(); // Update to show new stash in the virtual "stashes" branch
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error stashing changes: " + ex.getMessage());
                        chrome.enableUserActionButtons();
                    });
                }
            });
        });
        buttonPanel.add(stashButton);

        // "Commit" button
        commitButton = new JButton("Commit All");
        commitButton.setToolTipText("Commit files with the message");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
            String msg = commitMessageArea.getText().trim();
            if (msg.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        var allDirtyFiles = getRepo().getModifiedFiles();
                        getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        getRepo().commitFiles(selectedFiles, msg);
                    }
                    SwingUtilities.invokeLater(() -> {
                        try {
                            String shortHash = getRepo().getCurrentCommitId().substring(0, 7);
                            // show first line of commit
                            String firstLine = msg.contains("\n")
                                    ? msg.substring(0, msg.indexOf('\n'))
                                    : msg;
                            chrome.systemOutput("Committed " + shortHash + ": " + firstLine);
                        } catch (Exception ex) {
                            chrome.systemOutput("Changes committed successfully");
                        }
                        commitMessageArea.setText("");
                        updateCommitPanel();
                        // The GitLogPanel can refresh branches/commits:
                        gitLogPanel.update();
                    // Select the newly checked out branch in the log panel
                    gitLogPanel.selectCurrentBranch();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        chrome.actionOutput("Error committing files: " + ex.getMessage());
                        chrome.enableUserActionButtons();
                    });
                }
            });
        });
        buttonPanel.add(commitButton);

        // Commit message area updates
        commitMessageArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCommitButtonState(); }
            private void updateCommitButtonState() {
                String text = commitMessageArea.getText().trim();
                boolean hasNonCommentText = Arrays.stream(text.split("\n"))
                        .anyMatch(line -> !line.trim().isEmpty() && !line.trim().startsWith("#"));
                boolean enable = hasNonCommentText && suggestMessageButton.isEnabled();
                commitButton.setEnabled(enable);
                stashButton.setEnabled(enable);
            }
        });

        // Listen for selection changes to update commit button text
        uncommittedFilesTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateCommitButtonText();
        });

        commitBottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        commitTab.add(commitBottomPanel, BorderLayout.SOUTH);

        return commitTab;
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            logger.error("getRepo() returned null - no Git repository available");
        }
        return (GitRepo) repo;
    }

    /**
     * Populates the uncommitted files table and enables/disables commit-related buttons.
     */
    public void updateCommitPanel()
    {
        logger.debug("Starting updateCommitPanel");
        // Store currently selected rows before updating
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        List<String> selectedFiles = new ArrayList<>();

        // Store the filenames of selected rows to restore selection later
        for (int row : selectedRows) {
            String filename = (String) uncommittedFilesTable.getValueAt(row, 0);
            String path = (String) uncommittedFilesTable.getValueAt(row, 1);
            String fullPath = path.isEmpty() ? filename : path + "/" + filename;
            selectedFiles.add(fullPath);
        }
        logger.debug("Saved {} selected files before refresh", selectedFiles.size());

        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            logger.debug("Background task for uncommitted files started");
            try {
                logger.debug("Calling getRepo().getModifiedFiles()");
                var uncommittedFiles = getRepo().getModifiedFiles();
                logger.debug("Got {} modified files", uncommittedFiles.size());
                var gitStatus = getRepo().getGit().status().call();
                var addedSet = gitStatus.getAdded();
                var removedSet = new java.util.HashSet<>(gitStatus.getRemoved());
                removedSet.addAll(gitStatus.getMissing());
                SwingUtilities.invokeLater(() -> {
                    logger.debug("In Swing thread updating uncommitted files table");
                    fileStatusMap.clear();
                    for (var file : uncommittedFiles) {
                        String fullPath = file.getParent().isEmpty() ? file.getFileName() : file.getParent() + "/" + file.getFileName();
                        if (addedSet.contains(fullPath)) {
                            fileStatusMap.put(fullPath, "new");
                        } else if (removedSet.contains(fullPath)) {
                            fileStatusMap.put(fullPath, "deleted");
                        } else {
                            fileStatusMap.put(fullPath, "modified");
                        }
                    }
                    
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFiles.isEmpty()) {
                        logger.debug("No modified files found");
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        logger.debug("Found {} modified files to display", uncommittedFiles.size());
                        // Track row indices for files that were previously selected
                        List<Integer> rowsToSelect = new ArrayList<>();

                        for (int i = 0; i < uncommittedFiles.size(); i++) {
                            var file = uncommittedFiles.get(i);
                            model.addRow(new Object[]{file.getFileName(), file.getParent()});
                            logger.debug("Added file to table: {}/{}", file.getParent(), file.getFileName());

                            // Check if this file was previously selected
                            String fullPath = file.getParent().isEmpty() ?
                                file.getFileName() : file.getParent() + "/" + file.getFileName();
                            if (selectedFiles.contains(fullPath)) {
                                rowsToSelect.add(i);
                            }
                        }

                        // Restore selection if any previously selected files are still present
                        if (!rowsToSelect.isEmpty()) {
                            for (int row : rowsToSelect) {
                                uncommittedFilesTable.addRowSelectionInterval(row, row);
                            }
                            logger.debug("Restored selection for {} rows", rowsToSelect.size());
                        }

                        suggestMessageButton.setEnabled(true);

                        var text = commitMessageArea.getText().trim();
                        var hasNonCommentText = Arrays.stream(text.split("\n"))
                                                  .anyMatch(line -> !line.trim().isEmpty()
                                                                    && !line.trim().startsWith("#"));
                        commitButton.setEnabled(hasNonCommentText);
                        stashButton.setEnabled(hasNonCommentText);
                    }
                    updateCommitButtonText();
                });
            } catch (Exception e) {
                logger.error("Error fetching uncommitted files", e);
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Disabling commit buttons due to error");
                    suggestMessageButton.setEnabled(false);
                    commitButton.setEnabled(false);
                });
            }
            return null;
        });
    }

    /**
     * Adjusts the commit/stash button label/text depending on selected vs all.
     */
    private void updateCommitButtonText() {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        if (selectedRows.length > 0) {
            commitButton.setText("Commit Selected");
            commitButton.setToolTipText("Commit the selected files with the message");
            stashButton.setText("Stash Selected");
            stashButton.setToolTipText("Save your selected changes to the stash");
        } else {
            commitButton.setText("Commit All");
            commitButton.setToolTipText("Commit all files with the message");
            stashButton.setText("Stash All");
            stashButton.setToolTipText("Save all your changes to the stash");
        }
    }
    
    /**
     * Updates the enabled state of context menu items for the uncommitted files table
     * based on the current selection.
     */
    private void updateUncommittedContextMenuState(JMenuItem captureDiffItem, JMenuItem viewDiffItem, JMenuItem editFileItem) {
        int[] selectedRows = uncommittedFilesTable.getSelectedRows();
        int selectionCount = selectedRows.length;

        if (selectionCount == 0) {
            // No files selected: disable everything
            captureDiffItem.setEnabled(false);
            captureDiffItem.setToolTipText("Select file(s) to capture diff");
            viewDiffItem.setEnabled(false);
            viewDiffItem.setToolTipText("Select a file to view its diff");
            editFileItem.setEnabled(false);
            editFileItem.setToolTipText("Select a file to edit");
        } else if (selectionCount == 1) {
            // Exactly one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected file to context");
            viewDiffItem.setEnabled(true);
            viewDiffItem.setToolTipText("View diff of selected file");

            // Conditionally enable Edit File
            int row = selectedRows[0];
            String filename = (String) uncommittedFilesTable.getValueAt(row, 0);
            String path = (String) uncommittedFilesTable.getValueAt(row, 1);
            String filePath = path.isEmpty() ? filename : path + "/" + filename;
            var file = contextManager.toFile(filePath);
            boolean alreadyEditable = contextManager.getEditableFiles().contains(file);

            editFileItem.setEnabled(!alreadyEditable);
            editFileItem.setToolTipText(alreadyEditable ?
                    "File is already in editable context" :
                    "Edit this file");
        } else {
            // More than one file selected
            captureDiffItem.setEnabled(true);
            captureDiffItem.setToolTipText("Capture diff of selected files to context");
            viewDiffItem.setEnabled(false); // Disable View Diff for multiple files
            viewDiffItem.setToolTipText("Select a single file to view its diff");
            editFileItem.setEnabled(false); // Disable Edit File for multiple files
            editFileItem.setToolTipText("Select a single file to edit");
        }
    }

    /**
     * Helper to get a list of selected files from the uncommittedFilesTable.
     */
    private List<ProjectFile> getSelectedFilesFromTable()
    {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var selectedRows = uncommittedFilesTable.getSelectedRows();
        var files = new ArrayList<ProjectFile>();

        for (var row : selectedRows) {
            var filename = (String) model.getValueAt(row, 0);
            var path     = (String) model.getValueAt(row, 1);
            // Combine them to get the relative path
            var combined = path.isEmpty() ? filename : path + "/" + filename;
            files.add(new ProjectFile(contextManager.getRoot(), combined));
        }
        return files;
    }

    /**
     * Sets the text in the commit message area (used by LLM suggestions).
     */
    public void setCommitMessageText(String message) {
        commitMessageArea.setText(message);
    }

    /**
     * Creates a new tab showing the history of a specific file
     */
    public void addFileHistoryTab(ProjectFile file) {
        String filePath = file.toString();
        
        // If we already have a tab for this file, just select it
        if (fileHistoryTables.containsKey(filePath)) {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getTitleAt(i).equals(getFileTabName(filePath))) {
                    tabbedPane.setSelectedIndex(i);
                    return;
                }
            }
        }

        // Create a new tab with the file's name
        JPanel fileHistoryPanel = new JPanel(new BorderLayout());

        // Create a history table similar to the commits table but with different column proportions
        DefaultTableModel fileHistoryModel = new DefaultTableModel(
            new Object[]{"Message", "Author", "Date", "ID"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };

        JTable fileHistoryTable = new JTable(fileHistoryModel);
        fileHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileHistoryTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        fileHistoryTable.setRowHeight(18);

        // Hide ID column
        fileHistoryTable.getColumnModel().getColumn(3).setMinWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setMaxWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setWidth(0);

        // Add a context menu with same options as Changes tree
        JPopupMenu historyContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyContextMenu);
        } else {
            // Register this popup menu later when the theme manager is available
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(historyContextMenu);
                }
            });
        }
        JMenuItem addToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem compareWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem viewInLogItem = new JMenuItem("View in Log");
        JMenuItem editFileItem = new JMenuItem("Edit File");

        historyContextMenu.add(addToContextItem);
        historyContextMenu.add(compareWithLocalItem);
        historyContextMenu.add(viewInLogItem);
        historyContextMenu.add(editFileItem);

        // Make sure right-clicking selects row under cursor first
        historyContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, fileHistoryTable);
                    int row = fileHistoryTable.rowAtPoint(point);
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        fileHistoryTable.setComponentPopupMenu(historyContextMenu);
        
        // Add double-click listener to show diff
        fileHistoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileHistoryTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                        String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                        showFileHistoryDiff(commitId, filePath);
                    }
                }
            }
        });

        // Add listeners to context menu items
        addToContextItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                addFileChangeToContext(commitId, filePath);
            }
        });

        compareWithLocalItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                compareFileWithLocal(commitId, filePath);
            }
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e -> editFile(filePath));

        fileHistoryPanel.add(new JScrollPane(fileHistoryTable), BorderLayout.CENTER);

        // Add to tab pane with a filename title and close button
        String tabName = getFileTabName(filePath);

        // Create a custom tab component with close button
        JPanel tabComponent = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabComponent.setOpaque(false);
        JLabel titleLabel = new JLabel(tabName);
        titleLabel.setOpaque(false);

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        closeButton.setPreferredSize(new Dimension(24, 24));
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setToolTipText("Close");

        // Add visual feedback on mouse events
        closeButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                closeButton.setForeground(Color.RED);
                closeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                closeButton.setForeground(null); // Reset to default color
                closeButton.setCursor(Cursor.getDefaultCursor());
            }
        });

        closeButton.addActionListener(e -> {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                if (tabbedPane.getComponentAt(i) == fileHistoryPanel) {
                    tabbedPane.remove(i);
                    fileHistoryTables.remove(filePath);
                    break;
                }
            }
        });

        tabComponent.add(titleLabel);
        tabComponent.add(closeButton);

        tabbedPane.addTab(tabName, fileHistoryPanel);
        int tabIndex = tabbedPane.indexOfComponent(fileHistoryPanel);
        tabbedPane.setTabComponentAt(tabIndex, tabComponent);
        tabbedPane.setSelectedComponent(fileHistoryPanel);
        fileHistoryTables.put(filePath, fileHistoryTable);

        // Load the file history
        loadFileHistory(file, fileHistoryModel, fileHistoryTable);
    }
    
    private String getFileTabName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }
    
    /**
     * Switches to the Log tab and highlights the specified commit.
     */
    private void showCommitInLogTab(String commitId) {
        // Switch to Log tab
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Log")) {
                tabbedPane.setSelectedIndex(i);
                break;
            }
        }
        
        // Find and select the commit in gitLogPanel
        gitLogPanel.selectCommitById(commitId);
    }
    
    private void loadFileHistory(ProjectFile file, DefaultTableModel model, JTable table) {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var history = getRepo().getFileHistory(file);
                SwingUtilities.invokeLater(() -> {
                    model.setRowCount(0);
                    if (history.isEmpty()) {
                        model.addRow(new Object[]{"No history found", "", "", ""});
                        return;
                    }

                    var today = java.time.LocalDate.now();
                    for (var commit : history) {
                        var formattedDate = formatCommitDate(commit.date(), today);
                        model.addRow(new Object[]{
                            commit.message(),
                            commit.author(),
                            formattedDate,
                            commit.id()
                        });
                    }
                    
                    // Now that data is loaded, adjust column widths
                    TableUtils.fitColumnWidth(table, 1); // author column
                    TableUtils.fitColumnWidth(table, 2); // date column
                });
            } catch (Exception e) {
                logger.error("Error loading file history for: {}", file, e);
                SwingUtilities.invokeLater(() -> {
                    model.setRowCount(0);
                    model.addRow(new Object[]{
                        "Error loading history: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }
    
    private void addFileChangeToContext(String commitId, String filePath)
    {
        contextManager.submitContextTask("Adding file change to context", () -> {
            try {
                var repoFile = new ProjectFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + filePath);
                    return;
                }

                var shortHash  = commitId.substring(0, 7);
                var fileName   = getFileTabName(filePath);
                var description= "git %s (single file)".formatted(shortHash);

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for " + fileName + " to context");
            } catch (Exception e) {
                logger.error("Error adding file change to context", e);
                chrome.toolErrorRaw("Error adding file change to context: " + e.getMessage());
            }
        });
    }
    
    private void compareFileWithLocal(String commitId, String filePath) {
        contextManager.submitUserTask("Comparing file with local", () -> {
            try {
                var repoFile = new ProjectFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No differences found between " + filePath + " and local working copy");
                    return;
                }

                var shortHash = commitId.substring(0, 7);
                var fileName = getFileTabName(filePath);
                var description = "git local vs " + shortHash + " [" + fileName + "]";

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added comparison with local for " + fileName + " to context");
            } catch (Exception e) {
                logger.error("Error comparing file with local", e);
                chrome.toolErrorRaw("Error comparing file with local: " + e.getMessage());
            }
        });
    }
    
    private void editFile(String filePath) {
        List<ProjectFile> files = new ArrayList<>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }
    
    /**
     * Shows the diff for a file at a specific commit from file history.
     */
    private void showFileHistoryDiff(String commitId, String filePath) {
        ProjectFile file = new ProjectFile(contextManager.getRoot(), filePath);
        DiffPanel diffPanel = new DiffPanel(contextManager);

        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        String dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";

        diffPanel.showFileDiff(commitId, file);
        diffPanel.showInDialog(this, dialogTitle);
    }

    /**
     * Captures the diff of selected uncommitted files and adds it to the context.
     */
    private void captureUncommittedDiff() {
        List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
        if (selectedFiles.isEmpty()) {
            chrome.systemOutput("No files selected to capture diff");
            return;
        }

        contextManager.submitContextTask("Capturing uncommitted diff", () -> {
            try {
                String diff = getRepo().diffFiles(selectedFiles);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No uncommitted changes found for selected files");
                    return;
                }

                String description = "Diff of %s".formatted(selectedFiles.stream().map(ProjectFile::getFileName).collect(Collectors.joining(", ")));
                ContextFragment.StringFragment fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added uncommitted diff for " + selectedFiles.size() + " file(s) to context");
            } catch (Exception ex) {
                logger.error("Error capturing uncommitted diff", ex);
                chrome.toolErrorRaw("Error capturing uncommitted diff: " + ex.getMessage());
            }
        });
    }

    /**
     * Shows the diff for an uncommitted file.
     */
    private void viewDiffForUncommittedRow(int row)
    {
        var filename = (String) uncommittedFilesTable.getValueAt(row, 0);
        var path = (String) uncommittedFilesTable.getValueAt(row, 1);
        var filePath = path.isEmpty() ? filename : path + "/" + filename;
        showUncommittedFileDiff(filePath);
    }

    /**
     * Shows the diff for an uncommitted file by comparing HEAD to what's on disk.
     */
    private void showUncommittedFileDiff(String filePath) {
        var file = new ProjectFile(contextManager.getRoot(), filePath);
        var diffPanel = new DiffPanel(contextManager);

        String dialogTitle = "Uncommitted Changes: " + file.getFileName();

        // Use the unified compare-with-local approach for HEAD vs. disk
        diffPanel.showCompareWithLocal("HEAD", file, /*useParent=*/ false);
        diffPanel.showInDialog(this, dialogTitle);
    }
    

    protected String formatCommitDate(Date date, java.time.LocalDate today) {
        try {
            java.time.LocalDate commitDate = date.toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();

            String timeStr = new java.text.SimpleDateFormat("HH:mm:ss").format(date);

            if (commitDate.equals(today)) {
                // If it's today's date, just show the time with "today"
                return "Today " + timeStr;
            } else if (commitDate.equals(today.minusDays(1))) {
                // If it's yesterday
                return "Yesterday " + timeStr;
            } else if (commitDate.isAfter(today.minusDays(7))) {
                // If within the last week, show day of week
                String dayName = commitDate.getDayOfWeek().toString();
                dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1).toLowerCase();
                return dayName + " " + timeStr;
            }

            // Otherwise, show the standard date format
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
        } catch (Exception e) {
            logger.debug("Could not format date: {}", date, e);
            return date.toString();
        }
    }
}
