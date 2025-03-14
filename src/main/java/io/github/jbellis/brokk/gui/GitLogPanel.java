package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.GitRepo.CommitInfo;
import io.github.jbellis.brokk.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Panel that contains the "Log" tab UI and related functionality:
 * - Branch tables (local & remote)
 * - Commits table
 * - Tree of changed files (per commit)
 * - Search functionality
 */
public class GitLogPanel extends JPanel {
    
    // Methods to expose to GitPanel for finding and selecting commits by ID

    private static final Logger logger = LogManager.getLogger(GitLogPanel.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    // Branches
    private JTable branchTable;
    private DefaultTableModel branchTableModel;
    private JTable remoteBranchTable;
    private DefaultTableModel remoteBranchTableModel;

    // Commits
    private JTable commitsTable;
    private DefaultTableModel commitsTableModel;
    private JButton pushButton;

    // Changes tree
    private JTree changesTree;
    private DefaultTreeModel changesTreeModel;
    private DefaultMutableTreeNode changesRootNode;

    // Search
    private JTextArea searchField;

    /**
     * Constructor. Builds and arranges the UI components for the Log tab.
     */
    public GitLogPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        buildLogTabUI();
    }

    /**
     * Creates and arranges all the "Log" tab sub-panels:
     * - Branches (local + remote)
     * - Commits
     * - Changed files tree
     * - Simple text search
     */
    private void buildLogTabUI() {
        // Main container uses GridBagLayout with approximate percentage widths
        JPanel logPanel = new JPanel(new GridBagLayout());
        add(logPanel, BorderLayout.CENTER);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weighty = 1.0;  // always fill vertically

        // ============ Branches Panel (left ~15%) ============

        JPanel branchesPanel = new JPanel(new BorderLayout());
        branchesPanel.setBorder(BorderFactory.createTitledBorder("Branches"));
        JPanel branchSplitPanel = new JPanel(new GridLayout(2, 1));

        // Local branches
        JPanel localBranchPanel = new JPanel(new BorderLayout());
        localBranchPanel.setBorder(BorderFactory.createTitledBorder("Local"));
        branchTableModel = new DefaultTableModel(new Object[]{"", "Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };
        branchTable = new JTable(branchTableModel);
        branchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        branchTable.setRowHeight(18);
        branchTable.getColumnModel().getColumn(0).setMaxWidth(20);
        branchTable.getColumnModel().getColumn(0).setMinWidth(20);
        branchTable.getColumnModel().getColumn(0).setPreferredWidth(20);
        localBranchPanel.add(new JScrollPane(branchTable), BorderLayout.CENTER);

        // Remote branches
        JPanel remoteBranchPanel = new JPanel(new BorderLayout());
        remoteBranchPanel.setBorder(BorderFactory.createTitledBorder("Remote"));
        remoteBranchTableModel = new DefaultTableModel(new Object[]{"Branch"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };
        remoteBranchTable = new JTable(remoteBranchTableModel);
        remoteBranchTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        remoteBranchTable.setRowHeight(18);
        remoteBranchPanel.add(new JScrollPane(remoteBranchTable), BorderLayout.CENTER);

        branchSplitPanel.add(localBranchPanel);
        branchSplitPanel.add(remoteBranchPanel);
        branchesPanel.add(branchSplitPanel, BorderLayout.CENTER);

        // "Refresh" button for branches
        JPanel branchButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshBranchesButton = new JButton("Refresh");
        refreshBranchesButton.addActionListener(e -> updateBranchList());
        branchButtonPanel.add(refreshBranchesButton);
        branchesPanel.add(branchButtonPanel, BorderLayout.SOUTH);

        // ============ Commits Panel (center ~50%) ============

        JPanel commitsPanel = new JPanel(new BorderLayout());
        commitsPanel.setBorder(BorderFactory.createTitledBorder("Commits"));
        commitsTableModel = new DefaultTableModel(new Object[]{"Message", "Author", "Date", "ID", "Unpushed"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                // 4th (hidden) column stores Boolean for "unpushed" status
                if (columnIndex == 4) return Boolean.class;
                return String.class;
            }
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        commitsTable = new JTable(commitsTableModel) {
            // Add tooltip to show full commit message
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row >= 0 && col == 0) { // First column contains commit message
                    return (String) getValueAt(row, col);
                }
                return super.getToolTipText(e);
            }
        };
        commitsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Set percentages for main columns (60%/20%/20%)
        int tableWidth = commitsTable.getWidth();
        commitsTable.getColumnModel().getColumn(0).setPreferredWidth((int)(tableWidth * 0.6)); // message
        commitsTable.getColumnModel().getColumn(1).setPreferredWidth((int)(tableWidth * 0.2)); // author
        commitsTable.getColumnModel().getColumn(2).setPreferredWidth((int)(tableWidth * 0.2)); // date
        
        // Hide id and unpushed columns
        commitsTable.getColumnModel().getColumn(3).setMinWidth(0);
        commitsTable.getColumnModel().getColumn(3).setMaxWidth(0);
        commitsTable.getColumnModel().getColumn(3).setWidth(0);
        commitsTable.getColumnModel().getColumn(4).setMinWidth(0);
        commitsTable.getColumnModel().getColumn(4).setMaxWidth(0);
        commitsTable.getColumnModel().getColumn(4).setWidth(0);

        // Highlight unpushed rows
        commitsTable.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
            ) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    Boolean unpushed = (Boolean) table.getModel().getValueAt(row, 4);
                    if (Boolean.TRUE.equals(unpushed)) {
                        c.setBackground(new Color(220, 255, 220));  // light green
                    } else {
                        c.setBackground(table.getBackground());
                    }
                }
                return c;
            }
        });

        // When a commit is selected, show changed files
        commitsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && commitsTable.getSelectedRow() != -1) {
                int[] selectedRows = commitsTable.getSelectedRows();
                if (selectedRows.length >= 1) {
                    updateChangesForCommits(selectedRows);
                }
            }
        });

        // Context menu on commits
        JPopupMenu commitsContextMenu = new JPopupMenu();
        JMenuItem addToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem softResetItem = new JMenuItem("Soft Reset to Here");
        JMenuItem revertCommitItem = new JMenuItem("Revert Commit");
        commitsContextMenu.add(addToContextItem);
        commitsContextMenu.add(softResetItem);
        commitsContextMenu.add(revertCommitItem);
        commitsTable.setComponentPopupMenu(commitsContextMenu);

        commitsContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, commitsTable);
                    int row = commitsTable.rowAtPoint(point);
                    if (row >= 0) {
                        if (!commitsTable.isRowSelected(row)) {
                            commitsTable.setRowSelectionInterval(row, row);
                        }
                    }
                    // Update menu item states based on selection
                    int[] sel = commitsTable.getSelectedRows();
                    softResetItem.setEnabled(sel.length == 1);
                });
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        addToContextItem.addActionListener(e -> {
            int[] selectedRows = commitsTable.getSelectedRows();
            if (selectedRows.length >= 1) {
                addCommitRangeToContext(selectedRows);
            }
        });

        softResetItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                String commitId = (String) commitsTableModel.getValueAt(row, 3);
                String commitMessage = (String) commitsTableModel.getValueAt(row, 0);
                String firstLine = commitMessage.contains("\n")
                        ? commitMessage.substring(0, commitMessage.indexOf('\n'))
                        : commitMessage;
                softResetToCommit(commitId, firstLine);
            }
        });

        revertCommitItem.addActionListener(e -> {
            int row = commitsTable.getSelectedRow();
            if (row != -1) {
                String commitId = (String) commitsTableModel.getValueAt(row, 3);
                revertCommit(commitId);
            }
        });
        

        commitsPanel.add(new JScrollPane(commitsTable), BorderLayout.CENTER);

        // Buttons below commits table
        JPanel commitsPanelButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        pushButton = new JButton("Push");
        pushButton.setToolTipText("Push commits to remote repository");
        pushButton.setEnabled(false);
        pushButton.addActionListener(e -> pushBranch());
        commitsPanelButtons.add(pushButton);
        commitsPanel.add(commitsPanelButtons, BorderLayout.SOUTH);

        // ============ Changes Panel (right ~25%) ============

        JPanel changesPanel = new JPanel(new BorderLayout());
        changesPanel.setBorder(BorderFactory.createTitledBorder("Changes"));
        changesRootNode = new DefaultMutableTreeNode("Changes");
        changesTreeModel = new DefaultTreeModel(changesRootNode);
        changesTree = new JTree(changesTreeModel);
        changesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        changesPanel.add(new JScrollPane(changesTree), BorderLayout.CENTER);

        // Context menu on changes tree
        JPopupMenu changesContextMenu = new JPopupMenu();
        JMenuItem addFileToContextItem = new JMenuItem("Add Changes to Context");
        JMenuItem compareFileWithLocalItem = new JMenuItem("Compare with Local");
        JMenuItem viewHistoryItem = new JMenuItem("View History");
        JMenuItem editFileItem = new JMenuItem("Edit File");
        changesContextMenu.add(addFileToContextItem);
        changesContextMenu.add(compareFileWithLocalItem);
        changesContextMenu.add(viewHistoryItem);
        changesContextMenu.add(editFileItem);
        changesTree.setComponentPopupMenu(changesContextMenu);
        
        // Add double-click handler to show diff
        changesTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = changesTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node != changesRootNode && node.getParent() != changesRootNode) {
                            // This is a file node (not a directory or root)
                            String fileName = node.getUserObject().toString();
                            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
                            String dirPath = parentNode.getUserObject().toString();
                            String filePath = dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;
                            
                            int[] selRows = commitsTable.getSelectedRows();
                            if (selRows.length == 1) {
                                String commitId = (String) commitsTableModel.getValueAt(selRows[0], 3);
                                showFileDiff(commitId, filePath);
                            }
                        }
                    }
                }
            }
        });

        changesContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, changesTree);
                    int row = changesTree.getRowForLocation(point.x, point.y);
                    if (row >= 0) {
                        if (!changesTree.isRowSelected(row)) {
                            changesTree.setSelectionRow(row);
                        }
                    }
                    TreePath[] paths = changesTree.getSelectionPaths();
                    boolean hasFileSelection = paths != null && paths.length > 0 && hasFileNodesSelected(paths);
                    int[] selRows = commitsTable.getSelectedRows();
                    boolean isSingleCommit = (selRows.length == 1);

                    addFileToContextItem.setEnabled(hasFileSelection);
                    compareFileWithLocalItem.setEnabled(hasFileSelection && isSingleCommit);
                    viewHistoryItem.setEnabled(hasFileSelection);
                    editFileItem.setEnabled(hasFileSelection);
                });
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        addFileToContextItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                List<String> selectedFiles = getSelectedFilePaths(paths);
                if (!selectedFiles.isEmpty()) {
                    int[] selRows = commitsTable.getSelectedRows();
                    if (selRows.length >= 1) {
                        addFilesChangeToContext(selRows, selectedFiles);
                    }
                }
            }
        });

        compareFileWithLocalItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                List<String> selectedFiles = getSelectedFilePaths(paths);
                if (!selectedFiles.isEmpty()) {
                    int[] selRows = commitsTable.getSelectedRows();
                    if (selRows.length == 1) {
                        String commitId = (String) commitsTableModel.getValueAt(selRows[0], 3);
                        compareFilesWithLocal(commitId, selectedFiles);
                    }
                }
            }
        });

        viewHistoryItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                List<String> selectedFiles = getSelectedFilePaths(paths);
                for (String filePath : selectedFiles) {
                    viewFileHistory(filePath);
                }
            }
        });

        editFileItem.addActionListener(e -> {
            TreePath[] paths = changesTree.getSelectionPaths();
            if (paths != null && paths.length > 0) {
                List<String> selectedFiles = getSelectedFilePaths(paths);
                for (String filePath : selectedFiles) {
                    editFile(filePath);
                }
            }
        });

        // ============ Search Panel (rightmost ~10%) ============

        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBorder(BorderFactory.createTitledBorder("Search"));
        searchField = new JTextArea(3, 10);
        searchField.setLineWrap(true);
        searchField.setWrapStyleWord(true);
        searchPanel.add(new JScrollPane(searchField), BorderLayout.CENTER);

        JPanel searchButtonPanel = new JPanel();
        searchButtonPanel.setLayout(new BoxLayout(searchButtonPanel, BoxLayout.Y_AXIS));

        JButton textSearchButton = new JButton("Text Search");
        textSearchButton.addActionListener(e -> {
            String query = searchField.getText().trim();
            if (!query.isEmpty()) {
                searchCommits(query);
            }
        });
        textSearchButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, textSearchButton.getPreferredSize().height)
        );

        JButton resetButton = new JButton("Reset");
        resetButton.addActionListener(e -> {
            searchField.setText("");
            // Reload commits for currently selected branch
            int sel = branchTable.getSelectedRow();
            if (sel != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(sel, 1);
                updateCommitsForBranch(branchDisplay);
            }
        });
        resetButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, resetButton.getPreferredSize().height)
        );

        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(textSearchButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(resetButton);
        searchButtonPanel.add(Box.createVerticalStrut(5));
        searchButtonPanel.add(Box.createVerticalStrut(5));

        searchPanel.add(searchButtonPanel, BorderLayout.SOUTH);

        // ============ Add sub-panels to logPanel with GridBag ============

        constraints.gridx = 0; // branches
        constraints.weightx = 0.15;
        logPanel.add(branchesPanel, constraints);

        constraints.gridx = 1; // commits
        constraints.weightx = 0.50;
        logPanel.add(commitsPanel, constraints);

        constraints.gridx = 2; // changes
        constraints.weightx = 0.25;
        logPanel.add(changesPanel, constraints);

        constraints.gridx = 3; // search
        constraints.weightx = 0.10;
        logPanel.add(searchPanel, constraints);

        // Listeners for branch tables
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && branchTable.getSelectedRow() != -1) {
                String branchName = (String) branchTableModel.getValueAt(branchTable.getSelectedRow(), 1);
                // Clear the remote branch table selection so we don’t confuse local vs remote
                remoteBranchTable.clearSelection();
                updateCommitsForBranch(branchName);
            }
        });
        remoteBranchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && remoteBranchTable.getSelectedRow() != -1) {
                String branchName = (String) remoteBranchTableModel.getValueAt(remoteBranchTable.getSelectedRow(), 0);
                branchTable.clearSelection();
                updateCommitsForBranch(branchName);
            }
        });

        // Context menu for local branch table
        JPopupMenu branchContextMenu = new JPopupMenu();
        JMenuItem checkoutItem = new JMenuItem("Checkout");
        JMenuItem mergeItem = new JMenuItem("Merge into HEAD");
        JMenuItem renameItem = new JMenuItem("Rename");
        JMenuItem deleteItem = new JMenuItem("Delete");
        branchContextMenu.add(checkoutItem);
        branchContextMenu.add(mergeItem);
        branchContextMenu.add(renameItem);
        branchContextMenu.add(deleteItem);

        branchContextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(p, branchTable);
                    int row = branchTable.rowAtPoint(p);
                    if (row >= 0) {
                        branchTable.setRowSelectionInterval(row, row);
                    }
                    checkBranchContextMenuState(branchContextMenu);
                });
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        checkoutItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                checkoutBranch(branchDisplay);
            }
        });
        mergeItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                mergeBranchIntoHead(branchDisplay);
            }
        });
        renameItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                // only rename if it’s a local branch
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    renameBranch(branchName);
                }
            }
        });
        deleteItem.addActionListener(e -> {
            int selectedRow = branchTable.getSelectedRow();
            if (selectedRow != -1) {
                String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);
                if (branchDisplay.startsWith("Local: ")) {
                    String branchName = branchDisplay.substring("Local: ".length());
                    deleteBranch(branchName);
                }
            }
        });

        branchTable.setComponentPopupMenu(branchContextMenu);
    }

    /* =====================================================================================
       The methods below are all the code that was previously in GitPanel but
       specifically deals with branches, commits, searching, comparing diffs, etc.
       ===================================================================================== */

    /**
     * Update the branch list (local + remote) and select the current branch.
     */
    public void updateBranchList() {
        contextManager.submitBackgroundTask("Fetching git branches", () -> {
            try {
                String currentBranch = getRepo().getCurrentBranch();
                List<String> localBranches = getRepo().listLocalBranches();
                List<String> remoteBranches = getRepo().listRemoteBranches();

                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    remoteBranchTableModel.setRowCount(0);
                    int currentBranchRow = -1;

                    for (String branch : localBranches) {
                        String checkmark = branch.equals(currentBranch) ? "✓" : "";
                        branchTableModel.addRow(new Object[]{checkmark, branch});
                        if (branch.equals(currentBranch)) {
                            currentBranchRow = branchTableModel.getRowCount() - 1;
                        }
                    }
                    for (String branch : remoteBranches) {
                        remoteBranchTableModel.addRow(new Object[]{branch});
                    }

                    if (currentBranchRow >= 0) {
                        branchTable.setRowSelectionInterval(currentBranchRow, currentBranchRow);
                        // Ensure active branch is visible by scrolling to it
                        branchTable.scrollRectToVisible(branchTable.getCellRect(currentBranchRow, 0, true));
                        updateCommitsForBranch(currentBranch);
                    } else if (branchTableModel.getRowCount() > 0) {
                        branchTable.setRowSelectionInterval(0, 0);
                        String branchName = (String) branchTableModel.getValueAt(0, 1);
                        updateCommitsForBranch(branchName);
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching branches", e);
                SwingUtilities.invokeLater(() -> {
                    branchTableModel.setRowCount(0);
                    branchTableModel.addRow(new Object[]{"", "Error fetching branches: " + e.getMessage()});
                    remoteBranchTableModel.setRowCount(0);
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Update commits list for the given branch and highlights unpushed commits if applicable.
     */
    private void updateCommitsForBranch(String branchName) {
        contextManager.submitBackgroundTask("Fetching commits for " + branchName, () -> {
            try {
                List<CommitInfo> commits = getRepo().listCommitsDetailed(branchName);
                boolean isLocalBranch = branchName.equals(getRepo().getCurrentBranch()) || !branchName.contains("/");
                Set<String> unpushedCommitIds = new HashSet<>();
                boolean canPush = false;

                if (isLocalBranch) {
                    try {
                        unpushedCommitIds.addAll(getRepo().getUnpushedCommitIds(branchName));
                        canPush = !unpushedCommitIds.isEmpty() && getRepo().hasUpstreamBranch(branchName);
                    } catch (IOException e) {
                        logger.warn("Could not check for unpushed commits: {}", e.getMessage());
                    }
                }

                boolean finalCanPush = canPush;
                int unpushedCount = unpushedCommitIds.size();
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    pushButton.setEnabled(finalCanPush);
                    pushButton.setToolTipText(finalCanPush
                                                      ? "Push " + unpushedCount + " commit(s) to remote"
                                                      : "No unpushed commits or no upstream branch configured");

                    if (commits.isEmpty()) {
                        return;
                    }

                    java.time.LocalDate today = java.time.LocalDate.now();
                    for (CommitInfo commit : commits) {
                        String formattedDate = formatCommitDate(commit.date(), today);
                        boolean isUnpushed = unpushedCommitIds.contains(commit.id());
                        commitsTableModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                formattedDate,
                                commit.id(),
                                isUnpushed
                        });
                    }

                    if (commitsTableModel.getRowCount() > 0) {
                        commitsTable.setRowSelectionInterval(0, 0);
                        updateChangesForCommits(new int[]{0});
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching commits for branch: " + branchName, e);
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    commitsTableModel.addRow(new Object[]{
                            "Error fetching commits: " + e.getMessage(), "", "", ""
                    });
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Fills the "Changes" tree with files from the selected commits.
     */
    private void updateChangesForCommits(int[] selectedRows)
    {
        contextManager.submitBackgroundTask("Fetching changes for commits", () -> {
            try {
                var allChangedFiles = new HashSet<RepoFile>();
                for (var row : selectedRows) {
                    if (row >= 0 && row < commitsTableModel.getRowCount()) {
                        var commitId = (String) commitsTableModel.getValueAt(row, 3);
                        var changedFiles = getRepo().listChangedFilesInCommit(commitId);  // now returns List<RepoFile>
                        allChangedFiles.addAll(changedFiles);
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    if (allChangedFiles.isEmpty()) {
                        changesTreeModel.reload();
                        return;
                    }

                    Map<String, List<String>> filesByDir = new HashMap<>();
                    for (var file : allChangedFiles) {
                        filesByDir.computeIfAbsent(file.getParent(), k -> new ArrayList<>()).add(file.getFileName());
                    }

                    for (var entry : filesByDir.entrySet()) {
                        var dirPath = entry.getKey();
                        var files = entry.getValue();

                        DefaultMutableTreeNode dirNode;
                        if (dirPath.isEmpty()) {
                            dirNode = changesRootNode;
                        } else {
                            dirNode = new DefaultMutableTreeNode(dirPath);
                            changesRootNode.add(dirNode);
                        }
                        for (var f : files) {
                            dirNode.add(new DefaultMutableTreeNode(f));
                        }
                    }
                    changesTreeModel.reload();
                    expandAllNodes(changesTree, 0, changesTree.getRowCount());
                });
            } catch (Exception e) {
                logger.error("Error fetching changes for multiple commits", e);
                SwingUtilities.invokeLater(() -> {
                    changesRootNode.removeAllChildren();
                    changesRootNode.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
                    changesTreeModel.reload();
                });
            }
            return null;
        });
    }

    /**
     * Add changes from a selection of commits to the context.
     */
    private void addCommitRangeToContext(int[] selectedRows) {
        contextManager.submitContextTask("Adding commit range to context", () -> {
            try {
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.systemOutput("No commits selected or commits table is empty");
                    return null;
                }
                int[] sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);
                if (sortedRows[0] < 0 ||
                        sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.systemOutput("Invalid commit selection");
                    return null;
                }

                String firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                String lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);

                String diff = getRepo().showDiff(lastCommitId, firstCommitId + "^");
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found in the selected commit range");
                    return null;
                }

                String firstShortHash = firstCommitId.substring(0, 7);
                String lastShortHash = lastCommitId.substring(0, 7);
                String description;
                if (selectedRows.length > 1) {
                    description = String.format("git %s..%s: Changes across %d commits",
                                                firstShortHash, lastShortHash, selectedRows.length);
                } else {
                    description  = String.format("git %s", firstShortHash);
                }

                ContextFragment.StringFragment fragment =
                        new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for commit range to context");
            } catch (Exception ex) {
                logger.error("Error adding commit range to context", ex);
                chrome.toolErrorRaw("Error adding commit range to context: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Add changes from selected files in a range of commits to the context.
     */
    private void addFilesChangeToContext(int[] selectedRows, List<String> filePaths) {
        contextManager.submitContextTask("Adding file changes from range to context", () -> {
            try {
                if (selectedRows.length == 0 || commitsTableModel.getRowCount() == 0) {
                    chrome.systemOutput("No commits selected or commits table is empty");
                    return null;
                }
                int[] sortedRows = selectedRows.clone();
                Arrays.sort(sortedRows);
                if (sortedRows[0] < 0 ||
                        sortedRows[sortedRows.length - 1] >= commitsTableModel.getRowCount()) {
                    chrome.systemOutput("Invalid commit selection");
                    return null;
                }

                String firstCommitId = (String) commitsTableModel.getValueAt(sortedRows[0], 3);
                String lastCommitId = (String) commitsTableModel.getValueAt(sortedRows[sortedRows.length - 1], 3);

                var repoFiles = filePaths.stream()
                        .map(path -> new RepoFile(contextManager.getRoot(), path))
                        .collect(Collectors.toList());
                        
                String diffs = repoFiles.stream()
                        .map(file -> getRepo().showFileDiff(lastCommitId, firstCommitId + "^", file))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));

                if (diffs.isEmpty()) {
                    chrome.systemOutput("No changes found for the selected files in the commit range");
                    return null;
                }

                String firstShortHash = firstCommitId.substring(0, 7);
                String lastShortHash = lastCommitId.substring(0, 7);

                List<String> fileNames = filePaths.stream()
                        .map(path -> {
                            int slashPos = path.lastIndexOf('/');
                            return (slashPos >= 0 ? path.substring(slashPos + 1) : path);
                        })
                        .collect(Collectors.toList());
                String fileList = String.join(", ", fileNames);

                String description = String.format("git %s..%s [%s]: Changes across %d commits",
                                                   firstShortHash, lastShortHash, fileList, selectedRows.length);

                ContextFragment.StringFragment fragment =
                        new ContextFragment.StringFragment(diffs, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for selected files in commit range to context");
            } catch (Exception ex) {
                logger.error("Error adding file changes from range to context", ex);
                chrome.toolErrorRaw("Error adding file changes from range to context: " + ex.getMessage());
            }
            return null;
        });
    }
    
    /**
     * Compare selected files from a commit with the local working copy.
     */
    private void compareFilesWithLocal(String commitId, List<String> filePaths) {
        contextManager.submitContextTask("Comparing files with local", () -> {
            try {
                var repoFiles = filePaths.stream()
                        .map(path -> new RepoFile(contextManager.getRoot(), path))
                        .collect(Collectors.toList());
                        
                String allDiffs = repoFiles.stream()
                        .map(file -> getRepo().showFileDiff("HEAD", commitId, file))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));

                if (allDiffs.isEmpty()) {
                    chrome.systemOutput("No differences found between selected files and local working copy");
                    return null;
                }

                String shortHash = commitId.substring(0, 7);
                StringBuilder fileList = new StringBuilder();
                for (String fp : filePaths) {
                    if (fileList.length() > 0) fileList.append(", ");
                    int lastSlash = fp.lastIndexOf('/');
                    fileList.append(lastSlash >= 0 ? fp.substring(lastSlash + 1) : fp);
                }
                String description = "git local vs " + shortHash + " [" + fileList + "]";

                ContextFragment.StringFragment fragment =
                        new ContextFragment.StringFragment(allDiffs, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added comparison with local for " + filePaths.size() + " file(s) to context");
            } catch (Exception ex) {
                logger.error("Error comparing files with local", ex);
                chrome.toolErrorRaw("Error comparing files with local: " + ex.getMessage());
            }
            return null;
        });
    }

    /**
     * Soft reset HEAD to the specified commit, keeping changes in working directory.
     */
    private void softResetToCommit(String commitId, String commitMessage) {
        contextManager.submitUserTask("Soft resetting to commit: " + commitId, () -> {
            String oldHeadId = getOldHeadId();
            try {
                getRepo().softReset(commitId);
                SwingUtilities.invokeLater(() -> {
                    String oldHeadShort = oldHeadId.length() >= 7 ? oldHeadId.substring(0, 7) : oldHeadId;
                    String newHeadShort = commitId.length() >= 7 ? commitId.substring(0, 7) : commitId;
                    chrome.systemOutput("Soft reset from " + oldHeadShort + " to " + newHeadShort + ": " + commitMessage);

                    // Refresh uncommitted files or anything else if needed
                    // For example, if you have a method in GitPanel to update uncommitted:
                    // (You’d call something like gitPanelInstance.updateSuggestCommitButton();)

                    // Refresh the current branch's commits
                    int branchRow = branchTable.getSelectedRow();
                    if (branchRow != -1) {
                        String branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                        updateCommitsForBranch(branchDisplay);
                    }
                });
            } catch (IOException e) {
                logger.error("Error performing soft reset to commit: {}", commitId, e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error performing soft reset: " + e.getMessage()));
            }
            return null;
        });
    }

    /**
     * Revert a single commit.
     */
    private void revertCommit(String commitId) {
        contextManager.submitUserTask("Reverting commit", () -> {
            try {
                getRepo().revertCommit(commitId);
                chrome.systemOutput("Commit was successfully reverted.");

                int branchRow = branchTable.getSelectedRow();
                if (branchRow != -1) {
                    String branchDisplay = (String) branchTableModel.getValueAt(branchRow, 1);
                    updateCommitsForBranch(branchDisplay);
                }
            } catch (IOException e) {
                logger.error("Error reverting commit: {}", commitId, e);
                chrome.toolErrorRaw("Error reverting commit: " + e.getMessage());
            }
            return null;
        });
    }

    /**
     * Push the current branch to remote.
     */
    private void pushBranch() {
        int selectedRow = branchTable.getSelectedRow();
        if (selectedRow == -1) return;
        String branchDisplay = (String) branchTableModel.getValueAt(selectedRow, 1);

        contextManager.submitBackgroundTask("Pushing branch " + branchDisplay, () -> {
            try {
                getRepo().push();
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Successfully pushed " + branchDisplay + " to remote");
                    updateCommitsForBranch(branchDisplay);
                });
            } catch (IOException e) {
                logger.error("Error pushing branch: {}", branchDisplay, e);
                SwingUtilities.invokeLater(() -> chrome.toolErrorRaw(e.getMessage()));
            }
            return null;
        });
    }

    /**
     * Check out a branch (local or remote).
     */
    private void checkoutBranch(String branchName) {
        contextManager.submitUserTask("Checking out branch: " + branchName, () -> {
            try {
                if (branchName.startsWith("origin/") || branchName.contains("/")) {
                    getRepo().checkoutRemoteBranch(branchName);
                    chrome.systemOutput("Created local tracking branch for " + branchName);
                } else {
                    getRepo().checkout(branchName);
                }
                updateBranchList();
            } catch (IOException e) {
                logger.error("Error checking out branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Merge a branch into HEAD.
     */
    private void mergeBranchIntoHead(String branchName) {
        contextManager.submitUserTask("Merging branch: " + branchName, () -> {
            try {
                getRepo().mergeIntoHead(branchName);
                chrome.systemOutput("Branch '" + branchName + "' was successfully merged into HEAD.");
                updateBranchList();
            } catch (IOException e) {
                logger.error("Error merging branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Rename a local branch.
     */
    private void renameBranch(String branchName) {
        String newName = JOptionPane.showInputDialog(
                this,
                "Enter new name for branch '" + branchName + "':",
                "Rename Branch",
                JOptionPane.QUESTION_MESSAGE
        );
        if (newName != null && !newName.trim().isEmpty()) {
            contextManager.submitUserTask("Renaming branch: " + branchName, () -> {
                try {
                    getRepo().renameBranch(branchName, newName);
                    updateBranchList();
                } catch (IOException e) {
                    logger.error("Error renaming branch: {}", branchName, e);
                    chrome.toolErrorRaw("Error renaming branch: " + e.getMessage());
                }
                return null;
            });
        }
    }

    /**
     * Delete a local branch (with checks for merges).
     */
    private void deleteBranch(String branchName) {
        contextManager.submitUserTask("Checking branch merge status", () -> {
            try {
                boolean isMerged = getRepo().isBranchMerged(branchName);
                SwingUtilities.invokeLater(() -> {
                    if (isMerged) {
                        int result = JOptionPane.showConfirmDialog(
                                this,
                                "Are you sure you want to delete branch '" + branchName + "'?",
                                "Delete Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE
                        );
                        if (result == JOptionPane.YES_OPTION) {
                            performBranchDeletion(branchName, false);
                        }
                    } else {
                        Object[] options = {"Force Delete", "Cancel"};
                        int result = JOptionPane.showOptionDialog(
                                this,
                                "Branch '" + branchName + "' is not fully merged.\n" +
                                        "Changes on this branch will be lost if deleted.\n" +
                                        "Do you want to force delete it?",
                                "Unmerged Branch",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[1]
                        );
                        if (result == 0) { // Force Delete
                            performBranchDeletion(branchName, true);
                        }
                    }
                });
            } catch (IOException e) {
                logger.error("Error checking branch merge status: {}", branchName, e);
                chrome.toolErrorRaw("Error checking branch status: " + e.getMessage());
            }
            return null;
        });
    }

    /**
     * Perform the actual branch deletion with or without force.
     */
    private void performBranchDeletion(String branchName, boolean force) {
        contextManager.submitUserTask("Deleting branch: " + branchName, () -> {
            try {
                if (force) {
                    getRepo().forceDeleteBranch(branchName);
                } else {
                    getRepo().deleteBranch(branchName);
                }
                updateBranchList();
                chrome.systemOutput("Branch '" + branchName + "' " + (force ? "force " : "") + "deleted successfully.");
            } catch (IOException e) {
                logger.error("Error deleting branch: {}", branchName, e);
                chrome.toolErrorRaw(e.getMessage());
            }
            return null;
        });
    }

    /**
     * Text search across all commits. Shows results in the commits table.
     */
    private void searchCommits(String query) {
        contextManager.submitUserTask("Searching commits for: " + query, () -> {
            try {
                List<CommitInfo> searchResults = getRepo().searchCommits(query);
                SwingUtilities.invokeLater(() -> {
                    commitsTableModel.setRowCount(0);
                    changesRootNode.removeAllChildren();
                    changesTreeModel.reload();

                    if (searchResults.isEmpty()) {
                        chrome.systemOutput("No commits found matching: " + query);
                        return;
                    }
                    for (CommitInfo commit : searchResults) {
                        commitsTableModel.addRow(new Object[]{
                                commit.message(),
                                commit.author(),
                                commit.date(),
                                commit.id()
                        });
                    }
                    chrome.systemOutput("Found " + searchResults.size() + " commits matching: " + query);
                    if (commitsTableModel.getRowCount() > 0) {
                        commitsTable.setRowSelectionInterval(0, 0);
                        updateChangesForCommits(new int[]{0});
                    }
                });
            } catch (Exception e) {
                logger.error("Error searching commits: {}", query, e);
                SwingUtilities.invokeLater(() -> chrome.toolErrorRaw("Error searching commits: " + e.getMessage()));
            }
            return null;
        });
    }

    // ==================================================================
    // Helper methods
    // ==================================================================

    private GitRepo getRepo() {
        return contextManager.getProject().getRepo();
    }

    private String getOldHeadId() {
        try {
            return getRepo().getCurrentCommitId();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * Recursively expand all nodes in the JTree.
     */
    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; i++) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() > rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    /**
     * Format commit date to show e.g. "HH:MM:SS today" if it is today's date.
     */
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

    /**
     * Check if any file nodes (as opposed to directory nodes) are selected.
     */
    private boolean hasFileNodesSelected(TreePath[] paths) {
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node != changesRootNode && node.getParent() != null && node.getParent() != changesRootNode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieve file paths from the selected tree nodes.
     */
    private List<String> getSelectedFilePaths(TreePath[] paths) {
        List<String> selectedFiles = new ArrayList<>();
        for (TreePath path : paths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node == changesRootNode || node.getParent() == changesRootNode) {
                continue;
            }
            String fileName = node.getUserObject().toString();
            DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) node.getParent();
            String dirPath = parentNode.getUserObject().toString();
            String filePath = dirPath.isEmpty() ? fileName : dirPath + "/" + fileName;
            selectedFiles.add(filePath);
        }
        return selectedFiles;
    }

    /**
     * Opens a file history tab for the selected file
     */
    private void viewFileHistory(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;

        GitPanel gitPanel = chrome.getGitPanel();
        if (gitPanel != null) {
            var repoFile = new RepoFile(contextManager.getRoot(), filePath);
            gitPanel.addFileHistoryTab(repoFile);
        }
    }

    /**
     * Edit the given file inside your IDE or editor plugin.
     */
    private void editFile(String filePath) {
        List<RepoFile> files = new ArrayList<>();
        files.add(new RepoFile(contextManager.getRoot(), filePath));
        contextManager.editFiles(files);
    }
    
    /**
     * Shows the diff for a file in a commit.
     */
    private void showFileDiff(String commitId, String filePath) {
        RepoFile file = new RepoFile(contextManager.getRoot(), filePath);
        DiffPanel diffPanel = new DiffPanel(contextManager);
        
        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        String dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";
        
        diffPanel.showFileDiff(commitId, file, dialogTitle);
        diffPanel.showInDialog(this, dialogTitle);
    }

    /**
     * Enables/Disables items in the local-branch context menu based on selection.
     */

    private void checkBranchContextMenuState(JPopupMenu menu) {
        int selectedRow = branchTable.getSelectedRow();
        boolean isLocal = (selectedRow != -1);
        // rename = menu.getComponent(2), delete = menu.getComponent(3)
        // Adjust as needed if you change order
        menu.getComponent(2).setEnabled(isLocal);
        menu.getComponent(3).setEnabled(isLocal);
    }
    
    /**
     * Selects a commit in the commits table by its ID.
     */
    public void selectCommitById(String commitId) {
        for (int i = 0; i < commitsTableModel.getRowCount(); i++) {
            String currentId = (String) commitsTableModel.getValueAt(i, 3);
            if (commitId.equals(currentId)) {
                commitsTable.setRowSelectionInterval(i, i);
                commitsTable.scrollRectToVisible(commitsTable.getCellRect(i, 0, true));
                updateChangesForCommits(new int[]{i});
                return;
            }
        }
        
        // If not found in the current view, let the user know
        chrome.systemOutput("Commit " + commitId.substring(0, 7) + " not found in current branch view");
    }
}
