package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A panel for displaying file diffs in a syntax-highlighted editor.
 */
public class DiffPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(DiffPanel.class);
    
    private final RSyntaxTextArea diffArea;
    private final ContextManager contextManager;
    private String currentDiff;
    private String commitId;
    private ProjectFile projectFile;
    
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
     * @param commitId The commit ID to show the diff for (e.g. "HEAD", "abc123", etc.)
     * @param file The file to show the diff for
     */
    public void showFileDiff(String commitId, ProjectFile file) {
        this.commitId = commitId;
        this.projectFile = file;

        // Set syntax style based on file extension
        setSyntaxStyleForFile(file.getFileName());

        contextManager.submitBackgroundTask("Loading diff for " + file.getFileName(), () -> {
            try {
                var repo = (GitRepo) contextManager.getProject().getRepo();

                // Compare commitId vs its parent if possible
                var parentRef = commitId + "^";
                var diff = repo.showFileDiff(commitId, parentRef, file);

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
        if (commitId == null || projectFile == null) {
            return "Git diff";
        }

        var shortCommitId = (commitId.length() > 7) ? commitId.substring(0, 7) : commitId;
        return "git " + shortCommitId + ": " + projectFile.getFileName();
    }

    /**
     * Compare the file's content from the specified commit (or its parent if useParent=true)
     * against the actual on-disk (local) contents, then display an in-memory diff.
     *
     * @param commitId The commit to compare from
     * @param file The file to compare
     * @param useParent If true, we attempt to compare commitId^; if commitId has no parent, old content is empty
     */
    public void showCompareWithLocal(String commitId, ProjectFile file, boolean useParent)
    {
        this.commitId = commitId;
        this.projectFile = file;
        setSyntaxStyleForFile(file.getFileName());

        contextManager.submitBackgroundTask("Loading compare-with-local for " + file.getFileName(), () -> {
            try {
                // 1) read the local file from disk
                var localContent = file.exists() ? file.read() : "";

                // 2) figure out the "base commit" to read from
                String baseCommitId = commitId;
                if (useParent) {
                    // Attempt commit^, and if that doesn't exist (first commit), oldContent stays ""
                    var parentId = contextManager.getProject().getRepo().resolve(commitId + "^");
                    if (parentId == null) {
                        baseCommitId = null; // so we get blank oldContent
                    } else {
                        baseCommitId = commitId + "^";
                    }
                }

                // 3) read old content from that commit
                var oldContent = "";
                if (baseCommitId != null) {
                    // call our new GitRepo method
                    var repo = (GitRepo) contextManager.getProject().getRepo();
                    oldContent = repo.getFileContent(baseCommitId, file);
                }

                // 4) generate a unified diff
                var diff = generateInMemoryDiff(oldContent, localContent, file.getFileName());
                currentDiff = diff;

                SwingUtilities.invokeLater(() -> {
                    diffArea.setText(diff);
                    diffArea.setCaretPosition(0);
                });
            } catch (Exception ex) {
                logger.error("Error generating compare-with-local diff", ex);
                SwingUtilities.invokeLater(() -> {
                    diffArea.setText("Error generating diff:\n" + ex.getMessage());
                });
            }
            return null;
        });
    }

    /**
     * Produces a unified diff (similar to 'git diff') between oldContent and newContent.
     * This implementation writes header lines for the file paths and then formats the diff.
     *
     * @param oldContent the content from the old (commit) version of the file
     * @param newContent the content from the local (on-disk) version of the file
     * @param fileName the file name to display in the header lines
     * @return the unified diff as a String
     */
    private String generateInMemoryDiff(String oldContent, String newContent, String fileName) {
        try (var out = new ByteArrayOutputStream();
             var diffFormatter = new org.eclipse.jgit.diff.DiffFormatter(out)) {

            // Use the default comparator and configure repository for blob lookups.
            diffFormatter.setDiffComparator(org.eclipse.jgit.diff.RawTextComparator.DEFAULT);
            var repo = (GitRepo) contextManager.getProject().getRepo();
            diffFormatter.setRepository(repo.getGit().getRepository());

            // Create RawText instances from the old and new contents.
            var rawOld = new org.eclipse.jgit.diff.RawText(oldContent.getBytes(StandardCharsets.UTF_8));
            var rawNew = new org.eclipse.jgit.diff.RawText(newContent.getBytes(StandardCharsets.UTF_8));

            // Generate the list of edits using the Myers diff algorithm.
            var diffAlgorithm = org.eclipse.jgit.diff.DiffAlgorithm.getAlgorithm(
                    org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm.MYERS);
            org.eclipse.jgit.diff.EditList edits = diffAlgorithm.diff(
                    org.eclipse.jgit.diff.RawTextComparator.DEFAULT, rawOld, rawNew);

            // Write the header lines for the unified diff.
            out.write(("--- a/" + fileName + "\n").getBytes(StandardCharsets.UTF_8));
            out.write(("+++ b/" + fileName + "\n").getBytes(StandardCharsets.UTF_8));

            // Format the diff using the available format() method (with no DiffDriver).
            diffFormatter.format(edits, rawOld, rawNew);

            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Error creating in-memory diff", e);
            return "<<error generating diff: " + e.getMessage() + ">>";
        }
    }
}
