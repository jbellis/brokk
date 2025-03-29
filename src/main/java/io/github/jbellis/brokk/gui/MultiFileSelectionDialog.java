package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A file selection dialog that presents a tree view and a text input with autocomplete
 * for selecting MULTIPLE files.
 */
public class MultiFileSelectionDialog extends JDialog { // Renamed class
    private static final Logger logger = LogManager.getLogger(MultiFileSelectionDialog.class); // Updated logger

    private final Path rootPath;
    private final FileTree fileTree; // Uses FileTree
    private final JTextArea fileInput; // Still JTextArea for multiple files
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;
    private final Project project;
    private final boolean allowExternalFiles;
    private final Set<ProjectFile> completableFiles;

    // The selected files
    private List<BrokkFile> selectedFiles = new ArrayList<>();

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    /**
     * Constructor for multiple file selection.
     *
     * @param parent             Parent frame.
     * @param project            The current project (can be null if allowExternalFiles is true and no repo context needed).
     * @param title              Dialog title.
     * @param allowExternalFiles If true, shows the full file system and allows selecting files outside the project.
     * @param completableFiles
     */
    public MultiFileSelectionDialog(Frame parent, Project project, String title, boolean allowExternalFiles, Set<ProjectFile> completableFiles) {
        super(parent, title, true); // modal dialog
        this.completableFiles = completableFiles;
        assert project != null;
        this.rootPath = project.getRoot();
        this.project = project;
        this.allowExternalFiles = allowExternalFiles;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        fileInput = new JTextArea(3, 30); // Keep JTextArea
        fileInput.setLineWrap(true);
        fileInput.setWrapStyleWord(true);
        var provider = (CompletionProvider) new FileCompletionProvider(this.completableFiles, List.of());
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,
                                                            InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInput);

