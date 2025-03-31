package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Panel for the "Commit" tab in the Git Panel.
 * Handles uncommitted changes, staging, committing, and stashing.
 */
public class GitCommitTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitCommitTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel; // Reference to parent GitPanel

    // Commit tab UI
    private JTable uncommittedFilesTable;
    private JButton suggestMessageButton;
    private RSyntaxTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;
    private Map<String, String> fileStatusMap = new HashMap<>();

    public GitCommitTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel; // Store reference to parent
        buildCommitTabUI();
    }

    /**
     * Builds the Commit tab UI elements.
     */
    private void buildCommitTabUI() {
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
        add(uncommittedScrollPane, BorderLayout.CENTER);

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
                     logger.error("Error suggesting commit message:", ex);
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
                        gitPanel.updateLogTab(); // Update Log tab to show new stash
                        chrome.enableUserActionButtons();
                     });
                 } catch (Exception ex) {
                     logger.error("Error stashing changes:", ex);
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
                        gitPanel.updateLogTab(); // Update log tab
                        gitPanel.selectCurrentBranchInLogTab(); // Select the new commit
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    logger.error("Error committing files:", ex);
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
        add(commitBottomPanel, BorderLayout.SOUTH);
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            // Log error or handle appropriately if no repo is expected
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
        if (getRepo() == null) {
            logger.warn("Cannot update commit panel, GitRepo is null");
            suggestMessageButton.setEnabled(false);
            commitButton.setEnabled(false);
            stashButton.setEnabled(false);
            ((DefaultTableModel) uncommittedFilesTable.getModel()).setRowCount(0);
            return;
        }
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
                logger.error("Error fetching uncommitted files:", e);
                SwingUtilities.invokeLater(() -> {
                    logger.debug("Disabling commit buttons due to error");
                    suggestMessageButton.setEnabled(false);
                    commitButton.setEnabled(false);
                    stashButton.setEnabled(false); // Also disable stash on error
                    ((DefaultTableModel) uncommittedFilesTable.getModel()).setRowCount(0); // Clear table on error
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
     * Captures the diff of selected uncommitted files and adds it to the context.
     */
    private void captureUncommittedDiff() {
        List<ProjectFile> selectedFiles = getSelectedFilesFromTable();
        if (selectedFiles.isEmpty()) {
            chrome.systemOutput("No files selected to capture diff");
            return;
        }
        if (getRepo() == null) {
            chrome.toolError("Git repository not available.");
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
        if (getRepo() == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        var file = new ProjectFile(contextManager.getRoot(), filePath);
        var diffPanel = new DiffPanel(contextManager);

        String dialogTitle = "Uncommitted Changes: " + file.getFileName();

        // Use the unified compare-with-local approach for HEAD vs. disk
        diffPanel.showCompareWithLocal("HEAD", file, /*useParent=*/ false);
        diffPanel.showInDialog(this, dialogTitle);
    }

    /**
     * Edit the given file inside your IDE or editor plugin.
     */
    private void editFile(String filePath) {
        List<ProjectFile> files = new ArrayList<>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }
}
