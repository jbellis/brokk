package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.git.LocalFileRepo;
import io.github.jbellis.brokk.util.AtomicWrites;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class Project implements IProject, AutoCloseable {
    private final Path propertiesFile;
    private final Path workspacePropertiesFile;
    private final Path root;
    private final Properties projectProps;
    private final Properties workspaceProps;
    private final Path styleGuidePath;
    private final AnalyzerWrapper analyzerWrapper;
    private final IGitRepo repo;
    private final Set<ProjectFile> dependencyFiles;

    private static final int DEFAULT_AUTO_CONTEXT_FILE_COUNT = 10;
    private static final int DEFAULT_WINDOW_WIDTH = 800;
    private static final int DEFAULT_WINDOW_HEIGHT = 1200;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LogManager.getLogger(Project.class);

    // --- Static paths ---
    private static final Path BROKK_CONFIG_DIR = Path.of(System.getProperty("user.home"), ".config", "brokk");
    private static final Path PROJECTS_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("projects.properties");
    private static final Path GLOBAL_PROPERTIES_PATH = BROKK_CONFIG_DIR.resolve("brokk.properties");

    public Project(Path root, ContextManager.TaskRunner runner, AnalyzerListener analyzerListener) {
        this.repo = GitRepo.hasGitRepo(root) ? new GitRepo(root) : new LocalFileRepo(root);
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.workspacePropertiesFile = root.resolve(".brokk").resolve("workspace.properties");
        this.styleGuidePath = root.resolve(".brokk").resolve("style.md");
        this.projectProps = new Properties();
        this.workspaceProps = new Properties();
        this.dependencyFiles = loadDependencyFiles();

        // Load project properties
        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                projectProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading project properties: {}", e.getMessage());
                projectProps.clear();
            }
        }

        // Load workspace properties
        if (Files.exists(workspacePropertiesFile)) {
            try (var reader = Files.newBufferedReader(workspacePropertiesFile)) {
                workspaceProps.load(reader);
            } catch (Exception e) {
                logger.error("Error loading workspace properties: {}", e.getMessage());
                workspaceProps.clear();
            }
        }

        // Create the analyzer wrapper
        this.analyzerWrapper = new AnalyzerWrapper(this, runner, analyzerListener);

        // Set defaults for workspace properties if missing
        if (workspaceProps.isEmpty()) {
            workspaceProps.setProperty("autoContextFileCount", String.valueOf(DEFAULT_AUTO_CONTEXT_FILE_COUNT));
            // Create default window positions
            var mainFrame = objectMapper.createObjectNode();
            mainFrame.put("x", -1);
            mainFrame.put("y", -1);
            mainFrame.put("width", DEFAULT_WINDOW_WIDTH);
            mainFrame.put("height", DEFAULT_WINDOW_HEIGHT);

            try {
                workspaceProps.setProperty("mainFrame", objectMapper.writeValueAsString(mainFrame));
            } catch (Exception e) {
                logger.error("Error creating default window settings: {}", e.getMessage());
            }
            // Don't set default theme here anymore, it's global
        }
    }

    // --- Static methods for global properties ---

    /**
     * Reads the global properties file (~/.config/brokk/brokk.properties).
     * Returns an empty Properties object if the file doesn't exist or can't be read.
     */
    private static Properties loadGlobalProperties() {
        var props = new Properties();
        if (Files.exists(GLOBAL_PROPERTIES_PATH)) {
            try (var reader = Files.newBufferedReader(GLOBAL_PROPERTIES_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read global properties file: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Atomically saves the given Properties object to ~/.config/brokk/brokk.properties.
     * Only writes if the properties have actually changed.
     */
    private static void saveGlobalProperties(Properties props) {
        try {
            // Check if properties file exists and compare
            if (Files.exists(GLOBAL_PROPERTIES_PATH)) {
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(GLOBAL_PROPERTIES_PATH)) {
                    existingProps.load(reader);
                } catch (IOException e) {
                    // Ignore read error, proceed to save anyway
                }

                // Compare properties - only save if different
                if (propsEqual(existingProps, props)) {
                    return; // Skip saving if properties are identical
                }
            }

            // Use atomic save method
            AtomicWrites.atomicSaveProperties(GLOBAL_PROPERTIES_PATH, props, "Brokk global configuration");
        } catch (IOException e) {
            logger.error("Error saving global properties: {}", e.getMessage());
        }
    }

    // --- Instance methods ---

    @Override
    public Set<ProjectFile> getFiles() {
        var trackedFiles = repo.getTrackedFiles();
        var allFiles = new java.util.HashSet<>(trackedFiles);
        allFiles.addAll(dependencyFiles);
        return allFiles;
    }
    
    /**
     * Loads all files from the .brokk/dependencies directory
     * @return Set of RepoFile objects for all dependency files
     */
    private Set<ProjectFile> loadDependencyFiles() {
        var dependenciesPath = root.resolve(".brokk").resolve("dependencies");
        if (!Files.exists(dependenciesPath) || !Files.isDirectory(dependenciesPath)) {
            return Set.of();
        }
        
        try (var pathStream = Files.walk(dependenciesPath)) {
            return pathStream
                .filter(Files::isRegularFile)
                .map(path -> {
                    var relPath = root.relativize(path);
                    return new ProjectFile(root, relPath);
                })
                .collect(Collectors.toSet());
        } catch (IOException e) {
            logger.error("Error loading dependency files", e);
            return Set.of();
        }
    }

    @Override
    public IGitRepo getRepo() {
        return repo;
    }

    @Override
    public String getBuildCommand() {
        return projectProps.getProperty("buildCommand");
    }

    public Language getAnalyzerLanguage() {
        String lang = projectProps.getProperty("code_intelligence_language");
        if (lang == null || lang.isBlank()) {
            return Language.Java;
        }
        try {
            return Language.valueOf(lang.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Language.None;
        }
    }

    public void setBuildCommand(String command) {
        projectProps.setProperty("buildCommand", command);
        saveProjectProperties();
    }

    /**
     * Gets the name of the last used LLM model for this project.
     * @return Model name, or null if not set.
     */
    public String getLastUsedModel() {
        return workspaceProps.getProperty("lastUsedModel");
    }

    /**
     * Sets the name of the last used LLM model for this project.
     * @param modelName The name of the model.
     */
    public void setLastUsedModel(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            workspaceProps.setProperty("lastUsedModel", modelName);
            saveWorkspaceProperties();
        }
    }

    /**
     * Saves project-specific properties (buildCommand, code_intelligence_refresh)
     */
    public void saveProjectProperties() {
        saveProperties(propertiesFile, projectProps, "Brokk project configuration");
    }

    /**
     * Saves workspace-specific properties (window positions, etc.)
     */
    public void saveWorkspaceProperties() {
        saveProperties(workspacePropertiesFile, workspaceProps, "Brokk workspace configuration");
    }

    /**
     * Generic method to save properties to a file
     */
    private void saveProperties(Path file, Properties properties, String comment) {
        try {
            // Check if properties file exists
            if (Files.exists(file)) {
                // Load existing properties to compare
                Properties existingProps = new Properties();
                try (var reader = Files.newBufferedReader(file)) {
                    existingProps.load(reader);
                }

                // Compare properties - only save if different
                if (propsEqual(existingProps, properties)) {
                    return; // Skip saving if properties are identical
                }
            }

            // Use atomic save method
            AtomicWrites.atomicSaveProperties(file, properties, comment);
        } catch (IOException e) {
            logger.error("Error saving properties to {}: {}", file, e.getMessage());
        }
    }

    /**
     * Compares two Properties objects to see if they have the same key-value pairs
     * @return true if properties are equal
     */
    private static boolean propsEqual(Properties p1, Properties p2) {
        if (p1 == null || p2 == null || p1.size() != p2.size()) {
            return false;
        }

        return p1.entrySet().stream()
                .allMatch(e -> {
                    String key = (String) e.getKey();
                    String value = (String) e.getValue();
                    return value.equals(p2.getProperty(key));
                });
    }

    public Path getRoot() {
        return root;
    }

    public boolean hasGit() {
        return repo instanceof GitRepo;
    }

    public void pauseAnalyzerRebuilds() {
        analyzerWrapper.pause();
    }

    public void resumeAnalyzerRebuilds() {
        analyzerWrapper.resume();
    }

    public enum CpgRefresh {
        AUTO,
        MANUAL,
        UNSET
    }

    /**
     * Check if .brokk entries exist in .gitignore
     * @return true if .gitignore contains entries for .brokk
     */
    public boolean isGitIgnoreSet() {
        try {
            var gitignorePath = root.resolve(".gitignore");
            if (Files.exists(gitignorePath)) {
                var content = Files.readString(gitignorePath);
                return content.contains(".brokk/") || content.contains(".brokk/**");
            }
        } catch (IOException e) {
            logger.error("Error checking .gitignore: {}", e.getMessage());
        }
        return false;
    }

    public CpgRefresh getCpgRefresh() {
        String value = projectProps.getProperty("code_intelligence_refresh");
        if (value == null) {
            return CpgRefresh.UNSET;
        }
        try {
            return CpgRefresh.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CpgRefresh.UNSET;
        }
    }

    public void setCpgRefresh(CpgRefresh value) {
        assert value != null;
        projectProps.setProperty("code_intelligence_refresh", value.name());
        saveProjectProperties();
    }

    public String getStyleGuide() {
        try {
            if (Files.exists(styleGuidePath)) {
                return Files.readString(styleGuidePath);
            }
        } catch (IOException e) {
            logger.error("Error reading style guide: {}", e.getMessage());
        }
        return "";
    }

    public void saveStyleGuide(String styleGuide) {
        try {
            Files.createDirectories(styleGuidePath.getParent());
            AtomicWrites.atomicOverwrite(styleGuidePath, styleGuide);
        } catch (IOException e) {
            logger.error("Error saving style guide: {}", e.getMessage());
        }
    }

    /**
     * Saves a serialized Context object to the workspace properties
     */
    public void saveContext(Context context) {
        try {
            byte[] serialized = Context.serialize(context);
            String encoded = java.util.Base64.getEncoder().encodeToString(serialized);
            workspaceProps.setProperty("context", encoded);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving context: {}", e.getMessage());
        }
    }

    /**
     * Loads a serialized Context object from the workspace properties
     * @return The loaded Context, or null if none exists
     */
    public Context loadContext(IContextManager contextManager, String welcomeMessage) {
        try {
            String encoded = workspaceProps.getProperty("context");
            if (encoded != null && !encoded.isEmpty()) {
                byte[] serialized = java.util.Base64.getDecoder().decode(encoded);
                return Context.deserialize(serialized, welcomeMessage).withContextManager(contextManager);
            }
        } catch (Throwable e) {
            logger.error("Error loading context: {}", e.getMessage());
            clearSavedContext();
        }
        return null;
    }

    private void clearSavedContext() {
        workspaceProps.remove("context");
        saveWorkspaceProperties();
        logger.debug("Cleared saved context from workspace properties");
    }

    /**
     * Saves a list of text history items to workspace properties
     * @param historyItems The list of text history items to save (newest first)
     * @param maxItems Maximum number of items to store (older items are trimmed)
     */
    public void saveTextHistory(List<String> historyItems, int maxItems) {
        try {
            // Limit the list to the specified maximum size
            var limitedItems = historyItems.stream()
                    .limit(maxItems)
                    .collect(Collectors.toList());

            // Convert to JSON and store in properties
            String json = objectMapper.writeValueAsString(limitedItems);
            workspaceProps.setProperty("textHistory", json);
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving text history: {}", e.getMessage());
        }
    }

    /**
     * Loads the saved text history items
     * @return List of text history items (newest first), or empty list if none found
     */
    public List<String> loadTextHistory() {
        try {
            String json = workspaceProps.getProperty("textHistory");
            if (json != null && !json.isEmpty()) {
                List<String> result = objectMapper.readValue(json,
                                                             objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                logger.debug("Loaded {} history items", result.size());
                return result;
            }
        } catch (Exception e) {
            logger.error("Error loading text history: {}", e.getMessage(), e);
        }
        logger.debug("No text history found, returning empty list");
        return new ArrayList<>();
    }

    /**
     * Adds a new item to the text history, maintaining the maximum size
     * @param item New item to add to history
     * @param maxItems Maximum history size
     * @return The updated history list
     */
    public List<String> addToTextHistory(String item, int maxItems) {
        if (item == null || item.trim().isEmpty()) {
            return loadTextHistory(); // Don't add empty items
        }

        var history = new ArrayList<>(loadTextHistory());

        // Remove item if it already exists to avoid duplicates
        history.removeIf(i -> i.equals(item));

        // Add the new item at the beginning (newest first)
        history.addFirst(item);

        // Trim to max size
        if (history.size() > maxItems) {
            history = new ArrayList<>(history.subList(0, maxItems));
        }

        // Save and return the updated list
        saveTextHistory(history, maxItems);
        return history;
    }

    /**
     * Save a window's position and size
     * @param key identifier for the window
     * @param window the window to save position for
     */
    public void saveWindowBounds(String key, JFrame window) {
        if (window == null || !window.isDisplayable()) {
            return;
        }

        try {
            var node = objectMapper.createObjectNode();
            node.put("x", window.getX());
            node.put("y", window.getY());
            node.put("width", window.getWidth());
            node.put("height", window.getHeight());

            logger.debug("Saving {} bounds as {}", key, node);
            workspaceProps.setProperty(key, objectMapper.writeValueAsString(node));
            saveWorkspaceProperties();
        } catch (Exception e) {
            logger.error("Error saving window bounds: {}", e.getMessage());
        }
    }

    /**
     * Get the saved window bounds as a Rectangle
     * @param key identifier for the window
     * @param defaultWidth default width if not found
     * @param defaultHeight default height if not found
     * @return Rectangle with the window bounds
     */
    public java.awt.Rectangle getWindowBounds(String key, int defaultWidth, int defaultHeight) {
        var result = new java.awt.Rectangle(-1, -1, defaultWidth, defaultHeight);

        try {
            String json = workspaceProps.getProperty(key);
            logger.debug("Loading {} bounds from {}", key, json);
            if (json != null) {
                var node = objectMapper.readValue(json, ObjectNode.class);

                if (node.has("width") && node.has("height")) {
                    result.width = node.get("width").asInt();
                    result.height = node.get("height").asInt();
                }

                if (node.has("x") && node.has("y")) {
                    result.x = node.get("x").asInt();
                    result.y = node.get("y").asInt();
                }
            }
        } catch (Exception e) {
            logger.error("Error reading window bounds: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Gets the saved main window bounds
     */
    public java.awt.Rectangle getMainWindowBounds() {
        return getWindowBounds("mainFrame", DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
    }

    /**
     * Gets the saved preview window bounds
     */
    public java.awt.Rectangle getPreviewWindowBounds() {
        return getWindowBounds("previewFrame", 600, 400);
    }

    /**
     * Gets the saved diff window bounds
     */
    public java.awt.Rectangle getDiffWindowBounds() {
        return getWindowBounds("diffFrame", 900, 600);
    }

    /**
     * Gets the saved output window bounds
     */
    public java.awt.Rectangle getOutputWindowBounds() {
        return getWindowBounds("outputFrame", 800, 600);
    }

    /**
     * Save main window bounds
     */
    public void saveMainWindowBounds(JFrame window) {
        saveWindowBounds("mainFrame", window);
    }

    /**
     * Save preview window bounds
     */
    public void savePreviewWindowBounds(JFrame window) {
        saveWindowBounds("previewFrame", window);
    }

    /**
     * Save diff window bounds
     */
    public void saveDiffWindowBounds(JFrame frame) {
        saveWindowBounds("diffFrame", frame);
    }

    /**
     * Save output window bounds
     */
    public void saveOutputWindowBounds(JFrame frame) {
        saveWindowBounds("outputFrame", frame);
    }

    /**
     * Store the GitHub token in workspace properties.
     */
    public void setGitHubToken(String token) {
        workspaceProps.setProperty("githubToken", token);
        saveWorkspaceProperties();
    }

    /**
     * Retrieve the GitHub token from workspace properties (may be null).
     */
    public String getGitHubToken() {
        return workspaceProps.getProperty("githubToken");
    }

    /**
     * Save vertical split pane position
     */
    public void saveVerticalSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("verticalSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get vertical split pane position
     */
    public int getVerticalSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("verticalSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save history split pane position
     */
    public void saveTopSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("topSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get history split pane position
     */
    public int getTopSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("topSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Save context/git split pane position
     */
    public void saveContextGitSplitPosition(int position) {
        if (position > 0) {
            workspaceProps.setProperty("contextGitSplitPosition", String.valueOf(position));
            saveWorkspaceProperties();
        }
    }

    /**
     * Get context/git split pane position
     */
    public int getContextGitSplitPosition() {
        try {
            String posStr = workspaceProps.getProperty("contextGitSplitPosition");
            return posStr != null ? Integer.parseInt(posStr) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gets the current global UI theme (dark or light)
     * @return "dark" or "light" (defaults to "light" if not set)
     */
    public static String getTheme() {
        var props = loadGlobalProperties();
        return props.getProperty("theme", "dark"); // Default to light
    }

    /**
     * Sets the global UI theme
     * @param theme "dark" or "light"
     */
    public static void setTheme(String theme) {
        var props = loadGlobalProperties();
        props.setProperty("theme", theme);
        saveGlobalProperties(props);
    }

    public void rebuildAnalyzer() {
        analyzerWrapper.requestRebuild();
    }

    /**
     * Gets the analyzer, blocking if necessary while it's being built
     */
    @Override
    public IAnalyzer getAnalyzer() {
        return analyzerWrapper.get();
    }

    // --- Static methods for managing projects.properties ---

    /**
     * Reads the projects properties file (~/.config/brokk/projects.properties).
     * Returns an empty Properties object if the file doesn't exist or can't be read.
     */
    private static Properties loadProjectsProperties() {
        var props = new Properties();
        if (Files.exists(PROJECTS_PROPERTIES_PATH)) {
            try (var reader = Files.newBufferedReader(PROJECTS_PROPERTIES_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                logger.warn("Unable to read projects properties file: {}", e.getMessage());
            }
        }
        return props;
    }

    /**
     * Atomically saves the given Properties object to ~/.config/brokk/projects.properties.
     */
    private static void saveProjectsProperties(Properties props) {
        try {
            AtomicWrites.atomicSaveProperties(PROJECTS_PROPERTIES_PATH, props, "Brokk projects: recently opened and currently open");
        } catch (IOException e) {
            logger.error("Error saving projects properties: {}", e.getMessage());
        }
    }

    /**
     * Reads the recent projects list from ~/.config/brokk/projects.properties.
     * Returns a map of projectPath -> lastOpenedMillis.
     * If the file doesn't exist or can't be read, returns an empty map.
     */
    public static Map<String, Long> loadRecentProjects() {
        var result = new HashMap<String, Long>();
        var props = loadProjectsProperties();

        for (String key : props.stringPropertyNames()) {
            // Only process keys that look like paths (simple heuristic) and ignore the open list
            if (key.contains(java.io.File.separator) && !key.equals("openProjectsList")) {
                try {
                    var value = Long.parseLong(props.getProperty(key));
                    result.put(key, value);
                } catch (NumberFormatException nfe) {
                    logger.warn("Invalid timestamp for key {} in projects.properties", key);
                }
            }
        }
        return result;
    }

    /**
     * Saves the given map of recent projectPath -> lastOpenedMillis to
     * ~/.config/brokk/projects.properties, trimming recent projects to the 10 most recent.
     * Preserves the existing 'openProjectsList' property.
     */
    public static void saveRecentProjects(Map<String, Long> projects) {
        // Load existing properties to preserve the open projects list
        var existingProps = loadProjectsProperties();
        var openProjectsList = existingProps.getProperty("openProjectsList", ""); // Default to empty if not found

        // Sort recent projects entries by lastOpened descending
        var sorted = projects.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(10)
                .toList();

        var props = new Properties();
        // Add sorted recent projects
        for (var e : sorted) {
            props.setProperty(e.getKey(), Long.toString(e.getValue()));
        }
        // Add the (potentially preserved) open projects list
        props.setProperty("openProjectsList", openProjectsList);

        saveProjectsProperties(props);
    }

    /**
     * Updates the projects.properties with a single entry for the given directory path,
     * setting last opened to the current time, and adds it to the list of open projects.
     */
    public static void updateRecentProject(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var currentMap = loadRecentProjects();
        currentMap.put(abs, System.currentTimeMillis());
        saveRecentProjects(currentMap); // saveRecentProjects preserves the open list now

        // Also add to open projects list within the same properties file
        addToOpenProjects(projectDir);
    }

    /**
     * Adds a project to the 'openProjectsList' property in projects.properties
     */
    private static void addToOpenProjects(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties(); // Load current properties

        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new java.util.HashSet<>(List.of(openListStr.split(";")));
        openSet.remove(""); // Remove empty string artifact if list was empty

        if (openSet.add(abs)) { // Add returns true if the set was modified
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save updated properties
        }
    }

    /**
     * Removes a project from the 'openProjectsList' property in projects.properties
     */
    public static void removeFromOpenProjects(Path projectDir) {
        var abs = projectDir.toAbsolutePath().toString();
        var props = loadProjectsProperties(); // Load current properties

        var openListStr = props.getProperty("openProjectsList", "");
        var openSet = new java.util.HashSet<>(List.of(openListStr.split(";")));
        openSet.remove(""); // Remove empty string artifact

        if (openSet.remove(abs)) { // remove returns true if the set was modified
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save updated properties
        }
    }

    /**
     * Gets the list of currently open projects from the 'openProjectsList' property
     * in projects.properties. Performs validation and cleanup of invalid entries.
     * @return List of validated paths to currently open projects
     */
    public static List<Path> getOpenProjects() {
        var result = new ArrayList<Path>();
        var props = loadProjectsProperties();
        var openListStr = props.getProperty("openProjectsList", "");

        if (openListStr.isEmpty()) {
            return result;
        }

        var pathsToRemove = new ArrayList<String>();
        var openPaths = List.of(openListStr.split(";"));

        for (String pathStr : openPaths) {
            if (pathStr.isEmpty()) continue; // Skip empty strings from split

            try {
                var path = Path.of(pathStr);
                // Only include paths that still exist and have git repos
                if (Files.isDirectory(path)) {
                    result.add(path);
                } else {
                    // Mark for removal if invalid
                    logger.warn("Removing invalid or non-existent project from open list: {}", pathStr);
                    pathsToRemove.add(pathStr);
                }
            } catch (Exception e) {
                logger.warn("Invalid path string in openProjectsList: {}", pathStr, e);
                pathsToRemove.add(pathStr);
            }
        }

        // Clean up entries for non-existent/invalid projects if any were found
        if (!pathsToRemove.isEmpty()) {
            var openSet = new java.util.HashSet<>(openPaths);
            openSet.removeAll(pathsToRemove);
            openSet.remove(""); // Ensure empty string is not present
            props.setProperty("openProjectsList", String.join(";", openSet));
            saveProjectsProperties(props); // Save cleaned-up list
        }

        return result;
    }

    @Override
    public void close() {
        analyzerWrapper.close();
    }
}
