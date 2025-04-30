package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.gui.AutoCompleteUtil;
import io.github.jbellis.brokk.gui.FileTree;
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
import java.awt.event.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * A selection dialog that presents a tabbed interface for selecting
 * MULTIPLE files and/or MULTIPLE classes.
 */
public class MultiFileSelectionDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(MultiFileSelectionDialog.class);

    /**
     * Enum to specify which selection modes are available.
     */
    public enum SelectionMode {
        FILES, CLASSES
    }

    /**
     * Record to hold the results of the selection.
     * Usually, only one list will be non-null, but callers should handle the case where both might be populated.
     * If neither is populated, the dialog was cancelled.
     *
     * @param files   List of selected BrokkFile objects, or null.
     * @param classes List of selected CodeUnit objects (classes), or null.
     */
    public record Selection(List<BrokkFile> files, List<CodeUnit> classes) {
        public boolean isEmpty() {
            return (files == null || files.isEmpty()) && (classes == null || classes.isEmpty());
        }
    }

    private final Path rootPath;
    private final Project project;
    private final AnalyzerWrapper analyzer;
    private final boolean allowExternalFiles;
    private final Future<Set<ProjectFile>> completableFiles;
    private final Set<SelectionMode> modes;

    // UI Components - Files Tab
    private FileTree fileTree;
    private JTextArea fileInput;
    private AutoCompletion fileAutoCompletion;

    // UI Components - Classes Tab
    private JTextArea classInput;
    private AutoCompletion classAutoCompletion;

    // Common UI Components
    private JTabbedPane tabbedPane;
    private final JButton okButton;
    private final JButton cancelButton;

    // Result
    private Selection selectionResult = null; // Store the result here

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    // Dedicated executor for background tasks within this dialog (e.g., symbol completion loading)
    private final ExecutorService backgroundExecutor;

    /**
     * Constructor for multiple source selection.
     *
     * @param parent             Parent frame.
     * @param project            The current project.
     * @param title              Dialog title.
     * @param allowExternalFiles If true, shows the full file system and allows selecting files outside the project (Files tab only).
     * @param completableFiles   Set of project files for file completion.
     * @param modes              Set of allowed selection modes (determines which tabs are shown).
     */
    public MultiFileSelectionDialog(Frame parent, Project project, String title, boolean allowExternalFiles, Future<Set<ProjectFile>> completableFiles, Set<SelectionMode> modes)
    {
        super(parent, title, true); // modal dialog
        assert parent != null;
        assert project != null;
        assert title != null;
        assert completableFiles != null;
        assert modes != null && !modes.isEmpty();

        this.project = project;
        this.analyzer = project.getAnalyzerWrapper();
        this.rootPath = project.getRoot();
        this.allowExternalFiles = allowExternalFiles;
        this.completableFiles = completableFiles;
        this.modes = modes;

        // Create a dedicated executor for this dialog instance
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MultiFileSelectionDialog-BG");
            t.setDaemon(true); // Allow JVM exit even if this thread is running
            return t;
        });

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        tabbedPane = new JTabbedPane();

        // --- Create Files Tab (if requested) ---
        if (modes.contains(SelectionMode.FILES)) {
            tabbedPane.addTab("Files", createFileSelectionPanel());
        }

        // --- Create Classes Tab (if requested) ---
        if (modes.contains(SelectionMode.CLASSES) && analyzer.isCpg()) {
            tabbedPane.addTab("Classes", createClassSelectionPanel());
        }

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // --- Buttons at the bottom ---
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
            selectionResult = null; // Clear result on escape
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as default
        getRootPane().setDefaultButton(okButton);

        // Focus input on open - focus the input of the initially selected tab
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(() -> { // Ensure components are ready
                    Component selectedComponent = tabbedPane.getSelectedComponent();
                    if (selectedComponent != null) {
                        // Find the relevant JTextArea within the selected tab's panel
                        if (selectedComponent.getName() != null && selectedComponent.getName().equals("FilesPanel") && fileInput != null) {
                            fileInput.requestFocusInWindow();
                        } else if (selectedComponent.getName() != null && selectedComponent.getName().equals("ClassesPanel") && classInput != null) {
                            classInput.requestFocusInWindow();
                        }
                    }
                });
            }

            // Ensure the background executor is shut down when the dialog is closed
            @Override
            public void windowClosed(WindowEvent e) {
                backgroundExecutor.shutdown();
            }
        });


        setContentPane(mainPanel);
        pack();
        // Adjust size slightly - might need tweaking based on content
        setSize(Math.max(600, getWidth()), Math.max(550, getHeight()));
        setLocationRelativeTo(parent);
    }

    /**
     * Creates the panel containing components for file selection.
     */
    private JPanel createFileSelectionPanel() {
        JPanel filesPanel = new JPanel(new BorderLayout(8, 8));
        filesPanel.setName("FilesPanel"); // For focusing logic

        // Build text input with autocomplete at the top
        fileInput = new JTextArea(3, 30);
        fileInput.setLineWrap(true);
        fileInput.setWrapStyleWord(true);
        // Pass allowExternalFiles to the provider
        var provider = new FileCompletionProvider(this.completableFiles, this.allowExternalFiles);
        fileAutoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        fileAutoCompletion.setAutoActivationEnabled(false);
        fileAutoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        fileAutoCompletion.install(fileInput);
        AutoCompleteUtil.bindCtrlEnter(fileAutoCompletion, fileInput); // Bind Ctrl+Enter to OK action for this input
        fileInput.setToolTipText("Enter filenames (space-separated, use quotes for spaces). Ctrl+Space for autocomplete. Enter/Ctrl+Enter to confirm.");


        fileTree = new FileTree(project, allowExternalFiles, f -> true);
        // Show a loading placeholder while the tree loads in the background
        fileTree.showLoadingPlaceholder();
        SwingUtilities.invokeLater(() -> {
            fileTree.loadTreeInBackground();
        });

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(fileInput), BorderLayout.CENTER);

        JPanel labelsPanel = new JPanel(new GridLayout(2, 1));
        String hintText = allowExternalFiles ? "Ctrl-space to autocomplete project files. External files may be selected from the tree." : "Ctrl-space to autocomplete project files.";
        labelsPanel.add(new JLabel(hintText));
        labelsPanel.add(new JLabel("*/? to glob (project files only); ** to glob recursively"));
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        filesPanel.add(inputPanel, BorderLayout.NORTH);

        // Add FileTree to scroll pane
        JScrollPane treeScrollPane = new JScrollPane(fileTree);
        treeScrollPane.setPreferredSize(new Dimension(500, 350)); // Adjusted size for tab
        filesPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Make tree selection append to the text field
        fileTree.addTreeSelectionListener(e -> {
            TreePath path = e.getPath();
            if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node && node.isLeaf()) {
                handleFileTreeNodeSelection(node);
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
                        if (node.isLeaf()) {
                            fileTree.setSelectionPath(path);
                            handleFileTreeNodeSelection(node);
                        } else {
                            if (fileTree.isExpanded(path)) fileTree.collapsePath(path);
                            else fileTree.expandPath(path);
                        }
                    }
                }
            }
        });

        return filesPanel;
    }

    /**
     * Creates the panel containing components for class selection.
     */
    private JPanel createClassSelectionPanel() {
        JPanel classesPanel = new JPanel(new BorderLayout(8, 8));
        classesPanel.setName("ClassesPanel"); // For focusing logic

        // Text input area for classes
        classInput = new JTextArea(3, 30);
        classInput.setLineWrap(true);
        classInput.setWrapStyleWord(true);

        // Autocomplete for classes with background loading using the dialog's executor
        var provider = new SymbolCompletionProvider(analyzer, backgroundExecutor);
        classAutoCompletion = new AutoCompletion(provider);
        classAutoCompletion.setAutoActivationEnabled(false);
        classAutoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        classAutoCompletion.install(classInput);
        AutoCompleteUtil.bindCtrlEnter(classAutoCompletion, classInput); // Bind Ctrl+Enter to OK action
        classInput.setToolTipText("Enter fully qualified class names (space-separated). Ctrl+Space for autocomplete. Enter/Ctrl+Enter to confirm.");
        // Completion data is loaded in the background by the provider's constructor


        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(classInput), BorderLayout.CENTER);

        JPanel labelsPanel = new JPanel(new GridLayout(1, 1)); // Simpler label for classes
        labelsPanel.add(new JLabel("Ctrl-space to autocomplete class names."));
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        classesPanel.add(inputPanel, BorderLayout.NORTH);

        // Add some empty space perhaps, or a different component later if needed
        classesPanel.add(new Box.Filler(new Dimension(0, 0), new Dimension(500, 350), new Dimension(Short.MAX_VALUE, Short.MAX_VALUE)), BorderLayout.CENTER);


        return classesPanel;
    }


    /**
     * Handles selection of a node in the file tree (single or double click).
     */
    private void handleFileTreeNodeSelection(DefaultMutableTreeNode node) {
        if (node.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
            appendFilenameToInput(fileNode.getFile().getAbsolutePath());
        } else if (!allowExternalFiles && node.getUserObject() instanceof String filename) {
            // This case might be simplified if FileTreeNode is always used.
            // Reconstruct relative path using Path methods for platform neutrality.
            Object[] pathComponents = node.getPath();
            if (pathComponents.length > 1) {
                // Start with the first component after the root
                Path relativePath = Path.of(pathComponents[1].toString());
                // Join subsequent components
                for (int i = 2; i < pathComponents.length; i++) {
                    relativePath = relativePath.resolve(pathComponents[i].toString());
                }
                appendFilenameToInput(relativePath.toString());
            }
        }
    }

    /**
     * Appends a filename to the file input text area with space separator.
     * Handles quoting for paths with spaces.
     */
    private void appendFilenameToInput(String filename) {
        String currentText = fileInput.getText();
        String formattedFilename = filename.contains(" ") ? "\"" + filename + "\"" : filename;
        String textToAppend = formattedFilename + " "; // Always add trailing space

        int caretPos = fileInput.getCaretPosition();
        // If caret is at the end, or text ends with space, just append
        if (caretPos == currentText.length() || currentText.endsWith(" ")) {
            fileInput.insert(textToAppend, caretPos);
        } else {
            // Insert with a leading space if needed
            fileInput.insert(" " + textToAppend, caretPos);
        }

        fileInput.requestFocusInWindow();
        // Move caret after the inserted text
        // fileInput.setCaretPosition(caretPos + textToAppend.length() + (currentText.endsWith(" ") ? 0 : 1));
        // Simpler: move caret to end after insertion
        fileInput.setCaretPosition(fileInput.getDocument().getLength());
    }

    /**
     * Appends a fully qualified class name to the class input text area.
     */
    private void appendClassNameToInput(String fqn) {
        String currentText = classInput.getText();
        String textToAppend = fqn + " "; // Always add trailing space

        int caretPos = classInput.getCaretPosition();
        // If caret is at the end, or text ends with space, just append
        if (caretPos == currentText.length() || currentText.endsWith(" ")) {
            classInput.insert(textToAppend, caretPos);
        } else {
            // Insert with a leading space if needed
            classInput.insert(" " + textToAppend, caretPos);
        }

        classInput.requestFocusInWindow();
        classInput.setCaretPosition(classInput.getDocument().getLength());
    }

    // Removed duplicate appendFilenameToInput method

    /**
     * When OK is pressed, determine the active tab and parse its input.
     * Populates the selectionResult record.
     */
    private void doOk() {
        Component selectedComponent = tabbedPane.getSelectedComponent();
        List<BrokkFile> filesResult = null;
        List<CodeUnit> classesResult = null;

        if (selectedComponent == null) {
            logger.warn("No tab selected in MultiFileSelectionDialog");
            confirmed = false;
            selectionResult = null;
            dispose();
            return;
        }

        String componentName = selectedComponent.getName();

        if ("FilesPanel".equals(componentName) && fileInput != null) {
            // --- Parse Files Tab ---
            String typedFiles = fileInput.getText().trim();
            if (!typedFiles.isEmpty()) {
                filesResult = parseAndResolveFiles(typedFiles);
            }
        } else if ("ClassesPanel".equals(componentName) && classInput != null) {
            // --- Parse Classes Tab ---
            String typedClasses = classInput.getText().trim();
            if (!typedClasses.isEmpty()) {
                classesResult = parseAndResolveClasses(typedClasses);
            }
        } else {
            logger.warn("Unknown or unexpected tab selected: {}", componentName);
            // Default to trying files tab if available, otherwise classes
            if (modes.contains(SelectionMode.FILES) && fileInput != null) {
                String typedFiles = fileInput.getText().trim();
                if (!typedFiles.isEmpty()) filesResult = parseAndResolveFiles(typedFiles);
            } else if (modes.contains(SelectionMode.CLASSES) && classInput != null) {
                String typedClasses = classInput.getText().trim();
                if (!typedClasses.isEmpty()) classesResult = parseAndResolveClasses(typedClasses);
            }
        }

        // Create the result record
        selectionResult = new Selection((filesResult != null && !filesResult.isEmpty()) ? List.copyOf(filesResult) : null, (classesResult != null && !classesResult.isEmpty()) ? List.copyOf(classesResult) : null);

        // Only confirm if we actually got some selection
        confirmed = !selectionResult.isEmpty();

        if (!confirmed) {
            selectionResult = null; // Ensure null result if nothing was selected/resolved
        }

        dispose();
    }

    /**
     * Parses space-separated filenames (handling quotes) and resolves them to BrokkFile objects.
     */
    private List<BrokkFile> parseAndResolveFiles(String inputText) {
        // Split by whitespace, handling quoted paths
        List<String> filenames = splitQuotedString(inputText);
        logger.debug("Raw files parsed: {}", filenames);

        Map<Path, BrokkFile> uniqueFiles = new HashMap<>();
        for (String filename : filenames) {
            if (filename.isBlank()) continue;

            Path potentialPath = Path.of(filename);
            if (allowExternalFiles && potentialPath.isAbsolute()) {
                if (Files.exists(potentialPath)) {
                    if (rootPath != null && potentialPath.startsWith(rootPath)) {
                        // Convert absolute path within project back to ProjectFile
                        Path relPath = rootPath.relativize(potentialPath);
                        uniqueFiles.put(potentialPath, new ProjectFile(rootPath, relPath));
                    } else {
                        uniqueFiles.put(potentialPath, new ExternalFile(potentialPath));
                    }
                } else {
                    logger.warn("Absolute path provided does not exist: {}", filename);
                }
            } else {
                // Assume relative path or glob within project
                try {
                    var expanded = Completions.expandPath(project, filename);
                    for (BrokkFile file : expanded) {
                        // Ensure we store ProjectFiles correctly even if expanded from abs path within project
                        if (file instanceof ExternalFile && file.absPath().startsWith(rootPath)) {
                            Path relPath = rootPath.relativize(file.absPath());
                            uniqueFiles.put(file.absPath(), new ProjectFile(rootPath, relPath));
                        } else {
                            uniqueFiles.put(file.absPath(), file);
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error expanding path/glob: {}", filename, e);
                }
            }
        }
        logger.debug("Resolved unique files: {}", uniqueFiles.values());
        return new ArrayList<>(uniqueFiles.values());
    }

    /**
     * Parses space-separated class names and resolves them to CodeUnit objects using the IAnalyzer.
     */
    private List<CodeUnit> parseAndResolveClasses(String inputText) {
        List<String> classNames = splitQuotedString(inputText); // Use same splitting logic
        logger.debug("Raw class names parsed: {}", classNames);

        List<CodeUnit> resolvedClasses = new ArrayList<>();
        IAnalyzer az;
        try {
            az = analyzer.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assert az != null && az.isCpg();

        // Get all potential code units using Completions utility and filter for classes
        // This aligns with how SymbolCompletionProvider works and avoids assuming IAnalyzer.getClasses()
        Map<String, CodeUnit> knownClasses = Completions.completeSymbols("", az).stream().filter(cu -> cu.kind() == CodeUnitType.CLASS).collect(Collectors.toMap(CodeUnit::fqName, cu -> cu, (cu1, cu2) -> cu1)); // Handle potential duplicates

        for (String className : classNames) {
            if (className.isBlank()) continue;

            CodeUnit found = knownClasses.get(className);
            if (found != null) {
                resolvedClasses.add(found);
            } else {
                // Maybe it's a short name? Try completing and finding exact match.
                // This adds complexity; for now, require FQN.
                logger.warn("Could not resolve class name: {}", className);
            }
        }
        logger.debug("Resolved unique classes: {}", resolvedClasses.stream().map(CodeUnit::fqName).toList());
        return resolvedClasses;
    }

    /**
     * Splits a string by whitespace, respecting double quotes.
     */
    private List<String> splitQuotedString(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        for (char c : input.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0); // Reset
                }
            } else {
                currentToken.append(c);
            }
        }
        // Add the last token if any
        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }
        return tokens;
    }


    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Gets the selection result (files and/or classes).
     * Returns null if the dialog was cancelled or no valid selection was made.
     */
    public Selection getSelection() {
        return selectionResult;
    }

    /**
     * Extracts the text of the token currently being typed in a JTextComponent,
     * respecting double quotes. Used by completion providers.
     */
    private String getCurrentTokenText(JTextComponent comp) {
        String text = comp.getText();
        int caretPos = comp.getCaretPosition();
        int start = caretPos;
        boolean inQuotes = false;

        // Find start of current token, respecting quotes
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (c == '"') {
                // Count quotes between start-1 and caretPos to determine if we are inside quotes
                long quoteCount = text.substring(start - 1, caretPos).chars().filter(ch -> ch == '"').count();
                inQuotes = (quoteCount % 2 != 0);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                // Stop if we hit whitespace outside of quotes
                break;
            }
            start--;
        }

        // If the token starts with a quote, and the caret is inside the quotes, advance start
        if (start < caretPos && text.charAt(start) == '"') {
            long quoteCountToCaret = text.substring(start, caretPos).chars().filter(ch -> ch == '"').count();
            if (quoteCountToCaret % 2 == 0) { // If an even number of quotes between start and caret, caret is effectively inside
                // We might need to check if the quote at 'start' is truly the *opening* quote relative to caret
                // This logic gets tricky. Let's simplify: if token starts with quote, advance start.
                start++;
            }
        }

        // Return text from adjusted start to caret
        if (start < caretPos) {
            return text.substring(start, caretPos);
        } else {
            return "";
        }
    }


    // ========================================================================
    // Completion Providers
    // ========================================================================

    /**
     * Custom CompletionProvider for project files.
     * Overrides getAlreadyEnteredText for multi-file input area.
     */
    public class FileCompletionProvider extends DefaultCompletionProvider {
        private final Future<Set<ProjectFile>> projectFilesFuture;
        private final boolean allowExternalFiles; // Added field

        public FileCompletionProvider(Future<Set<ProjectFile>> projectFilesFuture, boolean allowExternalFiles) {
            super();
            assert projectFilesFuture != null;
            this.projectFilesFuture = projectFilesFuture;
            this.allowExternalFiles = allowExternalFiles; // Store the flag
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            // Delegate to the shared token extraction logic
            return getCurrentTokenText(comp);
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            String pattern = getAlreadyEnteredText(tc);
            if (pattern.isEmpty()) return List.of();

            Path potentialPath = null;
            boolean isAbsolutePattern;
            try {
                potentialPath = Path.of(pattern);
                isAbsolutePattern = potentialPath.isAbsolute();
            } catch (java.nio.file.InvalidPathException e) {
                // Invalid path syntax, treat as non-absolute project path for now
                isAbsolutePattern = false;
            }

            if (allowExternalFiles && isAbsolutePattern) {
                // Generate absolute path completions (List<Completion>)
                // Pass the Path object to avoid re-parsing
                return getAbsolutePathCompletions(potentialPath, pattern); // Return List<Completion> directly
            } else {
                // Fallback to project file completion
                String trimmedPattern = pattern.trim();
                if (trimmedPattern.isEmpty()) return List.of();

                Set<ProjectFile> projectFiles;
                try {
                    projectFiles = projectFilesFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.debug("Error getting project files for completion", e);
                    return List.of();
                }

                // Generate project file completions (List<ShorthandCompletion>)
                List<ShorthandCompletion> projectCompletions = Completions.scoreShortAndLong(
                        trimmedPattern,
                        projectFiles,
                        ProjectFile::getFileName,
                        ProjectFile::toString,
                        this::createRepoCompletion);

                // Call sizePopupWindows only when we have ShorthandCompletions
                AutoCompleteUtil.sizePopupWindows(fileAutoCompletion, tc, projectCompletions);

                // Return the list, casting back to the required List<Completion> type
                return projectCompletions.stream().map(c -> (Completion) c).toList();
            }
        }

        /**
         * Creates a completion item for a ProjectFile.
         */
        private ShorthandCompletion createRepoCompletion(ProjectFile file) {
            String relativePath = file.toString();
            String replacement = quotePathIfNecessary(relativePath) + " ";
            return new ShorthandCompletion(this, file.getFileName(), replacement, relativePath);
        }

        /**
         * /**
         * Provides completions for absolute filesystem paths.
         *
         * @param inputPath The Path object representing the user's input.
         * @param pattern   The original string pattern entered by the user (used for display and edge cases).
         * @return A list of matching completions.
         */
        private List<Completion> getAbsolutePathCompletions(Path inputPath, String pattern) {
            List<Completion> pathCompletions = new ArrayList<>();
            try {
                Path parentDir;
                String filePrefix;
                boolean endsWithSeparator = pattern.endsWith(inputPath.getFileSystem().getSeparator());

                // Determine the directory to list and the prefix to match
                if (endsWithSeparator) {
                    // User typed "C:\folder\" or "/folder/", list contents of "folder"
                    parentDir = inputPath;
                    filePrefix = "";
                } else {
                    // User typed "C:\folder\fi" or "/folder/fi"
                    parentDir = inputPath.getParent();
                    // Handle cases like "C:\" where getParent() might be null, but it's a valid directory
                    if (parentDir == null && inputPath.getRoot() != null && inputPath.getNameCount() == 0) {
                        parentDir = inputPath.getRoot(); // e.g., C:\ or /
                        filePrefix = ""; // No prefix to match in this case
                    } else if (parentDir != null) {
                        // Get the filename part as the prefix
                        filePrefix = inputPath.getFileName() != null ? inputPath.getFileName().toString() : "";
                    } else {
                        // Should not happen for valid absolute paths unless it's just the root?
                        // If input is just "C:" or "/", parent is null, filename is null. Treat as root.
                        parentDir = inputPath.getRoot();
                        if (parentDir == null) { // Should be extremely rare, e.g. malformed input caught earlier
                            logger.warn("Could not determine parent directory or root for path: {}", pattern);
                            return List.of();
                        }
                        filePrefix = "";
                    }
                }


                // Ensure parentDir exists and is a directory before listing
                if (!Files.isDirectory(parentDir)) {
                    // If parent is null (e.g., invalid input like "/../") or not a directory, no completions
                    logger.debug("Parent directory for absolute path completion is null or not a directory: {}", parentDir);
                    return List.of();
                }

                // Use a final variable for the lambda expression
                final String effectiveFilePrefix = filePrefix.toLowerCase();
                if (Files.isDirectory(parentDir)) {
                    try (var stream = Files.newDirectoryStream(parentDir, p -> p.getFileName().toString().toLowerCase().startsWith(effectiveFilePrefix))) {
                        for (Path p : stream) {
                            String absolutePath = p.toAbsolutePath().toString();
                            String replacement = quotePathIfNecessary(absolutePath) + " ";
                            String display = p.getFileName().toString();
                            pathCompletions.add(new org.fife.ui.autocomplete.BasicCompletion(this, replacement, display, absolutePath));
                        }
                    } catch (java.io.IOException e) {
                        logger.debug("IOException while listing directory for completion: {}", parentDir, e);
                        // Ignore and return whatever we have found so far, or empty list
                    }
                }
            } catch (java.nio.file.InvalidPathException e) {
                logger.debug("Invalid path entered for completion: {}", pattern, e);
            }

            // Sort completions alphabetically by display name
            pathCompletions.sort(Comparator.comparing(Completion::getInputText));
            return pathCompletions;
        }

        /** Quotes a path string if it contains spaces. */
        private String quotePathIfNecessary(String path) {
            return path.contains(" ") ? "\"" + path + "\"" : path;
        }
    }

    /**
     * Custom CompletionProvider for Java classes using the IAnalyzer.
     * Overrides getAlreadyEnteredText for multi-class input area.
     * Loads class completions in the background using a provided ExecutorService.
     */
    public class SymbolCompletionProvider extends DefaultCompletionProvider {
        private final AnalyzerWrapper analyzer;
        private final Future<List<CodeUnit>> completionsFuture;

        /**
         * Creates a SymbolCompletionProvider and immediately submits a task
         * to load class completions in the background.
         *
         * @param analyzer           The IAnalyzer instance to use for finding classes.
         * @param backgroundExecutor The ExecutorService to run the loading task on.
         */
        public SymbolCompletionProvider(AnalyzerWrapper analyzer, ExecutorService backgroundExecutor) {
            super();
            assert analyzer != null;
            assert backgroundExecutor != null;
            this.analyzer = analyzer;

            // Submit the task to load completions in the background
            this.completionsFuture = backgroundExecutor.submit(() -> {
                try {
                    // Filter for classes during the background load
                    return Completions.completeSymbols("", analyzer.get())
                            .stream()
                            .filter(c -> c.kind() == CodeUnitType.CLASS)
                            .toList();
                } catch (Exception e) {
                    logger.error("Error loading symbol completions in background", e);
                    return Collections.emptyList(); // Return empty list on error
                }
            });
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            // Delegate to the shared token extraction logic
            return getCurrentTokenText(comp);
        }

        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String pattern = getAlreadyEnteredText(comp).trim();
            if (pattern.isEmpty()) return List.of();

            IAnalyzer az;
            try {
                az = analyzer.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assert az != null && az.isCpg();

            List<CodeUnit> availableCompletions;
            try {
                availableCompletions = completionsFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.debug(e);
                return List.of();
            }

            var matches = Completions.scoreShortAndLong(pattern,
                                                        availableCompletions,
                                                        CodeUnit::identifier,
                                                        CodeUnit::fqName,
                                                        this::createClassCompletion);

            AutoCompleteUtil.sizePopupWindows(classAutoCompletion, comp, matches);
            return matches.stream().map(c -> (Completion) c).toList();
        }

        /**
         * Creates a completion item for a CodeUnit (Class).
         */
        private ShorthandCompletion createClassCompletion(CodeUnit codeUnit) {
            String fqn = codeUnit.fqName();
            String shortName = codeUnit.shortName();
            // Replacement text is the FQN plus a trailing space
            String replacement = fqn + " ";
            // Display text is the short name, summary is the FQN
            return new ShorthandCompletion(this, shortName, replacement, fqn);
        }
    }
}
