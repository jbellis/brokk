package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;

/**
 * A panel for displaying file diffs in a syntax-highlighted editor.
 */
public class DiffPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(DiffPanel.class);
    
    private final RSyntaxTextArea diffArea;
    private final ContextManager contextManager;
    private String currentDiff;
    private String commitId;
    private RepoFile repoFile;
    
    /**
     * Creates a new DiffPanel for displaying file diffs.
     */
    public DiffPanel(ContextManager contextManager) {
        super(new BorderLayout());
        this.contextManager = contextManager;

        // Create the RSyntaxTextArea with appropriate styling
        diffArea = new RSyntaxTextArea();
        diffArea.setEditable(false);
        diffArea.setCodeFoldingEnabled(true);
        diffArea.setAntiAliasingEnabled(true);
        diffArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        diffArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        // Create a scroll pane for the text area
        RTextScrollPane scrollPane = new RTextScrollPane(diffArea);
        scrollPane.setFoldIndicatorEnabled(true);

        add(scrollPane, BorderLayout.CENTER);
        
        // Add some controls at the bottom
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton addToContextButton = new JButton("Add to Context");
        addToContextButton.addActionListener(e -> {
            if (currentDiff != null && !currentDiff.isEmpty()) {
                String description = buildDescription();
                contextManager.addVirtualFragment(
                    new io.github.jbellis.brokk.ContextFragment.StringFragment(currentDiff, description)
                );
            }
        });
        
        controlPanel.add(addToContextButton);
        add(controlPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Displays a diff for a specific file at a commit.
     * 
     * @param commitId The commit ID to show the diff for
     * @param file The file to show the diff for
     */
    public void showFileDiff(String commitId, RepoFile file) {
        this.commitId = commitId;
        this.repoFile = file;
        
        // Set syntax style based on file extension
        setSyntaxStyleForFile(file.getFileName());
        
        contextManager.submitBackgroundTask("Loading diff for " + file.getFileName(), () -> {
            try {
                GitRepo repo = contextManager.getProject().getRepo();
                
                String diff;
                if (commitId.equals("UNCOMMITTED")) {
                    // Special case for uncommitted changes
                    diff = repo.diffFiles(java.util.List.of(file));
                } else {
                    // Get diff against parent commit
                    String parentRef = commitId + "^";
                    diff = repo.showFileDiff(commitId, parentRef, file);
                }
                
                // Store the diff for later use
                currentDiff = diff;
                
                SwingUtilities.invokeLater(() -> {
                    diffArea.setText(diff);
                    diffArea.setCaretPosition(0);
                });
            } catch (Exception e) {
                logger.error("Error loading diff", e);
                SwingUtilities.invokeLater(() -> {
                    diffArea.setText("Error loading diff: " + e.getMessage());
                });
            }
            return null;
        });
    }
    
    /**
     * Shows the diff panel in a frame.
     *
     * @param parent The parent component for the frame
     * @param title The frame title
     */
    public void showInDialog(Component parent, String title) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(this);

        // Get saved bounds from Project if available
        java.awt.Rectangle bounds = contextManager.getProject().getDiffWindowBounds();
        frame.setSize(bounds.width, bounds.height);

        // Only set location if coordinates were provided
        if (bounds.x >= 0 && bounds.y >= 0) {
            frame.setLocation(bounds.x, bounds.y);
        } else {
            frame.setLocationRelativeTo(parent);
        }

        // Save window position and size when closing
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Save window bounds manually since frame is not a JDialog
                contextManager.getProject().saveWindowBounds("diff", frame);
            }
        });

        frame.setVisible(true);
    }
    
    /**
     * Sets the syntax style based on the file extension.
     */
    private void setSyntaxStyleForFile(String fileName) {
        String style = SyntaxConstants.SYNTAX_STYLE_NONE;
        
        if (fileName == null) {
            diffArea.setSyntaxEditingStyle(style);
            return;
        }
        
        fileName = fileName.toLowerCase();
        
        if (fileName.endsWith(".java")) {
            style = SyntaxConstants.SYNTAX_STYLE_JAVA;
        } else if (fileName.endsWith(".js")) {
            style = SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
        } else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            style = SyntaxConstants.SYNTAX_STYLE_HTML;
        } else if (fileName.endsWith(".xml")) {
            style = SyntaxConstants.SYNTAX_STYLE_XML;
        } else if (fileName.endsWith(".css")) {
            style = SyntaxConstants.SYNTAX_STYLE_CSS;
        } else if (fileName.endsWith(".py")) {
            style = SyntaxConstants.SYNTAX_STYLE_PYTHON;
        } else if (fileName.endsWith(".rb")) {
            style = SyntaxConstants.SYNTAX_STYLE_RUBY;
        } else if (fileName.endsWith(".php")) {
            style = SyntaxConstants.SYNTAX_STYLE_PHP;
        } else if (fileName.endsWith(".sh")) {
            style = SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
        } else if (fileName.endsWith(".bat") || fileName.endsWith(".cmd")) {
            style = SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH;
        } else if (fileName.endsWith(".sql")) {
            style = SyntaxConstants.SYNTAX_STYLE_SQL;
        } else if (fileName.endsWith(".properties") || fileName.endsWith(".conf")) {
            style = SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
        } else if (fileName.endsWith(".json")) {
            style = SyntaxConstants.SYNTAX_STYLE_JSON;
        } else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            style = SyntaxConstants.SYNTAX_STYLE_YAML;
        } else if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            style = SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        } else if (fileName.endsWith(".c") || fileName.endsWith(".h")) {
            style = SyntaxConstants.SYNTAX_STYLE_C;
        } else if (fileName.endsWith(".cpp") || fileName.endsWith(".hpp") || 
                   fileName.endsWith(".cc") || fileName.endsWith(".hh")) {
            style = SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
        } else if (fileName.endsWith(".cs")) {
            style = SyntaxConstants.SYNTAX_STYLE_CSHARP;
        } else if (fileName.endsWith(".go")) {
            style = SyntaxConstants.SYNTAX_STYLE_GO;
        } else if (fileName.endsWith(".kt")) {
            style = SyntaxConstants.SYNTAX_STYLE_KOTLIN;
        } else if (fileName.endsWith(".groovy")) {
            style = SyntaxConstants.SYNTAX_STYLE_GROOVY;
        } else if (fileName.endsWith(".scala")) {
            style = SyntaxConstants.SYNTAX_STYLE_SCALA;
        } else if (fileName.endsWith(".dart")) {
            style = SyntaxConstants.SYNTAX_STYLE_DART;
        }
        
        diffArea.setSyntaxEditingStyle(style);
    }
    
    /**
     * Builds a description for the current diff to use when adding to context.
     */
    private String buildDescription() {
        if (commitId == null || repoFile == null) {
            return "Git diff";
        }
        
        String shortCommitId = commitId.length() > 7 ? commitId.substring(0, 7) : commitId;
        if (commitId.equals("UNCOMMITTED")) {
            return "Uncommitted changes: " + repoFile.getFileName();
        } else {
            return "git " + shortCommitId + ": " + repoFile.getFileName();
        }
    }
}
