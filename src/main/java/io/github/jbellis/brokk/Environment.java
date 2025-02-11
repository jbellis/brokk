package io.github.jbellis.brokk;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
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
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Process process = new ProcessBuilder("/bin/sh", "-c", command).start();

        // Read the process output and error streams
        try (Scanner scOut = new Scanner(process.getInputStream());
             Scanner scErr = new Scanner(process.getErrorStream())) {
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

        // Wait for the process to complete and capture the exit code.
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        return new ProcessResult(exitCode, out.toString(), err.toString());
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

    public void gitCommit(String message) throws IOException {
        try {
            git.commit()
               .setMessage(message)
               .setAll(true)
               .call();
        } catch (GitAPIException e) {
            throw new IOException("Unable to commit changes: " + e.getMessage());
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

    /**
     * Returns a string containing both staged and working directory changes (git diff HEAD)
     */
    public String gitDiff() {
        try {
            StringBuilder result = new StringBuilder();
            
            // Get staged changes (HEAD vs index)
            String stagedDiff = git.diff()
                    .setShowNameAndStatusOnly(false)
                    .setCached(true)
                    .call()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            result.append(stagedDiff);
            
            // Get unstaged changes (index vs working tree)
            String unstagedDiff = git.diff()
                    .setShowNameAndStatusOnly(false)
                    .setCached(false)
                    .call()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            
            if (!stagedDiff.isEmpty() && !unstagedDiff.isEmpty()) {
                result.append("\n");
            }
            result.append(unstagedDiff);
            
            return result.toString();
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Returns a list of filenames changed in the given commit
     */
    public List<String> getCommitFiles(String commitRef) {
        try {
            var commitId = repository.resolve(commitRef);
            if (commitId == null) {
                throw new IllegalArgumentException("Invalid commit reference: " + commitRef);
            }

            try (var revWalk = new RevWalk(repository)) {
                var commit = revWalk.parseCommit(commitId);
                if (commit.getParentCount() > 0) {
                    var parent = commit.getParent(0);
                    revWalk.parseCommit(parent);

                    var diffCommand = git.diff()
                            .setOldTree(prepareTreeParser(parent))
                            .setNewTree(prepareTreeParser(commit));
                    return diffCommand.call().stream()
                                .map(DiffEntry::getNewPath)
                                .collect(Collectors.toList());
                } else {
                    // First commit - get all files
                    try (var treeWalk = new TreeWalk(repository)) {
                        treeWalk.addTree(commit.getTree());
                        treeWalk.setRecursive(true);
                        var files = new ArrayList<String>();
                        while (treeWalk.next()) {
                            files.add(treeWalk.getPathString());
                        }
                        return files;
                    }
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to get files for commit " + commitRef, e));
        }
    }

    private AbstractTreeIterator prepareTreeParser(RevCommit commit) throws IOException {
        try (var treeWalk = new TreeWalk(repository)) {
            treeWalk.addTree(commit.getTree());
            treeWalk.setRecursive(true);
            var canonicalTreeParser = new CanonicalTreeParser();
            canonicalTreeParser.reset(repository.newObjectReader(), commit.getTree());
            return canonicalTreeParser;
        }
    }

    public Path getRoot() {
        return root;
    }

    public record ProcessResult(int status, String stdout, String stderr) {}
}
