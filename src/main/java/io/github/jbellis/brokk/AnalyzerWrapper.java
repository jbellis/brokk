package io.github.jbellis.brokk;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

public class AnalyzerWrapper {
    private final IConsoleIO consoleIO;
    private final Path root;
    private final ExecutorService executor;
    private volatile Future<Analyzer> future;

    public AnalyzerWrapper(IConsoleIO consoleIO, Path root, ExecutorService executor) {
        this.consoleIO = consoleIO;
        this.root = root;
        this.executor = executor;
        this.future = loadOrCreate();
    }

    private Future<Analyzer> loadOrCreate() {
        return executor.submit(() -> {
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
                    System.out.println("Using cached code intelligence data");
                    return new Analyzer(root, analyzerPath);
                }
            }
            System.out.println("Rebuilding code intelligence data");
            return new Analyzer(root);
        });
    }

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

    public void rebuild() {
        future = executor.submit(() -> {
            Analyzer newAnalyzer = new Analyzer(root);
            synchronized (this) {
                Path analyzerPath = root.resolve(".brokk").resolve("joern.cpg");
                newAnalyzer.writeCpg(analyzerPath);
            }
            return newAnalyzer;
        });
    }
}
