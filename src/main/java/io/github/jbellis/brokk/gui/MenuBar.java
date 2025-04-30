package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.gui.dialogs.FileSelectionDialog;
import io.github.jbellis.brokk.gui.dialogs.PreviewImagePanel;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.util.Decompiler;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public class MenuBar {
    /**
     * Builds the menu bar
     * @param chrome
     */
    static JMenuBar buildMenuBar(Chrome chrome) {
        // Check if project is available to enable/disable context-related items
        boolean hasProject = chrome.getProject() != null;
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");

        var openProjectItem = new JMenuItem("Open Project...");
        openProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        openProjectItem.addActionListener(e -> {
            // Use a directory chooser
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select a Git project directory");
            int result = chooser.showOpenDialog(chrome.frame);
            if (result == JFileChooser.APPROVE_OPTION) {
                var dir = chooser.getSelectedFile().toPath();
                io.github.jbellis.brokk.Brokk.openProject(dir);
            }
        });
        fileMenu.add(openProjectItem);

        var reopenProjectItem = new JMenuItem("Reopen Project");
        reopenProjectItem.setEnabled(hasProject);
        reopenProjectItem.addActionListener(e -> {
            if (chrome.contextManager != null) {
                var currentPath = chrome.contextManager.getProject().getRoot();
                Brokk.reOpenProject(currentPath);
            }
        });
        fileMenu.add(reopenProjectItem);

        var recentProjectsMenu = new JMenu("Recent Projects");
        fileMenu.add(recentProjectsMenu);
        rebuildRecentProjectsMenu(recentProjectsMenu);

        var settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            openSettingsDialog(chrome);
        });
        fileMenu.add(settingsItem);

        fileMenu.addSeparator();

        var openDependencyItem = new JMenuItem("Decompile Dependency...");
        openDependencyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        openDependencyItem.addActionListener(e -> {
            // Fixme ensure the menu item is disabled if no project is open
            assert chrome.getContextManager() != null;
            assert chrome.getProject() != null;
            var cm = chrome.getContextManager();

            var jarCandidates = cm.submitBackgroundTask("Scanning for JAR files", Decompiler::findCommonDependencyJars);

            // Now show the dialog on the EDT
            SwingUtilities.invokeLater(() -> {
                Predicate<File> jarFilter = file -> file.isDirectory() || file.getName().toLowerCase().endsWith(".jar");
                FileSelectionDialog dialog = new FileSelectionDialog(
                        chrome.getFrame(),
                        cm.getProject(), // Pass the current project
                        "Select JAR Dependency to Decompile",
                        true, // Allow external files
                        jarFilter, // Filter tree view for .jar files (and directories)
                        jarCandidates // Provide candidates for autocomplete
                );
                dialog.setVisible(true); // Show the modal dialog

                if (dialog.isConfirmed() && dialog.getSelectedFile() != null) {
                    var selectedFile = dialog.getSelectedFile();
                    Path jarPath = selectedFile.absPath();
                    assert Files.isRegularFile(jarPath) && jarPath.toString().toLowerCase().endsWith(".jar");
                    Decompiler.decompileJar(chrome, jarPath, cm::submitBackgroundTask);
                }
            });
        });
        fileMenu.add(openDependencyItem);

        menuBar.add(fileMenu);

        // Edit menu
        var editMenu = new JMenu("Edit");

        var undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        undoItem.addActionListener(e -> {
            chrome.contextManager.undoContextAsync();
        });
        undoItem.setEnabled(hasProject);
        editMenu.add(undoItem);

        var redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                       Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        redoItem.addActionListener(e -> {
            chrome.contextManager.redoContextAsync();
        });
        redoItem.setEnabled(hasProject);
        editMenu.add(redoItem);

        editMenu.addSeparator();

        var copyMenuItem = new JMenuItem("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyMenuItem.addActionListener(e -> {
            var selectedFragments = chrome.getSelectedFragments();
            chrome.getContextPanel().performContextActionAsync(ContextPanel.ContextAction.COPY, selectedFragments);
        });
        copyMenuItem.setEnabled(hasProject);
        editMenu.add(copyMenuItem);

        var pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteMenuItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(ContextPanel.ContextAction.PASTE, List.of());
        });
        pasteMenuItem.setEnabled(hasProject);
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);

        // Context menu
        var contextMenu = new JMenu("Context");

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            // Fixme ensure the menu item is disabled if no project is open
            assert chrome.getContextManager() != null;
            chrome.contextManager.requestRebuild();
            chrome.systemOutput("Code intelligence will refresh in the background");
        });
        refreshItem.setEnabled(hasProject);
        contextMenu.add(refreshItem);

        contextMenu.addSeparator();

        var editFilesItem = new JMenuItem("Edit Files");
        editFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editFilesItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    ContextPanel.ContextAction.EDIT, List.of());
        });
        editFilesItem.setEnabled(hasProject && chrome.getProject().hasGit());
        contextMenu.add(editFilesItem);

        var readFilesItem = new JMenuItem("Read Files");
        readFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        readFilesItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    ContextPanel.ContextAction.READ, List.of());
        });
        readFilesItem.setEnabled(hasProject);
        contextMenu.add(readFilesItem);
    
    var viewFileItem = new JMenuItem("View File");
    viewFileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    viewFileItem.addActionListener(e -> {
            assert chrome.getContextManager() != null;
            assert chrome.getProject() != null;
            var cm = chrome.getContextManager();
            var project = cm.getProject();

            // Use a simplified FileSelectionDialog for viewing
            SwingUtilities.invokeLater(() -> {
                // Autocomplete with all project files, transforming Set<ProjectFile> to List<Path>
                var allFilesFuture = cm.submitBackgroundTask("Fetching project files", () -> {
                    return project.getAllFiles().stream()
                            .map(io.github.jbellis.brokk.analyzer.BrokkFile::absPath)
                            .toList();
                });

                FileSelectionDialog dialog = new FileSelectionDialog(
                        chrome.getFrame(),
                        project,
                        "Select File to View",
                        false, // Don't allow external files
                        f -> true, // Allow all files/directories in tree
                        allFilesFuture
                );
                dialog.setVisible(true);

                if (dialog.isConfirmed() && dialog.getSelectedFile() != null) {
                    var selectedBrokkFile = dialog.getSelectedFile();
                    if (selectedBrokkFile instanceof io.github.jbellis.brokk.analyzer.ProjectFile selectedFile) {
                        chrome.previewFile(selectedFile);
                    } else if (!selectedBrokkFile.isText()) {
                        PreviewImagePanel.showInFrame(chrome.getFrame(), cm, selectedBrokkFile, chrome.themeManager);
                    } else {
                        chrome.toolErrorRaw("Cannot view this type of file: " + selectedBrokkFile.getClass().getSimpleName());
                    }
                }
            });
        });
        viewFileItem.setEnabled(hasProject);
        contextMenu.add(viewFileItem);

        contextMenu.addSeparator(); // Add separator before Summarize / Symbol Usage

        var summarizeItem = new JMenuItem("Summarize");
        summarizeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        summarizeItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    ContextPanel.ContextAction.SUMMARIZE, List.of());
        });
        summarizeItem.setEnabled(hasProject);
        contextMenu.add(summarizeItem);

        var symbolUsageItem = new JMenuItem("Symbol Usage");
            symbolUsageItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            symbolUsageItem.addActionListener(e -> {
                chrome.getContextPanel().findSymbolUsageAsync(); // Call via ContextPanel
            });
            symbolUsageItem.setEnabled(hasProject);
            contextMenu.add(symbolUsageItem);

        var callersItem = new JMenuItem("Call graph to function");
            callersItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            callersItem.addActionListener(e -> {
                chrome.getContextPanel().findMethodCallersAsync(); // Call via ContextPanel
            });
            callersItem.setEnabled(hasProject);
            contextMenu.add(callersItem);

        var calleesItem = new JMenuItem("Call graph from function");
            calleesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
            calleesItem.addActionListener(e -> {
                chrome.getContextPanel().findMethodCalleesAsync(); // Call via ContextPanel
            });
    calleesItem.setEnabled(hasProject);
    contextMenu.add(calleesItem);

    contextMenu.addSeparator(); // Add separator before Drop All

    var dropAllItem = new JMenuItem("Drop All");
    dropAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    dropAllItem.addActionListener(e -> {
            chrome.getContextPanel().performContextActionAsync(
                    ContextPanel.ContextAction.DROP, List.of());
        });
        dropAllItem.setEnabled(hasProject);
        contextMenu.add(dropAllItem);

        menuBar.add(contextMenu);

        // Help menu
        var helpMenu = new JMenu("Help");

        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(chrome.getFrame(),
                                          "Brokk Swing UI\nVersion X\n...",
                                          "About Brokk",
                                          JOptionPane.INFORMATION_MESSAGE);
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    /**
     * Opens the settings dialog
     * @param chrome the Chrome instance
     */
    static void openSettingsDialog(Chrome chrome) {
        var dialog = new SettingsDialog(chrome.frame, chrome);
        dialog.setVisible(true);
    }

    /**
     * Rebuilds the Recent Projects submenu using up to 5 from Project.loadRecentProjects(),
     * sorted by lastOpened descending.
     */
    private static void rebuildRecentProjectsMenu(JMenu recentMenu) {
        recentMenu.removeAll();

        var map = io.github.jbellis.brokk.Project.loadRecentProjects();
        if (map.isEmpty()) {
            var emptyItem = new JMenuItem("(No Recent Projects)");
            emptyItem.setEnabled(false);
            recentMenu.add(emptyItem);
            return;
        }

        var sorted = map.entrySet().stream()
            .sorted((a,b)-> Long.compare(b.getValue(), a.getValue()))
            .limit(5)
            .toList();

        for (var entry : sorted) {
            var path = entry.getKey();
            var item = new JMenuItem(path);
            item.addActionListener(e -> {
                io.github.jbellis.brokk.Brokk.openProject(java.nio.file.Path.of(path));
            });
            recentMenu.add(item);
        }
    }
}
