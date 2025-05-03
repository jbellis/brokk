package io.github.jbellis.brokk.gui;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.functions.Generator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.ContextFragment.TaskFragment;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;
import io.github.jbellis.brokk.gui.components.BrowserLabel;
import io.github.jbellis.brokk.gui.dialogs.ArchitectOptionsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.jbellis.brokk.gui.mop.ThemeColors;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;


/**
 * The InstructionsPanel encapsulates the command input area, history dropdown,
 * mic button, model dropdown, and the action buttons.
 * It also includes the system messages and command result areas.
 * All initialization and action code related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel implements IContextManager.ContextListener { 
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    private static final String PLACEHOLDER_TEXT = """
                                                   Put your instructions or questions here.  Brokk will suggest relevant files below; right-click on them to add them to your Workspace.  The Workspace will be visible to the AI when coding or answering your questions.
                                                   
                                                   More tips are available in the Getting Started section on the right -->
                                                   """;

    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters

    private final Chrome chrome;
    private final JTextArea commandInputField;
    private final VoiceInputButton micButton;
    private final JButton agentButton;
    private final JButton codeButton;
    private final JButton askButton;
    private final JButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;
    private final JButton configureModelsButton;
    private final JTextArea systemArea;
    private final JScrollPane systemScrollPane;
    private final JLabel commandResultLabel;
    private JTable referenceFileTable;
    private JScrollPane tableScrollPane;
    private JLabel failureReasonLabel;
    private JPanel suggestionContentPanel;
    private CardLayout suggestionCardLayout;
    private final JButton deepScanButton;
    private final JPanel centerPanel;
    private final Timer contextSuggestionTimer; // Timer for debouncing quick context suggestions
    // Worker for autocontext suggestion tasks. we don't use CM.backgroundTasks b/c we want this to be single threaded
    private final ExecutorService suggestionWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setName("Brokk-Suggestion-Worker");
        t.setDaemon(true);
        return t;
    });
    // Generation counter to identify the latest suggestion request
    private final AtomicLong suggestionGeneration = new AtomicLong(0);
    private final AtomicBoolean suppressExternalSuggestionsTrigger = new AtomicBoolean(false); // Flag to prevent self-refresh from table actions
    private JPanel overlayPanel; // Panel used to initially disable command input
    private boolean lowBalanceNotified = false;
    private boolean freeTierNotified = false;
    private String lastCheckedInputText = null;
    private float[][] lastCheckedEmbeddings = null;

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                   "Instructions",
                                                   TitledBorder.DEFAULT_JUSTIFICATION,
                                                   TitledBorder.DEFAULT_POSITION,
                                                   new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

        // Initialize components
        commandInputField = buildCommandInputField(); // Build first to add listener
        micButton = new VoiceInputButton(commandInputField, chrome.getContextManager(), () -> {
            activateCommandInput();
            chrome.actionOutput("Recording");
        },
                                         chrome::toolError);
        systemArea = new JTextArea();
        systemScrollPane = buildSystemMessagesArea();
        commandResultLabel = buildCommandResultLabel(); // Initialize moved component

        // Initialize Buttons first
        agentButton = new JButton("Architect"); // Initialize the agent button
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

        configureModelsButton = new JButton("Configure Models...");
        configureModelsButton.setToolTipText("Open settings to configure AI models");
        configureModelsButton.addActionListener(e -> SettingsDialog.showSettingsDialog(chrome, "Models"));

        // Renamed button and updated action listener
        deepScanButton = new JButton("Deep Scan");
        deepScanButton.setToolTipText("Perform a deeper analysis (Code + Tests) to suggest relevant context");
        deepScanButton.addActionListener(this::triggerDeepScan);
        deepScanButton.setEnabled(false); // Start disabled like command input

        // Top Bar (History, Configure Models, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + System/Result) (Center)
        this.centerPanel = buildCenterPanel();
        add(this.centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        // Initialize the reference file table and suggestion area
        initializeReferenceFileTable();

        // Initialize and configure the context suggestion timer
        contextSuggestionTimer = new Timer(200, this::triggerContextSuggestion);
        contextSuggestionTimer.setRepeats(false);
        commandInputField.getDocument().addDocumentListener(new DocumentListener() {
            private void checkAndHandleSuggestions() {
                if (getInstructions().split("\\s+").length >= 2) {
                    contextSuggestionTimer.restart();
                } else {
                    // Input is blank or too short: stop timer, invalidate generation, reset state, schedule UI clear.
                    contextSuggestionTimer.stop();
                    long myGen = suggestionGeneration.incrementAndGet(); // Invalidate any running/pending task
                    logger.trace("Input cleared/shortened, stopping timer and invalidating suggestions (gen {})", myGen);

                    // Reset internal state immediately
                    InstructionsPanel.this.lastCheckedInputText = null;
                    InstructionsPanel.this.lastCheckedEmbeddings = null;

                    // Schedule UI update, guarded by generation check
                    SwingUtilities.invokeLater(() -> {
                        if (myGen == suggestionGeneration.get()) {
                            logger.trace("Applying UI clear for gen {}", myGen);
                            referenceFileTable.setValueAt(List.of(), 0, 0);
                            failureReasonLabel.setVisible(false);
                            suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show empty table
                        } else {
                            logger.trace("Skipping UI clear for gen {} (current gen {})", myGen, suggestionGeneration.get());
                        }
                    });
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                checkAndHandleSuggestions();
            }
        });

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame() != null && chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });
        // Add this panel as a listener to context changes
        chrome.getContextManager().addContextListener(this);
    }

    private JTextArea buildCommandInputField() {
        var area = new JTextArea(3, 40);
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3); // Initial rows
        area.setMinimumSize(new Dimension(100, 80));
        area.setEnabled(false); // Start disabled
        area.setText(PLACEHOLDER_TEXT); // Keep placeholder, will be cleared on activation

        // Add Ctrl+Enter shortcut to trigger the default button
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        area.getInputMap().put(ctrlEnter, "submitDefault");
        area.getActionMap().put("submitDefault", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
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
        leftPanel.add(configureModelsButton); // Add the new button here

        topBarPanel.add(leftPanel, BorderLayout.WEST);

        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        // Command Input Field
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80)); // Use preferred size for layout
        commandScrollPane.setMinimumSize(new Dimension(100, 80));

        // Transparent input-overlay panel
        this.overlayPanel = new JPanel(); // Initialize the member variable
        overlayPanel.setOpaque(false); // Make it transparent
        overlayPanel.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)); // Hint text input

        // Layered pane to stack command input and overlay
        var layeredPane = new JLayeredPane();
        // Set layout manager for layered pane to handle component bounds automatically
        layeredPane.setLayout(new OverlayLayout(layeredPane)); // Or use custom layout if needed
        layeredPane.setPreferredSize(commandScrollPane.getPreferredSize()); // Match size
        layeredPane.setMinimumSize(commandScrollPane.getMinimumSize());

        // Add components to layers
        layeredPane.add(commandScrollPane, JLayeredPane.DEFAULT_LAYER); // Input field at the bottom
        layeredPane.add(overlayPanel, JLayeredPane.PALETTE_LAYER); // Overlay on top

        // Mouse listener for the overlay
        overlayPanel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                activateCommandInput();
            }
        });

        panel.add(layeredPane); // Add the layered pane instead of the scroll pane directly

        // Reference-file table will be inserted just below the command input (now layeredPane)
        // by initializeReferenceFileTable()

        // System Messages + Command Result
        var topInfoPanel = new JPanel();
        topInfoPanel.setLayout(new BoxLayout(topInfoPanel, BoxLayout.PAGE_AXIS));
        topInfoPanel.add(commandResultLabel);
        topInfoPanel.add(systemScrollPane);
        panel.add(topInfoPanel);

        return panel;
    }

    /**
     * Initializes the file-reference table that sits directly beneath the
     * command-input field and wires a context-menu that targets the specific
     * badge the mouse is over (mirrors ContextPanel behaviour).
     */
    private void initializeReferenceFileTable()
    {
        // ----- create the table itself --------------------------------------------------------
        referenceFileTable = new JTable(new javax.swing.table.DefaultTableModel(
                new Object[]{"File References"}, 1)
        {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return List.class;
            }
        });
        referenceFileTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        referenceFileTable.setRowHeight(23);                 // match ContextPanel
        referenceFileTable.setTableHeader(null);             // single-column ⇒ header not needed
        referenceFileTable.setShowGrid(false);
        referenceFileTable.getColumnModel()
                .getColumn(0)
                .setCellRenderer(new TableUtils.FileReferencesTableCellRenderer());

        // Clear initial content (it will be populated by context suggestions)
        referenceFileTable.setValueAt(List.of(), 0, 0);

        // ----- context-menu support -----------------------------------------------------------
        referenceFileTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = referenceFileTable.rowAtPoint(e.getPoint());
                if (row < 0) return;

                referenceFileTable.requestFocusInWindow(); // Request focus on right-click
                referenceFileTable.setRowSelectionInterval(row, row);
                @SuppressWarnings("unchecked")
                var fileRefs = (List<FileReferenceData>)
                        referenceFileTable.getValueAt(row, 0);

                if (fileRefs == null || fileRefs.isEmpty()) return;

                // --- NEW: determine which badge the mouse is over -----------------------------
                var targetRef = findClickedReference(e.getPoint(), row, fileRefs) == null
                                ? fileRefs.get(0)
                                : findClickedReference(e.getPoint(), row, fileRefs);
                assert targetRef != null;

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
                    chrome.contextManager.submitContextTask("Edit files", () -> {
                        if (targetRef.getRepoFile() != null) {
                            suppressExternalSuggestionsTrigger.set(true);
                            cm.editFiles(List.of(targetRef.getRepoFile()));
                        } else {
                            chrome.toolErrorRaw("Cannot edit file: " + targetRef.getFullPath() + " - no ProjectFile available");
                        }
                    });
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
                    chrome.contextManager.submitContextTask("Read files", () -> {
                        if (targetRef.getRepoFile() != null) {
                            suppressExternalSuggestionsTrigger.set(true);
                            cm.addReadOnlyFiles(List.of(targetRef.getRepoFile()));
                        } else {
                            chrome.toolErrorRaw("Cannot read file: " + targetRef.getFullPath() + " - no ProjectFile available");
                        }
                    });
                });
                menu.add(readItem);

                // Summarize option
                JMenuItem summarizeItem = new JMenuItem("Summarize " + targetRef.getFullPath());
                summarizeItem.addActionListener(e1 -> {
                    chrome.contextManager.submitContextTask("Summarize files", () -> {
                        if (targetRef.getRepoFile() != null) {
                            suppressExternalSuggestionsTrigger.set(true);
                            boolean success = cm.addSummaries(Set.of(targetRef.getRepoFile()), Set.of());
                            if (success) {
                                chrome.systemOutput("Summarized " + targetRef.getFullPath());
                            } else {
                                chrome.toolErrorRaw("No summarizable code found");
                            }
                        } else {
                            chrome.toolErrorRaw("Cannot summarize: " + targetRef.getFullPath() + " - ProjectFile information not available");
                        }
                    });
                });
                menu.add(summarizeItem);

                if (chrome.themeManager != null) chrome.themeManager.registerPopupMenu(menu);
                menu.show(referenceFileTable, e.getX(), e.getY());
            }
        });

        // Clear selection when the table loses focus
        referenceFileTable.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                referenceFileTable.clearSelection();
            }
        });

        // ----- wrap table in a scroll-pane ----------------------------------------------------
        this.tableScrollPane = new JScrollPane(referenceFileTable);
        tableScrollPane.setBorder(BorderFactory.createEmptyBorder());
        tableScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        // ----- create failure reason label ----------------------------------------------------
        this.failureReasonLabel = new JLabel();
        failureReasonLabel.setFont(referenceFileTable.getFont()); // Use same font as table/badges
        failureReasonLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5)); // Add some padding
        failureReasonLabel.setVisible(false); // Initially hidden

        // ----- create content panel with CardLayout -------------------------------------------
        this.suggestionCardLayout = new CardLayout();
        this.suggestionContentPanel = new JPanel(suggestionCardLayout);
        suggestionContentPanel.add(tableScrollPane, "TABLE");
        suggestionContentPanel.add(failureReasonLabel, "LABEL");

        // ----- create container panel for button and content (table/label) -------------------
        var suggestionAreaPanel = new JPanel(new BorderLayout(5, 0)); // 5px horizontal gap
        suggestionAreaPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0)); // Add vertical padding

        // Add the Deep Scan button to the left
        suggestionAreaPanel.add(deepScanButton, BorderLayout.WEST);
        // Add the card layout panel (containing table or label) to the center
        suggestionAreaPanel.add(suggestionContentPanel, BorderLayout.CENTER);

        // Apply height constraints to the container panel
        int rowHeight = referenceFileTable.getRowHeight();
        int fixedHeight = rowHeight + 2; // +2 for a tiny margin (match table row height)
        suggestionAreaPanel.setPreferredSize(new Dimension(600, fixedHeight));
        suggestionAreaPanel.setMinimumSize(new Dimension(100, fixedHeight));
        suggestionAreaPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fixedHeight));

        // Insert the container panel beneath the command-input area (index 1)
        centerPanel.add(suggestionAreaPanel, 1);
    }

    /**
     * Resolves which {@link FileReferenceData} badge is under the supplied mouse
     * location.  Logic is identical to ContextPanel#findClickedReference.
     */
    private FileReferenceData findClickedReference(Point pointInTableCoords,
                                                   int row,
                                                   List<FileReferenceData> references)
    {
        // Convert to cell-local coordinates
        Rectangle cellRect = referenceFileTable.getCellRect(row, 0, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) return null;

        // Badge layout parameters – keep in sync with FileReferenceList
        final int hgap = 4;     // FlowLayout hgap
        final int horizontalPadding = 12;    // label internal padding (6 px each side)
        final int borderThickness = 3;     // stroke + antialias buffer

        // Font used inside the badges (85 % of table font size)
        var baseFont = referenceFileTable.getFont();
        var badgeFont = baseFont.deriveFont(Font.PLAIN, baseFont.getSize() * 0.85f);
        var fm = referenceFileTable.getFontMetrics(badgeFont);

        int currentX = 0;
        for (var ref : references) {
            int textWidth = fm.stringWidth(ref.getFileName());
            int labelWidth = textWidth + horizontalPadding + borderThickness;
            if (xInCell >= currentX && xInCell <= currentX + labelWidth) {
                return ref;
            }
            currentX += labelWidth + hgap;
        }
        return null;
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
        // thinkButton is moved next to the reference table
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
                        // Hide overlay and enable input field and deep scan button
                        overlayPanel.setVisible(false);
                        setCommandInputAndDeepScanEnabled(true);

                        // Set text and request focus
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
                contextManager.topContext().allFragments()
                        .anyMatch(f -> !f.isText() && !(f instanceof ContextFragment.OutputFragment));
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

    /**
     * Gets the current user input text. If the placeholder is currently displayed,
     * it returns an empty string, otherwise it returns the actual text content.
     */
    public String getInstructions() {
        return commandInputField.getText().equals(PLACEHOLDER_TEXT)
               ? ""
               : commandInputField.getText();
    }

    /**
     * Clears the command input field and ensures the text color is set to the standard foreground.
     * This prevents the placeholder from reappearing inadvertently.
     */
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
    }


    // --- Private Execution Logic ---

    /**
     * Called by the contextSuggestionTimer or external events (like context changes)
     * to initiate a context suggestion task. It increments the generation counter
     * and submits the task to the sequential worker executor.
     */
    private void triggerContextSuggestion(ActionEvent e) { // ActionEvent will be null for external triggers
        var goal = getInstructions(); // Capture snapshot on EDT

        // Basic checks before submitting to worker
        if (goal.isBlank()) {
            // The DocumentListener handles clearing
            logger.trace("triggerContextSuggestion called with empty goal, not submitting task.");
            return;
        }

        // Increment generation and submit the task
        long myGen = suggestionGeneration.incrementAndGet();
        logger.trace("Submitting suggestion task generation {}", myGen);
        suggestionWorker.submit(() -> processInputSuggestions(myGen, goal));
    }

    /**
     * Performs the actual context suggestion logic off the EDT.
     * This method includes checks against the current `suggestionGeneration`
     * to ensure only the latest task proceeds and updates the UI.
     *
     * @param myGen    The generation number of this specific task.
     * @param snapshot The input text captured when this task was initiated.
     */
    private void processInputSuggestions(long myGen, String snapshot) {
        logger.trace("Starting suggestion task generation {}", myGen);
        var contextManager = chrome.getContextManager(); // Get latest context manager

        // 0. Initial staleness check
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale (current gen {}), aborting early.", myGen, suggestionGeneration.get());
            return;
        }

        // 1. Quick literal check
        if (snapshot.equals(lastCheckedInputText)) {
            logger.trace("Task {} input is literally unchanged, aborting.", myGen);
            clearTransientFailureLabelIfStale(myGen); // Clear "Waiting for model..." if applicable
            return;
        }

        // 2. Embedding Model Check
        if (!Brokk.embeddingModelFuture.isDone()) {
            SwingUtilities.invokeLater(() -> showFailureLabel("Waiting for model download"));
            logger.trace("Task {} waiting for model.", myGen);
            return; // Don't proceed further until model is ready
        }
        AbstractModel embeddingModel;
        try {
            embeddingModel = Brokk.embeddingModelFuture.get();
            assert embeddingModel != null;
        } catch (ExecutionException | InterruptedException ex) {
            logger.error("Task {} failed to get embedding model", myGen, ex);
            SwingUtilities.invokeLater(() -> showFailureLabel("Error loading embedding model"));
            return;
        }

        // 3. Staleness check before embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale before embedding, aborting.", myGen);
            return;
        }

        // 4. Compute Embeddings
        List<String> chunks = Arrays.stream(snapshot.split("[.\\n]"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        float[][] newEmbeddings = chunks.isEmpty()
                                  ? new float[0][]
                                  : chunks.stream()
                                          .map(chunk -> embeddingModel.embed(chunk, Generator.PoolingType.AVG))
                                          .toArray(float[][]::new);

        // 5. Staleness check after embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale after embedding, aborting.", myGen);
            return;
        }

        // 6. Semantic Comparison
        boolean isDifferent = isSemanticallyDifferent(snapshot, newEmbeddings);

        if (!isDifferent) {
            logger.trace("Task {} input is semantically similar, aborting ContextAgent.", myGen);
            clearTransientFailureLabelIfStale(myGen); // Clear "Waiting for model..." if applicable
            return;
        }

        // Update state
        this.lastCheckedInputText = snapshot;
        this.lastCheckedEmbeddings = newEmbeddings;
        // No more staleness checks so that we don't throw away the suggestions for this one only to discover
        // that the one that canceled it was not semantically different enough

        // 8. Run ContextAgent
        logger.debug("Task {} fetching QUICK context recommendations for: '{}'", myGen, snapshot);
        var model = contextManager.getModels().quickestModel();
        ContextAgent.RecommendationResult recommendations;
        try {
            ContextAgent agent = new ContextAgent(contextManager, model, snapshot, false);
            recommendations = agent.getRecommendations();
        } catch (InterruptedException ex) {
            throw new RuntimeException();
        }

        // 10. Process results and schedule UI update
        if (recommendations.success()) {
            var fileRefs = recommendations.fragments().stream()
                    .flatMap(f -> f.files(contextManager.getProject()).stream())
                    .distinct()
                    .map(pf -> new FileReferenceData(pf.getFileName(), pf.toString(), pf))
                    .toList();
            logger.debug("Task {} updating quick reference table with {} suggestions", myGen, fileRefs.size());
            SwingUtilities.invokeLater(() -> showSuggestionsTable(fileRefs));
        } else {
            logger.debug("Task {} quick context suggestion failed: {}", myGen, recommendations.reasoning());
            SwingUtilities.invokeLater(() -> showFailureLabel(recommendations.reasoning()));
        }
    }

    /**
     * Checks if the new text/embeddings are semantically different from the
     * last processed state (`lastCheckedInputText`, `lastCheckedEmbeddings`).
     */
    private boolean isSemanticallyDifferent(String currentText, float[][] newEmbeddings) {
        if (lastCheckedInputText == null || lastCheckedEmbeddings == null) {
            // First run or state was reset. Treat as different and store the new embeddings.
            logger.debug("New embeddings input is trivially different from empty old");
            return true;
        }

        // Compare lengths
        if (newEmbeddings.length != lastCheckedEmbeddings.length) {
            logger.debug("New embeddings length differs from last checked embeddings length.");
            return true;
        }

        // Compare pairwise cosine similarity
        final float SIMILARITY_THRESHOLD = 0.85f;
        float minSimilarity = Float.MAX_VALUE;
        for (int i = 0; i < newEmbeddings.length; i++) {
            float similarity = cosine(newEmbeddings[i], lastCheckedEmbeddings[i]);
            if (similarity < minSimilarity) {
                minSimilarity = similarity;
            }
            if (similarity < SIMILARITY_THRESHOLD) {
                var msg = """
                New embeddings similarity = %.3f, triggering recompute
                
                # Old text
                %s
                
                # New text
                %s
                """.formatted(similarity, lastCheckedInputText, currentText);
                logger.debug(msg);
                return true;
            }
        }

        logger.debug("Minimum similarity was {}", minSimilarity);

        // If lengths match and all similarities are above threshold, it's not different enough.
        // Do NOT update lastCheckedEmbeddings here, keep the previous ones for the next comparison.
        return false;
    }

    /**
     * Helper to clear transient failure messages (like "Waiting for model...") from the UI
     * *only if* the current generation matches the provided `taskGen` (meaning no newer
     * task has started) and the message indicates a known transient state.
     * This prevents clearing a valid error message from a newer task.
     */
    private void clearTransientFailureLabelIfStale(long taskGen) {
        SwingUtilities.invokeLater(() -> {
            if (taskGen == suggestionGeneration.get() && failureReasonLabel.isVisible()) {
                String currentLabelText = failureReasonLabel.getText();
                // Add more transient message prefixes if needed
                if (currentLabelText != null && currentLabelText.startsWith("Waiting for model")) {
                    logger.trace("Clearing transient failure label '{}' as task {} determined input unchanged/similar.", currentLabelText, taskGen);
                    failureReasonLabel.setVisible(false);
                    // Optionally show the (likely empty) table again
                    suggestionCardLayout.show(suggestionContentPanel, "TABLE");
                }
            }
        });
    }

    /**
     * Helper to show the failure label with a message.
     */
    private void showFailureLabel(String message) {
        boolean isDark = UIManager.getBoolean("laf.dark");
        failureReasonLabel.setForeground(ThemeColors.getColor(isDark, "badge_foreground"));
        failureReasonLabel.setText(message);
        failureReasonLabel.setVisible(true);
        tableScrollPane.setVisible(false); // Ensure table scrollpane is hidden
        referenceFileTable.setValueAt(List.of(), 0, 0); // Clear table data
        suggestionCardLayout.show(suggestionContentPanel, "LABEL"); // Show label
    }

    /**
     * Helper to show the suggestions table with file references.
     */
    private void showSuggestionsTable(List<FileReferenceData> fileRefs) {
        referenceFileTable.setValueAt(fileRefs, 0, 0);
        failureReasonLabel.setVisible(false);
        tableScrollPane.setVisible(true); // Ensure table scrollpane is visible
        suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show table
    }

    /**
     * Triggered by the "Deep Scan" button click.
     * Runs the Deep Scan agents and shows the results dialog.
     * Delegates the core logic to the DeepScanDialog class.
     */
    private void triggerDeepScan(ActionEvent e) {
        var goal = getInstructions();
        try {
            DeepScanDialog.triggerDeepScan(chrome, goal);
        } finally {
            chrome.getContextManager().submitBackgroundTask("", this::checkBalanceAndNotify);
        }
    }

    /**
     * Checks the user's balance if using the Brokk proxy and displays a notification
     * if the balance is low.
     */
    public void checkBalanceAndNotify() {
        if (Project.getLlmProxySetting() != Project.LlmProxySetting.BROKK) {
            return; // Only check balance when using Brokk proxy
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getModels();

        contextManager.submitBackgroundTask("", () -> {
            try {
                float balance = models.getUserBalance();
                logger.debug("Checked balance: ${}", String.format("%.2f", balance));

                // If balance drops below the minimum paid threshold, reinitialize models to enforce free tier
                if (balance < Models.MINIMUM_PAID_BALANCE) {
                    logger.debug("Balance below minimum paid threshold (${}), reinitializing models to free tier.", Models.MINIMUM_PAID_BALANCE);
                    // This will refetch models and apply the lowBalance filter based on MINIMUM_PAID_BALANCE
                    models.reinit(contextManager.getProject());

                    SwingUtilities.invokeLater(() -> {
                        if (freeTierNotified) {
                            // Only show the dialog once unless balance recovers
                            return;
                        }

                        var panel = new JPanel();
                        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
                        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

                        panel.add(new JLabel("Brokk is running in the free tier. Only low-cost models are available."));
                        panel.add(Box.createVerticalStrut(5));
                        var label = new JLabel("To enable smarter models, subscribe or top up at:");
                        panel.add(label);
                        var browserLabel = new BrowserLabel(Models.TOP_UP_URL);
                        browserLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                        label.setAlignmentX(Component.LEFT_ALIGNMENT);
                        panel.add(browserLabel);

                        JOptionPane.showMessageDialog(
                                chrome.getFrame(),
                                panel,
                                "Balance Exhausted",
                                JOptionPane.WARNING_MESSAGE
                        );
                    });
                } else if (balance < Models.LOW_BALANCE_WARN_AT) {
                    if (lowBalanceNotified) {
                        // Only show the dialog once unless balance recovers
                        return;
                    }

                    lowBalanceNotified = true;
                    SwingUtilities.invokeLater(() -> {
                        var panel = new JPanel(new BorderLayout(0, 5)); // Panel for text and link
                        var balanceMessage = String.format("Low account balance: $%.2f.", balance);
                        panel.add(new JLabel(balanceMessage), BorderLayout.NORTH);

                        var browserLabel = new io.github.jbellis.brokk.gui.components.BrowserLabel(Models.TOP_UP_URL, "Top up at " + Models.TOP_UP_URL + " to avoid interruptions.");
                        panel.add(browserLabel, BorderLayout.SOUTH);

                        JOptionPane.showMessageDialog(
                                chrome.getFrame(),
                                panel,
                                "Low Balance Warning",
                                JOptionPane.WARNING_MESSAGE);
                    });
                } else {
                    // reset the notification flag
                    lowBalanceNotified = false;
                }
            } catch (java.io.IOException e) {
                logger.error("Failed to check user balance", e);
            }
        });
    }


    /**
     * Executes the core logic for the "Code" command.
     * This runs inside the Runnable passed to contextManager.submitUserTask.
     */
    private void executeCodeCommand(StreamingChatLanguageModel model, String input) {
        var contextManager = chrome.getContextManager();
        var project = contextManager.getProject();

        project.pauseAnalyzerRebuilds();
        try {
            var result = new CodeAgent(contextManager, model).runSession(input, false);
            if (result.stopDetails().reason() == SessionResult.StopReason.INTERRUPTED) {
                chrome.systemOutput("Code Agent cancelled!");
                // Save the partial result (if we didn't interrupt before we got any replies)
                if (result.output().messages().stream().anyMatch(m -> m instanceof AiMessage)) {
                    contextManager.addToHistory(result, false);
                }
            } else {
                if (result.stopDetails().reason() == SessionResult.StopReason.SUCCESS) {
                    chrome.systemOutput("Code Agent complete!");
                }
                // Code agent has logged error to console already
                contextManager.addToHistory(result, false);
            }
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

            // stream from coder using the provided model
            var messages = CodePrompts.instance.collectAskMessages(contextManager, question);
            var response = contextManager.getLlm(model, "Ask: " + question).sendRequest(messages, true);
            if (response.error() != null) {
                chrome.toolErrorRaw("Error during 'Ask': " + response.error().getMessage());
            } else if (response.chatResponse() != null && response.chatResponse().aiMessage() != null) {
                var aiResponse = response.chatResponse().aiMessage();
                // Check if the response is valid before adding to history
                if (aiResponse.text() != null && !aiResponse.text().isBlank()) {
                    // Construct SessionResult for 'Ask'
                    var sessionResult = new SessionResult("Ask: " + question,
                                                          List.copyOf(chrome.getLlmRawMessages()),
                                                          Map.of(), // No undo contents for Ask
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
            // Check if we have any partial output to save
            maybeAddInterruptedResult("Ask", question);
        }
    }

    private void maybeAddInterruptedResult(String action, String input) {
        if (chrome.getLlmRawMessages().stream().anyMatch(m -> m instanceof AiMessage)) {
            logger.debug(action + " command cancelled with partial results");
            var sessionResult = new SessionResult("%s (Cancelled): %s".formatted(action, input),
                                                  new TaskFragment(List.copyOf(chrome.getLlmRawMessages()), input),
                                                  Map.of(),
                                                  new SessionResult.StopDetails(SessionResult.StopReason.INTERRUPTED));
            chrome.getContextManager().addToHistory(sessionResult, false);
        }
    }

    /**
     * Executes the core logic for the "Agent" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     *
     * @param goal    The initial user instruction passed to the agent.
     * @param options The configured options for the agent's tools.
     */
    private void executeAgentCommand(StreamingChatLanguageModel model, String goal, ArchitectAgent.ArchitectOptions options) {
        var contextManager = chrome.getContextManager();
        try {
            // Pass options to the constructor
            var agent = new ArchitectAgent(contextManager, model, contextManager.getToolRegistry(), goal, options);
            agent.execute();
            chrome.systemOutput("Architect complete!");
        } catch (InterruptedException e) {
            chrome.systemOutput("Architect Agent cancelled!");
            maybeAddInterruptedResult("Architect", goal);
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
            var agent = new SearchAgent(query, contextManager, model, contextManager.getToolRegistry(), 0);
            var result = agent.execute();
            assert result != null;
            // Search does not stream to llmOutput, so add the final answer here
            chrome.llmOutput("\n# Answer\n%s".formatted(((ContextFragment.SearchFragment) result.output()).explanation()), ChatMessageType.AI);
            contextManager.addToHistory(result, false);
        } catch (InterruptedException e) {
            chrome.toolErrorRaw("Search agent cancelled without answering");
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
            chrome.showOutputSpinner("Executing command...");
            result = Environment.instance.captureShellCommand(input, contextManager.getRoot());
        } catch (InterruptedException e) {
            chrome.systemOutput("Cancelled!");
            return;
        } finally {
            chrome.hideOutputSpinner();
        }
        String output = result.output().isBlank() ? "[operation completed with no output]" : result.output();
        var wrappedOutput = "```bash\n" + output + "\n```";
        chrome.llmOutput(wrappedOutput, ChatMessageType.CUSTOM);

        // Add to context history with the output text
        var action = "Run: " + input;
        contextManager.pushContext(ctx -> {
            var parsed = new TaskFragment(List.copyOf(chrome.getLlmRawMessages()), action);
            return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture(action));
        });
    }

    // --- Action Handlers ---

    public void runArchitectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolErrorRaw("Please provide an initial goal or instruction for the Architect");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getModels();
        var architectModel = contextManager.getArchitectModel();
        var editModel = contextManager.getEditModel();
        var searchModel = contextManager.getSearchModel();

        if (contextHasImages()) {
            var nonVisionModels = Stream.of(architectModel, editModel, searchModel)
                    .filter(m -> !models.supportsVision(m))
                    .map(models::nameOf)
                    .toList();
            if (!nonVisionModels.isEmpty()) {
                showVisionSupportErrorDialog(String.join(", ", nonVisionModels));
                return; // Abort if any required model lacks vision and context has images
            }
        }

        // Disable buttons immediately to provide feedback
        chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        // Submit the action. The dialog and core logic run in the background.
        submitAction("Architect", goal, () -> {
            ArchitectAgent.ArchitectOptions options = null; // Initialize to null
            try {
                // Show options dialog within the background task using the new static method
                options = ArchitectOptionsDialog.showDialog(chrome, contextManager);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Re-interrupt the thread
                logger.debug("Architect options dialog interrupted", e);
                // options remains null, handled below
            }

            if (options == null) {
                // User cancelled or dialog was interrupted
                chrome.systemOutput("Architect command cancelled during option selection.");
                return;
            }
            // Proceed with execution using the selected options
            executeAgentCommand(architectModel, goal, options);
        });
    }

    // Methods for running commands. These prepare the input and model, then delegate
    // the core logic execution to contextManager.submitAction, which calls back
    // into the private execute* methods above.

    public void runCodeCommand() {
        var input = getInstructions();
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
        submitAction("Code", input, () -> {
            executeCodeCommand(codeModel, input);
        });
    }

    public void runAskCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please enter a question");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getModels();
        var askModel = contextManager.getAskModel(); // Use dedicated Ask model

        // --- Vision Check ---
        if (contextHasImages() && !models.supportsVision(askModel)) {
            showVisionSupportErrorDialog(models.nameOf(askModel) + " (Ask)");
            return; // Abort if model doesn't support vision and context has images
        }
        // --- End Vision Check ---

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        submitAction("Ask", input, () -> {
            executeAskCommand(askModel, input);
        });
    }

    public void runSearchCommand() {
        var input = getInstructions();
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
        submitAction("Search", input, () -> {
            executeSearchCommand(searchModel, input);
        });
    }

    public void runRunCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command to run");
            return;
        }
        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        disableButtons();
        // Submit the action, calling the private execute method inside the lambda
        submitAction("Run", input, () -> executeRunCommand(input));
    }

    /**
     * sets the llm output to indicate the action has started, and submits the task on the user pool
     */
    public Future<?> submitAction(String action, String input, Runnable task) {
        var cm = chrome.getContextManager();
        // need to set the correct parser here since we're going to append to the same fragment during the action
        action = (action + " MODE").toUpperCase();
        chrome.setLlmOutput(new ContextFragment.TaskFragment(cm.getParserForWorkspace(), List.of(new UserMessage(action, input)), input));
        return cm.submitUserTask(action, true, () -> {
            try {
                task.run();
            } finally {
                checkBalanceAndNotify();
            }
        });
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
            chrome.disableHistoryPanel();
        });
    }

    @Override
    public void contextChanged(Context newCtx) {
        // Check if the update was triggered by one of the table's own actions
        if (suppressExternalSuggestionsTrigger.getAndSet(false)) {
            logger.debug("Suppressing context suggestion due to internal table action.");
            return;
        }

        // Otherwise, proceed with the normal suggestion logic by submitting a task
        logger.debug("Context changed externally, triggering suggestion check.");
        triggerContextSuggestion(null); // Use null ActionEvent to indicate non-timer trigger
    }

    public void enableButtons() {
        SwingUtilities.invokeLater(() -> {
            // Buttons are enabled if a project is loaded; specific model availability is checked in action handlers.
            boolean projectLoaded = chrome.getProject() != null;
            agentButton.setEnabled(projectLoaded);
            codeButton.setEnabled(projectLoaded);
            askButton.setEnabled(projectLoaded);
            searchButton.setEnabled(projectLoaded);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            configureModelsButton.setEnabled(projectLoaded);
            chrome.enableHistoryPanel();
        });
    }

    /**
     * Hides the command input overlay, enables the input field and deep scan button,
     * clears the placeholder text if present, and requests focus for the input field.
     */
    private void activateCommandInput() {
        overlayPanel.setVisible(false); // Hide the overlay
        setCommandInputAndDeepScanEnabled(true); // Enable input and deep scan button
        // Clear placeholder only if it's still present
        if (commandInputField.getText().equals(PLACEHOLDER_TEXT)) {
            clearCommandInput();
        }
        commandInputField.requestFocusInWindow(); // Give it focus
    }

    /**
     * Sets the enabled state for both the command input field and the Deep Scan button.
     *
     * @param enabled true to enable, false to disable.
     */
    void setCommandInputAndDeepScanEnabled(boolean enabled) {
        commandInputField.setEnabled(enabled);
        this.deepScanButton.setEnabled(enabled);
    }

    /**
     * Returns cosine similarity of two equal-length vectors.
     */
    private static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors differ in length");
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vectors must have at least one element");
        }

        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;

        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            magA += x * x;
            magB += y * y;
        }

        double denominator = Math.sqrt(magA) * Math.sqrt(magB);
        if (denominator == 0.0) {
            throw new IllegalArgumentException("One of the vectors is zero-length");
        }

        return (float) (dot / denominator);
    }
}
