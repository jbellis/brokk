package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionDialog;
import io.github.jbellis.brokk.util.DecompileHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);
    
    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final Path EMPTY_PROJECT = Path.of(":EMPTY:");

    public static final String ICON_RESOURCE = "/brokk-icon.png";

    /**
     * Main entry point: Start up Brokk with no project loaded,
     * then check if there's a "most recent" project to open.
     */
    public static void main(String[] args) {
        logger.debug("Brokk starting");

        // Set macOS to use system menu bar
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        // 1) Load your icon
        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);

            // 2) Attempt to set icon in macOS Dock & Windows taskbar (Java 9+)
            if (Taskbar.isTaskbarSupported()) {
                var taskbar = Taskbar.getTaskbar();
                try {
                    taskbar.setIconImage(icon.getImage());
                } catch (UnsupportedOperationException | SecurityException e) {
                    System.err.println("Unable to set taskbar icon: " + e.getMessage());
                }
            }
        }

        // 3) On macOS, optionally set the app name for the Dock:
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        SwingUtilities.invokeLater(() -> {
            // If a command line argument is provided, use it as project path
            if (args.length > 0) {
                var projectPath = Path.of(args[0]);
                openProject(projectPath);
            } else {
                // No argument provided - attempt to load open projects if any
                var openProjects = Project.getOpenProjects();

                if (openProjects.isEmpty()) {
                    var io = new Chrome(null);
                    io.onComplete();
                    openProjectWindows.put(EMPTY_PROJECT, io);
                } else {
                    // Open all previously open projects
                    logger.info("Opening {} previously open projects", openProjects.size());
                    for (var projectPath : openProjects) {
                        openProject(projectPath);
                    }
                }
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     * The folder must contain a .git subdirectory or else we will show an error.
     */
    public static void openProject(Path path) {
        // Normalize the path to handle potential inconsistencies (e.g., trailing slashes)
        final Path projectPath = path.toAbsolutePath().normalize();

        // Check if this project is already open
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            logger.info("Project already open: {}. Bringing window to front.", projectPath);
            SwingUtilities.invokeLater(() -> {
                var frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL); // Restore if minimized
                frame.toFront();
                frame.requestFocus();
                existingWindow.focusInput();
            });
            return;
        }

        // Save to recent projects and mark as open
        Project.updateRecentProject(projectPath);

        var contextManager = new ContextManager(projectPath);
        Models models;
        String modelsError = null;
        try {
            models = Models.load();
        } catch (Throwable th) {
            modelsError = th.getMessage();
            models = Models.disabled();
        }

        // Create a new Chrome instance with the fresh ContextManager
        var io = new Chrome(contextManager);

        // Create the Coder with the new IO
        var coder = new Coder(models, io, projectPath, contextManager);

        // Resolve circular references
        contextManager.resolveCircularReferences(io, coder);
        io.onComplete();
        io.systemOutput("Opened project at " + projectPath);

        if (!coder.isLlmAvailable()) {
            io.toolError("\nError loading models: " + modelsError);
            io.toolError("AI will not be available this session");
        }
        io.focusInput();
        
        // Add to open projects map
        openProjectWindows.put(projectPath, io);
        
        // Add window listener to remove from map when closed
        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                openProjectWindows.remove(projectPath).close();
                if (openProjectWindows.isEmpty()) {
                    System.exit(0);
                }
                Project.removeFromOpenProjects(projectPath);
                logger.debug("Removed project from open windows map: {}", projectPath);
            }
        });

        // remove placeholder frame if present
        if (openProjectWindows.get(EMPTY_PROJECT) != null) {
            openProjectWindows.remove(EMPTY_PROJECT).close();
        }
    }

    /**
     * Prompts the user to select a JAR file using FileSelectionDialog,
     * then decompiles it to a project directory (.brokk/dependencies/[jarname])
     * within the currently open project and opens the decompiled source as a new project.
     */
    public static void openJarDependency(Chrome io) {
        // Fixme ensure the menu item is disabled if no project is open
        assert io.getContextManager() != null;
        assert io.getProject() != null;
        logger.debug("Entered openJarDependency");
        var cm = io.getContextManager();

        var jarCandidates = cm.submitBackgroundTask("Scaning for JAR files", DecompileHelper::findCommonDependencyJars);

        // Now show the dialog on the EDT
        SwingUtilities.invokeLater(() -> {
            Predicate<File> jarFilter = file -> file.isDirectory() || file.getName().toLowerCase().endsWith(".jar");
            FileSelectionDialog dialog = new FileSelectionDialog(
                    io.getFrame(),
                    cm.getProject(), // Pass the current project
                    "Select JAR Dependency to Decompile",
                    true, // Allow external files
                    jarFilter, // Filter tree view for .jar files (and directories)
                    jarCandidates // Provide candidates for autocomplete
            );
            dialog.setVisible(true); // Show the modal dialog

            if (dialog.isConfirmed() && dialog.getSelectedFile() != null) {
                var selectedFile = dialog.getSelectedFile();
                Path jarPath = selectedFile.absPath();
                assert Files.isRegularFile(jarPath) && jarPath.toString().toLowerCase().endsWith(".jar");
                decompileAndOpenJar(io, jarPath);
            } else {
                logger.debug("JAR selection cancelled by user.");
            }
        });
    }

    /**
     * Performs the decompilation and opening of the selected JAR file.
     * Separated from openJarDependency to keep UI selection logic cleaner.
     * This method assumes jarPath is a valid JAR file.
     *
     * @param jarPath Path to the JAR file to decompile.
     */
    private static void decompileAndOpenJar(Chrome io, Path jarPath) {
        try {
            String jarName = jarPath.getFileName().toString();
            // Use the *original* project's root to determine the .brokk directory
            Path originalProjectRoot = io.getContextManager().getRoot();
            Path brokkDir = originalProjectRoot.resolve(".brokk");
            Path depsDir = brokkDir.resolve("dependencies");
            Path outputDir = depsDir.resolve(jarName.replaceAll("\\.jar$", "")); // Decompile target dir

            logger.debug("Original project root: {}", originalProjectRoot);
            logger.debug("Decompile output directory: {}", outputDir);

            Files.createDirectories(depsDir);

            // Check if already decompiled
            if (Files.exists(outputDir)) {
                int choice = JOptionPane.showConfirmDialog(
                        io.getFrame(), // Use current IO frame
                        """
                        This JAR appears to have been decompiled previously.
                        Output directory: %s

                        Open existing decompiled project?
                        (Choosing 'No' will delete the existing directory and re-decompile)
                        """.formatted(outputDir.toString()),
                        "Dependency exists",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );

                if (choice == JOptionPane.YES_OPTION) {
                    logger.debug("Opening previously decompiled dependency at {}", outputDir);
                    openProject(outputDir);
                    return;
                } else if (choice == JOptionPane.NO_OPTION) {
                    logger.debug("Removing old decompiled contents at {}", outputDir);
                    try {
                        DecompileHelper.deleteDirectoryRecursive(outputDir);
                    } catch (IOException e) {
                        logger.error("Failed to delete existing directory: {}", outputDir, e);
                        io.toolErrorRaw("Error deleting existing decompiled directory: " + e.getMessage());
                        return; // Stop if deletion fails
                    }
                    // Recreate the directory after deletion
                    Files.createDirectories(outputDir);
                } else { // CANCEL_OPTION or closed dialog
                    logger.debug("User cancelled decompilation for {}", jarPath);
                    return;
                }
            } else {
                // Create the output directory if it didn't exist
                Files.createDirectories(outputDir);
            }


            io.systemOutput("Decompiling " + jarName + "...");

            // Decompilation Worker
            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception { // Allow exceptions
                    logger.debug("Starting decompilation in background thread for {}", jarPath);
                    Path tempDir = null; // To store the path of the temporary directory

                    try {
                        // 1. Create a temporary directory
                        tempDir = Files.createTempDirectory("fernflower-extracted-");
                        logger.debug("Created temporary directory: {}", tempDir);

                        // 2. Extract the JAR contents to the temporary directory
                        DecompileHelper.extractJarToTemp(jarPath, tempDir);
                        logger.debug("Extracted JAR contents to temporary directory.");

                        // 3. Set up Decompiler with the *final* output directory
                        Map<String, Object> options = Map.of("hes", "1", // hide empty super
                                                             "hdc", "1", // hide default constructor
                                                             "dgs", "1", // decompile generic signature
                                                             "ren", "1" /* rename ambiguous */);
                        ConsoleDecompiler decompiler = new ConsoleDecompiler(
                                outputDir.toFile(), // Use the final desired output directory here
                                options,
                                new org.jetbrains.java.decompiler.main.extern.IFernflowerLogger() {
                                    @Override
                                    public void writeMessage(String message, Severity severity) {
                                        switch (severity) {
                                            case ERROR -> logger.error("Fernflower: {}", message);
                                            case WARN  -> logger.warn("Fernflower: {}", message);
                                            case INFO  -> logger.info("Fernflower: {}", message);
                                            case TRACE -> logger.trace("Fernflower: {}", message);
                                            default    -> logger.debug("Fernflower: {}", message);
                                        }
                                    }

                                    @Override
                                    public void writeMessage(String message, Severity severity, Throwable t) {
                                        switch (severity) {
                                            case ERROR -> logger.error("Fernflower: {}", message, t);
                                            case WARN  -> logger.warn("Fernflower: {}", message, t);
                                            case INFO  -> logger.info("Fernflower: {}", message, t);
                                            case TRACE -> logger.trace("Fernflower: {}", message, t);
                                            default   -> logger.debug("Fernflower: {}", message, t);
                                        }
                                    }
                                }
                        );

                        // 4. Add the *temporary directory* as the source
                        decompiler.addSource(tempDir.toFile());

                        // 5. Decompile
                        logger.info("Starting decompilation process...");
                        decompiler.decompileContext();
                        logger.info("Decompilation process finished.");

                        return null; // Indicate success
                    } catch (Exception e) {
                        // Log and rethrow to be caught by done()
                        logger.error("Error during decompilation background task for {}", jarPath, e);
                        throw e;
                    } finally {
                        // 6. Clean up the temporary directory
                        if (tempDir != null) {
                            try {
                                logger.debug("Cleaning up temporary directory: {}", tempDir);
                                DecompileHelper.deleteDirectoryRecursive(tempDir);
                                logger.debug("Temporary directory deleted.");
                            } catch (IOException e) {
                                logger.error("Failed to delete temporary directory: {}", tempDir, e);
                                // Don't prevent opening the project if temp dir cleanup fails
                            }
                        }
                    }
                }

                @Override
                protected void done() {
                    try {
                        get(); // Check for exceptions from doInBackground()
                        io.systemOutput("Decompilation completed. Opening decompiled project.");
                        // Log final directory structure for troubleshooting
                        logger.debug("Final contents of {} after decompilation:", outputDir);
                        try (var pathStream = Files.walk(outputDir, 1)) { // Walk only one level deep for brevity
                            pathStream.forEach(path -> logger.debug("   {}", path.getFileName()));
                        } catch (IOException e) {
                            logger.warn("Error listing output directory contents", e);
                        }

                        // Open the decompiled directory as a new project
                        openProject(outputDir);

                    } catch (Exception e) {
                        // Handle exceptions from get() or other logic in done()
                        Throwable cause = (e instanceof java.util.concurrent.ExecutionException) ? e.getCause() : e;
                        io.toolErrorRaw("Error during decompilation process: " + cause.getMessage());
                        logger.error("Error completing decompilation task for {}", jarPath, cause);
                    }
                }
            };

            worker.execute(); // Start the background decompilation
        } catch (IOException e) {
            // Error *before* starting the worker (e.g., creating directories)
            io.toolErrorRaw("Error preparing decompilation: " + e.getMessage());
            logger.error("Error preparing decompilation for {}", jarPath, e);
        }
    }

}
