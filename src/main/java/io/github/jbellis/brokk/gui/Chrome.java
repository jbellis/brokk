package io.github.jbellis.brokk.gui;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.ProjectFile;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;

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
    private JPanel bottomPanel;

    private JSplitPane verticalSplitPane;
    private JSplitPane contextGitSplitPane;
    private HistoryOutputPane historyOutputPane;

    // Panels:
    private ContextPanel contextPanel;
    private GitPanel gitPanel; // Will be null for dependency projects

    // Command input panel components:
    private JComboBox<String> modelDropdown; // Dropdown for model selection
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

        // 5) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        if (contextManager == null) {
            disableUserActionButtons();
            disableContextActionButtons();
        }
    }

    public void onComplete()
    {
        // Populate model dropdown after other components are ready
        initializeModelDropdown();

        if (contextManager == null) {
            frame.setTitle("Brokk (no project)");
            disableUserActionButtons(); // Ensure buttons disabled if no project/context
        } else {
            // Load saved theme, window size, and position
            frame.setTitle("Brokk: " + getProject().getRoot());
            loadWindowSizeAndPosition();

            // If the project uses Git, put the context panel and the Git panel in a split pane
            if (getProject().hasGit()) {
                contextGitSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
                contextGitSplitPane.setResizeWeight(0.7); // 70% for context panel

                contextPanel = new ContextPanel(this, contextManager);
                contextGitSplitPane.setTopComponent(contextPanel);

                gitPanel = new GitPanel(this, contextManager);
                contextGitSplitPane.setBottomComponent(gitPanel);

                bottomPanel.add(contextGitSplitPane, BorderLayout.CENTER);
                updateCommitPanel();
                gitPanel.updateRepo();
            } else {
                // No Git => only a context panel in the center
                gitPanel = null;
                contextPanel = new ContextPanel(this, contextManager);
                bottomPanel.add(contextPanel, BorderLayout.CENTER);
            }

            initializeThemeManager();

            // Force layout update for the bottom panel
            bottomPanel.revalidate();
            bottomPanel.repaint();
        }

        // Build menu (now that everything else is ready)
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // Show the window
        frame.setVisible(true);
        frame.validate();
        frame.repaint();

        // Set focus to command input field on startup
        commandInputField.requestFocusInWindow();

        // Possibly check if .gitignore is set
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
                    gitRepo.add(List.of(new ProjectFile(root, ".gitignore")));
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
                var filesToAdd = new ArrayList<ProjectFile>();
                filesToAdd.add(new ProjectFile(root, ".brokk/style.md"));
                filesToAdd.add(new ProjectFile(root, ".brokk/project.properties"));
                
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
        themeManager = new GuiTheme(frame, historyOutputPane.getLlmScrollPane(), this);

        // Apply current theme based on project settings
        String currentTheme = Project.getTheme();
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
     * - the bottom area (context/git panel + status label)
     */
    private JPanel buildMainPanel()
    {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Create history output pane (combines history panel and output panel)
        historyOutputPane = new HistoryOutputPane(this, contextManager);

        // Create a split pane: top = historyOutputPane, bottom = bottomPanel (to be populated later)
        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplitPane.setResizeWeight(0.4);
        verticalSplitPane.setTopComponent(historyOutputPane);

        // Create the bottom panel
        bottomPanel = new JPanel(new BorderLayout());

        // Build the top controls: result label + command input panel
        var topControlsPanel = new JPanel(new BorderLayout(0, 2));
        var resultLabel = buildCommandResultLabel();
        topControlsPanel.add(resultLabel, BorderLayout.NORTH);
        var commandPanel = buildCommandInputPanel();
        topControlsPanel.add(commandPanel, BorderLayout.SOUTH);
        bottomPanel.add(topControlsPanel, BorderLayout.NORTH);

        // Status label at the very bottom
        var statusLabel = buildBackgroundStatusLabel();
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        // For now, the center of bottomPanel is empty (we populate it in onComplete).
        verticalSplitPane.setBottomComponent(bottomPanel);

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

        // instantiate this early
        commandInputField = new RSyntaxTextArea(3, 40);
        // --- Row 0: History Dropdown ---
        gbc.gridx = 1; // Span across model and input area columns
        gbc.gridy = 0;
        gbc.gridwidth = 2; // Span 2 columns
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        // 1) History dropdown at the top
        JPanel historyPanel = buildHistoryDropdown();
        gbc.anchor = GridBagConstraints.WEST; // Align left
        gbc.insets = new Insets(0, 0, 5, 0); // Add bottom margin
        //JPanel historyPanel = buildHistoryDropdown(); // Remove duplicate declaration
        wrapper.add(historyPanel, gbc);
        gbc.insets = new Insets(0, 0, 0, 0); // Reset insets

        // --- Row 1 & 2: Mic Button, Command Input, Model Dropdown, Buttons ---

        // Mic button (Col 0, Row 1 & 2)
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.gridheight = 2; // Span 2 rows (input + buttons/dropdown)
        gbc.weightx = 0;
        gbc.weighty = 1.0; // Allow mic button to take vertical space
        gbc.fill = GridBagConstraints.VERTICAL; // Fill vertically
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(2, 2, 2, 8); // Right margin
        micButton = new VoiceInputButton(
                commandInputField, // Still needs the instance, even if created later
                contextManager,
                () -> actionOutput("Recording"),
                this::toolError
        );
        wrapper.add(micButton, gbc);
        gbc.insets = new Insets(0, 0, 0, 0); // Reset insets
        gbc.gridheight = 1; // Reset row span
        gbc.weighty = 0; // Reset weighty
        gbc.fill = GridBagConstraints.NONE; // Reset fill


        // Command input field (Col 1+2, Row 1)
        gbc.gridx = 1; // Start at column 1
        gbc.gridy = 1;
        gbc.gridwidth = 2; // Span 2 columns (above dropdown and buttons)
        gbc.weightx = 1.0; // Allow input field to take horizontal space
        gbc.weighty = 1.0; // Allow input field to take vertical space in its row
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 5, 0); // Bottom margin before next row
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
        gbc.insets = new Insets(0, 0, 0, 0); // Reset insets


        // --- Row 2: Model Dropdown, Buttons ---

        // Model dropdown (Col 1, Row 2)
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0; // Don't allow dropdown to expand horizontally excessively
        gbc.fill = GridBagConstraints.HORIZONTAL; // Fill horizontally within its cell
        gbc.anchor = GridBagConstraints.WEST; // Align left
        gbc.insets = new Insets(0, 0, 0, 5); // Right margin
        modelDropdown = new JComboBox<>();
        modelDropdown.setToolTipText("Select the AI model to use");
        wrapper.add(modelDropdown, gbc);
        gbc.insets = new Insets(0, 0, 0, 0); // Reset insets


        // Buttons holder panel (Col 2, Row 2)
        gbc.gridx = 2; // Column 2
        gbc.gridy = 2; // Row 2
        gbc.gridwidth = 1; // Only one column wide
        gbc.weightx = 1.0; // Allow buttons panel to take remaining horizontal space
        gbc.fill = GridBagConstraints.HORIZONTAL; // Fill horizontally
        gbc.anchor = GridBagConstraints.EAST; // Align buttons to the right
        var buttonsHolder = new JPanel(new BorderLayout()); // Use BorderLayout to manage inner panels

        // Panel for action buttons (aligned left within their space)
        var leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0)); // Use FlowLayout for buttons
        leftButtonsPanel.setBorder(BorderFactory.createEmptyBorder()); // Remove default FlowLayout gaps if needed
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

        // Panel for stop button (aligned right within its space)
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); // Use FlowLayout, align right
        rightButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);

        // Add button panels to the holder
        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST); // Action buttons on the left
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST); // Stop button on the right

        wrapper.add(buttonsHolder, gbc);

        // Set "Code" as default button
        // Defer setting default button until frame is realized? Seems okay here.
        SwingUtilities.invokeLater(() -> { // Deferring just in case
            if (frame != null && frame.getRootPane() != null) {
                frame.getRootPane().setDefaultButton(codeButton);
            }
        });

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

        // Get selected model
        var selectedModel = getSelectedModel();
        if (selectedModel == null) {
            toolError("Please select a valid model from the dropdown.");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);
        updateHistoryDropdown(); // Refresh history dropdown

        historyOutputPane.setLlmOutput("# Code\n" + input + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        // Schedule in ContextManager, passing the selected model
        currentUserTask = contextManager.runCodeCommandAsync(selectedModel, input);
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
     * Retrieves the StreamingChatLanguageModel instance corresponding to the
     * currently selected item in the model dropdown.
     *
     * @return The selected model instance, or null if no valid model is selected or available.
     */
    private StreamingChatLanguageModel getSelectedModel() {
        String selectedName = (String) modelDropdown.getSelectedItem();
        logger.debug("User selected model name from dropdown: {}", selectedName);

        if (selectedName == null || selectedName.startsWith("No Models") || selectedName.startsWith("Error")) {
            logger.warn("No valid model selected in dropdown.");
            return null;
        }

        try {
            return Models.get(selectedName);
        } catch (Exception e) {
            logger.error("Failed to get model instance for {}", selectedName, e);
            return null;
        }
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

        // Get selected model
        StreamingChatLanguageModel selectedModel = getSelectedModel();
        if (selectedModel == null) {
            toolError("Please select a valid model from the dropdown.");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);
        updateHistoryDropdown(); // Refresh history dropdown

        historyOutputPane.setLlmOutput("# Ask\n" + commandInputField.getText() + "\n\n# Response\n");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runAskAsync(selectedModel, input);
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

        // Get selected model
        StreamingChatLanguageModel selectedModel = getSelectedModel();
        if (selectedModel == null) {
            toolError("Please select a valid model from the dropdown.");
            return;
        }

        // Add to text history
        getProject().addToTextHistory(input, 20);
        updateHistoryDropdown(); // Refresh history dropdown

        historyOutputPane.setLlmOutput("# Search\n" + commandInputField.getText() + "\n\n");
        historyOutputPane.appendLlmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.");
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(selectedModel, input);
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
            modelDropdown.setEnabled(false); // Disable model selection during task
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true); // Enable stop button
        });
    }

    /**
     * Re-enables "Go/Ask/Search" when the task completes or is canceled
     */
    public void enableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(true);
            // Only enable if models are actually available
            boolean modelsAvailable = modelDropdown.getItemCount() > 0 && !modelDropdown.getItemAt(0).toString().startsWith("No Model");
            modelDropdown.setEnabled(modelsAvailable);
            codeButton.setEnabled(modelsAvailable);
            askButton.setEnabled(modelsAvailable);
            searchButton.setEnabled(modelsAvailable);
            runButton.setEnabled(true); // Run button doesn't strictly need LLM
            stopButton.setEnabled(false); // Disable stop button
            updateCommitPanel(); // Update git panel state too
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
            contextManager.close();
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
                                            fragment instanceof ContextFragment.RepoPathFragment(ProjectFile file) ? file : null,
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
        var project = getProject();
        if (project == null) {
            // If no project, just center the window
            frame.setLocationRelativeTo(null);
            return;
        }

        // 1) Apply saved window bounds
        var bounds = project.getMainWindowBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            // No valid saved size, just center the window
            frame.setLocationRelativeTo(null);
        } else {
            frame.setSize(bounds.width, bounds.height);
            if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
                frame.setLocation(bounds.x, bounds.y);
            } else {
                // If off-screen or invalid, center
                frame.setLocationRelativeTo(null);
            }
        }

        // 2) Listen for window moves/resizes and save
        frame.addComponentListener(new java.awt.event.ComponentAdapter()
        {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e)
            {
                project.saveMainWindowBounds(frame);
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e)
            {
                project.saveMainWindowBounds(frame);
            }
        });

        // 3) Defer JSplitPane divider calls until after the frame is shown
        SwingUtilities.invokeLater(() -> {
            // --- HistoryOutput pane divider ---
            int historyPos = project.getHistorySplitPosition();
            if (historyPos > 0) {
                historyOutputPane.setDividerLocation(historyPos);
            } else {
                // If none saved, pick an initial ratio
                historyOutputPane.setInitialWidth(); // calls setDividerLocation(0.2)
            }

            // Save changes to the history divider as the user drags/resizes
            historyOutputPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e ->
            {
                // Only store if fully realized and actually visible
                if (historyOutputPane.isShowing()) {
                    var newPos = historyOutputPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveHistorySplitPosition(newPos);
                    }
                }
            });

            // --- Vertical split pane divider ---
            int verticalPos = project.getVerticalSplitPosition();
            if (verticalPos > 0) {
                verticalSplitPane.setDividerLocation(verticalPos);
            } else {
                verticalSplitPane.setDividerLocation(0.4); // default ratio
            }

            verticalSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e ->
            {
                if (verticalSplitPane.isShowing()) {
                    var newPos = verticalSplitPane.getDividerLocation();
                    if (newPos > 0) {
                        project.saveVerticalSplitPosition(newPos);
                    }
                }
            });

            // --- Context/Git split pane divider ---
            if (contextGitSplitPane != null) {
                int contextGitPos = project.getContextGitSplitPosition();
                if (contextGitPos > 0) {
                    contextGitSplitPane.setDividerLocation(contextGitPos);
                } else {
                    contextGitSplitPane.setDividerLocation(0.7); // default ratio
                }

                contextGitSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e ->
                {
                    if (contextGitSplitPane.isShowing()) {
                        var newPos = contextGitSplitPane.getDividerLocation();
                        if (newPos > 0) {
                            project.saveContextGitSplitPosition(newPos);
                        }
                    }
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

        // Add listener to show the popup menu on button click
        historyButton.addActionListener(e -> showHistoryMenu(historyButton));

        return historyPanel;
    }

    /**
      * Refreshes the history dropdown button text, potentially needed if history changes often.
      * Currently not strictly necessary as the button text is static, but could be useful later.
      */
     public void updateHistoryDropdown() {
         // This method might be needed if the history button itself needs updating,
         // but currently, the menu is built dynamically on click.
         // Could potentially update tooltip or icon if desired.
     }


    /**
     * Creates and shows the history popup menu.
     * @param invoker The component (historyButton) that invoked the menu.
     */
    private void showHistoryMenu(Component invoker) {
         logger.debug("Showing history menu");
         JPopupMenu historyMenu = new JPopupMenu();

         // Get history items from project
         var project = getProject();
         if (project == null) {
             logger.warn("Cannot show history menu: project is null");
             JMenuItem errorItem = new JMenuItem("Project not loaded");
             errorItem.setEnabled(false);
             historyMenu.add(errorItem);
         } else {
             List<String> historyItems = project.loadTextHistory();
             logger.debug("History items loaded: {}", historyItems.size());

             if (historyItems.isEmpty()) {
                 JMenuItem emptyItem = new JMenuItem("(No history items)");
                 emptyItem.setEnabled(false);
                 historyMenu.add(emptyItem);
             } else {
                 // Newest items first (top of menu)
                 for (int i = historyItems.size() - 1; i >= 0; i--) {
                     String item = historyItems.get(i);
                     // Truncate for display
                     String itemWithoutNewlines = item.replace('\n', ' ');
                     String displayText = itemWithoutNewlines.length() > TRUNCATION_LENGTH
                         ? itemWithoutNewlines.substring(0, TRUNCATION_LENGTH) + "..."
                         : itemWithoutNewlines;

                     // Basic HTML escaping for tooltip
                     String escapedItem = item.replace("&", "&amp;")
                                              .replace("<", "&lt;")
                                              .replace(">", "&gt;")
                                              .replace("\"", "&quot;");

                     JMenuItem menuItem = new JMenuItem(displayText);
                     menuItem.setToolTipText("<html><pre>" + escapedItem + "</pre></html>"); // Show full text on hover, preserve format

                     menuItem.addActionListener(event -> {
                         commandInputField.setText(item);
                         commandInputField.requestFocusInWindow(); // Focus input after selecting
                     });
                     historyMenu.add(menuItem);
                 }
             }
         }

         // Apply theme to the menu
         if (themeManager != null) {
             themeManager.registerPopupMenu(historyMenu);
         }

         // Use fixed width for menu
         historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
         historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));
         historyMenu.pack(); // Pack after setting preferred size

         logger.debug("Showing history menu with preferred width: {}", DROPDOWN_MENU_WIDTH);
         // Show below the button
         historyMenu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * Initializes the model dropdown by fetching available models from LiteLLM.
     */
    private void initializeModelDropdown() {
        logger.debug("Initializing model dropdown...");

        // Runs on a background thread
        contextManager.submitBackgroundTask("Fetching available models", () -> {
            var modelLocationMap = Models.getAvailableModels();

            // Update UI on EDT
            SwingUtilities.invokeLater(() -> {
                modelDropdown.removeAllItems();

                // Extra debug logging
                modelLocationMap.forEach((k, v) -> logger.debug("Available modelName={} => location={}", k, v));

                if (modelLocationMap.isEmpty()) {
                    logger.error("No models discovered from LiteLLM.");
                    modelDropdown.addItem("No Models Available");
                    modelDropdown.setEnabled(false);
                    disableUserActionButtons();
                } else {
                    logger.debug("Populating dropdown with {} models.", modelLocationMap.size());
                    modelLocationMap.keySet().stream()
                            .filter(k -> !k.contains("-lite"))
                            .sorted()
                            .forEach(modelDropdown::addItem);
                    modelDropdown.setEnabled(true);

                    // Restore the last used model if available
                    var lastUsedModel = getProject().getLastUsedModel();
                    if (lastUsedModel != null) {
                        // Check if the saved model is actually in the list of available models
                        boolean found = false;
                        for (int i = 0; i < modelDropdown.getItemCount(); i++) {
                            if (modelDropdown.getItemAt(i).equals(lastUsedModel)) {
                                modelDropdown.setSelectedItem(lastUsedModel);
                                found = true;
                                logger.debug("Restored last used model: {}", lastUsedModel);
                                break;
                            }
                        }
                        if (!found) {
                            logger.warn("Last used model '{}' not found in available models.", lastUsedModel);
                        }
                    } else {
                         logger.debug("No last used model saved for this project.");
                    }

                    enableUserActionButtons();
                }
            });

            return null;
        });
    }

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
        SwingUtilities.invokeLater(() -> this.commandInputField.requestFocus());
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
        // don't remove null check, need it in case of race on startup
        if (contextPanel != null) {
            contextPanel.updateContextTable();
        }
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
        return gitPanel; // May be null for git-free
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
