package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;

public class Brokk {
    private static Chrome io;               // Initially null
    private static ContextManager contextManager;  // Initially null
    private static Coder coder;                    // Initially null

    /**
     * Main entry point: Start up Brokk with no project loaded,
     * then check if there's a "most recent" project to open.
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Create an empty UI with no project
            io = new Chrome(null);

            // Attempt to load the most recent project if any
            var recents = Project.loadRecentProjects();
            if (!recents.isEmpty()) {
                // find the project with the largest lastOpened time
                var mostRecent = recents.entrySet().stream()
                        .max(Comparator.comparingLong(Map.Entry::getValue))
                        .get()
                        .getKey();
                var path = Path.of(mostRecent);
                if (GitRepo.hasGitRepo(path)) {
                    openProject(path);
                }
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, discarding any previously loaded project.
     * The folder must contain a .git subdirectory or else we will show an error.
     */
    public static void openProject(Path projectPath) {
        if (!GitRepo.hasGitRepo(projectPath)) {
            if (io != null) {
                io.toolErrorRaw("Not a valid git project: " + projectPath);
            }
            return;
        }

        // Save to recent projects
        Project.updateRecentProject(projectPath);

        // Dispose of the old Chrome if it exists
        if (io != null) {
            io.close();
        }

        // If there's an existing contextManager, shut it down
        if (contextManager != null) {
            contextManager.shutdown();
        }

        // Create new Project, ContextManager, Coder
        contextManager = new ContextManager(projectPath);
        Models models;
        String modelsError = null;
        try {
            models = Models.load();
        } catch (Throwable th) {
            modelsError = th.getMessage();
            models = Models.disabled();
        }
        
        // Create a new Chrome instance with the fresh ContextManager
        io = new Chrome(contextManager);

        // Create the Coder with the new IO
        coder = new Coder(models, io, projectPath, contextManager);
        
        // Resolve circular references
        contextManager.resolveCircularReferences(io, coder);
        io.toolOutput("Opened project at " + projectPath);

        // Show welcome message
        showWelcomeMessage(contextManager);

        if (!coder.isLlmAvailable()) {
            io.toolError("\nError loading models: " + modelsError);
            io.toolError("AI will not be available this session");
        }
    }

    /**
     * Show the welcome message in the LLM output area.
     */
    private static void showWelcomeMessage(ContextManager cm) {
        assert io != null;
        
        try (var welcomeStream = Brokk.class.getResourceAsStream("/WELCOME.md")) {
            if (welcomeStream != null) {
                io.shellOutput(new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var models = cm.getCoder().models;
        Properties props = new Properties();
        try {
            props.load(Brokk.class.getResourceAsStream("/version.properties"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var version = props.getProperty("version");
        io.shellOutput("\n## Environment:");
        io.shellOutput("Brokk %s".formatted(version));
        io.shellOutput("Editor model: " + models.editModelName());
        io.shellOutput("Apply model: " + models.applyModelName());
        io.shellOutput("Quick model: " + models.quickModelName());
        var trackedFiles = Brokk.contextManager.getProject().getRepo().getTrackedFiles();
        io.shellOutput("Git repo at %s with %d files".formatted(cm.getProject().getRoot(), trackedFiles.size()));
    }
}
