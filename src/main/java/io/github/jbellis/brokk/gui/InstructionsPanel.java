package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Context.ParsedOutput;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.SessionResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The InstructionsPanel encapsulates the command input area, history dropdown,
 * mic button, model dropdown, and the action buttons.
 * It also includes the system messages and command result areas.
 * All initialization and action code related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters

    private final Chrome chrome;
    private final RSyntaxTextArea commandInputField;
    private final VoiceInputButton micButton;
    private final JButton agentButton;
    private final JButton codeButton;
    private final JButton askButton;
    private final JButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;
    private final JTextArea systemArea; // Moved from HistoryOutputPanel
    private final JScrollPane systemScrollPane; // Moved from HistoryOutputPanel
    private final JLabel commandResultLabel; // Moved from HistoryOutputPanel

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                   "Instructions",
                                                   TitledBorder.DEFAULT_JUSTIFICATION,
                                                   TitledBorder.DEFAULT_POSITION,
                                                   new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

        // Initialize components
        commandInputField = buildCommandInputField();
        micButton = new VoiceInputButton(
                commandInputField,
                chrome.getContextManager(),
                () -> chrome.actionOutput("Recording"),
                chrome::toolError
        );
        systemArea = new JTextArea(); // Initialize moved component
        systemScrollPane = buildSystemMessagesArea(); // Initialize moved component
        commandResultLabel = buildCommandResultLabel(); // Initialize moved component

        // Initialize Buttons first
        agentButton = new JButton("Agent"); // Initialize the agent button
        agentButton.setMnemonic(KeyEvent.VK_G); // Mnemonic for Agent
        agentButton.setToolTipText("Run the multi-step agent to execute the current plan");
        agentButton.addActionListener(e -> runArchitectCommand());

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

        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> chrome.getContextManager().interruptUserActionThread());

        // Top Bar (History, Model, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + System/Result) (Center)
        JPanel centerPanel = buildCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame() != null && chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });
    }

    private RSyntaxTextArea buildCommandInputField() {
        var area = new RSyntaxTextArea(3, 40);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setHighlightCurrentLine(false);
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3); // Initial rows
        area.setMinimumSize(new Dimension(100, 80));
        area.setAutoIndentEnabled(false);

        // Add Ctrl+Enter shortcut to trigger the default button
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        area.getInputMap().put(ctrlEnter, "submitDefault");
        area.getActionMap().put("submitDefault", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // If there's a default button, "click" it
                var rootPane = SwingUtilities.getRootPane(area);
                if (rootPane != null && rootPane.getDefaultButton() != null) {
                    rootPane.getDefaultButton().doClick();
                }
            }
        });

        return area;
    }

    private JPanel buildTopBarPanel() {
        JPanel topBarPanel = new JPanel(new BorderLayout(5, 0));
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 5));

        // Left Panel (Mic + History) (West)
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftPanel.add(micButton);

        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyButton.addActionListener(e -> showHistoryMenu(historyButton));
        leftPanel.add(historyButton);

        topBarPanel.add(leftPanel, BorderLayout.WEST);

        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(0, 2));

        // Command Input Field (Top)
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 80));
        centerPanel.add(commandScrollPane, BorderLayout.CENTER);

        // System Messages + Command Result (Bottom)
        // Create a panel to hold system messages and the command result label
        var topInfoPanel = new JPanel();
        topInfoPanel.setLayout(new BoxLayout(topInfoPanel, BoxLayout.PAGE_AXIS));
        topInfoPanel.add(commandResultLabel);
        topInfoPanel.add(systemScrollPane);
        centerPanel.add(topInfoPanel, BorderLayout.SOUTH);

        return centerPanel;
    }

    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Action Button Bar
        JPanel actionButtonBar = buildActionBar();
        actionButtonBar.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(actionButtonBar);

        // Add flexible space between action buttons and stop button
        bottomPanel.add(Box.createHorizontalGlue());

        // Add Stop button to the right side
        stopButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(stopButton);

        return bottomPanel;
    }

    // Helper to build just the action buttons (Code, Ask, Search, Run)
    private JPanel buildActionBar() {
        JPanel actionButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actionButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
        actionButtonsPanel.add(agentButton);
        actionButtonsPanel.add(codeButton);
        actionButtonsPanel.add(askButton);
        actionButtonsPanel.add(searchButton);
        actionButtonsPanel.add(runButton);
        return actionButtonsPanel;
    }

    /**
     * Builds the system messages area that appears below the command input area.
     * Moved from HistoryOutputPanel.
     */
    private JScrollPane buildSystemMessagesArea() {
        // Create text area for system messages
        systemArea.setEditable(false);
        systemArea.getCaret().setVisible(false); // Hide the edit caret
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        systemArea.setRows(4);

        // Create scroll pane with border and title
        var scrollPane = new JScrollPane(systemArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        // new SmartScroll(scrollPane);

        return scrollPane;
    }

    /**
     * Builds the command result label.
     * Moved from HistoryOutputPanel.
     */
    private JLabel buildCommandResultLabel() {
        var label = new JLabel(" "); // Start with a space to ensure height
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        label.setBorder(new EmptyBorder(2, 5, 2, 5));
        return label;
    }

    private void showHistoryMenu(Component invoker) {
        logger.debug("Showing history menu");
        JPopupMenu historyMenu = new JPopupMenu();
        var project = chrome.getProject();
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
                for (int i = historyItems.size() - 1; i >= 0; i--) {
                    String item = historyItems.get(i);
                    String itemWithoutNewlines = item.replace('\n', ' ');
                    String displayText = itemWithoutNewlines.length() > TRUNCATION_LENGTH
                                         ? itemWithoutNewlines.substring(0, TRUNCATION_LENGTH) + "..."
                                         : itemWithoutNewlines;
                    String escapedItem = item.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("\"", "&quot;");
                    JMenuItem menuItem = new JMenuItem(displayText);
                    menuItem.setToolTipText("<html><pre>" + escapedItem + "</pre></html>");
                    menuItem.addActionListener(event -> {
                        commandInputField.setText(item);
                        commandInputField.requestFocusInWindow();
                    });
                    historyMenu.add(menuItem);
                }
            }
        }
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyMenu);
        }
        historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
        historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));
        historyMenu.pack();
        logger.debug("Showing history menu with preferred width: {}", DROPDOWN_MENU_WIDTH);
        historyMenu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * Checks if the current context managed by the ContextManager contains any image fragments.
     *
     * @return true if the top context exists and contains at least one PasteImageFragment, false otherwise.
     */
    private boolean contextHasImages() {
        var contextManager = chrome.getContextManager();
        return contextManager.topContext() != null &&
                contextManager.topContext().allFragments().anyMatch(f -> !f.isText());
    }

    /**
     * Shows a modal error dialog informing the user that the required models lack vision support.
     * Offers to open the Model settings tab.
     *
     * @param requiredModelsInfo A string describing the model(s) that lack vision support (e.g., model names).
     */
    private void showVisionSupportErrorDialog(String requiredModelsInfo) {
        String message = """
                <html>The current operation involves images, but the following selected model(s) do not support vision:<br>
                <b>%s</b><br><br>
                Please select vision-capable models in the settings to proceed with image-based tasks.</html>
                """.formatted(requiredModelsInfo);
        Object[] options = {"Open Model Settings", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chrome.getFrame(),
                message,
                "Model Vision Support Error",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null, // icon
                options,
                options[0] // Default button (open settings)
        );

        if (choice == JOptionPane.YES_OPTION) { // Open Settings
            SwingUtilities.invokeLater(() -> SettingsDialog.showSettingsDialog(chrome, "Models"));
        }
        // In either case (Settings opened or Cancel pressed), the original action is aborted by returning from the caller.
    }

    // --- Public API ---

    public String getInputText() {
        return commandInputField.getText();
    }

    public void clearCommandInput() {
        commandInputField.setText("");
    }

    public void requestCommandInputFocus() {
        commandInputField.requestFocus();
    }

    /**
     * Sets the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void setCommandResultText(String text) {
        commandResultLabel.setText(text);
    }

    /**
     * Clears the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void clearCommandResultText() {
        commandResultLabel.setText(" "); // Set back to space to maintain height
    }

    /**
     * Appends text to the system output area with timestamp.
     * Moved from HistoryOutputPanel.
     */
    public void appendSystemOutput(String message) {
        // Format timestamp as HH:MM
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));

        // Add newline if needed
        if (!systemArea.getText().isEmpty() && !systemArea.getText().endsWith("\n")) {
            systemArea.append("\n");
        }

        // Append timestamped message
        systemArea.append(timestamp + ": " + message);
        // Scroll to bottom
        SwingUtilities.invokeLater(() -> systemArea.setCaretPosition(systemArea.getDocument().getLength()));
    } // Added missing closing brace


    // --- Private Execution Logic ---

    /**
     * Executes the core logic for the "Code" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeCodeCommand(StreamingChatLanguageModel model, String input) {
        var contextManager = chrome.getContextManager();
        var project = contextManager.getProject();
        project.pauseAnalyzerRebuilds();
        try {
            var result = new CodeAgent(contextManager, model).runSession(input, true);
            contextManager.addToHistory(result, false);
        } catch (InterruptedException e) {
            chrome.systemOutput("Code agent cancelled!");
        } finally {
            project.resumeAnalyzerRebuilds();
        }
    }

    /**
     * Executes the core logic for the "Ask" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeAskCommand(StreamingChatLanguageModel model, String question) {
        try {
            var contextManager = chrome.getContextManager();
            if (question.isBlank()) {
                chrome.toolErrorRaw("Please provide a question");
                return;
            }
            // Provide the prompt messages
            var messages = new LinkedList<>(AskPrompts.instance.collectMessages(contextManager));
            messages.add(new UserMessage("<question>\n%s\n</question>".formatted(question.trim())));

            // stream from coder using the provided model
            var response = contextManager.getCoder(model, question).sendRequest(messages, true);
            if (response.error() != null) {
                chrome.toolErrorRaw("Error during 'Ask': " + response.error().getMessage());
            } else if (response.chatResponse() != null && response.chatResponse().aiMessage() != null) {
                var aiResponse = response.chatResponse().aiMessage();
                // Check if the response is valid before adding to history
                if (aiResponse.text() != null && !aiResponse.text().isBlank()) {
                    // Construct SessionResult for 'Ask'
                    var sessionResult = new SessionResult("Ask: " + question,
                                                          List.of(messages.getLast(), aiResponse),
                                                          List.copyOf(chrome.getLlmRawMessages()),
                                                          Map.of(), // No undo contents for Ask
                                                          chrome.getLlmOutputText(),
                                                          new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS));
                    contextManager.addToHistory(sessionResult, false);
                } else {
                    chrome.systemOutput("Ask command completed with an empty response.");
                }
            } else {
                chrome.systemOutput("Ask command completed with no response data.");
            }
        } catch (InterruptedException e) {
            chrome.systemOutput("Ask command cancelled!");
        } catch (Exception e) {
            logger.error("Error during 'Ask' execution", e);
            chrome.toolErrorRaw("Internal error during ask command: " + e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Agent" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     *
     * @param goal The initial user instruction passed to the agent.
     */
    private void executeAgentCommand(StreamingChatLanguageModel model, String goal) {
        var contextManager = chrome.getContextManager();
        try {
            var agent = new ArchitectAgent(contextManager, model, contextManager.getToolRegistry(), goal);
            agent.execute();
        } catch (InterruptedException e) {
            chrome.systemOutput("Agent execution cancelled!");
        } catch (Exception e) {
            logger.error("Error during Agent execution", e);
            chrome.toolErrorRaw("Internal error during Agent command: " + e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Search" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeSearchCommand(StreamingChatLanguageModel model, String query) {
        if (query.isBlank()) {
            chrome.toolErrorRaw("Please provide a search query");
            return;
        }
        try {
            var contextManager = chrome.getContextManager();
            var agent = new SearchAgent(query, contextManager, model, contextManager.getToolRegistry(), true);
            var result = agent.execute();
            assert result != null;
            // Search does not stream to llmOutput, so set the final answer here
            chrome.setLlmOutput(List.of(new AiMessage(result.output().text())));
            contextManager.addToHistory(result, false);
        } catch (InterruptedException e) {
            chrome.toolErrorRaw("Search agent interrupted without answering");
        }
    }

    /**
     * Executes the core logic for the "Run in Shell" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeRunCommand(String input) {
        var contextManager = chrome.getContextManager();
        Environment.ProcessResult result;
        try {
            result = Environment.instance.captureShellCommand(input, contextManager.getRoot());
        } catch (InterruptedException e) {
            chrome.systemOutput("Cancelled!");
            return;
        }
        String output = result.output().isBlank() ? "[operation completed with no output]" : result.output();
        var wrappedPutput = "\n```\n" + output + "\n```";
        chrome.llmOutput(wrappedPutput, ChatMessageType.USER, IConsoleIO.MessageSubType.CommandOutput);

        // Add to context history with the output text
        var llmOutputText = chrome.getLlmOutputText();
        contextManager.pushContext(ctx -> {
            var runFrag = new ContextFragment.StringFragment(wrappedPutput, "Run " + input, SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
            var parsed = new ParsedOutput(llmOutputText, runFrag);
            return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture("Run " + input));
        });
    }

    // --- Action Handlers ---

    public void runArchitectCommand() {
        var goal = commandInputField.getText();
        if (goal.isBlank()) {
            chrome.toolErrorRaw("Please provide an initial goal or instruction for the Agent.");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getModels();
        var architectModel = contextManager.getArchitectModel();
        var editModel = contextManager.getEditModel();
        var searchModel = contextManager.getSearchModel();

        if (contextHasImages()) {
            var nonVisionModels = Stream.of(architectModel,editModel, searchModel)
                    .filter(m -> !models.supportsVision(m))
                    .map(models::nameOf)
                    .toList();
            if (!nonVisionModels.isEmpty()) {
                showVisionSupportErrorDialog(String.join(", ", nonVisionModels));
                    return; // Abort if any required model lacks vision and context has images
                }
            }

            disableButtons();
            chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        // Submit the action, calling the private execute method inside the lambda, passing the goal
        contextManager.submitAction("Agent", "Executing project...", () -> executeAgentCommand(architectModel, goal));
    }

    // Methods for running commands. These prepare the input and model, then delegate
    // the core logic execution to contextManager.submitAction, which calls back
    // into the private execute* methods above.

    public void runCodeCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please enter a command or text");
            return;
        }

        var contextManager = chrome.getContextManager();
            var models = contextManager.getModels();
            var codeModel = contextManager.getCodeModel();

            if (contextHasImages() && !models.supportsVision(codeModel)) {
                showVisionSupportErrorDialog(models.nameOf(codeModel) + " (Code)");
                return; // Abort if model doesn't support vision and context has images
            }

            chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();

        // Check if test files are needed before submitting the main task
        checkAndPromptForTests(input)
                .thenAcceptAsync(testsHandled -> {
                    if (testsHandled) {
                        // Tests were handled (added or not needed), proceed with code command
                        SwingUtilities.invokeLater(() -> { // Ensure UI updates happen on EDT if needed after background
                            // Submit the main action
                            chrome.getContextManager().submitAction("Code", input, () -> executeCodeCommand(codeModel, input));
                        });
                    } else {
                        // Test prompting failed or was cancelled, re-enable buttons
                        SwingUtilities.invokeLater(this::enableButtons);
                        chrome.systemOutput("Code command cancelled during test selection.");
                    }
                }, chrome.getContextManager().getBackgroundTasks()); // Execute the continuation on a background thread if needed
    }

    /**
     * Checks if any test files are already present in the context or if no test files exist at all.
     * If neither is true, it asks the LLM to suggest relevant tests and shows a modal selection dialog
     * for the user to pick which tests to add. Returns a CompletableFuture that completes with:
     * - true if tests are already in context, no test files exist, or the user confirms (even if no files were selected),
     * - false if an error occurs or the user cancels in the dialog.
     */
    private CompletableFuture<Boolean> checkAndPromptForTests(String userInput)
    {
        var contextManager = chrome.getContextManager();

        // Gather all test files in the project and all files currently in the context
        var projectTestFiles = contextManager.getTestFiles();
        var contextFiles = java.util.stream.Stream.concat(
                contextManager.getEditableFiles().stream(),
                contextManager.getReadonlyFiles().stream()
        ).collect(Collectors.toSet());

        // If we already have some tests in the context or if there are no tests in the project, no extra work is needed
        boolean testsInContext = projectTestFiles.stream().anyMatch(contextFiles::contains);
        if (testsInContext || projectTestFiles.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        logger.debug("No test files in context. Asking LLM for suggestions...");
        chrome.systemOutput("No test files in context. Asking LLM for suggestions...");

        // Bridge the background task to a CompletableFuture
        var resultFuture = new CompletableFuture<Boolean>();

        // Submit the background task that interacts with the LLM
        contextManager.submitBackgroundTask("Suggest relevant tests", () -> {
            try {
                var quickModel = contextManager.getModels().quickModel();
                var coder = contextManager.getCoder(quickModel, "Suggest Tests");
                var prompt = createTestSuggestionPrompt(userInput, projectTestFiles, contextManager);
                var llmResult = coder.sendRequest(List.of(new UserMessage(prompt)));

                if (llmResult.error() != null
                        || llmResult.chatResponse() == null
                        || llmResult.chatResponse().aiMessage() == null) {
                    logger.error("LLM failed to suggest tests: {}",
                                 llmResult.error() != null ? llmResult.error() : "Empty/Null response");
                    chrome.toolErrorRaw("LLM failed to suggest relevant tests.");
                    resultFuture.complete(true);
                    return;
                }

                var suggestedFiles = parseSuggestedFiles(llmResult.chatResponse().aiMessage().text(), contextManager);
                if (suggestedFiles.isEmpty()) {
                    logger.debug("No valid tests suggested; proceeding without adding tests.");
                    chrome.systemOutput("No specific tests suggested. Proceeding without adding tests.");
                    resultFuture.complete(true);
                    return;
                }

                // Show dialog in the EDT
                final List<ProjectFile>[] dialogResult = new List[1];
                SwingUtil.runOnEDT(() -> {
                    dialogResult[0] = showTestSelectionDialog(suggestedFiles); // null if user cancels
                });
                var selectedFiles = dialogResult[0];

                // If user canceled
                if (selectedFiles == null) {
                    logger.debug("User cancelled the test selection dialog.");
                    chrome.systemOutput("Test selection cancelled.");
                    resultFuture.complete(false);
                    return;
                }

                // If user confirmed but selected none
                if (selectedFiles.isEmpty()) {
                    logger.debug("No test files selected. Proceeding without adding tests.");
                    resultFuture.complete(true);
                    return;
                }

                // Add selected test files to the context
                logger.debug("User selected {} test file(s) to add.", selectedFiles.size());
                chrome.systemOutput("Adding %d selected test file(s) to context...".formatted(selectedFiles.size()));
                contextManager.addReadOnlyFiles(selectedFiles);

                // Success!
                resultFuture.complete(true);
            } catch (Exception e) {
                logger.error("Error while suggesting or selecting tests", e);
                chrome.toolErrorRaw("Error suggesting relevant tests: " + e.getMessage());
                resultFuture.complete(false);
            }
        });

        return resultFuture;
    }

    /**
     * Creates the prompt for the LLM to suggest relevant test files.
     */
    private String createTestSuggestionPrompt(String userInput, List<ProjectFile> allTestFiles, ContextManager contextManager) {
        String workspaceSummary = """
                Editable Files:
                %s
                
                Read-Only Files:
                %s
                """.formatted(contextManager.getEditableSummary(), contextManager.getReadOnlySummary(true)).stripIndent();

        String testFilePaths = allTestFiles.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));

        return """
                Given the user's goal and the current workspace files, which of the following test files seem most relevant?
                List *only* the file paths of the relevant tests, each on a new line.
                If none seem particularly relevant, respond with an empty message.
                
                User Goal:
                %s
                
                Workspace Files:
                %s
                
                Available Test Files:
                %s
                
                Relevant Test File Paths (one per line):
                """.formatted(userInput, workspaceSummary, testFilePaths).stripIndent();
    }

    /**
     * Parses the LLM response to extract suggested file paths.
     */
    private List<ProjectFile> parseSuggestedFiles(String llmResponse, ContextManager contextManager) {
        return contextManager.getProject().getFiles().stream().parallel()
                .filter(f -> llmResponse.contains(f.toString()))
                .toList();
    }

    /**
     * Shows a modal dialog allowing the user to select test files to add to context.
     *
     * @param suggestedFiles The list of ProjectFiles suggested by the LLM.
     * @return A list of ProjectFiles selected by the user, or null if the dialog was cancelled.
     */
    private List<ProjectFile> showTestSelectionDialog(List<ProjectFile> suggestedFiles) {
        JDialog dialog = new JDialog(chrome.getFrame(), "Add tests before coding?", true); // Modal dialog
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setMinimumSize(new Dimension(400, 300));

        JLabel instructionLabel = new JLabel("<html>Select test files to add to the context (read-only):</html>");
        instructionLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        dialog.add(instructionLabel, BorderLayout.NORTH);

        JButton okButton = new JButton("Continue without Tests"); // Initial text
        JButton cancelButton = new JButton("Cancel");

        // Panel to hold checkboxes
        JPanel checkboxPanel = new JPanel();
        checkboxPanel.setLayout(new BoxLayout(checkboxPanel, BoxLayout.Y_AXIS));
        List<JCheckBox> checkBoxes = new ArrayList<>();
        for (ProjectFile file : suggestedFiles) {
            JCheckBox checkBox = new JCheckBox(file.toString());
            checkBox.setSelected(false);
            // Add listener to update button text when selection changes
            checkBox.addItemListener(e -> {
                boolean anySelected = checkBoxes.stream().anyMatch(JCheckBox::isSelected);
                okButton.setText(anySelected ? "Add Tests and Continue" : "Continue without Tests");
            });
            checkBoxes.add(checkBox);
            checkboxPanel.add(checkBox);
        }

        JScrollPane scrollPane = new JScrollPane(checkboxPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        dialog.add(scrollPane, BorderLayout.CENTER);

        // Buttons for confirmation or cancellation
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        // Result list - effectively final for lambda access
        final List<ProjectFile>[] result = new List[1];
        result[0] = null; // Default to null (indicates cancellation)

        okButton.addActionListener(e -> {
            List<ProjectFile> selected = new ArrayList<>();
            for (int i = 0; i < checkBoxes.size(); i++) {
                if (checkBoxes.get(i).isSelected()) {
                    selected.add(suggestedFiles.get(i));
                }
            }
            result[0] = selected; // Store selected list
            dialog.dispose();
        });

        cancelButton.addActionListener(e -> {
            result[0] = null; // Ensure result is null on cancel
            dialog.dispose();
        });

        dialog.pack(); // Adjust size
        dialog.setLocationRelativeTo(chrome.getFrame()); // Center relative to main window
        dialog.setVisible(true); // Show modal dialog - blocks until disposed

        // Return the captured result after the dialog is closed
        return result[0];
    }


    public void runAskCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please enter a question");
            return;
        }

        var contextManager = chrome.getContextManager();
            var models = contextManager.getModels();
            var askModel = contextManager.getAskModel(); // Use dedicated Ask model

            // --- Vision Check ---
            if (contextHasImages() && !models.supportsVision(askModel)) {
                showVisionSupportErrorDialog(models.nameOf(askModel) + " (Ask)"); // Updated text
                return; // Abort if model doesn't support vision and context has images
            }
            // --- End Vision Check ---

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        chrome.getContextManager().submitAction("Ask", input, () -> executeAskCommand(askModel, input));
    }

    public void runSearchCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please provide a search query");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getModels();
        var searchModel = contextManager.getSearchModel();

        if (contextHasImages() && !models.supportsVision(searchModel)) {
            showVisionSupportErrorDialog(models.nameOf(searchModel) + " (Search)");
            return; // Abort if model doesn't support vision and context has images
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        // Update the LLM output panel directly via Chrome
        chrome.llmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.", ChatMessageType.USER);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        chrome.getContextManager().submitAction("Search", input, () -> executeSearchCommand(searchModel, input));
    }

    public void runRunCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command to run");
            return;
        }
        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        chrome.getContextManager().submitAction("Run", input, () -> executeRunCommand(input));
    }

    // Methods to disable and enable buttons.
    public void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            agentButton.setEnabled(false);
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
            micButton.setEnabled(false);
        });
    }

    public void enableButtons() {
        SwingUtilities.invokeLater(() -> {
            // Buttons are enabled if a project is loaded; specific model availability is checked in action handlers.
            boolean projectLoaded = chrome.getProject() != null;
            agentButton.setEnabled(projectLoaded);
            codeButton.setEnabled(projectLoaded);
            askButton.setEnabled(projectLoaded);
            searchButton.setEnabled(projectLoaded);
            runButton.setEnabled(true); // Run in shell is always available
            stopButton.setEnabled(false);
            micButton.setEnabled(true);
        });
    }
}
