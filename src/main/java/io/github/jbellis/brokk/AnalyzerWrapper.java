package io.github.jbellis.brokk;

import io.github.jbellis.brokk.Project.CpgRefresh;

import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.Language;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.KeyboardFocusManager;

import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class AnalyzerWrapper implements AutoCloseable {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private static final long DEBOUNCE_DELAY_MS = 500;
    private static final long POLL_TIMEOUT_FOCUSED_MS = 100;
    private static final long POLL_TIMEOUT_UNFOCUSED_MS = 1000;

    private final AnalyzerListener listener; // can be null if no one is listening
    private final Path root;
    private final ContextManager.TaskRunner runner;
    private final Project project;
    private final Language language;

    private volatile boolean running = true;
    private volatile boolean paused = false;

    private volatile Future<IAnalyzer> future;
    private volatile IAnalyzer currentAnalyzer = null;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false;
    private volatile boolean rebuildPending = false;

    /**
     * Create a new orchestrator. (We assume the analyzer executor is provided externally.)
     */
    public AnalyzerWrapper(Project project, ContextManager.TaskRunner runner, AnalyzerListener listener) {
        this.project = project;
        this.root = project.getRoot();
        this.runner = runner;
        this.listener = listener;

        // build the initial Analyzer
        language = project.getAnalyzerLanguage();
        future = runner.submit("Initializing code intelligence", this::loadOrCreateAnalyzer);
    }

    private void beginWatching(Path root) {
        try {
            // Wait for the initial analyzer to build
            future.get();
        } catch (InterruptedException | ExecutionException e) {
            // everything expects that get() will work, this is fatal
            logger.debug("Error building initial analyzer", e);
            throw new RuntimeException(e);
        }

        logger.debug("Setting up WatchService");
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            // Recursively register all directories except .brokk
            registerAllDirectories(root, watchService);

            // Watche for events, debounces them, and handles them
            while (running) {
                // Wait if paused
                while (paused) {
                    Thread.onSpinWait();
                }

                // Choose a short or long poll depending on focus
                long pollTimeout = isApplicationFocused() ? POLL_TIMEOUT_FOCUSED_MS : POLL_TIMEOUT_UNFOCUSED_MS;
                WatchKey key = watchService.poll(pollTimeout, TimeUnit.MILLISECONDS);

                // If no event arrived within the poll window, check for external rebuild requests
                if (key == null) {
                    if (externalRebuildRequested && !rebuildInProgress) {
                        logger.debug("External rebuild requested");
                        rebuild();
                    }
                    continue;
                }

                // We got an event, collect it and any others within the debounce window
                var batch = new HashSet<FileChangeEvent>();
                collectEventsFromKey(key, watchService, batch);

                long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;
                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    WatchKey nextKey = watchService.poll(remaining, TimeUnit.MILLISECONDS);
                    if (nextKey == null) break;
                    collectEventsFromKey(nextKey, watchService, batch);
                }

                // Process the batch
                handleBatch(batch);
            }
        }
        catch (IOException e) {
            logger.error("Error setting up watch service", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("FileWatchService thread interrupted; shutting down");
        }
    }

    /**
     * Check if changes in this batch of events require a .git refresh and/or analyzer rebuild.
     */
    private void handleBatch(Set<FileChangeEvent> batch) {
        // 1) Possibly refresh Git
        boolean needsGitRefresh = batch.stream().anyMatch(event -> {
            Path gitDir = root.resolve(".git");
            return event.path.startsWith(gitDir)
                    && (event.type == EventType.CREATE
                    || event.type == EventType.DELETE
                    || event.type == EventType.MODIFY);
        });
        if (needsGitRefresh) {
            logger.debug("Refreshing git due to changes in .git directory");
            listener.onRepoChange();
            listener.onTrackedFileChange();
        }

        // 2) Check if any *tracked* files changed
        Set<Path> trackedPaths = project.getRepo().getTrackedFiles().stream()
                .map(ProjectFile::absPath)
                .collect(Collectors.toSet());

        boolean needsAnalyzerRefresh = batch.stream()
                .anyMatch(event -> trackedPaths.contains(event.path));

        if (needsAnalyzerRefresh) {
            logger.debug("Rebuilding analyzer due to changes in tracked files: {}",
                         batch.stream()
                                 .filter(e -> trackedPaths.contains(e.path))
                                 .map(e -> e.path.toString())
                                 .collect(Collectors.joining(", "))
            );
            rebuild();
        } else {
            logger.trace("No tracked files changed; skipping analyzer rebuild");
        }
    }

    /**
     * Synchronously load or create an Analyzer:
     *   1) If the .brokk/joern.cpg file is up to date, reuse it;
     *   2) Otherwise, rebuild a fresh Analyzer.
     */
    private IAnalyzer loadOrCreateAnalyzer() {
        logger.debug("Loading/creating analyzer for {}", project.getAnalyzerLanguage());
        if (project.getAnalyzerLanguage() == Language.NONE) {
            return new DisabledAnalyzer();
        }

        // FIXME
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        if (project.getCpgRefresh() == CpgRefresh.UNSET) {
            logger.debug("First startup: timing Analyzer creation");
            long start = System.currentTimeMillis();
            var analyzer = createAndSaveAnalyzer();
            long duration = System.currentTimeMillis() - start;
            if (analyzer.isEmpty()) {
                logger.info("Empty analyzer");
                listener.afterFirstBuild("");
            } else if (duration > 3 * 6000) {
                project.setAnalyzerRefresh(CpgRefresh.MANUAL);
                var msg = """
                Code Intelligence found %d classes in %,d ms.
                Since this was slow, code intelligence will only refresh when explicitly requested via the Context menu.
                You can change this in the Settings -> Project dialog.
                """.stripIndent().formatted(analyzer.getAllClasses().size(), duration);
                listener.afterFirstBuild(msg);
                logger.info(msg);
            } else if (duration > 5000) {
                project.setAnalyzerRefresh(CpgRefresh.ON_RESTART);
                var msg = """
                Code Intelligence found %d classes in %,d ms.
                Since this was slow, code intelligence will only refresh on restart, or when explicitly requested via the Context menu.
                You can change this in the Settings -> Project dialog.
                """.stripIndent().formatted(analyzer.getAllClasses().size(), duration);
                listener.afterFirstBuild(msg);
                logger.info(msg);
            } else {
                project.setAnalyzerRefresh(CpgRefresh.AUTO);
                var msg = """
                Code Intelligence found %d classes in %,d ms.
                If this is fewer than expected, it's probably because Brokk only looks for %s files.
                If this is not a useful subset of your project, you can change it in the Settings -> Project
                dialog, or disable Code Intelligence by setting the language to NONE.
                """.stripIndent().formatted(analyzer.getAllClasses().size(), duration, language.getExtensions(), Language.NONE);
                listener.afterFirstBuild(msg);
                logger.info(msg);
                startWatcher();
            }
            return analyzer;
        }

        var analyzer = loadCachedAnalyzer(analyzerPath);
        if (analyzer == null) {
            analyzer = createAndSaveAnalyzer();
        }
        if (project.getCpgRefresh() == CpgRefresh.AUTO) {
            startWatcher();
        }
        return analyzer;
    }

    public boolean isCpg() {
        return project.getAnalyzerLanguage() == io.github.jbellis.brokk.analyzer.Language.JAVA;
    }

    private IAnalyzer createAndSaveAnalyzer() {
        IAnalyzer newAnalyzer;
        logger.debug("Creating {} analyzer for {}", language, project.getRoot());
        if (language == io.github.jbellis.brokk.analyzer.Language.JAVA) {
            newAnalyzer = new JavaAnalyzer(root);
            Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
            ((JavaAnalyzer) newAnalyzer).writeCpg(analyzerPath);
        } else {
            newAnalyzer = switch (language) {
                case PYTHON -> new PythonAnalyzer(project);
                case C_SHARP -> new CSharpAnalyzer(project);
                default -> new DisabledAnalyzer();
            };
        }

        logger.debug("Analyzer (re)build completed");
        return newAnalyzer;
    }

    /** Load a cached analyzer if it is up to date; otherwise return null. */
    private IAnalyzer loadCachedAnalyzer(Path analyzerPath) {
        if (!Files.exists(analyzerPath)) {
            return null;
        }

        // In MANUAL mode, always use cached data if it exists
        if (project.getCpgRefresh() == CpgRefresh.MANUAL) {
            logger.debug("MANUAL refresh mode - using cached analyzer");
            try {
                return new JavaAnalyzer(root, analyzerPath);
            } catch (Throwable th) {
                logger.info("Error loading analyzer", th);
                return null;
            }
        }

        var trackedFiles = project.getAllFiles();
        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            throw new RuntimeException("Error reading analyzer file timestamp", e);
        }
        long maxTrackedMTime = 0L;
        for (ProjectFile rf : trackedFiles) {
            try {
                long fileMTime = Files.getLastModifiedTime(rf.absPath()).toMillis();
                maxTrackedMTime = Math.max(maxTrackedMTime, fileMTime);
            } catch (IOException e) {
                // probable cause: file exists in git but is removed
                logger.debug("Error reading analyzer file timestamp", e);
            }
        }
        if (cpgMTime > maxTrackedMTime) {
            logger.debug("Using cached code intelligence data ({} > {})", cpgMTime, maxTrackedMTime);
            try {
                return new JavaAnalyzer(root, analyzerPath);
            } catch (Throwable th) {
                logger.info("Error loading analyzer", th);
                // fall through to return null
            }
        }
        return null;
    }

    /**
     * Force a fresh rebuild of the analyzer by scheduling a job on the analyzerExecutor.
     * Avoids concurrent rebuilds by setting a flag, but if a change is detected during
     * the rebuild, a new rebuild will be scheduled immediately afterwards.
     */
    private synchronized void rebuild() {
        listener.onTrackedFileChange();

        // If a rebuild is already running, just mark that another rebuild is pending.
        if (rebuildInProgress) {
            rebuildPending = true;
            return;
        }

        rebuildInProgress = true;
        logger.trace("Rebuilding analyzer");
        future = runner.submit("Rebuilding code intelligence", () -> {
            try {
                currentAnalyzer = createAndSaveAnalyzer();
                return currentAnalyzer;
            } finally {
                synchronized (AnalyzerWrapper.this) {
                    rebuildInProgress = false;
                    // If another rebuild got requested while we were busy, immediately start a new one.
                    if (rebuildPending) {
                        rebuildPending = false;
                        logger.trace("rebuilding immediately");
                        rebuild();
                    } else {
                        externalRebuildRequested = false;
                    }
                }
            }
        });
    }

    /**
     * Get the analyzer, showing a spinner UI while waiting if requested.
     */
    public IAnalyzer get() throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new UnsupportedOperationException("Never call blocking get() from EDT");
        }

        // If we already have an analyzer, just return it.
        if (currentAnalyzer != null) {
            return currentAnalyzer;
        }

        // Otherwise, this must be the very first build (or a failed one).
        listener.onBlocked();
        try {
            // Block until the future analyzer finishes building
            return future.get();
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    /**
     * @return null if analyzer is not ready yet
     */
    public IAnalyzer getNonBlocking() {
        if (currentAnalyzer != null) {
            return currentAnalyzer;
        }

        try {
            // Try to get with zero timeout - returns null if not done
            return future.get(0, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Not done yet
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while checking analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }

    public void requestRebuild() {
        externalRebuildRequested = true;
    }

    /**
     * Checks if any window in the application currently has focus
     * @return true if any application window has focus, false otherwise
     */
    private boolean isApplicationFocused() {
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        return focusedWindow != null;
    }

    private void startWatcher() {
        Thread watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher");
        watcherThread.start();
    }
    
    private void collectEventsFromKey(WatchKey key, WatchService watchService, Set<FileChangeEvent> batch)
    {
        for (WatchEvent<?> event : key.pollEvents()) {
            @SuppressWarnings("unchecked")
            WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
            Path relativePath = pathEvent.context();
            Path parentDir = (Path) key.watchable();
            Path absolutePath;
            try {
                absolutePath = parentDir.resolve(relativePath);
            } catch (NullPointerException e) {
                logger.warn(e);
                continue;
            }

            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                logger.debug("Overflow event: {}", absolutePath);
                continue;
            }

            // Skip .brokk or log file paths
            String pathStr = absolutePath.toString();
            if (pathStr.contains("${sys:logfile.path}") ||
                absolutePath.startsWith(root.resolve(".brokk"))) {
                continue;
            }

            WatchEvent.Kind<Path> kind = pathEvent.kind();
            EventType eventType = switch (kind.name()) {
                case "ENTRY_CREATE" -> EventType.CREATE;
                case "ENTRY_DELETE" -> EventType.DELETE;
                case "ENTRY_MODIFY" -> EventType.MODIFY;
                default -> EventType.OVERFLOW;
            };

            logger.trace("Directory event: {} on {}", eventType, absolutePath);
            batch.add(new FileChangeEvent(eventType, absolutePath));

            // If it's a directory creation, register it so we can watch its children
            if (eventType == EventType.CREATE && Files.isDirectory(absolutePath)) {
                try {
                    registerAllDirectories(absolutePath, watchService);
                } catch (IOException ex) {
                    logger.warn("Failed to register new directory for watching: {}", absolutePath, ex);
                }
            }
        }

        // If the key is no longer valid, we can't watch this path anymore
        if (!key.reset()) {
            logger.debug("Watch key no longer valid: {}", key.watchable());
        }
    }
    
    private void registerAllDirectories(Path start, WatchService watchService) throws IOException
    {
        if (!Files.isDirectory(start)) return;

        // Skip .brokk itself
        if (start.getFileName() != null && start.getFileName().toString().equals(".brokk")) {
            return;
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (var walker = Files.walk(start)) {
                walker.filter(Files::isDirectory)
                        .filter(dir -> !dir.toString().contains(".brokk"))
                        .forEach(dir -> {
                            try {
                                dir.register(watchService,
                                             StandardWatchEventKinds.ENTRY_CREATE,
                                             StandardWatchEventKinds.ENTRY_DELETE,
                                             StandardWatchEventKinds.ENTRY_MODIFY);
                            } catch (IOException e) {
                                logger.warn("Failed to register directory for watching: {}", dir, e);
                            }
                        });
                // Success: If the walk completes without exception, break the retry loop.
                return;
            } catch (IOException | java.io.UncheckedIOException e) {
                // Determine the root cause, handling the case where the UncheckedIOException wraps another exception.
                Throwable cause = (e instanceof java.io.UncheckedIOException uioe) ? uioe.getCause() : e;

                // Retry only if it's a NoSuchFileException and we have attempts left.
                if (cause instanceof java.nio.file.NoSuchFileException && attempt < 3) {
                    logger.warn("Attempt {} failed to walk directory {} due to NoSuchFileException. Retrying in 10ms...", attempt, start, cause);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } // End of retry loop
        logger.debug("Failed to (completely) register directory `{}` for watching", start);
    }

    /** Pause the file watching service. */
    public synchronized void pause() {
        logger.debug("Pausing file watcher");
        paused = true;
    }

    /** Resume the file watching service. */
    public synchronized void resume() {
        logger.debug("Resuming file watcher");
        paused = false;
    }

    @Override
    public void close() {
        running = false;
        resume(); // Ensure any waiting thread is woken up to exit
    }

    public record CodeWithSource(String code, Set<CodeUnit> sources) {
    }
    
    // Internal event representation to replace DirectoryChangeEvent
    private enum EventType {
        CREATE, MODIFY, DELETE, OVERFLOW
    }
    
    private record FileChangeEvent(EventType type, Path path) {
    }
}
