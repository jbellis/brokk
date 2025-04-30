package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.Project.DataRetentionPolicy;
import io.github.jbellis.brokk.gui.Chrome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import io.github.jbellis.brokk.gui.components.BrowserLabel;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SettingsDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(SettingsDialog.class);

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private final JPanel projectPanel; // Keep a reference to enable/disable
    // Brokk Key field (Global)
    private JTextField brokkKeyField;
    // Global proxy selection
    private JRadioButton brokkProxyRadio;
    private JRadioButton localhostProxyRadio;
    // Project fields
    private JComboBox<Project.CpgRefresh> cpgRefreshComboBox; // ComboBox for CPG refresh
    private JTextField buildCleanCommandField;
    private JTextField allTestsCommandField;
    private JTextArea buildInstructionsArea;
    private DataRetentionPanel dataRetentionPanel; // Reference to the new panel
    // Model selection combo boxes (initialized in createModelsPanel)
    private JComboBox<String> architectModelComboBox;
    private JComboBox<String> codeModelComboBox;
    private JComboBox<Project.ReasoningLevel> codeReasoningComboBox;
    private JComboBox<String> askModelComboBox; // Added Ask model combo box
    private JComboBox<Project.ReasoningLevel> askReasoningComboBox;
    private JComboBox<String> editModelComboBox;
    private JComboBox<Project.ReasoningLevel> editReasoningComboBox;
    private JComboBox<String> searchModelComboBox;
    private JComboBox<Project.ReasoningLevel> searchReasoningComboBox;
    private JComboBox<Project.ReasoningLevel> architectReasoningComboBox;


    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings", true); // Modal dialog
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(600, 400); // Increased width
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT); // Tabs on the left

        // Global Settings Panel
        var globalPanel = createGlobalPanel();
        tabbedPane.addTab("Global", null, globalPanel, "Global application settings");

        // Project Settings Panel
        projectPanel = createProjectPanel();
        tabbedPane.addTab("Project", null, projectPanel, "Settings specific to the current project");

        // Enable/disable project tab based on whether a project is open
        projectPanel.setEnabled(chrome.getProject() != null);
        for (Component comp : projectPanel.getComponents()) {
            comp.setEnabled(projectPanel.isEnabled());
        }
        tabbedPane.setEnabledAt(1, chrome.getProject() != null); // Index 1 is the Project tab

        // Buttons Panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");
        var applyButton = new JButton("Apply"); // Added Apply button

        okButton.addActionListener(e -> {
            applySettings();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        applyButton.addActionListener(e -> applySettings());

        // Add Escape key binding to close the dialog (like Cancel)
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        // Layout
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createGlobalPanel() {
        var panel = new JPanel(new GridBagLayout()); // Use GridBagLayout for better alignment
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST; // Align components to the left
        int row = 0;

        // Brokk Key Input
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Brokk Key:"), gbc);

        brokkKeyField = new JTextField(20); // Initialize the field
        brokkKeyField.setText(Project.getBrokkKey());
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL; // Make field expand horizontally
        panel.add(brokkKeyField, gbc);

        // Sign-up/login link using BrowserLabel
        gbc.gridx = 1;
        gbc.gridy = row++;
        var signupUrl = "https://brokk.ai";
        var loginLabel = new BrowserLabel(signupUrl, "Sign up or get your key at " + signupUrl);
        // Make it look like the old italic label
        loginLabel.setFont(loginLabel.getFont().deriveFont(Font.ITALIC));
        panel.add(loginLabel, gbc);

        // Reset fill after Brokk Key
        gbc.fill = GridBagConstraints.NONE;

        // --- LLM Proxy Setting ---
        row++;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel("LLM Proxy:"), gbc);

        // Brokk vs localhost selection
        brokkProxyRadio = new JRadioButton("Brokk");
        // Removed italic on proxy radio label to keep standard font
        localhostProxyRadio = new JRadioButton("Localhost");
        // Removed italic on proxy radio label to keep standard font
        var proxyGroup = new ButtonGroup();
        proxyGroup.add(brokkProxyRadio);
        proxyGroup.add(localhostProxyRadio);

        // Brokk radio on this row
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(brokkProxyRadio, gbc);

        // Localhost radio on next line
        gbc.gridy = row++;
        panel.add(localhostProxyRadio, gbc);

        // Informational label under localhost
        gbc.insets = new Insets(0, 25, 2, 2);
        gbc.gridy = row++;
        var proxyInfoLabel = new JLabel("Brokk will look for a litellm proxy on localhost:4000");
        proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
        panel.add(proxyInfoLabel, gbc);

        // Add restart requirement note
        gbc.gridy = row++;
        var restartLabel = new JLabel("Restart required after changing proxy settings");
        restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
        panel.add(restartLabel, gbc);

        gbc.insets = new Insets(2, 2, 2, 2);

        // Load initial proxy setting via enum
        if (Project.getLlmProxySetting() == Project.LlmProxySetting.BROKK) {
            brokkProxyRadio.setSelected(true);
        } else {
            localhostProxyRadio.setSelected(true);
        }
        // -- Apply LLM Proxy Setting --
        if (brokkProxyRadio.isSelected()) {
            Project.setLlmProxySetting(Project.LlmProxySetting.BROKK);
        } else {
            Project.setLlmProxySetting(Project.LlmProxySetting.LOCALHOST);
        }

        // Reset fill for the next label
        gbc.fill = GridBagConstraints.NONE;


        // --- Theme Selection ---
        row++; // Move to next row for theme
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Theme:"), gbc);

        var lightRadio = new JRadioButton("Light");
        var darkRadio = new JRadioButton("Dark");
        var themeGroup = new ButtonGroup();
        themeGroup.add(lightRadio);
        themeGroup.add(darkRadio);

        if (Project.getTheme().equals("dark")) { // Use Project.getTheme()
            darkRadio.setSelected(true);
        } else {
            lightRadio.setSelected(true);
        }

        lightRadio.putClientProperty("theme", false);
        darkRadio.putClientProperty("theme", true);

        // Panel to hold radio buttons together
        var themeRadioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); // No gaps
        themeRadioPanel.add(lightRadio);
        themeRadioPanel.add(darkRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0; // Let the radio button panel take remaining space if needed
        // No fill needed here as FlowLayout handles sizing
        panel.add(themeRadioPanel, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    private JPanel createProjectPanel() {
        var project = chrome.getProject();
        var outerPanel = new JPanel(new BorderLayout());
        if (project == null) {
            outerPanel.add(new JLabel("No project is open."), BorderLayout.CENTER);
            return outerPanel;
        }

        // Create a sub-tabbed pane for Project settings: Build and Other
        var subTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // ----- Build Tab -----
        var buildPanel = new JPanel(new GridBagLayout());
        buildPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Initialize Build fields for new BuildDetails
        buildCleanCommandField = new JTextField(); // used for Build/Lint Command
        allTestsCommandField = new JTextField(); // used for Test All Command
        // Set the text area for build instructions to be 10 lines tall
        buildInstructionsArea = new JTextArea(10, 20);
        buildInstructionsArea.setWrapStyleWord(true);
        buildInstructionsArea.setLineWrap(true);
        var instructionsScrollPane = new JScrollPane(buildInstructionsArea);
        instructionsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        instructionsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        var details = project.getBuildDetails();
        logger.debug("Initial Build Details: {}", details);
        // Build/Lint Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Build/Lint Command:"), gbc);
        buildCleanCommandField.setText(details != null ? details.buildLintCommand() : ""); // Existing line
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(buildCleanCommandField, gbc);

        // Test All Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test All Command:"), gbc);
        allTestsCommandField.setText(details != null ? details.testAllCommand() : ""); // Existing line
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(allTestsCommandField, gbc);

        // Build Instructions
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        buildPanel.add(new JLabel("Build Instructions:"), gbc);
        buildInstructionsArea.setText(details != null ? details.instructions() : ""); // Set initial text
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(instructionsScrollPane, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = ++row; // Move to next row
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        buildPanel.add(Box.createVerticalGlue(), gbc);

        // ----- Other Tab -----
        var otherPanel = new JPanel(new GridBagLayout());
        otherPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row = 0;

        // Code Intelligence Refresh
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        otherPanel.add(new JLabel("Code Intelligence Refresh:"), gbc);
        cpgRefreshComboBox = new JComboBox<>(new Project.CpgRefresh[]{Project.CpgRefresh.AUTO, Project.CpgRefresh.ON_RESTART});
        var currentRefresh = project.getCpgRefresh();
        cpgRefreshComboBox.setSelectedItem(currentRefresh == Project.CpgRefresh.UNSET ? Project.CpgRefresh.AUTO : currentRefresh);
        gbc.gridx = 1;
        gbc.gridy = row++; // Increment row after adding combo box
        gbc.weightx = 1.0;
        otherPanel.add(cpgRefreshComboBox, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = row; // Use the incremented row
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        otherPanel.add(Box.createVerticalGlue(), gbc);


        // Add General tab first, then Build tab
        subTabbedPane.addTab("General", otherPanel); // Renamed from "Other"
        subTabbedPane.addTab("Build", buildPanel);

        // Add General tab first, then Build tab
        subTabbedPane.addTab("General", otherPanel); // Renamed from "Other"
        subTabbedPane.addTab("Build", buildPanel);

        // ----- Data Retention Tab -----
        dataRetentionPanel = new DataRetentionPanel(project); // Create the panel instance
        subTabbedPane.addTab("Data Retention", dataRetentionPanel); // Add the new tab

        // ----- Models Tab -----
        var modelsPanel = createModelsPanel(project);
        subTabbedPane.addTab("Models", modelsPanel);

        var outerPanelContainer = new JPanel(new BorderLayout());
        outerPanelContainer.add(subTabbedPane, BorderLayout.CENTER);
        return outerPanelContainer;
    }

    /**
     * Static inner class to encapsulate the Data Retention settings UI and logic.
     */
    public static class DataRetentionPanel extends JPanel {
        private final Project project;
        private final ButtonGroup policyGroup;
        private final JRadioButton improveRadio;
        private final JRadioButton minimalRadio;
        private final JLabel infoLabel;

        public DataRetentionPanel(Project project) {
            super(new GridBagLayout());
            this.project = project;
            this.policyGroup = new ButtonGroup();

            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            int y = 0;

            // --- Improve Brokk Radio Button ---
            improveRadio = new JRadioButton(DataRetentionPolicy.IMPROVE_BROKK.getDisplayName());
            improveRadio.putClientProperty("policy", DataRetentionPolicy.IMPROVE_BROKK);
            policyGroup.add(improveRadio);
            gbc.gridx = 0;
            gbc.gridy = y++;
            gbc.insets = new Insets(5, 5, 0, 5); // Reduced bottom margin
            add(improveRadio, gbc);

            // --- Improve Brokk Description Label ---
            var improveDescLabel = new JLabel("<html>Allow Brokk and/or its partners to use requests from this project " + "to train models and improve the Brokk service.</html>");
            improveDescLabel.setFont(improveDescLabel.getFont().deriveFont(Font.ITALIC, improveDescLabel.getFont().getSize() * 0.9f));
            gbc.gridx = 0;
            gbc.gridy = y++;
            gbc.insets = new Insets(0, 25, 10, 5); // Indent left, add bottom margin
            add(improveDescLabel, gbc);

            // --- Minimal Radio Button ---
            minimalRadio = new JRadioButton(DataRetentionPolicy.MINIMAL.getDisplayName());
            minimalRadio.putClientProperty("policy", DataRetentionPolicy.MINIMAL);
            policyGroup.add(minimalRadio);
            gbc.gridx = 0;
            gbc.gridy = y++;
            gbc.insets = new Insets(5, 5, 0, 5); // Reduced bottom margin
            add(minimalRadio, gbc);

            // --- Minimal Description Label ---
            var minimalDescLabel = new JLabel("<html>Brokk will not share data from this project with anyone and " + "will restrict its use to the minimum necessary to provide the Brokk service.</html>");
            minimalDescLabel.setFont(minimalDescLabel.getFont().deriveFont(Font.ITALIC, minimalDescLabel.getFont().getSize() * 0.9f));
            gbc.gridx = 0;
            gbc.gridy = y++;
            gbc.insets = new Insets(0, 25, 10, 5); // Indent left, add bottom margin
            add(minimalDescLabel, gbc);

            // --- Informational Text ---
            // Adjust top inset for spacing after the description labels
            gbc.insets = new Insets(15, 5, 5, 5); // Add spacing above info text
            infoLabel = new JLabel("<html>Data retention policy affects which AI models are allowed. In particular, Deepseek models are not available under the Essential Use Only policy, since Deepseek will train on API requests independently of Brokk.</html>");
            infoLabel.setFont(infoLabel.getFont().deriveFont(infoLabel.getFont().getSize() * 0.9f));
            gbc.gridx = 0;
            gbc.gridy = y++;
            add(infoLabel, gbc);

            // Add vertical glue to push components to the top
            gbc.gridx = 0;
            gbc.gridy = y; // Use the final incremented y value
            gbc.weighty = 1.0; // Take up remaining vertical space
            gbc.fill = GridBagConstraints.VERTICAL;
            add(Box.createVerticalGlue(), gbc);

            loadPolicy();
        }

        /**
         * Loads the current policy from the project and selects the corresponding radio button.
         */
        public void loadPolicy() {
            var currentPolicy = project.getDataRetentionPolicy();
            if (currentPolicy == DataRetentionPolicy.IMPROVE_BROKK) {
                improveRadio.setSelected(true);
            } else if (currentPolicy == DataRetentionPolicy.MINIMAL) {
                minimalRadio.setSelected(true);
            } else {
                policyGroup.clearSelection(); // No selection for UNSET
            }
        }

        /**
         * Returns the currently selected policy, or UNSET if none is selected.
         */
        public DataRetentionPolicy getSelectedPolicy() {
            if (improveRadio.isSelected()) {
                return DataRetentionPolicy.IMPROVE_BROKK;
            } else if (minimalRadio.isSelected()) {
                return DataRetentionPolicy.MINIMAL;
            } else {
                return DataRetentionPolicy.UNSET;
            }
        }

        /**
         * Saves the currently selected policy back to the project if it has changed.
         */
        public void applyPolicy() {
            var selectedPolicy = getSelectedPolicy();
            // Only save if a valid policy is selected and it's different from the current one
            if (selectedPolicy != DataRetentionPolicy.UNSET && selectedPolicy != project.getDataRetentionPolicy()) {
                project.setDataRetentionPolicy(selectedPolicy);
            }
        }
    }

    /**
     * Creates the panel containing the model selection dropdowns.
     */
    private JPanel createModelsPanel(Project project) {
        var modelsPanel = new JPanel(new GridBagLayout());
        modelsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        var models = chrome.getContextManager().getModels(); // Get Models instance
        var availableModels = models.getAvailableModels().keySet().stream().sorted().toList(); // Use new method
        var reasoningLevels = Project.ReasoningLevel.values();

        // Helper function to update reasoning combo box state
        Runnable updateReasoningState = () -> {
            updateReasoningComboBox(architectModelComboBox, architectReasoningComboBox, models);
            updateReasoningComboBox(codeModelComboBox, codeReasoningComboBox, models);
            updateReasoningComboBox(askModelComboBox, askReasoningComboBox, models);
            updateReasoningComboBox(editModelComboBox, editReasoningComboBox, models);
            updateReasoningComboBox(searchModelComboBox, searchReasoningComboBox, models);
        };

        // --- Architect Model ---
        gbc.gridx = 0; // Label column
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST; // Align label right
        modelsPanel.add(new JLabel("Architect Model:"), gbc);

        architectModelComboBox = new JComboBox<>(availableModels.toArray(new String[0]));
        architectModelComboBox.setSelectedItem(project.getArchitectModelName());
        architectModelComboBox.addActionListener(e -> updateReasoningState.run()); // Add listener

        gbc.gridx = 1; // Model combo column
        gbc.gridy = row;
        gbc.weightx = 0.0; // Don't stretch combo box horizontally
        gbc.fill = GridBagConstraints.NONE; // Use preferred size
        gbc.anchor = GridBagConstraints.WEST; // Align left
        modelsPanel.add(architectModelComboBox, gbc);

        gbc.gridx = 2; // Reasoning Label column
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Reasoning:"), gbc);

        architectReasoningComboBox = new JComboBox<>(reasoningLevels);
        architectReasoningComboBox.setSelectedItem(project.getArchitectReasoningLevel());
        gbc.gridx = 3; // Reasoning Combo column
        gbc.gridy = row;
        gbc.weightx = 0.0; // Don't stretch
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(architectReasoningComboBox, gbc);

        // Tooltip spans model and reasoning columns
        JLabel architectTooltip = new JLabel("Plans and executes multi-step projects, calling other agents as needed");
        architectTooltip.setFont(architectTooltip.getFont().deriveFont(Font.ITALIC, architectTooltip.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row + 1; // Next row
        gbc.gridwidth = 3;   // Span 3 columns (model combo, reasoning label, reasoning combo)
        gbc.weightx = 1.0;   // Let tooltip fill space horizontally
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(architectTooltip, gbc);
        row += 2; // Move down two rows (one for controls, one for tooltip)

        // --- Code Model ---
        gbc.gridwidth = 1; // Reset gridwidth
        gbc.gridx = 0; // Label column
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Code Model:"), gbc); // Label changed

        codeModelComboBox = new JComboBox<>(availableModels.toArray(new String[0]));
        codeModelComboBox.setSelectedItem(project.getCodeModelName());
        codeModelComboBox.addActionListener(e -> updateReasoningState.run());

        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(codeModelComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Reasoning:"), gbc);

        codeReasoningComboBox = new JComboBox<>(reasoningLevels);
        codeReasoningComboBox.setSelectedItem(project.getCodeReasoningLevel());
        gbc.gridx = 3;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(codeReasoningComboBox, gbc);

        JLabel codeTooltip = new JLabel("Used when invoking Code Agent manually");
        codeTooltip.setFont(codeTooltip.getFont().deriveFont(Font.ITALIC, codeTooltip.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row + 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(codeTooltip, gbc);
        row += 2;

        // --- Ask Model --- Added
        gbc.gridwidth = 1;
        gbc.gridx = 0; // Label column
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Ask Model:"), gbc);

        askModelComboBox = new JComboBox<>(availableModels.toArray(new String[0]));
        askModelComboBox.setSelectedItem(project.getAskModelName()); // Use getAskModelName
        askModelComboBox.addActionListener(e -> updateReasoningState.run());

        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(askModelComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Reasoning:"), gbc);

        askReasoningComboBox = new JComboBox<>(reasoningLevels);
        askReasoningComboBox.setSelectedItem(project.getAskReasoningLevel());
        gbc.gridx = 3;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(askReasoningComboBox, gbc);

        JLabel askTooltip = new JLabel("Used when invoking the Ask command");
        askTooltip.setFont(askTooltip.getFont().deriveFont(Font.ITALIC, askTooltip.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row + 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(askTooltip, gbc);
        row += 2;

        // --- Edit Model ---
        gbc.gridwidth = 1;
        gbc.gridx = 0; // Label column
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Edit Model:"), gbc);

        editModelComboBox = new JComboBox<>(availableModels.toArray(new String[0]));
        editModelComboBox.setSelectedItem(project.getEditModelName());
        editModelComboBox.addActionListener(e -> updateReasoningState.run());

        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(editModelComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Reasoning:"), gbc);

        editReasoningComboBox = new JComboBox<>(reasoningLevels);
        editReasoningComboBox.setSelectedItem(project.getEditReasoningLevel());
        gbc.gridx = 3;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(editReasoningComboBox, gbc);

        JLabel editTooltip = new JLabel("Used when invoking Code Agent from the Architect");
        editTooltip.setFont(editTooltip.getFont().deriveFont(Font.ITALIC, editTooltip.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row + 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(editTooltip, gbc);
        row += 2;

        // --- Search Model ---
        gbc.gridwidth = 1;
        gbc.gridx = 0; // Label column
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Search Model:"), gbc);

        searchModelComboBox = new JComboBox<>(availableModels.toArray(new String[0]));
        searchModelComboBox.setSelectedItem(project.getSearchModelName());
        searchModelComboBox.addActionListener(e -> updateReasoningState.run());

        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(searchModelComboBox, gbc);

        gbc.gridx = 2;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        modelsPanel.add(new JLabel("Reasoning:"), gbc);

        searchReasoningComboBox = new JComboBox<>(reasoningLevels);
        searchReasoningComboBox.setSelectedItem(project.getSearchReasoningLevel());
        gbc.gridx = 3;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(searchReasoningComboBox, gbc);

        JLabel searchTooltip = new JLabel("Searches the project for information described in natural language");
        searchTooltip.setFont(searchTooltip.getFont().deriveFont(Font.ITALIC, searchTooltip.getFont().getSize() * 0.9f));
        gbc.gridx = 1;
        gbc.gridy = row + 1;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        modelsPanel.add(searchTooltip, gbc);
        row += 2;

        // Initial state update for reasoning dropdowns
        SwingUtilities.invokeLater(updateReasoningState);

        // Add vertical glue
        gbc.gridx = 0;
        gbc.gridy = row; // Use final row value
        gbc.gridwidth = 4; // Span all columns
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        modelsPanel.add(Box.createVerticalGlue(), gbc);

        return modelsPanel;
    }

    private void applySettings() {
        // Apply Global Settings
        var globalPanel = (JPanel) tabbedPane.getComponentAt(0); // Assuming Global is the first tab

        // -- Apply Brokk Key --
        String currentBrokkKey = Project.getBrokkKey();
        String newBrokkKey = brokkKeyField.getText().trim(); // Read from the new field
        if (!newBrokkKey.equals(currentBrokkKey)) {
            if (!newBrokkKey.isEmpty()) {
                try {
                    io.github.jbellis.brokk.Models.KeyParts kp = io.github.jbellis.brokk.Models.parseKey(newBrokkKey);
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this,
                                                  "Invalid Brokk Key: " + ex.getMessage(),
                                                  "Invalid Key",
                                                  JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            Project.setBrokkKey(newBrokkKey);
            logger.debug("Applied Brokk Key: {}", newBrokkKey.isEmpty() ? "<empty>" : "****");
        }

        // -- Apply LLM Proxy Setting --
        Project.LlmProxySetting proxySetting = brokkProxyRadio.isSelected()
                                                ? Project.LlmProxySetting.BROKK
                                                : Project.LlmProxySetting.LOCALHOST;
        Project.setLlmProxySetting(proxySetting);
        logger.debug("Applied LLM Proxy Setting: {}", proxySetting);


        // -- Apply Theme --
        // Find the themeRadioPanel (it's the component holding theme radio buttons)
        Component themeComponent = null;
        // Locate the panel holding the theme radio buttons based on content, not fixed grid coordinates
        for (Component comp : globalPanel.getComponents()) {
            if (comp instanceof JPanel panel) {
                // Check if the panel contains the light/dark radio buttons we created
                boolean hasLight = false;
                boolean hasDark = false;
                for(Component child : panel.getComponents()) {
                    if (child instanceof JRadioButton radio) {
                        if ("Light".equals(radio.getText())) hasLight = true;
                        if ("Dark".equals(radio.getText())) hasDark = true;
                    }
                }
                if (hasLight && hasDark) {
                    themeComponent = comp;
                    break;
                }
            }
        }

        if (themeComponent instanceof JPanel themeRadioPanel) {
            for (Component radioComp : themeRadioPanel.getComponents()) {
                if (radioComp instanceof JRadioButton radio && radio.isSelected()) {
                    boolean useDark = (Boolean) radio.getClientProperty("theme");
                    // Only switch theme if it actually changed
                    boolean newIsDark = (Boolean) radio.getClientProperty("theme");
                    String newTheme = newIsDark ? "dark" : "light";
                    // Only switch theme if it actually changed
                    if (!newTheme.equals(Project.getTheme())) {
                        chrome.switchTheme(newIsDark); // switchTheme calls Project.setTheme internally
                        logger.debug("Applied Theme: {}", newTheme);
                    }
                    break;
                }
            }
        } else {
            // Log error or handle case where theme panel wasn't found as expected
            logger.error("Could not find theme radio button panel in SettingsDialog.");
        }

        // Apply Project Settings (if project is open and tab is enabled)
        var project = chrome.getProject();
        if (project != null && tabbedPane.isEnabledAt(1)) {
            // Get current details to compare against and preserve non-editable fields
            var currentDetails = project.getBuildDetails();
            if (currentDetails == null) {
                currentDetails = BuildAgent.BuildDetails.EMPTY; // Use empty if somehow null
            }

            // Read potentially edited values from Build tab
            var newBuildLint = buildCleanCommandField.getText();
            var newTestAll = allTestsCommandField.getText();
            var newInstructions = buildInstructionsArea.getText();

            // Create a new BuildDetails record with updated fields
            var newDetails = new BuildAgent.BuildDetails(currentDetails.buildfiles().isEmpty() ? java.util.List.of() : currentDetails.buildfiles(), currentDetails.dependencies(), newBuildLint, newTestAll, newInstructions);

            logger.debug("Applying Build Details: {}", newDetails);

            // Only save if details have actually changed
            if (!newDetails.equals(currentDetails)) {
                project.saveBuildDetails(newDetails);
            }

            // Apply CPG Refresh Setting
            var selectedRefresh = (Project.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
            if (selectedRefresh != project.getCpgRefresh()) {
                project.setCpgRefresh(selectedRefresh);
            }

            // Apply Data Retention Policy
            if (dataRetentionPanel != null) { // Check if the panel was created
                var oldPolicy = project.getDataRetentionPolicy();
                dataRetentionPanel.applyPolicy();
                var newPolicy = project.getDataRetentionPolicy();
                // Refresh models if the policy changed, as it might affect availability
                    if (oldPolicy != newPolicy && newPolicy != Project.DataRetentionPolicy.UNSET) {
                        // Submit as background task so it doesn't block the settings dialog closing
                        chrome.getContextManager().submitBackgroundTask("Refreshing models due to policy change", () -> {
                            chrome.getContextManager().getModels().reinit(project);
                        });
                    }
                }

            // Apply Model Selections and Reasoning Levels
            applyModelAndReasoning(project, architectModelComboBox, architectReasoningComboBox,
                                   project::getArchitectModelName, project::setArchitectModelName,
                                   project::getArchitectReasoningLevel, project::setArchitectReasoningLevel);

            applyModelAndReasoning(project, codeModelComboBox, codeReasoningComboBox,
                                   project::getCodeModelName, project::setCodeModelName,
                                   project::getCodeReasoningLevel, project::setCodeReasoningLevel);

            applyModelAndReasoning(project, askModelComboBox, askReasoningComboBox,
                                   project::getAskModelName, project::setAskModelName,
                                   project::getAskReasoningLevel, project::setAskReasoningLevel);

            applyModelAndReasoning(project, editModelComboBox, editReasoningComboBox,
                                   project::getEditModelName, project::setEditModelName,
                                   project::getEditReasoningLevel, project::setEditReasoningLevel);

            applyModelAndReasoning(project, searchModelComboBox, searchReasoningComboBox,
                                   project::getSearchModelName, project::setSearchModelName,
                                   project::getSearchReasoningLevel, project::setSearchReasoningLevel);
        }
    }


    /**
     * Helper method to apply model name and reasoning level settings
     */
    private void applyModelAndReasoning(Project project,
                                        JComboBox<String> modelCombo,
                                        JComboBox<Project.ReasoningLevel> reasoningCombo,
                                        java.util.function.Supplier<String> currentModelGetter,
                                        java.util.function.Consumer<String> modelSetter,
                                        java.util.function.Supplier<Project.ReasoningLevel> currentReasoningGetter,
                                        java.util.function.Consumer<Project.ReasoningLevel> reasoningSetter)
    {
        if (modelCombo != null) { // Check if combo box was initialized
            String selectedModel = (String) modelCombo.getSelectedItem();
            if (selectedModel != null && !selectedModel.equals(currentModelGetter.get())) {
                modelSetter.accept(selectedModel);
            }
        }
        if (reasoningCombo != null) { // Check if combo box was initialized
            Project.ReasoningLevel selectedReasoning = (Project.ReasoningLevel) reasoningCombo.getSelectedItem();
            // Only save if the combo box is enabled (i.e., model supports reasoning)
            // and the selected value is different from the current setting.
            if (selectedReasoning != null && reasoningCombo.isEnabled() && selectedReasoning != currentReasoningGetter.get()) {
                reasoningSetter.accept(selectedReasoning);
            }
            // If the combo is disabled, we don't save anything, implicitly leaving it as DEFAULT.
        }
    }

    /**
     * Updates the enabled state and selection of a reasoning combo box based on the selected model.
     */
    private void updateReasoningComboBox(JComboBox<String> modelComboBox, JComboBox<Project.ReasoningLevel> reasoningComboBox, io.github.jbellis.brokk.Models models) {
        if (modelComboBox == null || reasoningComboBox == null) return; // Not initialized yet

        String selectedModelName = (String) modelComboBox.getSelectedItem();
        boolean supportsReasoning = selectedModelName != null && models.supportsReasoning(selectedModelName);

        reasoningComboBox.setEnabled(supportsReasoning);
        reasoningComboBox.setToolTipText(supportsReasoning ? "Select reasoning effort" : "Reasoning effort not supported by this model");

        if (!supportsReasoning) {
            // Set underlying item to DEFAULT, but render as "Off" when disabled
            reasoningComboBox.setSelectedItem(Project.ReasoningLevel.DEFAULT);
            reasoningComboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    // For the selected item display in a disabled box (index == -1), show "Off"
                    // Otherwise, show normal enum values for the dropdown list itself.
                    if (index == -1 && !reasoningComboBox.isEnabled()) {
                        label.setText("Off");
                        label.setForeground(UIManager.getColor("ComboBox.disabledForeground")); // Use standard disabled color
                    } else if (value instanceof Project.ReasoningLevel level) {
                        label.setText(level.toString()); // Use standard enum toString for dropdown items
                    } else {
                        label.setText(value == null ? "" : value.toString()); // Handle null or unexpected types
                    }
                    return label;
                }
            });
        } else {
            // Restore default renderer when enabled - needed if switching from non-supported to supported model
            reasoningComboBox.setRenderer(new DefaultListCellRenderer());
            // The actual selection is handled by the initial load or retained from previous state
        }
    }

    /**
     * Displays a standalone, modal dialog forcing the user to choose a data retention policy.
     * This should be called when a project is opened and has no policy set.
     *
     * @param project The project requiring a policy.
     * @param owner   The parent frame for the dialog.
     */
    public static void showStandaloneDataRetentionDialog(Project project, Frame owner) {
        var dialog = new JDialog(owner, "Data Retention Policy Required", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing without selection initially
        dialog.setSize(450, 350);
        dialog.setLocationRelativeTo(owner);

        var retentionPanel = new DataRetentionPanel(project);

        var contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        contentPanel.add(retentionPanel, BorderLayout.CENTER);

        // Add note about changing settings later
        var noteLabel = new JLabel("<html>(You can change this setting later under Project -> Data Retention in the main Settings dialog.)</html>");
        // Use deriveFont with PLAIN style to ensure it's not italic
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.PLAIN, noteLabel.getFont().getSize() * 0.9f));
        contentPanel.add(noteLabel, BorderLayout.NORTH);

        var okButton = new JButton("OK");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.add(okButton);
        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Shared logic for handling the OK button click or window close attempt
        Runnable confirmAndClose = () -> {
            var selectedPolicy = retentionPanel.getSelectedPolicy();
            if (selectedPolicy == DataRetentionPolicy.UNSET) {
                // If no policy is selected, show a warning and *do not* close the dialog.
                JOptionPane.showMessageDialog(dialog, "Please select a data retention policy to continue.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            } else {
                // Only apply and close if a valid policy is selected.
                retentionPanel.applyPolicy();
                dialog.dispose();
            }
        };

        okButton.addActionListener(e -> confirmAndClose.run());

        // Handle the window close ('X') button
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndClose.run(); // Run the same logic as the OK button
            }
        });

        dialog.setContentPane(contentPanel);
        // Request focus on the OK button to avoid initial focus on a radio button
        okButton.requestFocusInWindow();
        dialog.setVisible(true); // Show the modal dialog and block until closed
    }

    /**
     * Opens the Settings dialog, optionally pre‑selecting an inner tab (e.g. “Models”)
     * inside the Project settings.  If the requested tab is not found—or no project
     * is open—the dialog falls back to its default view.
     */
    public static void showSettingsDialog(Chrome chrome, String targetTabName)
    {
        var dialog = new SettingsDialog(chrome.getFrame(), chrome);

        // Only attempt inner‑tab selection when (a) a project is present and
        // (b) the caller named a tab.
        if (targetTabName != null && chrome.getProject() != null) {
            // 1. Select the outer “Project” tab so its inner JTabbedPane is visible.
            dialog.tabbedPane.setSelectedComponent(dialog.projectPanel);

            // 2. Locate the inner JTabbedPane (added to the CENTER of projectPanel’s BorderLayout).
            for (var comp : dialog.projectPanel.getComponents()) {
                if (comp instanceof JTabbedPane subTabbedPane) {
                    // 3. Find and select the requested inner tab.
                    for (int i = 0; i < subTabbedPane.getTabCount(); i++) {
                        if (targetTabName.equals(subTabbedPane.getTitleAt(i))) {
                            subTabbedPane.setSelectedIndex(i);
                            break;
                        }
                    }
                    break; // we found the inner tabbed‑pane; nothing more to search
                }
            }
        }

        dialog.setVisible(true);   // show the modal dialog
    }

    /**
     * Displays a modal dialog requiring the user to enter a valid Brokk Key
     * if one is not already configured. This blocks until a valid key is provided.
     * This should be called early in the application startup process.
     */
    public static void showSignupDialog() {
        if (!Project.getBrokkKey().isEmpty()) {
            logger.debug("Brokk key already configured, skipping signup dialog.");
            return; // Key already exists, nothing to do
        }

        // hardcode dark theme
        com.formdev.flatlaf.FlatDarkLaf.setup();
        logger.debug("No Brokk key found, showing signup/login dialog.");

        // Create a modal dialog using the Chrome utility to ensure the icon is set
        var dialog = Chrome.newDialog(null, "Brokk Setup", true); // Parentless, modal
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Prevent closing via 'X' initially
        // dialog.setSize(400, 200); // Size is now handled by pack() later
        dialog.setLocationRelativeTo(null); // Center on screen

        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // --- Info Label ---
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        var infoLabel = new JLabel("<html>Please enter your Brokk Key to continue.<br>" +
                                   "You can sign up for free at:</html>");
        panel.add(infoLabel, gbc);

        // --- Signup URL Link ---
        gbc.gridy++;
        var signupUrl = "https://brokk.ai";
        var loginLabel = new BrowserLabel(signupUrl, signupUrl); // Use new BrowserLabel
        panel.add(loginLabel, gbc);

        // --- Key Label ---
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Brokk Key:"), gbc);

        // --- Key Text Field ---
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        var keyField = new JTextField(25);
        panel.add(keyField, gbc);

        // --- Button Panel ---
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        
        var okButton = new JButton("OK");
        var exitButton = new JButton("Exit");
        
        buttonPanel.add(okButton);
        buttonPanel.add(exitButton);
        
        panel.add(buttonPanel, gbc);

        // --- Action Listeners for Buttons ---
        okButton.addActionListener(e -> {
            String newBrokkKey = keyField.getText().trim();
            if (newBrokkKey.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please enter a Brokk Key.", "Key Required", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                // Validate the key structure
                io.github.jbellis.brokk.Models.parseKey(newBrokkKey);
                // If valid, save it and close the dialog
                Project.setBrokkKey(newBrokkKey);
                logger.debug("Brokk Key successfully configured.");
                dialog.dispose(); // Close the dialog
            } catch (IllegalArgumentException ex) {
                logger.warn("Invalid Brokk Key entered: {}", ex.getMessage());
                JOptionPane.showMessageDialog(dialog,
                                              "Invalid Brokk Key: " + ex.getMessage() + "\nPlease check your key and try again.",
                                              "Invalid Key",
                                              JOptionPane.ERROR_MESSAGE);
                keyField.requestFocusInWindow(); // Focus the field again
                keyField.selectAll();
            }
        });
        
        // Exit button action - exit the application
        exitButton.addActionListener(e -> {
            logger.debug("User chose to exit from signup dialog");
            System.exit(0);
        });

        // --- Window Closing Behavior ---
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Check if a valid key has been set *before* allowing close
                if (Project.getBrokkKey().isEmpty()) {
                    JOptionPane.showMessageDialog(dialog,
                                                  "You must enter a valid Brokk Key to use Brokk.",
                                                  "Key Required",
                                                  JOptionPane.WARNING_MESSAGE);
                    // Optionally, could offer an "Exit" button here, but for now, force key entry.
                } else {
                    // This case should ideally not be reached if OK button logic is correct,
                    // but allows closing if a key *was* somehow set without disposing.
                    dialog.dispose();
                }
            }
        });

        // Add Escape key binding to trigger OK button
        dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
              .put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancel");
        dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                 // Treat Escape like clicking OK for validation purposes
                 okButton.doClick();
            }
        });
        // Set default button
        dialog.getRootPane().setDefaultButton(okButton);

        dialog.setContentPane(panel);
        dialog.pack(); // Adjust size to fit content
        dialog.setResizable(false);
        dialog.setVisible(true); // Show the modal dialog and block until disposed
    }
}
