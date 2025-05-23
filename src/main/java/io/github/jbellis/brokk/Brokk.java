package io.github.jbellis.brokk;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import io.github.jbellis.brokk.gui.CheckThreadViolationRepaintManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.StartupDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import javax.swing.border.Border;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);

    private static JWindow splashScreen = null;
    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final Set<Path> reOpeningProjects = ConcurrentHashMap.newKeySet();
    public static final CompletableFuture<AbstractModel> embeddingModelFuture;

    public static final String ICON_RESOURCE = "/brokk-icon.png";

    static {
        embeddingModelFuture = CompletableFuture.supplyAsync(() -> {
            logger.info("Loading embedding model asynchronously...");
            var modelName = "sentence-transformers/all-MiniLM-L6-v2";
            File localModelPath;
            try {
                var cacheDir = System.getProperty("user.home") + "/.cache/brokk";
                if (!Files.exists(Path.of(cacheDir)) && !new File(cacheDir).mkdirs()) {
                    throw new IOException("Unable to create model-cache directory at " + cacheDir);
                }
                localModelPath = SafeTensorSupport.maybeDownloadModel(cacheDir, modelName);
            } catch (IOException e) {
                // InstructionsPanel will catch ExecutionException
                throw new UncheckedIOException(e);
            }
            // Assuming loadEmbeddingModel returns BertEmbeddingModel or a compatible type
            var model = ModelSupport.loadEmbeddingModel(localModelPath, DType.F32, DType.I8);
            logger.info("Embedding model loading complete.");
            // Cast to the specific type expected by the Future if necessary
            return model;
        });
    }

    /**
     * Main entry point: Start up Brokk with no project loaded,
     * then check if there's a "most recent" project to open.
     */
    public static void main(String[] args) {
        logger.debug("Brokk starting");

        // Set macOS system properties (non-UI, can be immediate)
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        // Set up thread violation checker for dev mode
        if (Boolean.getBoolean("brokk.devmode")) {
            RepaintManager.setCurrentManager(new CheckThreadViolationRepaintManager());
        }

        // Set application icon
        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            if (Taskbar.isTaskbarSupported()) {
                var taskbar = Taskbar.getTaskbar();
                try {
                    taskbar.setIconImage(icon.getImage());
                } catch (UnsupportedOperationException | SecurityException e) {
                    logger.warn("Unable to set taskbar icon: {}", e.getMessage());
                }
            }
        }

        // Ensure L&F is set on EDT before any UI is created, then show splash screen.
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    com.formdev.flatlaf.FlatLightLaf.setup();
                } catch (Exception e) {
                    logger.warn("Failed to set LAF, using default", e);
                }
                showSplashScreen();
            });
        } catch (Exception e) { // Catches InterruptedException and InvocationTargetException
            logger.fatal("Failed to initialize Look and Feel or SplashScreen on EDT. Exiting.", e);
            System.exit(1);
        }

        // Argument parsing and initial validation (off-EDT / main thread)
        boolean noProjectFlag = false;
        boolean noKeyFlag = false;
        String projectPathArg = null;

        for (String arg : args) {
            if (arg.equals("--no-project")) {
                noProjectFlag = true;
            } else if (arg.equals("--no-key")) {
                noKeyFlag = true;
            } else if (!arg.startsWith("--")) {
                projectPathArg = arg;
            }
        }

        Path determinedProjectPath = null;
        if (projectPathArg != null) {
            determinedProjectPath = Path.of(projectPathArg).toAbsolutePath().normalize();
        } else if (!noProjectFlag) {
            var openProjects = Project.getOpenProjects(); // I/O
            if (!openProjects.isEmpty()) {
                determinedProjectPath = openProjects.getFirst();
            }
        }

        String currentBrokkKey;
        boolean keyIsValid = false;
        if (!noKeyFlag) {
            currentBrokkKey = Project.getBrokkKey(); // I/O (prefs)
            if (!currentBrokkKey.isEmpty()) {
                try {
                    Service.validateKey(currentBrokkKey); // I/O (network)
                    keyIsValid = true;
                } catch (IOException e) {
                    logger.warn("Network error validating existing Brokk key at startup. Assuming valid for now.", e);
                    keyIsValid = true; // Assume valid to allow offline use / project access
                    // Show error on EDT
                    SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null,
                                                      "Network error validating Brokk key. AI services may be unavailable.",
                                                      "Network Validation Warning",
                                                      JOptionPane.WARNING_MESSAGE));
                } catch (IllegalArgumentException e) {
                    logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                    // keyIsValid remains false
                }
            }
        }

        boolean projectPathIsValid = false;
        if (determinedProjectPath != null) {
            try {
                Path realProjectPath = determinedProjectPath.toRealPath(); // I/O
                if (Files.isDirectory(realProjectPath)) { // I/O
                    projectPathIsValid = true;
                    determinedProjectPath = realProjectPath;
                } else {
                    logger.warn("Project path is not a directory: {}", realProjectPath);
                }
            } catch (IOException e) { // Includes NoSuchFileException
                logger.warn("Project path not valid or not accessible: {}. Error: {}", determinedProjectPath, e.getMessage());
            }
        }

        if (projectPathArg != null && !projectPathIsValid) {
            SwingUtil.runOnEdt(Brokk::hideSplashScreen);
            System.err.printf("Specified project path `%s` does not appear to be a valid directory%n", projectPathArg);
            System.exit(1);
        }

        // Main application loop: select project, attempt to open, repeat if failed.
        Path projectToOpen = determinedProjectPath; // Initially from args or recent
        boolean currentKeyIsValid = keyIsValid;    // Initially from startup check

        while (true) {
            // If no project is selected, or key is invalid, or selected project is not a valid directory, show StartupDialog
            if (!currentKeyIsValid || !isValidDirectory(projectToOpen)) {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Hide splash before showing StartupDialog
                final Path initialDialogPath = projectToOpen; // Capture for lambda
                final boolean initialDialogKeyValid = currentKeyIsValid; // Capture for lambda

                projectToOpen = SwingUtil.runOnEdt(() -> {
                    StartupDialog.DialogMode mode;
                    boolean needsProject = !isValidDirectory(initialDialogPath);
                    boolean needsKey = !initialDialogKeyValid;

                    if (needsProject && needsKey) {
                        mode = StartupDialog.DialogMode.REQUIRE_BOTH;
                    } else if (needsKey) {
                        mode = StartupDialog.DialogMode.REQUIRE_KEY_ONLY;
                    } else { // needsProject is true
                        mode = StartupDialog.DialogMode.REQUIRE_PROJECT_ONLY;
                    }
                    return StartupDialog.showDialog(null, Project.getBrokkKey(), initialDialogKeyValid, initialDialogPath, mode);
                }, null);

                if (projectToOpen == null) { // User quit the dialog
                    logger.info("Startup dialog was closed or exited. Shutting down.");
                    SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Hide splash before exiting
                    System.exit(0);
                    return; // Unreachable
                }
                // If StartupDialog was shown and returned a project, key is assumed to be (re)validated or handled by it.
                currentKeyIsValid = true;
            }

            CompletableFuture<Boolean> openFuture = openProject(projectToOpen);
            boolean success = false;
            try {
                success = openFuture.get(); // Block for the result of opening
            } catch (Exception e) { // Catches InterruptedException and ExecutionException
                logger.error("Exception waiting for project {} to open: {}", projectToOpen, e);
                // success remains false, error logged by openProject or initializeProjectAndContextManager
            }

            if (success) {
                // Project opened successfully, splash screen hidden by openProject. Break the loop.
                // The AWT event dispatch thread will keep the application alive if windows are open.
                break;
            } else {
                // Project failed to open. Error messages/dialogs handled by openProject/initializeProjectAndContextManager.
                // Splash screen should have been hidden by openProject's failure paths.
                // Nullify projectToOpen to force StartupDialog again for a new selection.
                SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Ensure hidden if openProject failed to hide it
                projectToOpen = null;
                // Assume key might be an issue, or user might want to change it.
                // StartupDialog will re-verify/prompt for key if currentKeyIsValid is false.
                currentKeyIsValid = false;
            }
        }
    }

    private static void showSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.dispose(); // Should not happen if logic is correct
        }
        splashScreen = new JWindow();
        Chrome.applyIcon(splashScreen); // Sets window icon for taskbar if applicable

        var panel = new JPanel(new BorderLayout(0, 10)); // Vertical gap
        Border lineBorder = BorderFactory.createLineBorder(Color.GRAY);
        Border emptyBorder = BorderFactory.createEmptyBorder(30, 50, 30, 50); // Increased padding
        panel.setBorder(BorderFactory.createCompoundBorder(lineBorder, emptyBorder));

        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            // Scale icon to a reasonable size for splash, e.g., 64x64
            Image scaledImage = icon.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            JLabel iconLabel = new JLabel(new ImageIcon(scaledImage), SwingConstants.CENTER);
            panel.add(iconLabel, BorderLayout.NORTH);
        }

        var label = new JLabel("Connecting to Brokk...", SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f)); // Larger font
        panel.add(label, BorderLayout.CENTER);

        splashScreen.add(panel);
        splashScreen.pack();
        splashScreen.setLocationRelativeTo(null); // Center on screen
        splashScreen.setVisible(true);
        splashScreen.toFront(); // Ensure it's on top
    }

    private static void hideSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.setVisible(false);
            splashScreen.dispose();
            splashScreen = null;
        }
    }

    private static boolean isValidDirectory(Path path) {
        if (path == null) return false;
        try {
            return Files.isDirectory(path.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static void createAndShowGui(Path projectPath, ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread();
        assert contextManager != null : "ContextManager cannot be null when creating GUI";

        var io = contextManager.createGui();
        var project = contextManager.getProject();

        // Log the current data retention policy.
        // This is called after any necessary dialog has been shown and policy confirmed.
        io.systemOutput("Data Retention Policy set to: " + project.getDataRetentionPolicy());

        openProjectWindows.put(projectPath, io);

        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // Use projectPath directly as it's effectively final from the enclosing scope.
                Chrome ourChrome = openProjectWindows.remove(projectPath);
                if (ourChrome != null) {
                    ourChrome.close(); // Instance method on Chrome to release its resources
                }
                logger.debug("Removed project from open windows map: {}", projectPath);

                if (reOpeningProjects.contains(projectPath)) {
                    // This project is being reopened. Remove from persistent open list;
                    // it will be re-added by updateRecentProject if the reopen succeeds.
                    CompletableFuture.runAsync(() -> Project.removeFromOpenProjects(projectPath))
                            .exceptionally(ex -> {
                                logger.error("Error removing project (before reopen) from open projects list: {}", projectPath, ex);
                                return null;
                            });

                    // Reopen logic (same as before)
                    openProject(projectPath).whenCompleteAsync((success, ex) -> {
                        reOpeningProjects.remove(projectPath); // Always remove after attempt
                        if (ex != null) {
                            logger.error("Exception occurred while trying to reopen project: {}", projectPath, ex);
                        } else if (success == null || !success) {
                            logger.warn("Failed to reopen project: {}. It will not be reopened.", projectPath);
                        }
                        // Check for exit condition after processing reopen attempt
                        if (openProjectWindows.isEmpty() && reOpeningProjects.isEmpty()) {
                            logger.info("All projects closed after reopen attempt of {}. Exiting.", projectPath);
                            System.exit(0);
                        }
                    }, SwingUtilities::invokeLater);
                    return;
                }

                // The project is actually being closed
                boolean appIsExiting = openProjectWindows.isEmpty() && reOpeningProjects.isEmpty();
                if (appIsExiting) {
                    // We are about to exit the application.
                    // Do NOT remove this project from the persistent "open projects" list.
                    logger.info("Last project window ({}) closed. App exiting. It remains MRU.", projectPath);
                    System.exit(0);
                } else {
                    // Other projects are still open or other projects are pending reopening.
                    // This one is just closing, so remove it from the persistent "open projects" list.
                    CompletableFuture.runAsync(() -> Project.removeFromOpenProjects(projectPath))
                            .exceptionally(ex -> {
                                logger.error("Error removing project from open projects list: {}", projectPath, ex);
                                return null;
                            });
                    // No System.exit(0) here, as other windows/tasks are active.
                }
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     *
     * @param path The path to the project.
     * @return A CompletableFuture that completes with true if the project was opened successfully, false otherwise.
     */
    /**
     * Checks if a project window is already open for the given path.
     * @param path The project path.
     * @return true if the project window is open, false otherwise.
     */
    public static boolean isProjectOpen(Path path) {
        return openProjectWindows.containsKey(path.toAbsolutePath().normalize());
    }

    /**
     * Brings the existing window for the given project path to the front and focuses it.
     * Does nothing if the project is not currently open.
     * @param path The project path.
     */
    public static void focusProjectWindow(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Chrome existingWindow = openProjectWindows.get(normalizedPath);
        if (existingWindow != null) {
            SwingUtilities.invokeLater(() -> {
                JFrame frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
        }
    }

    public static CompletableFuture<Boolean> openProject(Path path) {
        final Path projectPath = path.toAbsolutePath().normalize();
        logger.debug("Attempting to open project at " + projectPath);

        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            logger.debug("Project already open: {}. Bringing window to front.", projectPath);
            SwingUtilities.invokeLater(() -> {
                var frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> openCompletionFuture = new CompletableFuture<>();

        // Stage 1: Initialize Project and ContextManager (off-EDT)
        CompletableFuture.supplyAsync(() -> initializeProjectAndContextManager(projectPath), ForkJoinPool.commonPool())
            .thenAcceptAsync(contextManagerOpt -> { // Stage 2: Handle policy dialog and GUI creation (on-EDT)
                if (contextManagerOpt.isEmpty()) {
                    hideSplashScreen(); // Initialization failed
                    openCompletionFuture.complete(false);
                    return;
                }

                ContextManager contextManager = contextManagerOpt.get();
                Project project = contextManager.getProject();
                Path actualProjectPath = project.getRoot(); // Use path from Project instance

                // Check and show data retention dialog if needed
                if (project.getDataRetentionPolicy() == Project.DataRetentionPolicy.UNSET) {
                    logger.debug("Project {} has no Data Retention Policy set. Showing dialog.", actualProjectPath.getFileName());
                    boolean policySetAndConfirmed = SettingsDialog.showStandaloneDataRetentionDialog(project, null); // Parent frame is null

                    if (!policySetAndConfirmed) {
                        logger.info("Data retention dialog cancelled for project {}. Aborting open.", actualProjectPath.getFileName());
                        hideSplashScreen();
                        openCompletionFuture.complete(false);
                        return;
                    }
                    // Policy set and OK'd by dialog; project object is updated.
                    logger.info("Data Retention Policy set to: {} for project {}", project.getDataRetentionPolicy(), actualProjectPath.getFileName());
                }

                // If policy was already set, or was set and OK'd by the dialog
                hideSplashScreen(); // Hide splash just before showing the main GUI
                createAndShowGui(actualProjectPath, contextManager);
                openCompletionFuture.complete(true);

            }, SwingUtilities::invokeLater) // Execute Stage 2 on EDT
            .exceptionally(ex -> { // Handles exceptions from Stage 1 or Stage 2
                logger.fatal("Fatal error during project opening process for: {}", projectPath, ex);
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = """
                                      A critical error occurred while trying to open the project:
                                      %s

                                      Please check the logs at ~/.brokk/debug.log and consider filing a bug report.
                                      """.formatted(cause.getMessage()).stripIndent();
                SwingUtil.runOnEdt(() -> {
                    hideSplashScreen(); // Hide splash before showing error dialog
                    JOptionPane.showMessageDialog(null,
                                                  errorMessage,
                                                  "Project Open Error", JOptionPane.ERROR_MESSAGE);
                });
                openCompletionFuture.complete(false); // Signify failure
                return null; // Required for exceptionally's Function<Throwable, ? extends T>
            });

        return openCompletionFuture;
    }

    /**
     * Handles the core logic of initializing a project, including Git setup and ContextManager creation.
     * This method performs I/O and may show dialogs (which are correctly dispatched to EDT).
     *
     * @param projectPath The path to the project.
     * @return An Optional containing the ContextManager if successful, or Optional.empty() if initialization fails.
     */
    private static java.util.Optional<ContextManager> initializeProjectAndContextManager(Path projectPath) {
        try {
            Project.updateRecentProject(projectPath);
            Project project = new Project(projectPath);

            if (!project.hasGit()) {
                int response = SwingUtil.runOnEdt(() -> JOptionPane.showConfirmDialog(
                        null,
                        """
                        This project is not under Git version control. Would you like to initialize a new Git repository here?\

                        Without Git, the project will be read-only, and some features may be limited.""",
                        "Initialize Git Repository?",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE), JOptionPane.NO_OPTION);

                if (response == JOptionPane.YES_OPTION) {
                    try {
                        logger.info("Initializing Git repository at {}...", project.getRoot());
                        io.github.jbellis.brokk.git.GitRepo.initRepo(project.getRoot());
                        project = new Project(project.getRoot()); // Re-create project to reflect new .git dir
                        logger.info("Git repository initialized successfully at {}.", project.getRoot());
                    } catch (Exception e) {
                        logger.error("Failed to initialize Git repository at {}: {}", project.getRoot(), e.getMessage(), e);
                        final String errorMsg = e.getMessage();
                        SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                                null,
                                "Failed to initialize Git repository: " + errorMsg +
                                "\nThe project will be opened as read-only.",
                                "Git Initialization Error",
                                JOptionPane.ERROR_MESSAGE));
                        // Continue as read-only with the non-Git project instance
                    }
                } else {
                    logger.info("User declined Git initialization for project {}. Proceeding as read-only.", project.getRoot());
                    // Continue as read-only
                }
            }
            return java.util.Optional.of(new ContextManager(project));
        } catch (Exception e) {
            logger.error("Failed to initialize project and ContextManager for path {}: {}", projectPath, e.getMessage(), e);
            final String errorMsg = e.getMessage();
            SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                    null,
                    "Could not open project " + projectPath.getFileName() + ":\n" + errorMsg +
                    "\nPlease check the logs for more details.",
                    "Project Initialization Failed",
                    JOptionPane.ERROR_MESSAGE));
            return java.util.Optional.empty();
        }
    }

    /**
     * Closes the project if it's already open, then opens it again fresh.
     * Useful for reloading after external changes or config updates.
     */
    public static void reOpenProject(Path path)
    {
        assert SwingUtilities.isEventDispatchThread();

        final Path projectPath = path.toAbsolutePath().normalize();
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            // Mark as re-opening, the windowClosed listener will do the rest
            reOpeningProjects.add(projectPath);
            // Programatically close the window
            var frame = openProjectWindows.get(projectPath).getFrame();
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
    }
}
