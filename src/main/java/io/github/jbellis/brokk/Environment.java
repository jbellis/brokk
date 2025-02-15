package io.github.jbellis.brokk;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import sun.misc.Signal;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

public class Environment implements Closeable {
    public static final Environment instance = new Environment();

    private static final IConsoleIO dummyIo = new IConsoleIO() {
        @Override
        public void toolOutput(String msg) {}

        @Override
        public void toolErrorRaw(String msg) {}

        @Override
        public boolean confirmAsk(String msg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void llmOutput(String token) {
            throw new UnsupportedOperationException();
        }
    };

    private final Repository repository;
    private final Git git;
    private final Path root;

    private Environment() {
        try {
            root = findGitRoot();
            if (root == null) {
                System.out.println("No git repository found");
                System.exit(1);
            }

            repository = new FileRepositoryBuilder()
                    .setGitDir(root.resolve(Path.of(".git")).toFile())
                    .build();
            git = new Git(repository);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize git repository", e);
        }
    }
    
    public void gitRefresh() {
        repository.getRefDatabase().refresh();
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }

    /**
     * Runs a shell command using /bin/sh, returning {stdout, stderr}.
     */
    public ProcessResult runShellCommand(String command, IConsoleIO io) throws IOException {
        var process = createProcessBuilder("/bin/sh", "-c", command).start();
        var sig = new Signal("INT");
        var oldHandler = Signal.handle(sig, signal -> process.destroy());

        try {
            var out = new StringBuilder();
            var err = new StringBuilder();
            try (var scOut = new Scanner(process.getInputStream());
                 var scErr = new Scanner(process.getErrorStream())) {
                while (scOut.hasNextLine()) {
                    var line = scOut.nextLine();
                    io.toolOutput(line);
                    out.append(line).append("\n");
                }
                while (scErr.hasNextLine()) {
                    var line = scErr.nextLine();
                    io.toolError(line);
                    err.append(line).append("\n");
                }
            }

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new ProcessResult(exitCode, out.toString(), err.toString());
        } finally {
            Signal.handle(sig, oldHandler);
        }
    }

    private static ProcessBuilder createProcessBuilder(String... command) {
        // Redirect input to /dev/null so interactive prompts fail fast
        var pb = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // Remove environment variables that might interfere with non-interactive operation
        pb.environment().remove("EDITOR");
        pb.environment().remove("VISUAL");
        pb.environment().put("TERM", "dumb");
        return pb;
    }

    /**
     * Run a shell command, returning stdout or stderr in an OperationResult.
     */
    public ContextManager.OperationResult captureShellCommand(String command) {
        ProcessResult result;
        try {
            result = runShellCommand(command, dummyIo);
        } catch (Exception e) {
            return ContextManager.OperationResult.error("Error executing command: " + e.getMessage());
        }

        var stdout = result.stdout().trim();
        var stderr = result.stderr().trim();
        var combinedOut = new StringBuilder();
        if (!stdout.isEmpty()) {
            if (!stderr.isEmpty()) {
                combinedOut.append("stdout: ");
            }
            combinedOut.append(stdout);
        }
        if (!stderr.isEmpty()) {
            if (!stdout.isEmpty()) {
                combinedOut.append("\n\n").append("stderr: ");
            }
            combinedOut.append(stderr);
        }
        var output = combinedOut.toString();

        if (result.status() > 0) {
            return ContextManager.OperationResult.error("`%s` returned code %d\n%s".formatted(command, result.status(), output));
        }
        return ContextManager.OperationResult.success(output.isEmpty() ? "[command completed successfully with no output]" : output);
    }

    public List<String> gitLogShort() {
        try {
            List<String> logs = new ArrayList<>();
            git.log()
               .setMaxCount(50)
               .call()
               .forEach(commit -> 
                   logs.add(commit.getName().substring(0, 7) + ":" + commit.getShortMessage()));
            return logs;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * @return the aboslute filename of the git root that cwd is part of, or null if none
     */
    public static Path findGitRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public static void createDirIfNotExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        if (!path.toFile().mkdir()) {
            throw new IOException("mkdir failed");
        }
    }

    public void gitAdd(String relName) throws IOException {
        try {
            git.add()
                    .addFilepattern(relName)
                    .call();
        } catch (GitAPIException e) {
            throw new IOException("Unable to add file %s to git %s".formatted(relName, e.getMessage()));
        }
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository,
     * including unchanged files from HEAD and any files with staged or unstaged modifications
     * (changed, modified, added, removed) from the working directory.
     */
    public List<RepoFile> getGitTrackedFiles() {
        Path gitRoot = findGitRoot();
        assert gitRoot != null;
        var trackedPaths = new HashSet<String>();
        try {
            // Walk the HEAD tree to capture all files in the last commit (unchanged tracked files)
            var headTreeId = repository.resolve("HEAD^{tree}");
            if (headTreeId != null) {
                try (var revWalk = new RevWalk(repository);
                     var treeWalk = new TreeWalk(repository))
                {
                    var headTree = revWalk.parseTree(headTreeId);
                    treeWalk.addTree(headTree);
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        trackedPaths.add(treeWalk.getPathString());
                    }
                }
            }
            // Add files that are in the working directory (or staged) with changes
            var status = git.status().call();
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
        return trackedPaths.stream()
                .map(path -> new RepoFile(gitRoot, path))
                .collect(Collectors.toList());
    }

    public String gitDiff() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Gather tracked files only
            var status = git.status().call();
            var trackedPaths = new HashSet<String>();
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
            trackedPaths.addAll(status.getMissing());

            // Convert the tracked path strings into a TreeFilter
            var filters = new ArrayList<PathFilter>();
            for (String path : trackedPaths) {
                filters.add(PathFilter.create(path));
            }
            var filterGroup = PathFilterGroup.create(filters);

            // 1) Staged changes (HEAD vs index)
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();

            String staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) Unstaged changes (index vs working tree)
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();

            String unstaged = out.toString(StandardCharsets.UTF_8);

            // Combine with a blank line in between if both are non-empty
            if (!staged.isEmpty() && !unstaged.isEmpty()) {
                return staged + "\n" + unstaged;
            }
            return staged + unstaged;

        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }


    public Path getRoot() {
        return root;
    }

    public record ProcessResult(int status, String stdout, String stderr) {}
}
