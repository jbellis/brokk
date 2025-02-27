package io.github.jbellis.brokk;

import io.github.jbellis.brokk.Project.CpgRefresh;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final IConsoleIO io;
    private final Path root;
    private final ExecutorService analyzerExecutor;   // where analyzer rebuilds run
    private final BlockingQueue<DirectoryChangeEvent> eventQueue = new LinkedBlockingQueue<>();
    private final Project project;

    private volatile boolean running = true;

    private volatile Future<Analyzer> future;
    private volatile boolean rebuildInProgress = false;
    private volatile boolean externalRebuildRequested = false;
    private volatile boolean rebuildPending = false;

    /**
     * Create a new orchestrator. (We assume the analyzer executor is provided externally.)
     */
    public AnalyzerWrapper(Project project, ExecutorService analyzerExecutor) {
        this.project = project;
        this.analyzerExecutor = analyzerExecutor;
        this.io = project.getIo();
        this.root = project.getRoot();

        // build the initial Analyzer
        future = analyzerExecutor.submit(this::loadOrCreateAnalyzer);
    }

    @NotNull
    static CodeWithSource processUsages(Analyzer analyzer, List<CodeUnit> uses) {
        StringBuilder code = new StringBuilder();
        Set<CodeUnit> sources = new HashSet<>();

        // method uses
        var methodUses = uses.stream()
                .filter(CodeUnit::isFunction)
                .sorted()
                .toList();
        // type uses
        var typeUses = uses.stream()
                .filter(CodeUnit::isClass)
                .sorted()
                .toList();

        if (!methodUses.isEmpty()) {
            Map<String, List<String>> groupedMethods = new LinkedHashMap<>();
            for (var cu : methodUses) {
                var source = analyzer.getMethodSource(cu.reference());
                if (source.isDefined()) {
                    String classname = ContextFragment.toClassname(cu.reference());
                    groupedMethods.computeIfAbsent(classname, k -> new ArrayList<>()).add(source.get());
                    sources.add(cu);
                }
            }
            if (!groupedMethods.isEmpty()) {
                code.append("Method uses:\n\n");
                for (var entry : groupedMethods.entrySet()) {
                    var methods = entry.getValue();
                    if (!methods.isEmpty()) {
                        code.append("In ").append(entry.getKey()).append(":\n\n");
                        for (String ms : methods) {
                            code.append(ms).append("\n\n");
                        }
                    }
                }
            }
        }

        if (!typeUses.isEmpty()) {
            code.append("Type uses:\n\n");
            for (var cu : typeUses) {
                var skeletonHeader = analyzer.getSkeletonHeader(cu.reference());
                if (skeletonHeader.isEmpty()) {
                    continue;
                }
                code.append(skeletonHeader.get()).append("\n");
                sources.add(cu);
            }
        }

        return new CodeWithSource(code, sources);
    }

    public static List<String> combinedPageRankFor(Analyzer analyzer, HashMap<String, Double> weightedSeeds) {
        // do forward and reverse pagerank passes
        var forwardResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, false);
        var reverseResults = analyzer.getPagerank(weightedSeeds, 3 * Context.MAX_AUTO_CONTEXT_FILES, true);

        // combine results by summing scores
        var combinedScores = new HashMap<String, Double>();
        forwardResults.forEach(pair -> combinedScores.put(pair._1, pair._2));
        reverseResults.forEach(pair -> combinedScores.merge(pair._1, pair._2, Double::sum));

        // sort by combined score
        return combinedScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .filter(analyzer::isClassInProject)
            .toList();
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
        Set<Path> trackedPaths = GitRepo.instance.getTrackedFiles().stream()
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
        if (project.getCpgRefresh() == CpgRefresh.UNSET) {
            logger.debug("First startup: timing CPG creation");
            long start = System.currentTimeMillis();
            var analyzer = createAndSaveAnalyzer();
            long duration = System.currentTimeMillis() - start;
            if (duration > 5000) {
                project.setCpgRefresh(CpgRefresh.MANUAL);
                var msg = """
                CPG creation was slow (%,d ms); code intelligence will only refresh when explicitly requested via /refresh.
                (Code intelligence will still refresh once automatically at startup.)
                You can change this with the cpg_refresh parameter in .brokk/project.properties.
                """.stripIndent().formatted(duration);
                io.toolOutput(msg);
            } else {
                project.setCpgRefresh(CpgRefresh.AUTO);
                var msg = """
                CPG creation was fast (%,d ms); code intelligence will refresh automatically when changes are made to tracked files.
                You can change this with the cpg_refresh parameter in .brokk/project.properties.
                """.stripIndent().formatted(duration);
                io.toolOutput(msg);
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

    private Analyzer createAndSaveAnalyzer() {
        Analyzer newAnalyzer = new Analyzer(root);
        Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
        newAnalyzer.writeCpg(analyzerPath);
        logger.debug("Analyzer (re)build completed");
        return newAnalyzer;
    }

    /** Load a cached analyzer if it is up to date; otherwise return null. */
    private Analyzer loadCachedAnalyzer(Path analyzerPath) {
        if (!Files.exists(analyzerPath)) {
            return null;
        }

        List<RepoFile> trackedFiles = GitRepo.instance.getTrackedFiles();
        long cpgMTime;
        try {
            cpgMTime = Files.getLastModifiedTime(analyzerPath).toMillis();
        } catch (IOException e) {
            throw new RuntimeException("Error reading analyzer file timestamp", e);
        }
        long maxTrackedMTime = 0L;
        for (RepoFile rf : trackedFiles) {
            try {
                long fileMTime = Files.getLastModifiedTime(rf.absPath()).toMillis();
                maxTrackedMTime = Math.max(maxTrackedMTime, fileMTime);
            } catch (IOException e) {
                throw new RuntimeException("Error reading file timestamp", e);
            }
        }
        if (cpgMTime > maxTrackedMTime) {
            logger.debug("Using cached code intelligence data ({} > {})", cpgMTime, maxTrackedMTime);
            return new Analyzer(root, analyzerPath);
        }
        return null;
    }

    /**
     * Force a fresh rebuild of the analyzer by scheduling a job on the analyzerExecutor.
     * Avoids concurrent rebuilds by setting a flag, but if a change is detected during
     * the rebuild, a new rebuild will be scheduled immediately afterwards.
     */
    private synchronized void rebuild() {
        // If a rebuild is already running, just mark that another rebuild is pending.
        if (rebuildInProgress) {
            rebuildPending = true;
            return;
        }

        rebuildInProgress = true;
        future = analyzerExecutor.submit(() -> {
            try {
                return createAndSaveAnalyzer();
            } finally {
                synchronized (AnalyzerWrapper.this) {
                    rebuildInProgress = false;
                    // If another rebuild got requested while we were busy, immediately start a new one.
                    if (rebuildPending) {
                        rebuildPending = false;
                        logger.debug("rebuilding immediately");
                        rebuild();
                    } else {
                        externalRebuildRequested = false;
                    }
                }
            }
        });
    }

    public Analyzer get() {
        if (!future.isDone()) {
            io.spin("Analyzer is being created");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching analyzer", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to create analyzer", e);
        }
        finally {
            io.spinComplete();
        }
    }
    
    public void requestRebuild() {
        externalRebuildRequested = true;
    }
    private void startWatcher() {
        Thread watcherThread = new Thread(() -> beginWatching(root), "DirectoryWatcher");
        watcherThread.start();
    }

    public record CodeWithSource(StringBuilder code, Set<CodeUnit> sources) {
    }
}
