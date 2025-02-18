package io.github.jbellis.brokk;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AnalyzerWrapper {
    private final Logger logger = LogManager.getLogger(AnalyzerWrapper.class);

    private static final long DEBOUNCE_DELAY_MS = 500;

    private final IConsoleIO consoleIO;
    private final Path root;
    private final ExecutorService analyzerExecutor;   // where analyzer rebuilds run
    private final BlockingQueue<DirectoryChangeEvent> eventQueue = new LinkedBlockingQueue<>();

    private volatile boolean running = true;

    private volatile Future<Analyzer> future;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false;

    /**
     * Create a new orchestrator. (We assume the analyzer executor is provided externally.)
     */
    public AnalyzerWrapper(IConsoleIO consoleIO, Path root, ExecutorService analyzerExecutor) {
        this.consoleIO = consoleIO;
        this.root = root;
        this.analyzerExecutor = analyzerExecutor;

        // build the initial Analyzer
        future = analyzerExecutor.submit(this::loadOrCreateAnalyzer);

        var watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher");
        watcherThread.start();
    }

    private void beginWatching(Path root) {
        try {
            future.get(); // Wait for the initial analyzer to be built
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        logger.debug("Setting up directoryWatcher");
        DirectoryWatcher directoryWatcher;
        try {
            directoryWatcher = DirectoryWatcher.builder()
                    .path(root)
                    .listener(event -> {
                        // Check for overflow
                        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
                            return;
                        }

                        // Filter out changes in .brokk or the log file (also in .brokk but doesn't show that way in the events)
                        Path changedAbs = event.path();
                        String changedAbsStr = changedAbs.toString();
                        if (changedAbsStr.contains("${sys:logfile.path}")
                                || changedAbs.startsWith(root.resolve(".brokk"))) {
                            return;
                        }

                        logger.trace("Directory event: {} on {}", event.eventType(), changedAbs);
                        boolean offered = eventQueue.offer(event);
                        assert offered;
                    })
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // start watching!
        directoryWatcher.watchAsync();
        eventLoop();
    }

    /**
     * The single-threaded loop that drains the eventQueue, applies debouncing,
     * and triggers rebuild when needed.
     */
    private void eventLoop() {
        while (running) {
            try {
                // Wait for the first event (with a timeout to let the loop exit if idle)
                DirectoryChangeEvent firstEvent = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (firstEvent == null) {
                    if (externalRebuildRequested && !rebuildInProgress) {
                        logger.debug("External rebuild requested");
                        rebuild();
                    }
                    continue; // Nothing arrived within 1s; loop again
                }

                // Once we get an event, gather more events arriving during the debounce window.
                Set<DirectoryChangeEvent> batch = new HashSet<>();
                batch.add(firstEvent);
                long deadline = System.currentTimeMillis() + DEBOUNCE_DELAY_MS;

                while (true) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    DirectoryChangeEvent next = eventQueue.poll(remaining, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        break;
                    }
                    batch.add(next);
                }

                // We have a batch of events that arrived within DEBOUNCE_DELAY_MS
                handleBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Orchestrator thread interrupted; shutting down");
                running = false;
            }
        }
    }

    /**
     * Check if changes in this batch of events require a .git refresh and/or analyzer rebuild.
     */
    private void handleBatch(Set<DirectoryChangeEvent> batch) {
        // 1) Possibly refresh Git
        boolean needsGitRefresh = batch.stream().anyMatch(event -> {
            Path gitDir = root.resolve(".git");
            return event.path().startsWith(gitDir)
                    && (event.eventType() == DirectoryChangeEvent.EventType.CREATE
                    || event.eventType() == DirectoryChangeEvent.EventType.DELETE
                    || event.eventType() == DirectoryChangeEvent.EventType.MODIFY);
        });
        if (needsGitRefresh) {
            logger.debug("Refreshing git due to changes in .git directory");
            GitRepo.instance.refresh();
        }

        // 2) Check if any *tracked* files changed
        Set<Path> trackedPaths = ContextManager.getTrackedFiles().stream()
                .map(RepoFile::absPath)
                .collect(Collectors.toSet());

        boolean needsAnalyzerRefresh = batch.stream()
                .anyMatch(event -> trackedPaths.contains(event.path()));

        if (needsAnalyzerRefresh) {
            logger.debug("Rebuilding analyzer due to changes in tracked files: {}",
                         batch.stream()
                                 .filter(e -> trackedPaths.contains(e.path()))
                                 .map(e -> e.path().toString())
                                 .collect(Collectors.joining(", "))
            );
            rebuild();
        } else {
            logger.debug("No tracked files changed; skipping analyzer rebuild");
        }
    }

    /**
     * Synchronously load or create an Analyzer:
     *   1) If the .brokk/joern.cpg file is up to date, reuse it;
     *   2) Otherwise, rebuild a fresh Analyzer.
     */
    private Analyzer loadOrCreateAnalyzer() {
        logger.debug("Loading/creating analyzer");
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        Set<String> newerFiles = new HashSet<>();
        
        if (!Files.exists(analyzerPath)) {
            logger.debug("Rebuilding code intelligence data (cache unavailable)");
            return createAndSaveAnalyzer();
        }

        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            throw new RuntimeException("Error reading analyzer file timestamp", e);
        }

        List<RepoFile> trackedFiles = ContextManager.getTrackedFiles();
        long maxTrackedMTime = 0L;
        try {
            for (RepoFile rf : trackedFiles) {
                Path p = rf.absPath();
                FileTime fTime = Files.getLastModifiedTime(p);
                long fileMTime = fTime.toMillis();
                if (fileMTime > cpgMTime) {
                    newerFiles.add(p.toString());
                }
                maxTrackedMTime = Math.max(maxTrackedMTime, fileMTime);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading file timestamp", e);
        }

        if (cpgMTime > maxTrackedMTime) {
            logger.debug("Using cached code intelligence data ({} > {})", cpgMTime, maxTrackedMTime);
            return new Analyzer(root, analyzerPath);
        }

        assert !newerFiles.isEmpty();
        logger.debug("Rebuilding code intelligence data. Files newer than cache: {}",
                   String.join(", ", newerFiles));
        return createAndSaveAnalyzer();
    }

    private Analyzer createAndSaveAnalyzer() {
        Analyzer newAnalyzer = new Analyzer(root);
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        newAnalyzer.writeCpg(analyzerPath);
        logger.debug("Analyzer (re)build completed");
        return newAnalyzer;
    }

    /**
     * Force a fresh rebuild of the analyzer by scheduling a job on the analyzerExecutor.
     */
    private void rebuild() {
        rebuildInProgress = true;
        future = analyzerExecutor.submit(() -> {
            try {
                return createAndSaveAnalyzer();
            } finally {
                rebuildInProgress = false;
                externalRebuildRequested = false;
            }
        });
    }

    public Analyzer get() {
        if (!future.isDone()) {
            consoleIO.toolOutput("Analyzer is being created; blocking until it is ready...");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
    }
    
    public void requestRebuild() {
        externalRebuildRequested = true;
    }
}
