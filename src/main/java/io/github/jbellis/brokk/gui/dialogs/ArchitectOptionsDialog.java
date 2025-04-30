package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A modal dialog to configure the tools available to the Architect agent.
 */
public class ArchitectOptionsDialog {
    // Remember last selection for the current session
    private static ArchitectAgent.ArchitectOptions lastArchitectOptions = ArchitectAgent.ArchitectOptions.DEFAULTS;

    /**
     * Shows a modal dialog to configure Architect tools and returns the chosen options, or null if cancelled.
     * Remembers the last selection for the current session.
     *
     * @param chrome         The main application window reference for positioning and theme.
     * @param contextManager The context manager to check project capabilities (e.g., CPG).
     * @throws InterruptedException if the *calling* thread is interrupted while waiting for the dialog.
     */
    public static ArchitectAgent.ArchitectOptions showDialog(Chrome chrome, ContextManager contextManager) throws InterruptedException
    {
        // Use last options as default for this session
        var currentOptions = lastArchitectOptions;
        // Use AtomicReference to pass result from EDT back to calling thread
        var result = new AtomicReference<ArchitectAgent.ArchitectOptions>();

        // Initial checks must happen *before* switching to EDT
        var isCpg = contextManager.getProject().getAnalyzerWrapper().isCpg();

        SwingUtil.runOnEDT(() -> {
            JDialog dialog = new JDialog(chrome.getFrame(), "Architect Tools", true); // Modal dialog, requires EDT
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            // --- Main Panel for Checkboxes ---
            JPanel mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JLabel explanationLabel = new JLabel("Select the sub-agents and tools that the Architect agent will have access to:");
            explanationLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            mainPanel.add(explanationLabel);

            // Helper to create checkbox with description
            java.util.function.BiFunction<String, String, JCheckBox> createCheckbox = (text, description) -> {
                JCheckBox cb = new JCheckBox("<html>" + text + "<br><i><font size='-2'>" + description + "</font></i></html>");
                cb.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0)); // Spacing below checkbox
                mainPanel.add(cb);
                return cb;
            };

            // Create checkboxes for each option
            var codeCb = createCheckbox.apply("Code Agent", "Allow invoking the Code Agent to modify files");
            codeCb.setSelected(currentOptions.includeCodeAgent());

            var contextCb = createCheckbox.apply("Context Agent", "Suggest relevant workspace additions based on the goal");
            contextCb.setSelected(currentOptions.includeContextAgent());

            var validationCb = createCheckbox.apply("Validation Agent", "Suggest relevant test files for Code Agent");
            validationCb.setSelected(currentOptions.includeValidationAgent());

            var analyzerCb = createCheckbox.apply("Code Analyzer Tools", "Allow direct querying of code structure (e.g., find usages, call graphs)");
            analyzerCb.setSelected(currentOptions.includeAnalyzerTools());
            analyzerCb.setEnabled(isCpg); // Disable if not a CPG
            if (!isCpg) {
                analyzerCb.setToolTipText("Code Analyzer tools require a Code Property Graph (CPG) build.");
            }

            var workspaceCb = createCheckbox.apply("Workspace Management Tools", "Allow adding/removing files, URLs, or text to/from the workspace");
            workspaceCb.setSelected(currentOptions.includeWorkspaceTools());

            var searchCb = createCheckbox.apply("Search Agent", "Allow invoking the Search Agent to find information beyond the current workspace");
            searchCb.setSelected(currentOptions.includeSearchAgent());

            dialog.add(new JScrollPane(mainPanel), BorderLayout.CENTER); // Add scroll pane for potentially many options

            // --- Button Panel ---
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton okButton = new JButton("OK");
            JButton cancelButton = new JButton("Cancel");
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            // --- Actions ---
            okButton.addActionListener(e -> {
                var selectedOptions = new ArchitectAgent.ArchitectOptions(
                        contextCb.isSelected(),
                        validationCb.isSelected(),
                        isCpg && analyzerCb.isSelected(), // Force false if not CPG
                        workspaceCb.isSelected(),
                        codeCb.isSelected(),
                        searchCb.isSelected()
                );
                lastArchitectOptions = selectedOptions; // Remember for next time this session
                result.set(selectedOptions);
                dialog.dispose();
            });

            cancelButton.addActionListener(e -> {
                result.set(null); // Indicate cancellation
                dialog.dispose();
            });

            // Apply theme using the public helper method in Chrome
            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true); // This blocks until the dialog is closed
        });

        // Wait for EDT task to complete (dialog closed)
        // This can throw InterruptedException if the calling thread is interrupted
        while (result.get() == null && !Thread.currentThread().isInterrupted()) {
            try {
                // Check frequently to respond to interrupts
                Thread.sleep(50);
                // Check if the dialog might have set the result in the meantime
                if (result.get() != null) break;
                // Check again if the dialog was closed without setting a result (e.g. window close button)
                // We rely on SwingUtil.runOnEDT completing eventually. If the EDT task itself hangs, this won't help.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Re-interrupt
                throw e; // Propagate immediately
            }
        }
        if (Thread.currentThread().isInterrupted()) {
             throw new InterruptedException("Architect options dialog interrupted while waiting.");
        }

        return result.get(); // Return selected options or null from the AtomicReference
    }
}
