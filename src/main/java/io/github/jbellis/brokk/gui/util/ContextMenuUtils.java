package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.TableUtils;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

/**
 * Utility class for creating and displaying context menus for file references.
 */
public final class ContextMenuUtils {
    
    /**
     * Handles mouse clicks on file reference badges in a table.
     * Shows the appropriate context menu or overflow popup based on the click location and type.
     *
     * @param e The mouse event
     * @param table The table containing file reference badges
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void handleFileReferenceClick(MouseEvent e, JTable table, Chrome chrome, Runnable onRefreshSuggestions) {
        handleFileReferenceClick(e, table, chrome, onRefreshSuggestions, 0);
    }

    /**
     * Handles mouse clicks on file reference badges in a table with a specified column index.
     * Shows the appropriate context menu or overflow popup based on the click location and type.
     *
     * @param e The mouse event
     * @param table The table containing file reference badges
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     * @param columnIndex The column index containing the file references
     */
    public static void handleFileReferenceClick(MouseEvent e, JTable table, Chrome chrome, Runnable onRefreshSuggestions, int columnIndex) {
        assert SwingUtilities.isEventDispatchThread();
        
        Point p = e.getPoint();
        int row = table.rowAtPoint(p);
        if (row < 0) return;

        // Always select the row for visual feedback
        table.requestFocusInWindow();
        table.setRowSelectionInterval(row, row);
        
        @SuppressWarnings("unchecked")
        var fileRefs = (List<FileReferenceData>)
                table.getValueAt(row, columnIndex);

        if (fileRefs == null || fileRefs.isEmpty()) return;
        
        // Get the renderer and extract visible/hidden files using reflection
        Component renderer = table.prepareRenderer(
            table.getCellRenderer(row, columnIndex), row, columnIndex);
        
        // Extract needed data from renderer using pattern matching
        List<FileReferenceData> visibleFiles;
        List<FileReferenceData> hiddenFiles = List.of();
        boolean hasOverflow = false;
        
        if (renderer instanceof TableUtils.FileReferenceList.AdaptiveFileReferenceList afl) {
            // Direct method calls on the casted object
            visibleFiles = afl.getVisibleFiles();
            hasOverflow = afl.hasOverflow();
            if (hasOverflow) {
                hiddenFiles = afl.getHiddenFiles();
            }
        } else {
            // Fallback if not the expected renderer type
            visibleFiles = fileRefs;
            // Log the issue but continue (avoid breaking the UI)
            System.err.println("Unexpected renderer type: " + renderer.getClass().getName());
        }
        
        // Check what kind of mouse event we're handling
        if (e.isPopupTrigger()) {
            // Right-click (context menu)
            var targetRef = TableUtils.findClickedReference(p, row, columnIndex, table, visibleFiles);
            
            // Right-click on overflow badge?
            if (targetRef == null && hasOverflow) {
                TableUtils.showOverflowPopup(chrome, table, row, columnIndex, hiddenFiles);
                e.consume(); // Prevent further listeners from acting on this event
                return;
            }
            
            // Default to first file if click wasn't on a specific badge
            if (targetRef == null) targetRef = fileRefs.get(0);
            
            // Show the context menu near the mouse click location
            showFileRefMenu(
                table,
                e.getX(),
                e.getY(),
                targetRef,
                chrome,
                onRefreshSuggestions
            );
            e.consume(); // Prevent further listeners from acting on this event
        } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
            // Left-click
            var targetRef = TableUtils.findClickedReference(p, row, columnIndex, table, visibleFiles);
            
            // If no visible badge was clicked AND we have overflow
            if (targetRef == null && hasOverflow) {
                // Show the overflow popup with only the hidden files
                TableUtils.showOverflowPopup(chrome, table, row, columnIndex, hiddenFiles);
                e.consume(); // Prevent further listeners from acting on this event
            }
        }
    }
    
   
    // Private constructor to prevent instantiation
    private ContextMenuUtils() {
    }
    
    /**
     * Shows a context menu for a file reference.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(Component owner, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        showFileRefMenu(owner, 0, 0, fileRefData, chrome, onRefreshSuggestions);
    }
    
    /**
     * Shows a context menu for a file reference at the specified position.
     *
     * @param owner The component that owns the popup (where it will be displayed)
     * @param x The x position for the menu relative to the owner
     * @param y The y position for the menu relative to the owner
     * @param fileRefData The file reference data for which to show the menu
     * @param chrome The Chrome instance for UI integration
     * @param onRefreshSuggestions Runnable to call when "Refresh Suggestions" is selected
     */
    public static void showFileRefMenu(Component owner, int x, int y, Object fileRefData, Chrome chrome, Runnable onRefreshSuggestions) {
        // Convert to our FileReferenceData - we know it's always this type from all callers
        TableUtils.FileReferenceList.FileReferenceData targetRef = 
            (TableUtils.FileReferenceList.FileReferenceData) fileRefData;
        
        var cm = chrome.getContextManager();
        JPopupMenu menu = new JPopupMenu();

        JMenuItem showContentsItem = new JMenuItem("Show Contents");
        showContentsItem.addActionListener(e1 -> {
            if (targetRef.getRepoFile() != null) {
                chrome.openFragmentPreview(new ContextFragment.ProjectPathFragment(targetRef.getRepoFile()));
            }
        });
        menu.add(showContentsItem);
        menu.addSeparator();

        // Edit option
        JMenuItem editItem = new JMenuItem("Edit " + targetRef.getFullPath());
        editItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() != null) {
                    cm.editFiles(List.of(targetRef.getRepoFile()));
                } else {
                    chrome.toolErrorRaw("Cannot edit file: " + targetRef.getFullPath() + " - no ProjectFile available");
                }
            }, "Edit files");
        });
        // Disable for dependency projects
        if (cm.getProject() != null && !cm.getProject().hasGit()) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available without Git");
        }
        menu.add(editItem);

        // Read option
        JMenuItem readItem = new JMenuItem("Read " + targetRef.getFullPath());
        readItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() != null) {
                    cm.addReadOnlyFiles(List.of(targetRef.getRepoFile()));
                } else {
                    chrome.toolErrorRaw("Cannot read file: " + targetRef.getFullPath() + " - no ProjectFile available");
                }
            }, "Read files");
        });
        menu.add(readItem);

        // Summarize option
        JMenuItem summarizeItem = new JMenuItem("Summarize " + targetRef.getFullPath());
        summarizeItem.addActionListener(e1 -> {
            withTemporaryListenerDetachment(chrome, cm, () -> {
                if (targetRef.getRepoFile() == null) {
                    chrome.toolErrorRaw("Cannot summarize: " + targetRef.getFullPath() + " - ProjectFile information not available");
                } else {
                    boolean success = cm.addSummaries(Set.of(targetRef.getRepoFile()), Set.of());
                    if (!success) {
                        chrome.toolErrorRaw("No summarizable code found");
                    }
                }
            }, "Summarize files");
        });
        menu.add(summarizeItem);
        menu.addSeparator();

        JMenuItem refreshSuggestionsItem = new JMenuItem("Refresh Suggestions");
        refreshSuggestionsItem.addActionListener(e1 -> onRefreshSuggestions.run());
        menu.add(refreshSuggestionsItem);

        // Theme management will be handled by the caller
        menu.show(owner, x, y);
    }
    
    /**
     * Helper method to detach context listener temporarily while performing operations.
     */
    private static void withTemporaryListenerDetachment(Chrome chrome, ContextManager cm, Runnable action, String taskDescription) {
        // Access the contextManager from Chrome and call submitContextTask on it
        chrome.getContextManager().submitContextTask(taskDescription, action);
    }
}
