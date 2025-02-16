package io.github.jbellis.brokk;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.stream.Collectors;

public class AnalyzerWrapper {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private final IConsoleIO consoleIO;
    private final Path root;
    private final ExecutorService executor;

    private volatile Future<Analyzer> future;
    private DirectoryWatcher directoryWatcher;
    private CompletableFuture<Void> watcherFuture;

    // Debounce future to avoid rebuilding too frequently
    private volatile ScheduledFuture<?> debounceFuture;
    // Debounce delay in milliseconds
    private static final long DEBOUNCE_DELAY_MS = 500;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Track file events that need processing
    private final Set<DirectoryChangeEvent> pendingEvents = Collections.synchronizedSet(new HashSet<>());

    public AnalyzerWrapper(IConsoleIO consoleIO, Path root, ExecutorService executor) {
        this.consoleIO = consoleIO;
        this.root = root;
        this.executor = executor;
        this.future = loadOrCreate();
    }

    private Future<Analyzer> loadOrCreate() {
        return executor.submit(() -> {
            try {
                Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
                if (Files.exists(analyzerPath)) {
                    long cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
                    List<RepoFile> trackedFiles = ContextManager.getTrackedFiles();
                    long maxTrackedMTime = trackedFiles.parallelStream()
                            .mapToLong(file -> {
                                try {
                                    return Files.getLastModifiedTime(file.absPath()).toMillis();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .max()
                            .orElse(0L);

                    if (cpgMTime > maxTrackedMTime) {
                        logger.debug("Using cached code intelligence data ({} > {})", cpgMTime, maxTrackedMTime);
                        return new Analyzer(root, analyzerPath);
                    }
                }
                logger.debug("Rebuilding code intelligence data");
                return new Analyzer(root);
            } finally {
                initDirectoryWatcher();
            }
        });
    }

    /**
     * Create and start the DirectoryWatcher asynchronously.
     */
    private void initDirectoryWatcher() {
        try {
            directoryWatcher = DirectoryWatcher.builder()
                    .path(root) // This will watch recursively
                    .listener(event -> {
                        try {
                            handleDirectoryEvent(event);
                        } catch (IOException e) {
                            consoleIO.toolOutput("Error handling directory event: " + e.getMessage());
                        }
                    })
                    .build();

            watcherFuture = directoryWatcher.watchAsync();
        } catch (IOException e) {
            consoleIO.toolOutput("Error initializing directory watcher: " + e.getMessage());
        }
    }

    /**
     * Called whenever DirectoryWatcher detects a change event.
     */
    private void handleDirectoryEvent(DirectoryChangeEvent event) throws IOException {
        // Skip overflow events (if any)
        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
            return;
        }

        Path changedAbs = event.path();
        String changedAbsStr = changedAbs.toString();
        if (changedAbsStr.contains("${sys:logfile.path}") || changedAbs.startsWith(root.resolve(".brokk"))) {
            return;
        }

        // Add event to pending set
        logger.trace("Detected {} event on file: {}", event.eventType(), changedAbs);
        pendingEvents.add(event);

        // Debounce the rebuild
        debounceRebuild();
    }

    private void debounceRebuild() {
        // Cancel any previously scheduled rebuild
        if (debounceFuture != null && !debounceFuture.isDone()) {
            logger.trace("Canceling existing debounce future");
            debounceFuture.cancel(false);
        }
        // Schedule a new rebuild task
        logger.trace("Creating rebuild future");
        debounceFuture = scheduler.schedule(this::rebuild, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Called from external code to retrieve the Analyzer once it is ready.
     */
    public Analyzer get() {
        if (!future.isDone()) {
            consoleIO.toolOutput("Analyzer is being created; blocking until it is ready...");
        }
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to retrieve analyzer", e);
        }
    }

    /**
     * Force a rebuild of the analyzer (runs asynchronously on the Executor).
     */
    public void rebuild() {
        Set<DirectoryChangeEvent> eventsSnapshot;
        synchronized (pendingEvents) {
            if (pendingEvents.isEmpty()) {
                return;
            }
            eventsSnapshot = new HashSet<>(pendingEvents);
            pendingEvents.clear();
        }

        // Check if we need to rebuild git (changes within .git directory)
        boolean needsGitRefresh = eventsSnapshot.stream()
                .anyMatch(event -> {
                    Path gitDir = root.resolve(".git");
                    return event.path().startsWith(gitDir) &&
                            (event.eventType() == DirectoryChangeEvent.EventType.CREATE ||
                                    event.eventType() == DirectoryChangeEvent.EventType.DELETE ||
                                    event.eventType() == DirectoryChangeEvent.EventType.MODIFY);
                });
        if (needsGitRefresh) {
            logger.debug("Refreshing git due to changes in .git directory");
            GitRepo.instance.refresh();
        }

        // Get current tracked files
        Set<Path> trackedPaths = ContextManager.getTrackedFiles().stream()
                .map(RepoFile::absPath)
                .collect(Collectors.toSet());

        // Check if any of the changed files are tracked by git
        boolean needsAnalyzerRefresh = eventsSnapshot.stream()
                .anyMatch(event -> trackedPaths.contains(event.path()));

        if (needsAnalyzerRefresh) {
            logger.debug("Rebuilding analyzer due to changes in tracked files: {}",
                    eventsSnapshot.stream()
                            .filter(event -> trackedPaths.contains(event.path()))
                            .map(event -> event.path().toString())
                            .collect(Collectors.joining(", ")));

            future = executor.submit(() -> {
                Analyzer newAnalyzer = new Analyzer(root);
                synchronized (this) {
                    Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
                    newAnalyzer.writeCpg(analyzerPath);
                }
                return newAnalyzer;
            });
        } else {
            logger.debug("Skipping analyzer rebuild - no tracked files changed");
        }
    }
}
