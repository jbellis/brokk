package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import io.github.jbellis.brokk.analyzer.RepoFile;

public class Chrome implements AutoCloseable, IConsoleIO {
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Used as the default text for the background tasks label
    private final String BGTASK_EMPTY = "No background tasks";
    
    // For collapsing/expanding the Git panel
    private int lastGitPanelDividerLocation = -1;

    // Dependencies:
    ContextManager contextManager;

    // Swing components:
    final JFrame frame;
    private JLabel commandResultLabel;
    private RSyntaxTextArea commandInputField;
    private JLabel backgroundStatusLabel;
    private Dimension backgroundLabelPreferredSize;


    private JSplitPane verticalSplitPane;
    private JSplitPane contextGitSplitPane;
    private HistoryOutputPane historyOutputPane;

    // Panels:
    private ContextPanel contextPanel;
    private GitPanel gitPanel; // Will be null for dependency projects

    // Buttons for the command input panel:
    private JButton codeButton;  // renamed from goButton
    private JButton askButton;
    private JButton searchButton;
    private JButton runButton;
    private JButton stopButton;  // cancels the current user-driven task

    // Track the currently running user-driven future (Code/Ask/Search/Run)
    volatile Future<?> currentUserTask;

    // For voice input
    private VoiceInputButton micButton;

    public Project getProject() {
        return contextManager == null ? null : contextManager.getProject();
    }

    /**
     * Enum representing the different types of context actions that can be performed.
     * This replaces the use of magic strings when calling performContextActionAsync.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, before creating the Coder,
     * and before calling .resolveCircularReferences(...).
     * We allow contextManager to be null for the initial empty UI.
     */
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;

