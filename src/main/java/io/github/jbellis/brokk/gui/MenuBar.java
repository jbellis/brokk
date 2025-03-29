package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
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

        var editKeysItem = new JMenuItem("Edit secret keys");
        editKeysItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editKeysItem.addActionListener(e -> {
            chrome.showSecretKeysDialog();
            if (chrome.contextManager != null) {
                // Reopen the current project to create new Models and Coder with updated keys
                var currentPath = chrome.contextManager.getProject().getRoot();
                if (currentPath != null) {
                    io.github.jbellis.brokk.Brokk.openProject(currentPath);
                }
            }
        });
        fileMenu.add(editKeysItem);

        fileMenu.addSeparator();

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            chrome.contextManager.requestRebuild();
            chrome.systemOutput("Code intelligence will refresh in the background");
        });
        refreshItem.setEnabled(hasProject);
        fileMenu.add(refreshItem);

        fileMenu.addSeparator();

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
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = chrome.contextManager.undoContextAsync();
        });
        undoItem.setEnabled(hasProject);
        editMenu.add(undoItem);

        var redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                       Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        redoItem.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = chrome.contextManager.redoContextAsync();
        });
        redoItem.setEnabled(hasProject);
        editMenu.add(redoItem);

        editMenu.addSeparator();

        var copyMenuItem = new JMenuItem("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyMenuItem.addActionListener(e -> {
            var selectedFragments = chrome.getSelectedFragments();
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(Chrome.ContextAction.COPY, selectedFragments);
        });
        copyMenuItem.setEnabled(hasProject);
        editMenu.add(copyMenuItem);

        var pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteMenuItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(Chrome.ContextAction.PASTE, List.of());
        });
        pasteMenuItem.setEnabled(hasProject);
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);

        // Context menu
        var contextMenu = new JMenu("Context");

        var editFilesItem = new JMenuItem("Edit Files");
        editFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        editFilesItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(
                    Chrome.ContextAction.EDIT, List.of());
        });
        editFilesItem.setEnabled(hasProject && chrome.getProject().hasGit());
        contextMenu.add(editFilesItem);

        var readFilesItem = new JMenuItem("Read Files");
        readFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        readFilesItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(
                    Chrome.ContextAction.READ, List.of());
        });
        readFilesItem.setEnabled(hasProject);
        contextMenu.add(readFilesItem);

        var summarizeFilesItem = new JMenuItem("Summarize Files");
        summarizeFilesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        summarizeFilesItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(
                    Chrome.ContextAction.SUMMARIZE, List.of());
        });
        summarizeFilesItem.setEnabled(hasProject);
        contextMenu.add(summarizeFilesItem);

        var symbolUsageItem = new JMenuItem("Symbol Usage");
        symbolUsageItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        symbolUsageItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.findSymbolUsageAsync();
        });
        symbolUsageItem.setEnabled(hasProject);
        contextMenu.add(symbolUsageItem);

        var callersItem = new JMenuItem("Call graph to function");
        callersItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        callersItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.findMethodCallersAsync();
        });
        callersItem.setEnabled(hasProject);
        contextMenu.add(callersItem);

        var calleesItem = new JMenuItem("Call graph from function");
        calleesItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        calleesItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.findMethodCalleesAsync();
        });
        calleesItem.setEnabled(hasProject);
        contextMenu.add(calleesItem);

        var dropAllItem = new JMenuItem("Drop All");
        dropAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        dropAllItem.addActionListener(e -> {
            chrome.disableContextActionButtons();
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(
                    Chrome.ContextAction.DROP, List.of());
        });
        dropAllItem.setEnabled(hasProject);
        contextMenu.add(dropAllItem);

        menuBar.add(contextMenu);

        // Help menu
        var helpMenu = new JMenu("Help");

        // Theme submenu
        var themeMenu = new JMenu("Theme");
        
        var lightThemeItem = new JMenuItem("Light");
        lightThemeItem.addActionListener(e -> chrome.switchTheme(false));
        lightThemeItem.setToolTipText("Switch to light theme");

        var darkThemeItem = new JMenuItem("Dark");
        darkThemeItem.addActionListener(e -> chrome.switchTheme(true));
        darkThemeItem.setToolTipText("Switch to dark theme");
        
        themeMenu.add(lightThemeItem);
        themeMenu.add(darkThemeItem);
        helpMenu.add(themeMenu);
        
        helpMenu.addSeparator();
        
        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(chrome.frame,
                                          "Brokk Swing UI\nVersion X\n...",
                                          "About Brokk",
                                          JOptionPane.INFORMATION_MESSAGE);
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
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
