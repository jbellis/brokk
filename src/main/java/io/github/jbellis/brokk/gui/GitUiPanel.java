package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Static utilities for showing diffs, capturing diffs, or editing files
 * in the Git UI, removing duplicated code across multiple panels.
 */
public final class GitUIUtils
{
    private GitUIUtils() {}

    /**
     * Capture uncommitted diffs for the specified files, adding the result to the context.
     */
    public static void captureUncommittedDiff
    (
            ContextManager contextManager,
            Chrome chrome,
            List<ProjectFile> selectedFiles
    ) {
        if (selectedFiles.isEmpty()) {
            chrome.systemOutput("No files selected to capture diff");
            return;
        }
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }

        contextManager.submitContextTask("Capturing uncommitted diff", () -> {
            try {
                var diff = repo.diffFiles(selectedFiles);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No uncommitted changes found for selected files");
                    return;
                }
                var description = "Diff of %s".formatted(
                        selectedFiles.stream()
                                .map(ProjectFile::getFileName)
                                .collect(Collectors.joining(", "))
                );
                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added uncommitted diff for " + selectedFiles.size() + " file(s) to context");
            } catch (Exception ex) {
                chrome.toolErrorRaw("Error capturing uncommitted diff: " + ex.getMessage());
            }
        });
    }

    /**
     * Show the diff of the uncommitted (working directory) changes for a single file,
     * comparing HEAD vs the local on-disk version.
     */
    public static void showUncommittedFileDiff
    (
            ContextManager contextManager,
            Chrome chrome,
            Component parent,
            String filePath
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        var file = new ProjectFile(contextManager.getRoot(), filePath);
        var diffPanel = new DiffPanel(contextManager);

        var dialogTitle = "Uncommitted Changes: " + file.getFileName();
        diffPanel.showCompareWithLocal("HEAD", file, /*useParent=*/ false);
        diffPanel.showInDialog(parent, dialogTitle);
    }

    /**
     * Open a file in the project’s editor.
     */
    public static void editFile
    (
            ContextManager contextManager,
            String filePath
    ) {
        var file = contextManager.toFile(filePath);
        contextManager.editFiles(List.of(file));
    }

    /**
     * Capture a single file’s historical changes into the context (HEAD vs commitId).
     */
    public static void addFileChangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            String commitId,
            String filePath
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        contextManager.submitContextTask("Adding file change to context", () -> {
            try {
                var file = new ProjectFile(contextManager.getRoot(), filePath);
                var diff = repo.showFileDiff("HEAD", commitId, file);
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found for " + filePath);
                    return;
                }
                var shortHash = (commitId.length() > 7) ? commitId.substring(0, 7) : commitId;
                var description = "git %s (single file)".formatted(shortHash);

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for " + file.getFileName() + " to context");
            } catch (Exception e) {
                chrome.toolErrorRaw("Error adding file change to context: " + e.getMessage());
            }
        });
    }

    /**
     * Show the diff for a single file at a specific commit.
     */
    public static void showFileHistoryDiff
    (
            ContextManager contextManager,
            Chrome chrome,
            Component parent,
            String commitId,
            ProjectFile file
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        var diffPanel = new DiffPanel(contextManager);
        var shortCommitId = (commitId.length() > 7) ? commitId.substring(0, 7) : commitId;
        var dialogTitle = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";

        diffPanel.showFileDiff(commitId, file);
        diffPanel.showInDialog(parent, dialogTitle);
    }

    /**
     * View the file content at a specific commit (opens it in a preview window).
     */
    public static void viewFileAtRevision
    (
            ContextManager contextManager,
            Chrome chrome,
            String commitId,
            String filePath
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        contextManager.submitUserTask("Viewing file at revision", () -> {
            var file = new ProjectFile(contextManager.getRoot(), filePath);
            var content = repo.getFileContent(commitId, file);
            if (content.isEmpty()) {
                chrome.systemOutput("File not found in this revision or is empty.");
                return;
            }
            SwingUtilities.invokeLater(() -> {
                var shortHash = (commitId.length() > 7) ? commitId.substring(0, 7) : commitId;
                var title = "%s at %s".formatted(file.getFileName(), shortHash);
                var fragment = new ContextFragment.StringFragment(content, title);
                chrome.openFragmentPreview(fragment, SyntaxConstants.SYNTAX_STYLE_JAVA);
            });
        });
    }

    /**
     * Add the combined diff of multiple commits to context (from first selected to last).
     */
    public static void addCommitRangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            int[] selectedRows,
            javax.swing.table.TableModel tableModel
    ) {
        contextManager.submitContextTask("Adding commit range to context", () -> {
            try {
                if (selectedRows.length == 0 || tableModel.getRowCount() == 0) {
                    chrome.systemOutput("No commits selected or commits table is empty");
                    return;
                }
                var sorted = selectedRows.clone();
                java.util.Arrays.sort(sorted);
                if (sorted[0] < 0 || sorted[sorted.length - 1] >= tableModel.getRowCount()) {
                    chrome.systemOutput("Invalid commit selection");
                    return;
                }
                var firstCommitId = (String) tableModel.getValueAt(sorted[0], 3);
                var lastCommitId  = (String) tableModel.getValueAt(sorted[sorted.length - 1], 3);

                var repo = contextManager.getProject().getRepo();
                var diff = repo.showDiff(firstCommitId, lastCommitId + "^");
                if (diff.isEmpty()) {
                    chrome.systemOutput("No changes found in the selected commit range");
                    return;
                }

                var changedFiles = repo.listChangedFilesInCommitRange(firstCommitId, lastCommitId);
                var fileNames = changedFiles.stream()
                        .map(ProjectFile::getFileName)
                        .collect(Collectors.toList());
                var filesTxt  = String.join(", ", fileNames);

                var firstShort = firstCommitId.substring(0, 7);
                var lastShort  = lastCommitId.substring(0, 7);
                var hashTxt    = firstCommitId.equals(lastCommitId)
                        ? firstShort
                        : firstShort + ".." + lastShort;
                var description = "Diff of %s [%s]".formatted(filesTxt, hashTxt);

                var fragment = new ContextFragment.StringFragment(diff, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for commit range to context");
            } catch (Exception ex) {
                chrome.toolErrorRaw("Error adding commit range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Add file changes (a subset of the commits range) to the context.
     */
    public static void addFilesChangeToContext
    (
            ContextManager contextManager,
            Chrome chrome,
            int[] selectedRows,
            javax.swing.table.TableModel tableModel,
            List<String> filePaths
    ) {
        contextManager.submitContextTask("Adding file changes from range to context", () -> {
            try {
                if (selectedRows.length == 0 || tableModel.getRowCount() == 0) {
                    chrome.systemOutput("No commits selected or commits table is empty");
                    return;
                }
                var sorted = selectedRows.clone();
                java.util.Arrays.sort(sorted);
                if (sorted[0] < 0 || sorted[sorted.length - 1] >= tableModel.getRowCount()) {
                    chrome.systemOutput("Invalid commit selection");
                    return;
                }
                var firstCommitId = (String) tableModel.getValueAt(sorted[0], 3);
                var lastCommitId  = (String) tableModel.getValueAt(sorted[sorted.length - 1], 3);

                var repo = contextManager.getProject().getRepo();
                var repoFiles = filePaths.stream()
                        .map(fp -> new ProjectFile(contextManager.getRoot(), fp))
                        .toList();

                var diffs = repoFiles.stream()
                        .map(file -> repo.showFileDiff(firstCommitId, lastCommitId + "^", file))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining("\n\n"));
                if (diffs.isEmpty()) {
                    chrome.systemOutput("No changes found for the selected files in the commit range");
                    return;
                }
                var firstShort = firstCommitId.substring(0,7);
                var lastShort  = lastCommitId.substring(0,7);
                var shortHash  = firstCommitId.equals(lastCommitId)
                        ? firstShort
                        : "%s..%s".formatted(firstShort, lastShort);

                var filesTxt = filePaths.stream()
                        .map(fp -> {
                            var slashPos = fp.lastIndexOf('/');
                            return (slashPos >= 0) ? fp.substring(slashPos + 1) : fp;
                        })
                        .collect(Collectors.joining(", "));
                var description = "Diff of %s [%s]".formatted(filesTxt, shortHash);

                var fragment = new ContextFragment.StringFragment(diffs, description);
                contextManager.addVirtualFragment(fragment);
                chrome.systemOutput("Added changes for selected files in commit range to context");
            } catch (Exception ex) {
                chrome.toolErrorRaw("Error adding file changes from range to context: " + ex.getMessage());
            }
        });
    }

    /**
     * Show a simple file diff (commitId vs file) in a new dialog.
     */
    public static void showFileDiffDialog
    (
            ContextManager contextManager,
            Chrome chrome,
            Component parent,
            String commitId,
            String filePath
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        var file = new ProjectFile(contextManager.getRoot(), filePath);
        var diffPanel = new DiffPanel(contextManager);

        var shortCommitId = (commitId.length() > 7) ? commitId.substring(0,7) : commitId;
        var dialogTitle   = "Diff: " + file.getFileName() + " (" + shortCommitId + ")";

        diffPanel.showFileDiff(commitId, file);
        diffPanel.showInDialog(parent, dialogTitle);
    }

    /**
     * Compare a single file from a specific commit to the local (working directory) version.
     * If useParent=true, compares the file's parent commit to local.
     */
    public static void compareFileWithLocal
    (
            ContextManager contextManager,
            Chrome chrome,
            Component parent,
            String commitId,
            String filePath,
            boolean useParent
    ) {
        var repo = contextManager.getProject().getRepo();
        if (repo == null) {
            chrome.toolError("Git repository not available.");
            return;
        }
        var file = new ProjectFile(contextManager.getRoot(), filePath);
        var diffPanel = new DiffPanel(contextManager);

        if (useParent) {
            // Compare commitId^ (parent) to local
            diffPanel.showCompareWithLocal(commitId, file, /*useParent=*/ true);
            var shortHash = commitId + "^";
            if (commitId.length() >= 7) {
                shortHash = commitId.substring(0,7) + "^";
            }
            var title = "Diff: %s [Local vs %s]".formatted(file.getFileName(), shortHash);
            diffPanel.showInDialog(parent, title);
        } else {
            // Compare commitId to local
            diffPanel.showCompareWithLocal(commitId, file, /*useParent=*/ false);
            var shortHash = (commitId.length() >= 7) ? commitId.substring(0, 7) : commitId;
            var title = "Diff: %s [Local vs %s]".formatted(file.getFileName(), shortHash);
            diffPanel.showInDialog(parent, title);
        }
    }
}
