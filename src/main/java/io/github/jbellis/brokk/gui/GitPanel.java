package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private JTextArea commitMessageArea;
    private JButton commitButton;
    private JButton stashButton;

    // History tabs
    private JTabbedPane tabbedPane;
    private final Map<String, JTable> fileHistoryTables = new HashMap<>();
    
    // Stash tab UI
    private JTable stashTable;
    private DefaultTableModel stashTableModel;

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
                "Git",
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
        setMaximumSize(panelSize);

        // Tabbed pane
        tabbedPane = new JTabbedPane();
        add(tabbedPane, BorderLayout.CENTER);

        // 1) Commit tab
        JPanel commitTab = buildCommitTab();
        tabbedPane.addTab("Commit", commitTab);

        // 2) Stash tab
        JPanel stashTab = buildStashTab();
        tabbedPane.addTab("Stash", stashTab);

        // 3) Log tab (moved into GitLogPanel)
        gitLogPanel = new GitLogPanel(chrome, contextManager);
        tabbedPane.addTab("Log", gitLogPanel);

        // After UI is built, asynchronously refresh data
        SwingUtilities.invokeLater(() -> {
            updateStashList();
            gitLogPanel.updateBranchList(); // load branches, commits, etc.
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
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);
        uncommittedFilesTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Add double-click handler to show diff for uncommitted files
        uncommittedFilesTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = uncommittedFilesTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        uncommittedFilesTable.setRowSelectionInterval(row, row);
                        String filename = (String) uncommittedFilesTable.getValueAt(row, 0);
                        String path = (String) uncommittedFilesTable.getValueAt(row, 1);
                        String filePath = path.isEmpty() ? filename : path + "/" + filename;
                        showUncommittedFileDiff(filePath);
                    }
                }
            }
        });

        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        commitTab.add(uncommittedScrollPane, BorderLayout.CENTER);

        // Commit message + buttons at bottom
        JPanel commitBottomPanel = new JPanel(new BorderLayout());
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.add(new JLabel("Commit/Stash Description:"), BorderLayout.NORTH);

        commitMessageArea = new JTextArea(2, 50);
        commitMessageArea.setLineWrap(true);
        commitMessageArea.setWrapStyleWord(true);
        messagePanel.add(new JScrollPane(commitMessageArea), BorderLayout.CENTER);

        commitBottomPanel.add(messagePanel, BorderLayout.CENTER);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        // "Suggest Message" button
        suggestMessageButton = new JButton("Suggest Message");
        suggestMessageButton.setToolTipText("Suggest a commit message for the selected files");
        suggestMessageButton.setEnabled(false);
        suggestMessageButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();
            contextManager.submitBackgroundTask("Suggesting commit message", () -> {
                try {
                    var diff = selectedFiles.isEmpty()
                            ? getRepo().diff()
                            : getRepo().diffFiles(selectedFiles);
                    if (diff.isEmpty()) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                                          "No changes to commit",
                                                          "Error",
                                                          JOptionPane.ERROR_MESSAGE);
                            chrome.enableUserActionButtons();
                        });
                        return null;
                    }
                    // Trigger LLM-based commit message generation
                    contextManager.performCommitActionAsync(diff);
                    SwingUtilities.invokeLater(chrome::enableUserActionButtons);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error suggesting commit message: " + ex.getMessage(),
                                                      "Error", JOptionPane.ERROR_MESSAGE);
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
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();

            contextManager.submitUserTask("Stashing changes", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
                    } else {
                        for (var file : selectedFiles) {
                            getRepo().add(file); // now uses add(RepoFile)
                        }
                        getRepo().createStash(stashDescription.isEmpty() ? null : stashDescription);
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
                        updateSuggestCommitButton();
                        updateStashList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error stashing changes: " + ex.getMessage(),
                                                      "Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
            });
        });
        buttonPanel.add(stashButton);

        // "Commit" button
        commitButton = new JButton("Commit All");
        commitButton.setToolTipText("Commit files with the message");
        commitButton.setEnabled(false);
        commitButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            List<RepoFile> selectedFiles = getSelectedFilesFromTable();
            String msg = commitMessageArea.getText().trim();
            if (msg.isEmpty()) {
                chrome.enableUserActionButtons();
                return;
            }
            contextManager.submitUserTask("Committing files", () -> {
                try {
                    if (selectedFiles.isEmpty()) {
                        var allDirtyFiles = getRepo().getUncommittedFiles();
                        contextManager.getProject().getRepo().commitFiles(allDirtyFiles, msg);
                    } else {
                        contextManager.getProject().getRepo().commitFiles(selectedFiles, msg);
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
                        updateSuggestCommitButton();
                        // The GitLogPanel can refresh branches/commits:
                        gitLogPanel.updateBranchList();
                        chrome.enableUserActionButtons();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                                      "Error committing files: " + ex.getMessage(),
                                                      "Commit Error", JOptionPane.ERROR_MESSAGE);
                        chrome.enableUserActionButtons();
                    });
                }
                return null;
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
     * Builds the Stash tab.
     */
    private JPanel buildStashTab() {
        JPanel stashTab = new JPanel(new BorderLayout());

        stashTableModel = new DefaultTableModel(
                new Object[]{"Index", "Message", "Author", "Date", "ID"}, 0
        ) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };
        stashTable = new JTable(stashTableModel);
        stashTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stashTable.setRowHeight(18);

        stashTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        stashTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        stashTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        stashTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        // Hide ID column
        stashTable.getColumnModel().getColumn(4).setMinWidth(0);
        stashTable.getColumnModel().getColumn(4).setMaxWidth(0);
        stashTable.getColumnModel().getColumn(4).setWidth(0);

        stashTab.add(new JScrollPane(stashTable), BorderLayout.CENTER);

        // Context menu for stash
        JPopupMenu stashContextMenu = new JPopupMenu();
        JMenuItem popStashItem = new JMenuItem("Pop Stash");
        JMenuItem applyStashItem = new JMenuItem("Apply Stash");
        JMenuItem dropStashItem = new JMenuItem("Drop Stash");
        stashContextMenu.add(popStashItem);
        stashContextMenu.add(applyStashItem);
        stashContextMenu.add(dropStashItem);

        stashTable.setComponentPopupMenu(stashContextMenu);
        stashContextMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point point = MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.convertPointFromScreen(point, stashTable);
                    int row = stashTable.rowAtPoint(point);
                    if (row >= 0) {
                        stashTable.setRowSelectionInterval(row, row);
                        int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                        // only enable "Pop" for stash@{0}
                        popStashItem.setEnabled(index == 0);
                    }
                });
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });

        popStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                popStash(index);
            }
        });
        applyStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                applyStash(index);
            }
        });
        dropStashItem.addActionListener(e -> {
            int row = stashTable.getSelectedRow();
            if (row != -1) {
                int index = Integer.parseInt((String) stashTableModel.getValueAt(row, 0));
                dropStash(index);
            }
        });

        // Refresh button
        JPanel stashButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshStashButton = new JButton("Refresh");
        refreshStashButton.addActionListener(e -> updateStashList());
        stashButtonPanel.add(refreshStashButton);

        stashTab.add(stashButtonPanel, BorderLayout.SOUTH);

        return stashTab;
    }

    /**
     * Returns the current GitRepo from ContextManager.
     */
    private GitRepo getRepo() {
        return contextManager.getProject().getRepo();
    }

    /**
     * Populates the uncommitted files table and enables/disables commit-related buttons.
     */
    public void updateSuggestCommitButton()
    {
        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            try {
                var uncommittedFiles = getRepo().getUncommittedFiles();
                SwingUtilities.invokeLater(() -> {
                    var model = (DefaultTableModel) uncommittedFilesTable.getModel();
                    model.setRowCount(0);

                    if (uncommittedFiles.isEmpty()) {
                        suggestMessageButton.setEnabled(false);
                        commitButton.setEnabled(false);
                        stashButton.setEnabled(false);
                    } else {
                        for (var file : uncommittedFiles) {
                            model.addRow(new Object[]{file.getFileName(), file.getParent()});
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
                SwingUtilities.invokeLater(() -> {
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
     * Helper to get a list of selected files from the uncommittedFilesTable.
     */
    private List<RepoFile> getSelectedFilesFromTable()
    {
        var model = (DefaultTableModel) uncommittedFilesTable.getModel();
        var selectedRows = uncommittedFilesTable.getSelectedRows();
        var files = new ArrayList<RepoFile>();

        for (var row : selectedRows) {
            var filename = (String) model.getValueAt(row, 0);
            var path     = (String) model.getValueAt(row, 1);
            // Combine them to get the relative path
            var combined = path.isEmpty() ? filename : path + "/" + filename;
            files.add(new RepoFile(contextManager.getRoot(), combined));
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
     * Allows external code to size the commit-related buttons consistently.
     */
    public void setSuggestCommitButtonSize(Dimension preferredSize) {
        suggestMessageButton.setPreferredSize(preferredSize);
        suggestMessageButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        commitButton.setPreferredSize(preferredSize);
        commitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        stashButton.setPreferredSize(preferredSize);
        stashButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
    }
    
    /**
     * Creates a new tab showing the history of a specific file
     */
    public void addFileHistoryTab(RepoFile file) {
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

        // Set column widths to 80%/10%/10%
        fileHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(800); // message (80%)
        fileHistoryTable.getColumnModel().getColumn(1).setPreferredWidth(100); // author (10%)
        fileHistoryTable.getColumnModel().getColumn(2).setPreferredWidth(100); // date (10%)

        // Hide ID column
        fileHistoryTable.getColumnModel().getColumn(3).setMinWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setMaxWidth(0);
        fileHistoryTable.getColumnModel().getColumn(3).setWidth(0);

        // Add a context menu with same options as Changes tree
        JPopupMenu historyContextMenu = new JPopupMenu();
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
        loadFileHistory(file, fileHistoryModel);
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
    
    private void loadFileHistory(RepoFile file, DefaultTableModel model) {
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
                var repoFile = new RepoFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + filePath);
                    return null;
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
            return null;
        });
    }
    
    private void compareFileWithLocal(String commitId, String filePath) {
        contextManager.submitContextTask("Comparing file with local", () -> {
            try {
                var repoFile = new RepoFile(contextManager.getRoot(), filePath);
                var diff = getRepo().showFileDiff("HEAD", commitId, repoFile);

                if (diff.isEmpty()) {
                    chrome.systemOutput("No differences found between " + filePath + " and local working copy");
                    return null;
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
            return null;
        });
    }
    
    private void editFile(String filePath) {
        List<RepoFile> files = new ArrayList<>();
        files.add(contextManager.toFile(filePath));
        contextManager.editFiles(files);
    }
    
    /**
     * Shows the diff for a file at a specific commit from file history.
     */
    private void showFileHistoryDiff(String commitId, String filePath) {
        RepoFile file = new RepoFile(contextManager.getRoot(), filePath);
        DiffPanel diffPanel = new DiffPanel(contextManager);
        
        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        String dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";
        
        diffPanel.showFileDiff(commitId, file, dialogTitle);
        diffPanel.showInDialog(this, dialogTitle);
    }

    // ================ Stash Methods ==================

    public void updateStashList() {
        contextManager.submitBackgroundTask("Fetching stashes", () -> {
            try {
                var stashes = getRepo().listStashes();
                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);
                    if (stashes.isEmpty()) return;

                    java.time.LocalDate today = java.time.LocalDate.now();
                    for (var stash : stashes) {
                        String formattedDate = gitLogPanel // reuse helper from GitLogPanel or local:
                                .formatCommitDate(stash.date(), today); // Or replicate that code here
                        stashTableModel.addRow(new Object[]{
                                String.valueOf(stash.index()),
                                stash.message(),
                                stash.author(),
                                formattedDate,
                                stash.id()
                        });
                    }
                });
            } catch (Exception e) {
                logger.error("Error fetching stashes", e);
                SwingUtilities.invokeLater(() -> {
                    stashTableModel.setRowCount(0);
                    stashTableModel.addRow(new Object[]{
                            "", "Error fetching stashes: " + e.getMessage(), "", "", ""
                    });
                });
            }
            return null;
        });
    }

    private void popStash(int index) {
        contextManager.submitUserTask("Popping stash", () -> {
            try {
                getRepo().popStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash popped successfully");
                    updateStashList();
                    updateSuggestCommitButton();
                });
            } catch (Exception e) {
                logger.error("Error popping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error popping stash: " + e.getMessage()));
            }
            return null;
        });
    }

    private void applyStash(int index) {
        contextManager.submitUserTask("Applying stash", () -> {
            try {
                getRepo().applyStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash applied successfully");
                    updateSuggestCommitButton();
                });
            } catch (Exception e) {
                logger.error("Error applying stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error applying stash: " + e.getMessage()));
            }
            return null;
        });
    }

    private void dropStash(int index) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to delete this stash?",
                "Delete Stash",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        contextManager.submitUserTask("Dropping stash", () -> {
            try {
                getRepo().dropStash(index);
                SwingUtilities.invokeLater(() -> {
                    chrome.systemOutput("Stash dropped successfully");
                    updateStashList();
                });
            } catch (Exception e) {
                logger.error("Error dropping stash", e);
                SwingUtilities.invokeLater(() ->
                                                   chrome.toolErrorRaw("Error dropping stash: " + e.getMessage()));
            }
            return null;
        });
    }

    /**
     * Format commit date to show e.g. "HH:MM:SS today" if it is today's date.
     */
    /**
     * Shows the diff for an uncommitted file.
     */
    private void showUncommittedFileDiff(String filePath) {
        RepoFile file = new RepoFile(contextManager.getRoot(), filePath);
        DiffPanel diffPanel = new DiffPanel(contextManager);
        
        String dialogTitle = "Uncommitted Changes: " + file.getFileName();
        diffPanel.showFileDiff("UNCOMMITTED", file, dialogTitle);
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