        fileTree = new FileTree(project, allowExternalFiles, f -> true);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(fileInput), BorderLayout.CENTER);

        JPanel labelsPanel = new JPanel(new GridLayout(2, 1));
        // Updated hint text as external candidates are not pre-filled
        String hintText = allowExternalFiles ?
                "Ctrl-space to autocomplete project files. External files may be selected from the tree." :
                "Ctrl-space to autocomplete project files.";
        labelsPanel.add(new JLabel(hintText));
        labelsPanel.add(new JLabel("*/? to glob (project files only); ** to glob recursively"));
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Add FileTree to scroll pane
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(500, 450));
        mainPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Make tree selection append to the text field (Multi-file behavior)
        fileTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                if (node.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
                    appendFilenameToInput(fileNode.getFile().getAbsolutePath());
                } else if (!allowExternalFiles && node.getUserObject() instanceof String) {
                    StringBuilder rel = new StringBuilder();
                    for (int i = 1; i < path.getPathCount(); i++) {
                        String seg = path.getPathComponent(i).toString();
                        if (i > 1) rel.append("/");
                        rel.append(seg);
                    }
                    appendFilenameToInput(rel.toString());
                }
            }
        });

        // Double-click handler appends to input
        fileTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.isLeaf()) { // Only act on leaf nodes (files)
                            fileTree.setSelectionPath(path); // Select visually
                            // Logic mirrors the TreeSelectionListener for appending
                            if (node.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
                                appendFilenameToInput(fileNode.getFile().getAbsolutePath());
                            } else if (!allowExternalFiles && node.getUserObject() instanceof String) {
                                StringBuilder rel = new StringBuilder();
                                for (int i = 1; i < path.getPathCount(); i++) { // Skip root
                                    if (i > 1) rel.append("/");
                                    rel.append(path.getPathComponent(i).toString());
                                }
                                appendFilenameToInput(rel.toString());
                            }
                            // Don't call doOk() on double-click for multi-select
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
        okButton.addActionListener(e -> doOk()); // doOk handles multi-file parsing
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
            selectedFiles.clear();
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as default
        getRootPane().setDefaultButton(okButton);

        // Tooltip
        fileInput.setToolTipText("Enter filenames separated by spaces. Press Enter or click OK to confirm.");

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
     * Appends a filename to the input text area with space separator.
     * Handles quoting for paths with spaces.
     */
    private void appendFilenameToInput(String filename) {
        String currentText = fileInput.getText();
        String formattedFilename = filename.contains(" ") ? "\"" + filename + "\"" : filename;
        if (currentText.isEmpty()) {
            fileInput.setText(formattedFilename + " ");
        } else if (currentText.endsWith(" ")) {
            fileInput.setText(currentText + formattedFilename + " ");
        } else {
            fileInput.setText(currentText + " " + formattedFilename + " ");
        }
        fileInput.requestFocusInWindow();
        fileInput.setCaretPosition(fileInput.getDocument().getLength());
    }

    /**
     * When OK is pressed, parse the text input to get multiple selected files.
     * This logic remains largely the same as the original FileSelectionDialog.
     */
    private void doOk() {
        confirmed = true;
        selectedFiles.clear();
        String typed = fileInput.getText().trim();

        if (!typed.isEmpty()) {
            // Split by whitespace, handling quoted paths
            List<String> filenames = new ArrayList<>();
            StringBuilder currentToken = new StringBuilder();
            boolean inQuotes = false;
            for (char c : typed.toCharArray()) {
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (Character.isWhitespace(c) && !inQuotes) {
                    if (currentToken.length() > 0) {
                        filenames.add(currentToken.toString());
                        currentToken.setLength(0);
                    }
                } else {
                    currentToken.append(c);
                }
            }
            if (currentToken.length() > 0) {
                filenames.add(currentToken.toString());
            }


            Map<Path, BrokkFile> uniqueFiles = new HashMap<>();

            for (String filename : filenames) {
                if (filename.isBlank()) continue;

                Path potentialPath = Path.of(filename);
                if (allowExternalFiles && potentialPath.isAbsolute()) {
                    if (Files.exists(potentialPath)) {
                        if (rootPath != null && potentialPath.startsWith(rootPath)) {
                            Path relPath = rootPath.relativize(potentialPath);
                            uniqueFiles.put(potentialPath, new ProjectFile(rootPath, relPath));
                        } else {
                            uniqueFiles.put(potentialPath, new ExternalFile(potentialPath));
                        }
                    } else {
                        logger.warn("Absolute path provided does not exist: {}", filename);
                    }
                } else {
                    // Assume relative path or glob within repo
                    try {
                        var expanded = Completions.expandPath(project, filename);
                        for (BrokkFile file : expanded) {
                            uniqueFiles.put(file.absPath(), file);
                        }
                    } catch (Exception e) {
                        logger.error("Error expanding path/glob: {}", filename, e);
                    }
                }
            }
            selectedFiles.addAll(uniqueFiles.values());
        }
        dispose();
    }


    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Gets the list of selected files.
     */
    public List<BrokkFile> getSelectedFiles() {
        return selectedFiles;
    }

    /**
     * Custom CompletionProvider for files. Handles RepoFiles and potentially external Paths if logic changes.
     * Crucially, overrides getAlreadyEnteredText for multi-file input area.
     */
    public class FileCompletionProvider extends DefaultCompletionProvider {
        private final Collection<ProjectFile> projectFiles;
        private final Collection<Path> externalCandidates; // Kept in signature, but expected to be empty for MFSD

        public FileCompletionProvider(Collection<ProjectFile> projectFiles, Collection<Path> externalCandidates) {
            super();
            assert projectFiles != null;
            assert externalCandidates != null;
            this.projectFiles = projectFiles;
            this.externalCandidates = externalCandidates;
            // Assert externalCandidates is empty if this provider is strictly for MFSD use
            assert this.externalCandidates.isEmpty() : "MultiFileSelectionDialog should not have pre-filled external candidates";
        }

        // Override to complete only the current token (word separated by whitespace, respecting quotes)
        // This is essential for the multi-file JTextArea.
        @Override
        public String getAlreadyEnteredText(javax.swing.text.JTextComponent comp) {
            String text = comp.getText();
            int caretPos = comp.getCaretPosition();
            int start = caretPos;
            boolean inQuotes = false;

            // Find start of current token, respecting quotes
            while (start > 0) {
                char c = text.charAt(start - 1);
                if (c == '"') {
                    // Simple toggle; assumes non-escaped quotes define token boundaries
                    // If the character *before* start is a quote, we might be inside quoted text
                    // Need to check if the quote *opens* or *closes* the quoted section relative to caret
                    // This simple approach works okay if caret is *after* the opening quote.
                    boolean balanced = true;
                    for(int i = start - 1; i < caretPos; i++) {
                        if (text.charAt(i) == '"') balanced = !balanced;
                    }
                    inQuotes = !balanced; // If unbalanced up to caret, we are inside quotes

                } else if (Character.isWhitespace(c) && !inQuotes) {
                    // Stop if we hit whitespace outside of quotes
                    break;
                }
                start--;
            }

            // If the first char of the found token is a quote, advance start
            if (start < text.length() && text.charAt(start) == '"') {
                // Check if caret is also inside these quotes
                boolean caretInQuotes = false;
                boolean quoteOpen = false;
                for(int i=start; i < caretPos; i++) {
                    if (text.charAt(i) == '"') quoteOpen = !quoteOpen;
                }
                if (quoteOpen) { // Caret is inside the quotes that start at 'start'
                    start++; // Start completing *after* the opening quote
                }
            }


            // Return text from adjusted start to caret
            if (start < caretPos) {
                return text.substring(start, caretPos);
            } else {
                return "";
            }
        }


        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var input = getAlreadyEnteredText(tc);
            // No external candidates to check for MFSD based on current design
            if (input.isEmpty() && projectFiles.isEmpty()) {
                return Collections.emptyList();
            }

            List<ShorthandCompletion> completions = new ArrayList<>();

            if (!projectFiles.isEmpty()) {
                if (input.contains("*") || input.contains("?")) {
                    try {
                        // Completions.expandPath might be too slow/broad for interactive completion.
                        // Consider limiting depth or using a simpler glob matcher if performance is an issue.
                        // For now, stick with existing logic but limit results.
                        completions.addAll(Completions.expandPath(project, input).stream()
                                                   .filter(bf -> bf instanceof ProjectFile)
                                                   .map(bf -> createRepoCompletion((ProjectFile)bf))
                                                   .limit(100) // Limit glob results
                                                   .toList());
                    } catch (Exception e) {
                        logger.warn("Error during glob completion: {}", e.getMessage());
                    }
                } else if (!input.isEmpty()) { // Only do prefix matching if there's non-glob input
                    // Simple prefix matching for repo files otherwise
                    completions.addAll(Completions.getFileCompletions(input, projectFiles).stream()
                                               .map(this::createRepoCompletion)
                                               .limit(100) // Limit prefix results
                                               .toList());
                }
                // If input is empty, we might want to show top-level repo dirs/files? For now, require some input.
            }

            // Dynamically size the popup windows
            AutoCompleteUtil.sizePopupWindows(autoCompletion, tc, completions);

            // Deduplicate and sort
            return completions.stream()
                    .collect(Collectors.toMap(
                            Completion::getReplacementText,
                            c -> c,
                            (existing, replacement) -> existing
                    ))
                    .values().stream()
                    .map(shc -> (Completion) shc)
                    .sorted(Comparator.comparing(Completion::getInputText))
                    .toList();
        }

        // Creates a completion item for a RepoFile.
        private ShorthandCompletion createRepoCompletion(ProjectFile file) {
            String relativePath = file.toString();
            // Add trailing space, quote if needed. Replacement should replace the token + add space.
            String replacement = relativePath.contains(" ") ? "\"" + relativePath + "\" " : relativePath + " ";
            // Display filename, summary is relative path, replacement is quoted/spaced relative path
            return new ShorthandCompletion(this, file.getFileName(), replacement, relativePath);
        }
    }
}
