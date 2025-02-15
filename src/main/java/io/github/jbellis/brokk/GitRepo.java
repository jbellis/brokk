package io.github.jbellis.brokk;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class GitRepo implements Closeable {
    public static final GitRepo instance = new GitRepo();

    private final Path root;
    private final Repository repository;
    private final Git git;

    private GitRepo() {
        // Moved "findGitRoot" logic here
        this.root = findGitRoot();
        if (root == null) {
            throw new IllegalStateException("No git repository found");
        }
        try {
            repository = new FileRepositoryBuilder()
                    .setGitDir(root.resolve(".git").toFile())
                    .build();
            git = new Git(repository);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open repository", e);
        }
    }

    /**
     * The single place for locating .git upward from current directory.
     */
    private static Path findGitRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    public Path getRoot() {
        return root;
    }

    public synchronized void refresh() {
        repository.getRefDatabase().refresh();
    }

    public synchronized List<String> logShort() {
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

    public synchronized void add(String relName) throws IOException {
        try {
            git.add().addFilepattern(relName).call();
        } catch (GitAPIException e) {
            throw new IOException("Unable to add file %s to git: %s".formatted(relName, e.getMessage()));
        }
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository,
     * including unchanged files from HEAD and any files with staged or unstaged modifications
     * (changed, modified, added, removed) from the working directory.
     */
    public synchronized List<RepoFile> getTrackedFiles() {
        var trackedPaths = new HashSet<String>();
        try {
            // HEAD (unchanged) files
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
            // Staged/modified/added/removed
            var status = git.status().call();
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
        return trackedPaths.stream()
                .map(path -> new RepoFile(root, path))
                .collect(Collectors.toList());
    }

    public synchronized String diff() {
        try (var out = new ByteArrayOutputStream()) {
            var status = git.status().call();
            var trackedPaths = new HashSet<String>();
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
            trackedPaths.addAll(status.getMissing());

            var filters = new ArrayList<PathFilter>();
            for (String path : trackedPaths) {
                filters.add(PathFilter.create(path));
            }
            var filterGroup = PathFilterGroup.create(filters);

            // 1) staged changes
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) unstaged changes
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String unstaged = out.toString(StandardCharsets.UTF_8);

            if (!staged.isEmpty() && !unstaged.isEmpty()) {
                return staged + "\n" + unstaged;
            }
            return staged + unstaged;
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }
}
