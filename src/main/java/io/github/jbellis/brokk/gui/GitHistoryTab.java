package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A panel representing a single tab showing the Git history for a specific file.
 */
public class GitHistoryTab extends JPanel {

    private static final Logger logger = LogManager.getLogger(GitHistoryTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel; // Parent panel to call back for Log tab interaction
    private final ProjectFile file;
    private JTable fileHistoryTable;
    private DefaultTableModel fileHistoryModel;

    public GitHistoryTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel, ProjectFile file) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.gitPanel = gitPanel;
        this.file = file;
        buildHistoryTabUI();
        loadFileHistory();
    }

    public String getFilePath() {
        return file.toString();
    }

    private void buildHistoryTabUI() {
        // Create a history table similar to the commits table but with different column proportions
        fileHistoryModel = new DefaultTableModel(
                new Object[]{"Message", "Author", "Date", "ID"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };

        fileHistoryTable = new JTable(fileHistoryModel);
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
        JMenuItem addToContextItem = new JMenuItem("Capture Diff");
        JMenuItem compareWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem viewFileAtRevisionItem = new JMenuItem("View File at Revision"); // New item
        JMenuItem viewDiffItem = new JMenuItem("View Diff");
        JMenuItem viewInLogItem = new JMenuItem("View in Log");
        JMenuItem editFileItem = new JMenuItem("Edit File");

        historyContextMenu.add(addToContextItem);
        historyContextMenu.add(editFileItem);
        historyContextMenu.addSeparator();
        historyContextMenu.add(viewInLogItem);
        historyContextMenu.addSeparator();
        historyContextMenu.add(viewFileAtRevisionItem);
        historyContextMenu.add(viewDiffItem);
        historyContextMenu.add(compareWithLocalItem);

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

        // Add selection listener to enable/disable context menu items
        fileHistoryTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRowCount = fileHistoryTable.getSelectedRowCount();
                boolean singleSelected = selectedRowCount == 1;

                addToContextItem.setEnabled(singleSelected);
                compareWithLocalItem.setEnabled(singleSelected);
                viewFileAtRevisionItem.setEnabled(singleSelected); // Enable/disable View File at Revision
                viewDiffItem.setEnabled(singleSelected);
                viewInLogItem.setEnabled(singleSelected);

                // Enable Edit File only if single row is selected and file isn't already editable
                if (singleSelected) {
                    var selectedFile = contextManager.toFile(getFilePath());
                    editFileItem.setEnabled(!contextManager.getEditableFiles().contains(selectedFile));
                } else {
                    editFileItem.setEnabled(false);
                }
            }
        });

        // Add double-click listener to show diff
        fileHistoryTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileHistoryTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        fileHistoryTable.setRowSelectionInterval(row, row);
                        String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                        showFileHistoryDiff(commitId, getFilePath());
                    }
                }
            }
        });

        // Add listeners to context menu items
        addToContextItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                addFileChangeToContext(commitId, getFilePath());
            }
        });

        // Hook up "View File at Revision" action
        viewFileAtRevisionItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                viewFileAtRevision(commitId, getFilePath());
            }
        });

        // Hook up "View Diff" action
        viewDiffItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                showFileHistoryDiff(commitId, getFilePath());
            }
        });

        compareWithLocalItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                compareFileWithLocal(commitId, getFilePath());
            }
        });

        viewInLogItem.addActionListener(e -> {
            int row = fileHistoryTable.getSelectedRow();
            if (row >= 0) {
                String commitId = (String) fileHistoryModel.getValueAt(row, 3);
                showCommitInLogTab(commitId);
            }
        });

        editFileItem.addActionListener(e -> editFile(getFilePath()));

        add(new JScrollPane(fileHistoryTable), BorderLayout.CENTER);
    }


    private void loadFileHistory() {
        contextManager.submitBackgroundTask("Loading file history: " + file, () -> {
            try {
                var repo = getRepo();
                if (repo == null) {
                    SwingUtilities.invokeLater(() -> {
                        fileHistoryModel.setRowCount(0);
                        fileHistoryModel.addRow(new Object[]{"Git repository not available", "", "", ""});
                    });
                    return null;
                }
                var history = repo.getFileHistory(file);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    if (history.isEmpty()) {
                        fileHistoryModel.addRow(new Object[]{"No history found", "", "", ""});
                        return;
                    }

                    var today = java.time.LocalDate.now();
                    for (var commit : history) {
                        var formattedDate = GitLogTab.formatCommitDate(commit.date(), today);
                        fileHistoryModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id()
                        });
                    }

                    // Now that data is loaded, adjust column widths
                    TableUtils.fitColumnWidth(fileHistoryTable, 1); // author column
                    TableUtils.fitColumnWidth(fileHistoryTable, 2); // date column
                });
            } catch (Exception e) {
                logger.error("Error loading file history for: {}", file, e);
                SwingUtilities.invokeLater(() -> {
                    fileHistoryModel.setRowCount(0);
                    fileHistoryModel.addRow(new Object[]{
                            "Error loading history: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }

    private void addFileChangeToContext(String commitId, String filePath)
    {
        var repo = getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        contextManager.submitContextTask("Adding file change to context", () -> {
            try {
                var repoFile = new ProjectFile(contextManager.getRoot(), filePath);
                var diff = repo.showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + filePath);
                    return;
                }

                var shortHash  = commitId.substring(0, 7);
                var fileName   = gitPanel.getFileTabName(filePath);
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
        var repo = getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        contextManager.submitUserTask("Comparing file with local", () -> {
            try {
                var repoFile = new ProjectFile(contextManager.getRoot(), filePath);
                var diff = repo.showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No differences found between " + filePath + " and local working copy");
                    return;
                }

                var shortHash = commitId.substring(0, 7);
                var fileName = gitPanel.getFileTabName(filePath);
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
        var repo = getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        ProjectFile repoFile = new ProjectFile(contextManager.getRoot(), filePath);
        DiffPanel diffPanel = new DiffPanel(contextManager);

        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        String dialogTitle = "Diff: " + repoFile.getFileName() + " (" + shortCommitId + ")";

        diffPanel.showFileDiff(commitId, repoFile);
        diffPanel.showInDialog(this, dialogTitle);
    }

    /**
     * Shows the content of a file at a specific revision.
     */
    private void viewFileAtRevision(String commitId, String filePath) {
        var repo = getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        contextManager.submitUserTask("Viewing file at revision", () -> {
            try {
                var repoFile = new ProjectFile(contextManager.getRoot(), filePath);
                var content = repo.getFileContent(commitId, repoFile);

                if (content.isEmpty()) {
                    chrome.systemOutput("File not found in this revision or is empty.");
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    String shortHash = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
                    String title = String.format("%s at %s", repoFile.getFileName(), shortHash);

                    var fragment = new ContextFragment.StringFragment(content, title);
                    // Assuming we want Java syntax highlighting as before, adjust if needed
                    chrome.openFragmentPreview(fragment, SyntaxConstants.SYNTAX_STYLE_JAVA);
                });
            } catch (Exception ex) {
                logger.error("Error viewing file at revision", ex);
                chrome.toolErrorRaw("Error viewing file at revision: " + ex.getMessage());
            }
        });
    }

    /**
     * Switches to the Log tab and highlights the specified commit.
     */
    private void showCommitInLogTab(String commitId) {
        gitPanel.showCommitInLogTab(commitId);
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
}
