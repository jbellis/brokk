package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A file selection dialog that presents a tree view and a text input with autocomplete
 * for selecting a SINGLE file. Accepts pre-filled autocomplete candidates.
 */
public class FileSelectionDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(FileSelectionDialog.class);

    private final Path rootPath;
    private final FileTree fileTree;
    private final JTextField fileInput; // JTextField for single file input
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;
    private final IGitRepo repo;
    private final Project project;
    private final boolean allowExternalFiles;
    private final Future<List<Path>> autocompletePaths; // For pre-filled options

    // The single selected file
    private BrokkFile selectedFile = null; // Store single selected file

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    /**
     * Constructor for single file selection, potentially with external candidates.
     *
     * @param parent                 Parent frame.
     * @param project                The current project (can be null if allowExternalFiles is true).
     * @param title                  Dialog title.
     * @param allowExternalFiles     If true, shows the full file system.
     * @param fileFilter             Optional predicate to filter files in the tree (external mode only).
     * @param autocompleteCandidates Optional collection of external file paths for autocompletion.
     */
    public FileSelectionDialog(Frame parent, Project project, String title, boolean allowExternalFiles,
                               Predicate<File> fileFilter, Future<List<Path>> autocompleteCandidates)
    {
        super(parent, title, true); // modal dialog
        assert autocompleteCandidates != null;
        this.rootPath = (project != null) ? project.getRoot() : null;
        this.repo = (project != null) ? project.getRepo() : null;
        this.project = project;
        this.allowExternalFiles = allowExternalFiles;
        this.autocompletePaths = autocompleteCandidates;

        // Don't need project if only allowing external files (unless for initial expansion)
        // But repo is needed for repo-based autocompletion/resolution if external candidates aren't used/matched
        // Let's keep the check for simplicity, although allowExternal without project might be okay if only tree is used.
        if (!allowExternalFiles && project == null) {
            throw new IllegalArgumentException("Project must be provided if allowExternalFiles is false");
        }

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input (JTextField) with autocomplete
        fileInput = new JTextField(30); // Use JTextField
        autoCompletion = new AutoCompletion(new FileCompletionProvider());
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput); // Install on JTextField

        // Instantiate FileTree
        fileTree = new FileTree(project, allowExternalFiles, fileFilter);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(fileInput, BorderLayout.CENTER); // Add JTextField

        JPanel labelsPanel = new JPanel(new GridLayout(1, 1)); // Simpler label
        String hintText = "Ctrl-space to autocomplete known files, or select from tree.";
        labelsPanel.add(new JLabel(hintText));
        // No globbing hint needed for single selection typically
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Add FileTree to scroll pane
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(500, 450));
        mainPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Make tree selection update (overwrite) the text field
        fileTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                setFileInputFromNode(node, path); // Use helper method
            }
        });

        // Double-click handler selects and confirms (OK)
        fileTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.isLeaf()) { // Only act on leaf nodes (files)
                            fileTree.setSelectionPath(path); // Select visually
                            if (setFileInputFromNode(node, path)) { // Set text field
                                doOk(); // Confirm dialog
                            }
                        } else {
                            // Double-clicked a directory, toggle expansion
                            if (fileTree.isExpanded(path)) {
                                fileTree.collapsePath(path);
                            } else {
                                fileTree.expandPath(path);
                            }
                        }
                    }
                }
            }
        });


        // Buttons at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk()); // doOk handles single file parsing
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedFile = null; // Clear single selection
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as default
        getRootPane().setDefaultButton(okButton);

        // Tooltip
        fileInput.setToolTipText("Enter a filename or select from tree. Press Enter or click OK to confirm.");

        // Focus input on open
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                fileInput.requestFocusInWindow();
            }
        });

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Sets the fileInput text based on the selected tree node.
     * Returns true if text was set, false otherwise.
     */
    private boolean setFileInputFromNode(DefaultMutableTreeNode node, TreePath path) {
        String filePath = null;
        if (node.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
            // External file or repo file in external mode
            filePath = fileNode.getFile().getAbsolutePath();
        } else if (!allowExternalFiles && node.getUserObject() instanceof String) {
            // Repo file in repo-only mode - reconstruct relative path
            StringBuilder rel = new StringBuilder();
            for (int i = 1; i < path.getPathCount(); i++) { // Skip root
                if (i > 1) rel.append("/");
                rel.append(path.getPathComponent(i).toString());
            }
            filePath = rel.toString();
        }

        if (filePath != null) {
            fileInput.setText(filePath);
            fileInput.requestFocusInWindow();
            fileInput.setCaretPosition(fileInput.getDocument().getLength());
            return true;
        }
        return false;
    }


    /**
     * When OK is pressed, parse the text input to get the single selected file.
     */
    private void doOk() {
        selectedFile = null; // Reset
        String filename = fileInput.getText().trim();

        if (!filename.isEmpty()) {
            Path potentialPath = Path.of(filename);
            BrokkFile resolvedFile = null;

            if (potentialPath.isAbsolute()) {
                if (Files.isRegularFile(potentialPath)) { // Check if it's a file
                    // Check if it's within the current project root
                    if (rootPath != null && potentialPath.startsWith(rootPath)) {
                        Path relPath = rootPath.relativize(potentialPath);
                        resolvedFile = new ProjectFile(rootPath, relPath);
                        logger.debug("Resolved absolute path as RepoFile: {}", resolvedFile);
                    } else if (allowExternalFiles) {
                        resolvedFile = new ExternalFile(potentialPath);
                        logger.debug("Resolved absolute path as ExternalFile: {}", resolvedFile);
                    } else {
                        logger.warn("Absolute path provided is outside the project and external files are not allowed: {}", filename);
                    }
                } else {
                    logger.warn("Absolute path provided is not a regular file or does not exist: {}", filename);
                }
            } else if (repo != null) {
                // Assume relative path within the repo. Use expandPath which handles this.
                try {
                    // Expand should ideally return one file for a non-glob relative path
                    var expanded = Completions.expandPath(project, filename);
                    if (!expanded.isEmpty()) {
                        if (expanded.size() > 1) {
                            logger.warn("Relative path '{}' expanded to multiple files, using first: {}", filename, expanded.getFirst());
                        }
                        resolvedFile = expanded.getFirst(); // Take the first result
                        logger.debug("Resolved relative path as RepoFile: {}", resolvedFile);
                    } else {
                        logger.warn("Relative path provided does not match any file in the repository: {}", filename);
                    }
                } catch (Exception e) {
                    logger.error("Error resolving relative path: {}", filename, e);
                }
            } else if (allowExternalFiles) {
                // Try resolving relative to CWD if no repo and external allowed
                Path cwdRelativePath = Path.of(System.getProperty("user.dir")).resolve(filename).normalize();
                if (Files.isRegularFile(cwdRelativePath)) {
                    logger.debug("Assuming relative path from CWD: {} -> {}", filename, cwdRelativePath);
                    // Check if it falls within the project root if project exists
                    if (rootPath != null && cwdRelativePath.startsWith(rootPath)) {
                        Path relPath = rootPath.relativize(cwdRelativePath);
                        resolvedFile = new ProjectFile(rootPath, relPath);
                    } else {
                        resolvedFile = new ExternalFile(cwdRelativePath);
                    }
                } else {
                    logger.warn("Cannot resolve relative path, repo not available, and CWD relative path is not a file: {}", filename);
                }
            }
            else {
                logger.warn("Cannot resolve relative path without a project/repo: {}", filename);
            }

            if (resolvedFile != null) {
                this.selectedFile = resolvedFile;
                this.confirmed = true;
                dispose();
            } else {
                // Optionally show an error message to the user that the file couldn't be resolved
                JOptionPane.showMessageDialog(this,
                                              "Could not resolve file: " + filename,
                                              "File Not Found",
                                              JOptionPane.ERROR_MESSAGE);
                // Keep dialog open for user to correct input
            }
        } else {
            // No input, just close the dialog without confirmation
            this.confirmed = false;
            dispose();
        }
    }


    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Gets the single selected file, or null if none was selected or confirmed.
     */
    public BrokkFile getSelectedFile() {
        return selectedFile;
    }

    /**
     * Custom CompletionProvider for files. Handles RepoFiles and external Paths.
     * Uses default getAlreadyEnteredText behavior suitable for JTextField.
     */
    private class FileCompletionProvider extends DefaultCompletionProvider {
        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var input = getAlreadyEnteredText(tc);
            if (input.isEmpty()) {
                return Collections.emptyList();
            }
            String lowerInput = input.toLowerCase();

            List<ShorthandCompletion> completions;
            try {
                completions = autocompletePaths.get().stream()
                        .filter(path -> path.toAbsolutePath().toString().toLowerCase().contains(lowerInput))
                        .limit(50)
                        .map(this::createExternalCompletion)
                        .toList();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }

            // Dynamically size the popup windows
            AutoCompleteUtil.sizePopupWindows(autoCompletion, tc, completions);

            // Deduplicate and sort
            return completions.stream()
                    .collect(Collectors.toMap(
                            Completion::getReplacementText, // Replacement text is the full path
                            c -> c,
                            (existing, replacement) -> existing
                    ))
                    .values().stream()
                    .map(shc -> (Completion) shc)
                    .sorted(Comparator.comparing(Completion::getInputText)) // Sort by display text
                    .toList();
        }

        /** Creates a completion item for an external Path. */
        private ShorthandCompletion createExternalCompletion(Path path) {
            String absolutePath = path.toAbsolutePath().toString();
            String shortText = path.getFileName().toString(); // Simpler display for single file
            // Display filename, summary is absolute path, replacement is absolute path
            return new ShorthandCompletion(this, shortText, absolutePath, absolutePath);
        }
    }
}