        // 1) Set FlatLaf Look & Feel - we'll use light as default initially
        // The correct theme will be applied in onComplete() when project is available
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Exception e) {
            logger.warn("Failed to set LAF, using default", e);
        }
        
        // 2) Build main window
        frame = new JFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // Set application icon
        try {
            var iconUrl = getClass().getResource(Brokk.ICON_RESOURCE);
            if (iconUrl != null) {
                var icon = new ImageIcon(iconUrl);
                frame.setIconImage(icon.getImage());
            } else {
                logger.warn("Could not find resource {}", Brokk.ICON_RESOURCE);
            }
        } catch (Exception e) {
            logger.warn("Failed to set application icon", e);
        }

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER);

        // 4) Build menu
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // 5) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        if (contextManager == null) {
            disableUserActionButtons();
            disableContextActionButtons();
        }
    }

    public void onComplete() {
        // Configure STT availability for mic button
        boolean sttEnabled = contextManager != null
                && !(contextManager.getCoder().models.sttModel() instanceof Models.UnavailableSTT);
        micButton.configure(sttEnabled);

        if (contextManager == null) {
            frame.setTitle("Brokk (no project)");
        } else {
            // Load saved theme, window size, and position
            frame.setTitle("Brokk: " + getProject().getRoot());
            initializeThemeManager();
            loadWindowSizeAndPosition();

            // Setup the context/git panels based on project type
            // Get the existing bottom panel (which already contains NORTH and SOUTH components)
            var bottomPanel = (JPanel) verticalSplitPane.getBottomComponent();

            // DO NOT removeAll() here. Instead, add the correct component to the CENTER.

            if (getProject().hasGit()) {
                // For main projects, create a split pane with both panels for the center
                this.contextGitSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                // Ensure contextPanel is created if buildMainPanel didn't already
                if (contextPanel == null) {
                    contextPanel = new ContextPanel(this, contextManager);
                }
                contextGitSplitPane.setTopComponent(contextPanel);

                // Create and add git panel
                gitPanel = new GitPanel(this, contextManager);
                contextGitSplitPane.setBottomComponent(gitPanel);

                // Set resize weight so context panel gets extra space
                contextGitSplitPane.setResizeWeight(0.7); // 70% to context, 30% to git

                // Add the split pane to the center of the bottom panel
                bottomPanel.add(contextGitSplitPane, BorderLayout.CENTER);

                // Update git panel state
                updateCommitPanel();
                gitPanel.updateRepo();
            } else {
                // For dependency projects, just add the context panel directly to the center
                gitPanel = null;
                // Ensure contextPanel is created if buildMainPanel didn't already
                if (contextPanel == null) {
                    contextPanel = new ContextPanel(this, contextManager);
                }
                bottomPanel.add(contextPanel, BorderLayout.CENTER);
            }

            // Force layout update for the bottom panel
            bottomPanel.revalidate();
            bottomPanel.repaint();
        }

        // show the window
        frame.setVisible(true);
        // this gets it to respect the minimum size on buttons panel, fuck it
        frame.validate();
        frame.repaint();

        // Set focus to command input field on startup
        commandInputField.requestFocusInWindow();

        // Check if .gitignore is set and prompt user to update if needed
        if (getProject() != null && getProject().hasGit()) {
            contextManager.submitBackgroundTask("Checking .gitignore", () -> {
                if (!getProject().isGitIgnoreSet()) {
                    SwingUtilities.invokeLater(() -> {
                        int result = JOptionPane.showConfirmDialog(
                                frame,
                                "Update .gitignore and add .brokk project files to git?",
                                "Git Configuration",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE
                        );

                        if (result == JOptionPane.YES_OPTION) {
                            setupGitIgnore();
                        }
                    });
                }
                return null;
            });
        }
    }

    /**
     * Sets up .gitignore entries and adds .brokk project files to git
     */
    private void setupGitIgnore() {
        contextManager.submitUserTask("Updating .gitignore", () -> {
            try {
                var gitRepo = (GitRepo) getProject().getRepo();
                var root = getProject().getRoot();
                
                // Update .gitignore
                var gitignorePath = root.resolve(".gitignore");
                String content = "";
                
                if (Files.exists(gitignorePath)) {
                    content = Files.readString(gitignorePath);
                    if (!content.endsWith("\n")) {
                        content += "\n";
                    }
                }
                
                // Add entries to .gitignore if they don't exist
                if (!content.contains(".brokk/**") && !content.contains(".brokk/")) {
                    content += "\n### BROKK'S CONFIGURATION ###\n";
                    content += ".brokk/**\n";
                    content += "!.brokk/style.md\n";
                    content += "!.brokk/project.properties\n";
                    
                    Files.writeString(gitignorePath, content);
                    systemOutput("Updated .gitignore with .brokk entries");
                    
                    // Add .gitignore to git if it's not already in the index
                    gitRepo.add(List.of(new RepoFile(root, ".gitignore")));
                }
                
                // Create .brokk directory if it doesn't exist
                var brokkDir = root.resolve(".brokk");
                Files.createDirectories(brokkDir);
                
                // Add specific files to git
                var styleMdPath = brokkDir.resolve("style.md");
                var projectPropsPath = brokkDir.resolve("project.properties");
                
                // Create files if they don't exist (empty files)
                if (!Files.exists(styleMdPath)) {
                    Files.writeString(styleMdPath, "# Style Guide\n");
                }
                if (!Files.exists(projectPropsPath)) {
                    Files.writeString(projectPropsPath, "# Brokk project configuration\n");
                }
                
                // Add files to git
                var filesToAdd = new ArrayList<RepoFile>();
                filesToAdd.add(new RepoFile(root, ".brokk/style.md"));
                filesToAdd.add(new RepoFile(root, ".brokk/project.properties"));
                
                gitRepo.add(filesToAdd);
                systemOutput("Added .brokk project files to git");
                
                // Update commit message
                SwingUtilities.invokeLater(() -> {
                    gitPanel.setCommitMessageText("Update for Brokk project files");
                    updateCommitPanel();
                });
                
            } catch (Exception e) {
                logger.error("Error setting up git ignore", e);
                toolError("Error setting up git ignore: " + e.getMessage());
            }
        });
    }

    private void initializeThemeManager() {
        assert getProject() != null;

        logger.debug("Initializing theme manager");
        // Initialize theme manager now that all components are created
        // and conmtextManager should be properly set
        themeManager = new GuiTheme(getProject(), frame, historyOutputPane.getLlmScrollPane(), this);

        // Apply current theme based on project settings
        String currentTheme = getProject().getTheme();
        logger.debug("Applying theme from project settings: {}", currentTheme);
        // Apply the theme from project settings now
        boolean isDark = THEME_DARK.equalsIgnoreCase(currentTheme);
        themeManager.applyTheme(isDark);
        historyOutputPane.updateTheme(isDark);
    }

    /**
     * Build the main panel that includes:
     * - the LLM stream (top)
     * - the command result label
     * - the command input
     * - the context panel
     * - the background status label at bottom
     */
    private JPanel buildMainPanel() {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Create history output pane (combines history panel and output panel)
        historyOutputPane = new HistoryOutputPane(this, contextManager);

        // Create a split pane with output+history in top and command+context+status in bottom
        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        // Set resize weight to prevent top component from collapsing on initial load
        verticalSplitPane.setResizeWeight(0.4); // Give 40% of space to top component
        verticalSplitPane.setTopComponent(historyOutputPane);

        // Create a panel for everything below the output area, using BorderLayout
        // North: Command input/results
        // Center: Context/Git (added in onComplete)
        // South: Status label
        var bottomPanel = new JPanel(new BorderLayout());

        // Create and add the top controls (result label + command input panel)
        var topControlsPanel = new JPanel(new BorderLayout(0, 2));
        var resultLabel = buildCommandResultLabel();
        topControlsPanel.add(resultLabel, BorderLayout.NORTH);
        var commandPanel = buildCommandInputPanel();
        topControlsPanel.add(commandPanel, BorderLayout.SOUTH);
        bottomPanel.add(topControlsPanel, BorderLayout.NORTH);

        // Create the context panel (will be added to bottomPanel.CENTER later)
        contextPanel = new ContextPanel(this, contextManager);

        // Create and add the background status label at the very bottom
        var statusLabel = buildBackgroundStatusLabel();
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        // Set the fully constructed bottomPanel (minus the center component)
        verticalSplitPane.setBottomComponent(bottomPanel);

        // Add the vertical split pane to the main content panel
        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(verticalSplitPane, gbc);

        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Lightweight method to preview a context without updating history
     * Only updates the LLM text area and context panel display
     */
    public void loadContext(Context ctx) {
        assert ctx != null;

        SwingUtilities.invokeLater(() -> {
            // Don't clear history selection when previewing
            contextPanel.populateContextTable(ctx);

            // If there's textarea content, restore it to the LLM output area
            historyOutputPane.resetLlmOutput(ctx.getParsedOutput() == null ? "" : ctx.getParsedOutput().output());

            updateCaptureButtons();
        });
    }

    // Theme manager
    GuiTheme themeManager;

    // Theme constants - matching GuiTheme values
    private static final String THEME_DARK = "dark";
    private static final String THEME_LIGHT = "light";

    /**
     * Switches between light and dark theme
     *
     * @param isDark true for dark theme, false for light theme
     */
    public void switchTheme(boolean isDark) {
        themeManager.applyTheme(isDark);

        // Update history output pane theme
        historyOutputPane.updateTheme(isDark);

        // Update themes in all preview windows (if there are open ones)
        for (Window window : Window.getWindows()) {
            if (window instanceof JFrame && window != frame) {
                Container contentPane = ((JFrame) window).getContentPane();
                if (contentPane instanceof PreviewPanel) {
                    ((PreviewPanel) contentPane).updateTheme(themeManager);
                }
            }
        }
    }

    


    /**
     * Gets the current text from the LLM output area
     */
    public String getLlmOutputText() {
        return SwingUtil.runOnEDT(() -> historyOutputPane.getLlmOutputText(), null);
    }


    /**
     * Creates the command result label used to display messages.
     */
    private JComponent buildCommandResultLabel() {
        commandResultLabel = new JLabel(" ");
        commandResultLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandResultLabel.setBorder(new EmptyBorder(5, 8, 5, 8));
        return commandResultLabel;
    }

    /**
     * Creates the bottom-most background status label that shows "Working on: ..." or is blank when idle.
     */
    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        
        // Store the preferred size with the default text
        backgroundLabelPreferredSize = backgroundStatusLabel.getPreferredSize();
        
        return backgroundStatusLabel;
    }

    /**
     * Creates a panel with:
     *  - History dropdown at top (full width).
     *  - A mic button in the left column (spans rows 2 and 3).
     *  - The command input area in the middle column (row 2).
     *  - The "Code / Ask / Search / Run" and "Stop" buttons in row 3 (still two flow panels).
     *
     * We use GridBagLayout so the mic button doesn't stretch horizontally under the entire panel.
     */
    private JPanel buildCommandInputPanel() {
        var wrapper = new JPanel(new GridBagLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        "Instructions",
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        new Font(Font.DIALOG, Font.BOLD, 12)
                ),
                new EmptyBorder(5, 5, 5, 5)
        ));

        var gbc = new GridBagConstraints();
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        // 1) History dropdown at the top
        JPanel historyPanel = buildHistoryDropdown();
        wrapper.add(historyPanel, gbc);

        // 3) The command input field (scrollpane) in the middle column, row=1
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        commandInputField = new RSyntaxTextArea(3, 40);
        commandInputField.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandInputField.setHighlightCurrentLine(false);
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        commandInputField.setLineWrap(true);
        commandInputField.setWrapStyleWord(true);
        commandInputField.setRows(3);
        commandInputField.setMinimumSize(new Dimension(100, 80));
        // Enable undo/redo
        commandInputField.setAutoIndentEnabled(false);

        // Add Ctrl+Enter shortcut to trigger the default button
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        commandInputField.getInputMap().put(ctrlEnter, "submitDefault");
        commandInputField.getActionMap().put("submitDefault", new AbstractAction() 
        {
            @Override
            public void actionPerformed(ActionEvent e) 
            {
                // If there's a default button, "click" it
                var rootPane = SwingUtilities.getRootPane(commandInputField);
                if (rootPane != null && rootPane.getDefaultButton() != null) 
                {
                    rootPane.getDefaultButton().doClick();
                }
            }
        });

        // Create a scrollpane for the text area
        var commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 80));
        wrapper.add(commandScrollPane, gbc);

        // 2) Mic button on the left
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(2, 2, 2, 8);
        micButton = new VoiceInputButton(
                commandInputField instanceof JTextArea ? (JTextArea)commandInputField : null,
                contextManager,
                () -> actionOutput("Recording"),
                this::toolError
        );
        wrapper.add(micButton, gbc);

        // 4) The row of left buttons (Code/Ask/Search/Run) and right button (Stop) in row=2, col=1
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        var buttonsHolder = new JPanel(new BorderLayout());

        var leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        codeButton = new JButton("Code");
        codeButton.setMnemonic(KeyEvent.VK_C);
        codeButton.setToolTipText("Tell the LLM to write code to solve this problem using the current context");
        codeButton.addActionListener(e -> runCodeCommand());

        askButton = new JButton("Ask");
        askButton.setMnemonic(KeyEvent.VK_A);
        askButton.setToolTipText("Ask the LLM a question about the current context");
        askButton.addActionListener(e -> runAskCommand());

        searchButton = new JButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.setToolTipText("Explore the codebase beyond the current context");
        searchButton.addActionListener(e -> runSearchCommand());

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_N);
        runButton.setToolTipText("Execute the current instructions in a shell");
        runButton.addActionListener(e -> runRunCommand());

        leftButtonsPanel.add(codeButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);
        leftButtonsPanel.add(runButton);

        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);

        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST);
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST);

        wrapper.add(buttonsHolder, gbc);

        // set "Code" as default button
        frame.getRootPane().setDefaultButton(codeButton);

        return wrapper;
    }

    /**
     * Invoked on "Code" button or pressing Enter in the command input field.
     */
    private void runCodeCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a command or text");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        historyOutputPane.setLlmOutput("# Code\n" + commandInputField.getText() + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        // schedule in ContextManager
        currentUserTask = contextManager.runCodeCommandAsync(input);
    }

    /**
     * Invoked on "Run" button
     */
    private void runRunCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolError("Please enter a command to run");
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        historyOutputPane.setLlmOutput("# Run\n" + commandInputField.getText() + "\n\n# Output\n");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runRunCommandAsync(input);
    }

    public String getInputText() {
        return SwingUtil.runOnEDT(() -> commandInputField.getText(), "");
    }

    /**
     * Invoked on "Ask" button
     */
    private void runAskCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a question");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        historyOutputPane.setLlmOutput("# Ask\n" + commandInputField.getText() + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runAskAsync(input);
    }

    /**
     * Invoked on "Search" button
     */
    private void runSearchCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please provide a search query");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);

        historyOutputPane.setLlmOutput("# Search\n" + commandInputField.getText() + "\n\n");
        historyOutputPane.appendLlmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(input);
    }

    public void clear() {
        SwingUtilities.invokeLater(() -> {
            historyOutputPane.clear();
        });
    }

    /**
     * Disables "Go/Ask/Search" to prevent overlapping tasks, until re-enabled
     */
    void disableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
        });
    }

    /**
     * Re-enables "Go/Ask/Search" when the task completes or is canceled
     */
    public void enableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(true);
            askButton.setEnabled(true);
            searchButton.setEnabled(true);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            updateCommitPanel();
        });
    }

    /**
     * Disables the context action buttons while an action is in progress
     */
    void disableContextActionButtons() {
        // No longer needed - buttons are in menus now
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons() {
        // No longer needed - buttons are in menus now
    }

    /**
     * Cancels the currently running user-driven Future (Go/Ask/Search), if any
     */
    private void stopCurrentUserTask() {
        if (currentUserTask != null && !currentUserTask.isDone()) {
            currentUserTask.cancel(true);
        }
    }
    
    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    public void updateCommitPanel() {
        if (gitPanel != null) {
            gitPanel.updateCommitPanel();
        }
    }

    public void updateGitRepo() {
        if (gitPanel != null) {
            gitPanel.updateRepo();
        }
    }

    /**
     * Registers global keyboard shortcuts for undo/redo
     */
    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Cmd/Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.undoContextAsync();
            }
        });

        // Cmd/Ctrl+Shift+Z => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.redoContextAsync();
            }
        });

        // Cmd/Ctrl+V => paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentUserTask = contextManager.performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });
    }

    /**
     * Shows a dialog for editing LLM API secret keys.
     */
    void showSecretKeysDialog() {
        if (getProject() == null) {
            toolErrorRaw("Project not available");
            return;
        }

        // Reuse the existing code for editing keys
        // unchanged from original example
        JDialog dialog = new JDialog(frame, "Edit LLM API Keys", true);
        dialog.setLayout(new BorderLayout());

        // main panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        var existingKeys = getProject().getLlmKeys();
        List<KeyValueRowPanel> keyRows = new ArrayList<>();

        // if empty, add one row
        if (existingKeys.isEmpty()) {
            var row = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(row);
            mainPanel.add(row);
        }

        // Actually, we want multiple rows in a vertical layout
        var keysPanel = new JPanel();
        keysPanel.setLayout(new BoxLayout(keysPanel, BoxLayout.Y_AXIS));

        if (!existingKeys.isEmpty()) {
            for (var entry : existingKeys.entrySet()) {
                var row = new KeyValueRowPanel(Models.defaultKeyNames, entry.getKey(), entry.getValue());
                keyRows.add(row);
                keysPanel.add(row);
            }
        } else {
            var row = new KeyValueRowPanel(Models.defaultKeyNames, "", "");
            keyRows.add(row);
            keysPanel.add(row);
        }

        var scrollPane = new JScrollPane(keysPanel);
        scrollPane.setPreferredSize(new Dimension((int) (frame.getWidth() * 0.9), 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Add/Remove
        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add Key");
        addButton.addActionListener(ev -> {
            var newRow = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(newRow);
            keysPanel.add(newRow);
            keysPanel.revalidate();
            keysPanel.repaint();
        });

        var removeButton = new JButton("Remove Last Key");
        removeButton.addActionListener(ev -> {
            if (!keyRows.isEmpty()) {
                var last = keyRows.remove(keyRows.size() - 1);
                keysPanel.remove(last);
                keysPanel.revalidate();
                keysPanel.repaint();
            }
        });
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);
        mainPanel.add(addRemovePanel, BorderLayout.NORTH);

        // OK/Cancel
        var actionButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newKeys = new java.util.HashMap<String, String>();
            boolean hasEmptyKey = false;

            for (var row : keyRows) {
                var key = row.getKeyName();
                var value = row.getKeyValue();
                if (key.isBlank() && value.isBlank()) {
                    continue;
                }
                if (key.isBlank()) {
                    hasEmptyKey = true;
                    continue;
                }
                newKeys.put(key, value);
            }

            if (hasEmptyKey) {
                JOptionPane.showMessageDialog(dialog,
                                              "Some keys have empty names and will be skipped.",
                                              "Warning",
                                              JOptionPane.WARNING_MESSAGE);
            }

            getProject().saveLlmKeys(newKeys);
            systemOutput("Saved " + newKeys.size() + " API keys");
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());

        actionButtonsPanel.add(okButton);
        actionButtonsPanel.add(cancelButton);
        mainPanel.add(actionButtonsPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * For the IConsoleIO interface, sets the text in commandResultLabel. Safe to call from any thread.
     */
    @Override
    public void actionOutput(String msg) {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText(msg);
            logger.info(msg);
        });
    }

    @Override
    public void actionComplete() {
        SwingUtilities.invokeLater(() -> commandResultLabel.setText(""));
    }

    @Override
    public void toolErrorRaw(String msg) {
        systemOutput(msg);
    }

    @Override
    public void llmOutput(String token) {
        SwingUtilities.invokeLater(() -> {
            historyOutputPane.appendLlmOutput(token);
        });
    }

    @Override
    public void systemOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            historyOutputPane.appendSystemOutput(message);
        });
    }

    public void backgroundOutput(String message) {
        SwingUtilities.invokeLater(() -> {
            // Set to empty string when message is empty
            if (message == null || message.isEmpty()) {
                backgroundStatusLabel.setText("");
            } else {
                backgroundStatusLabel.setText(message);
            }
            
            // Ensure the label keeps its preferred size even when empty
            backgroundStatusLabel.setPreferredSize(backgroundLabelPreferredSize);
        });
    }

    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        if (contextManager != null) {
            contextManager.shutdown();
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    /**
     * Opens a preview window for a context fragment
     *
     * @param fragment   The fragment to preview
     * @param syntaxType The syntax highlighting style to use
     */
    public void openFragmentPreview(ContextFragment fragment, String syntaxType) {
        // Hardcode reading the text from the fragment (handles IO errors)
        String content;
        try {
            content = fragment.text();
        } catch (IOException e) {
            systemOutput("Error reading fragment: " + e.getMessage());
            return;
        }

        // Create a JFrame to hold the new PreviewPanel
        var frame = new JFrame("Preview: " + fragment.shortDescription());
        // Pass the theme manager to properly style the preview
        var previewPanel = new PreviewPanel(contextManager,
                                            fragment instanceof ContextFragment.RepoPathFragment(RepoFile file) ? file : null,
                                            content,
                                            syntaxType,
                                            themeManager);
        frame.setContentPane(previewPanel);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // Load location/dimensions from Project (if any)
        var project = contextManager.getProject();
        var storedBounds = project.getPreviewWindowBounds();
        if (storedBounds != null) {
            frame.setBounds(storedBounds);
        } else {
            frame.setSize(800, 600);
            frame.setLocationRelativeTo(this.getFrame());
        }

        // When the user moves or resizes, store in Project
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame);
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.savePreviewWindowBounds(frame);
            }
        });

        frame.setVisible(true);
    }

    /**
     * Loads window size and position from project properties
     */
    private void loadWindowSizeAndPosition() {
        Rectangle bounds = getProject() == null ? null : getProject().getMainWindowBounds();

        // Only apply saved values if they're valid
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            // If no valid size is saved, center the window
            frame.setLocationRelativeTo(null);
            return;
        }

        frame.setSize(bounds.width, bounds.height);
        // Only use the position if it was actually set (not -1)
        if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
            frame.setLocation(bounds.x, bounds.y);
        } else {
            // If not on a visible screen, center the window
            frame.setLocationRelativeTo(null);
        }

        // Restore split pane positions after the frame has been shown and sized
        SwingUtilities.invokeLater(() -> {
            // Restore vertical split pane position
            int verticalPos = getProject().getVerticalSplitPosition();
            if (verticalPos > 0) {
                verticalSplitPane.setDividerLocation(verticalPos);
            } else {
                // For new projects with no saved position, set a reasonable default
                // to prevent the top component from collapsing to zero height
                verticalSplitPane.setDividerLocation(0.4);
            }

            // Restore history split pane position
            int historyPos = getProject().getHistorySplitPosition();
            if (historyPos > 0) {
                historyOutputPane.setDividerLocation(historyPos);
            } else {
                // If no saved position, use the default
                historyOutputPane.setInitialWidth();
            }

            // Restore context/git split pane position (only if not a dependency project)
            if (contextGitSplitPane != null) {
                int contextGitPos = getProject().getContextGitSplitPosition();
                if (contextGitPos > 0) {
                    contextGitSplitPane.setDividerLocation(contextGitPos);
                }
            }

            // Add listener to save window size and position when they change
            frame.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    getProject().saveMainWindowBounds(frame);
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    getProject().saveMainWindowBounds(frame);
                }
            });

            // Add listeners to save split pane positions when they change
            historyOutputPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                getProject().saveHistorySplitPosition(historyOutputPane.getDividerLocation());
            });

            verticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                getProject().saveVerticalSplitPosition(verticalSplitPane.getDividerLocation());
            });

            if (contextGitSplitPane != null) {
                contextGitSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    getProject().saveContextGitSplitPosition(contextGitSplitPane.getDividerLocation());
                });
            }
        });
    }

    /**
     * Updates the context history table and keeps the currently selected context selected if possible
     */
    public void updateContextHistoryTable() {
        Context selectedContext = contextManager.selectedContext();
        updateContextHistoryTable(selectedContext);
    }

    /**
     * Updates the context history table with the current context history, and selects the given context
     */
    public void updateContextHistoryTable(Context contextToSelect) {
        historyOutputPane.updateHistoryTable(contextToSelect);
    }

    /**
     * Checks if a position is on any available screen
     */
    private boolean isPositionOnScreen(int x, int y) {
        for (var screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (var config : screen.getConfigurations()) {
                if (config.getBounds().contains(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Updates the state of capture buttons based on textarea content
     *
     * If `ctx` is null it means we're processing a new response from the LLM
     * and we should parse our raw text for references instead
     */
    public void updateCaptureButtons() {
        String text = historyOutputPane.getLlmOutputText();

        SwingUtilities.invokeLater(() -> {
            historyOutputPane.setCopyButtonEnabled(!text.isBlank());  // Enable copy button when there's text
        });
    }

    /**
     * Builds the history dropdown panel with template selections
     *
     * @return A panel containing the history dropdown button
     */
    // Constants for the history dropdown
    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters - appropriate for 1000px width

    private JPanel buildHistoryDropdown() {
        JPanel historyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyPanel.add(historyButton);

        // Show popup when button is clicked
        historyButton.addActionListener(e -> {
            logger.debug("History button clicked, creating menu");

            // Create a fresh popup menu each time
            JPopupMenu historyMenu = new JPopupMenu();

            // Get history items from project
            var project = getProject();
            if (project == null) {
                logger.warn("Cannot show history menu: project is null");
                return;
            }

            List<String> historyItems = project.loadTextHistory();
            logger.debug("History items loaded: {}", historyItems.size());

            if (historyItems.isEmpty()) {
                JMenuItem emptyItem = new JMenuItem("(No history items)");
                emptyItem.setEnabled(false);
                historyMenu.add(emptyItem);
            } else {
                // Iterate in reverse order so newest items appear at the bottom of the dropdown
                // This creates a more natural flow when the dropdown appears above the button
                for (int i = historyItems.size() - 1; i >= 0; i--) {
                    String item = historyItems.get(i);
                    // Use static truncation length
                    String displayText = item.length() > TRUNCATION_LENGTH ?
                            item.substring(0, TRUNCATION_LENGTH - 3) + "..." : item;

                    JMenuItem menuItem = new JMenuItem(displayText);
                    menuItem.setToolTipText(item); // Show full text on hover

                    menuItem.addActionListener(event -> {
                        commandInputField.setText(item);
                    });
                    historyMenu.add(menuItem);
                    logger.debug("Added menu item: {}", displayText);
                }
            }

            // Apply theme to the menu
            if (themeManager != null) {
                themeManager.registerPopupMenu(historyMenu);
            }

            // Use fixed width for menu
            historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
            historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));

            // Pack and show
            historyMenu.pack();

            logger.debug("Menu width set to fixed value: {}", DROPDOWN_MENU_WIDTH);

            // Show above the button instead of below
            historyMenu.show(historyButton, 0, -historyMenu.getPreferredSize().height);

            logger.debug("Menu shown with dimensions: {}x{}",
                         historyMenu.getWidth(), historyMenu.getHeight());
        });

        return historyPanel;
    }

    // This method is no longer needed as we use fixed width


    /**
     * Be very careful to run any UI updates on the EDT
     * It's impossible to enforce this when we just hand out the JFrame reference
     * We should probably encapsulate what callers need and remove this
     */
    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> {
            this.commandInputField.requestFocus();
        });
    }

    /**
     * Sets the text in the commit message area
     */
    public void setCommitMessageText(String message) {
        SwingUtilities.invokeLater(() -> {
            if (gitPanel != null) {
                gitPanel.setCommitMessageText(message);
            }
        });
    }

    /**
     * Collapses or restores the bottom (Git) panel in the contextGitSplitPane.
     * Toggling once hides the git panel, toggling again restores it to its last size.
     */
    public void toggleGitPanel()
    {
        if (contextGitSplitPane == null) {
            // No split pane for dependency projects
            return;
        }
        
        // Store the current divider location
        lastGitPanelDividerLocation = contextGitSplitPane.getDividerLocation();
        // Move divider down to hide the git panel completely
        // (We subtract a little extra so the bottom border is truly out of sight)
        var totalHeight = contextGitSplitPane.getHeight();
        var dividerSize = contextGitSplitPane.getDividerSize();
        contextGitSplitPane.setDividerLocation(totalHeight - dividerSize - 1);

        logger.debug("Git panel collapsed; stored divider location={}", lastGitPanelDividerLocation);

        // Force a re-layout
        contextGitSplitPane.revalidate();
        contextGitSplitPane.repaint();
    }
    
    public void updateContextTable() {
        contextPanel.updateContextTable();
    }

    /**
     * Clears the context panel
     */

    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Get the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        return contextPanel.getSelectedFragments();
    }

    GitPanel getGitPanel() {
        return gitPanel; // May be null for dependency projects
    }

    /**
     * Shows a dialog for setting a custom AutoContext size (0-100).
     */
    public void showSetAutoContextSizeDialog() {
        var dialog = new JDialog(getFrame(), "Set AutoContext Size", true);
        dialog.setLayout(new BorderLayout());

        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var label = new JLabel("Enter autocontext size (0-100):");
        panel.add(label, BorderLayout.NORTH);

        // Current AutoContext file count as the spinner's initial value
        var spinner = new JSpinner(new SpinnerNumberModel(
                contextManager.selectedContext().getAutoContextFileCount(),
                0, 100, 1
        ));
        panel.add(spinner, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newSize = (int) spinner.getValue();
            contextManager.setAutoContextFilesAsync(newSize);
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(getFrame());
        dialog.setVisible(true);
    }
}
