package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.StashApplyFailureException;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Git repository abstraction using JGit
 */
public class GitRepo implements Closeable, IGitRepo {
    private static final Logger logger = LogManager.getLogger(GitRepo.class);

    private final Path root;
    private final Repository repository;
    private final Git git;
    private Set<ProjectFile> trackedFilesCache = null;

    /**
     * Returns true if the directory has a .git folder.
     */
    public static boolean hasGitRepo(Path dir) {
        assert dir != null;
        return dir.resolve(".git").toFile().isDirectory();
    }

    /**
     * Get the JGit instance for direct API access
     */
    public Git getGit() {
        return git;
    }

    public GitRepo(Path root) {
        this.root = root;
        if (root == null) {
            throw new IllegalStateException("No git repository found");
        }
        try {
            repository = new FileRepositoryBuilder()
                    .setGitDir(root.resolve(".git").toFile())
                    .build();
            git = new Git(repository);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open repository", e);
        }
    }

    @Override
    public synchronized void refresh() {
        logger.debug("Refreshing git repository");
        repository.getRefDatabase().refresh();
        trackedFilesCache = null;
    }

    /**
     * Adds files to staging.
     */
    @Override
    public synchronized void add(List<ProjectFile> files) throws GitAPIException {
        var addCommand = git.add();
        for (var file : files) {
            addCommand.addFilepattern(toGitPath(file.toString()));
        }
        addCommand.call();
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository.
     */
    @Override
    public synchronized Set<ProjectFile> getTrackedFiles() {
        if (trackedFilesCache != null) {
            return trackedFilesCache;
        }
        var trackedPaths = new HashSet<String>();
        try {
            // HEAD (unchanged) files
            var headTreeId = resolve("HEAD^{tree}");
            if (headTreeId != null) {
                try (var revWalk = new RevWalk(repository);
                     var treeWalk = new TreeWalk(repository)) {
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
            logger.error("getTrackedFiles failed", e);
            // not really much caller can do about this, it's a critical method
            throw new RuntimeException(e);
        }
        trackedFilesCache = trackedPaths.stream()
                .map(path -> new ProjectFile(root, path))
                .collect(Collectors.toSet());
        return trackedFilesCache;
    }

    /**
     * Get the current commit ID (HEAD)
     */
    public String getCurrentCommitId() throws GitAPIException {
        var head = resolve("HEAD");
        return head != null ? head.getName() : "";
    }

    /**
     * Produces a combined diff of staged + unstaged changes, restricted to the given files.
     */
    public synchronized String diffFiles(List<ProjectFile> files) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            var filters = files.stream()
                    .map(file -> PathFilter.create(file.toString()))
                    .collect(Collectors.toCollection(ArrayList::new));
            var filterGroup = PathFilterGroup.create(filters);

            // 1) staged changes
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            var staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) unstaged changes
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            var unstaged = out.toString(StandardCharsets.UTF_8);

            return Stream.of(staged, unstaged)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized String diff() throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            var status = git.status().call();
            var trackedPaths = new HashSet<String>();
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
            trackedPaths.addAll(status.getMissing());

            if (trackedPaths.isEmpty()) {
                return "";
            }

            var filters = trackedPaths.stream()
                    .map(PathFilter::create)
                    .collect(Collectors.toCollection(ArrayList::new));
            var filterGroup = PathFilterGroup.create(filters);

            // 1) staged changes
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            var staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) unstaged changes
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            var unstaged = out.toString(StandardCharsets.UTF_8);

            return Stream.of(staged, unstaged)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns a list of uncommitted RepoFiles (including added/removed/missing).
     */
    public List<ProjectFile> getModifiedFiles() throws GitAPIException {
        var status = git.status().call();
        var filePaths = new HashSet<String>();
        filePaths.addAll(status.getModified());
        filePaths.addAll(status.getChanged());
        filePaths.addAll(status.getAdded());
        filePaths.addAll(status.getRemoved());
        filePaths.addAll(status.getMissing());

        return filePaths.stream()
                .map(path -> new ProjectFile(root, path))
                .collect(Collectors.toList());
    }

    /**
     * Commit a specific list of RepoFiles.
     * @return The commit ID of the new commit
     */
    public String commitFiles(List<ProjectFile> files, String message) throws GitAPIException {
        add(files);
        var commitCommand = git.commit().setMessage(message);

        if (!files.isEmpty()) {
            for (var file : files) {
                commitCommand.setOnly(toGitPath(file.toString()));
            }
        }

        var commitResult = commitCommand.call();
        var commitId = commitResult.getId().getName();
        refresh();
        return commitId;
    }

    /**
     * Push the committed changes to the remote repository
     */
    public void push() throws GitAPIException {
        git.push().call();
    }

    /**
     * Get a set of commit IDs that exist in the local branch but not in its remote tracking branch
     */
    public Set<String> getUnpushedCommitIds(String branchName) throws GitAPIException {
        var unpushedCommits = new HashSet<String>();
        var trackingBranch = getTrackingBranch(branchName);
        if (trackingBranch == null) {
            return unpushedCommits;
        }

        var branchRef = "refs/heads/" + branchName;
        var trackingRef = "refs/remotes/" + trackingBranch;

        var localObjectId = resolve(branchRef);
        var remoteObjectId = resolve(trackingRef);

        if (localObjectId == null || remoteObjectId == null) {
            return unpushedCommits;
        }

        try (var revWalk = new RevWalk(repository)) {
            try {
                revWalk.markStart(revWalk.parseCommit(localObjectId));
                revWalk.markUninteresting(revWalk.parseCommit(remoteObjectId));
            } catch (IOException e) {
                throw new GitWrappedIOException(e);
            }

            revWalk.forEach(commit -> unpushedCommits.add(commit.getId().getName()));
        }
        return unpushedCommits;
    }

    /**
     * Check if a local branch has a configured upstream (tracking) branch
     */
    public boolean hasUpstreamBranch(String branchName) {
        return getTrackingBranch(branchName) != null;
    }

    /**
     * Get the tracking branch name for a local branch
     */
    private String getTrackingBranch(String branchName) {
        try {
            var config = repository.getConfig();
            var trackingBranch = config.getString("branch", branchName, "remote");
            var remoteBranch = config.getString("branch", branchName, "merge");

            if (trackingBranch != null && remoteBranch != null) {
                if (remoteBranch.startsWith("refs/heads/")) {
                    remoteBranch = remoteBranch.substring("refs/heads/".length());
                }
                return trackingBranch + "/" + remoteBranch;
            }
            return null;
        } catch (Exception e) {
            // Return null if there's any unexpected config or parse issue
            return null;
        }
    }

    /**
     * List all local branches
     */
    public List<String> listLocalBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref : git.branchList().call()) {
            branches.add(ref.getName().replaceFirst("^refs/heads/", ""));
        }
        return branches;
    }

    /**
     * List all remote branches
     */
    public List<String> listRemoteBranches() throws GitAPIException {
        var branches = new ArrayList<String>();
        for (var ref : git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()) {
            branches.add(ref.getName().replaceFirst("^refs/remotes/", ""));
        }
        return branches;
    }

    /**
     * Checkout a specific branch
     */
    public void checkout(String branchName) throws GitAPIException {
        git.checkout().setName(branchName).call();
        refresh();
    }

    /**
     * Checkout a remote branch, creating a local tracking branch
     * with the default naming convention (using the remote branch name)
     */
    public void checkoutRemoteBranch(String remoteBranchName) throws GitAPIException {
        String branchName;
        if (remoteBranchName.contains("/")) {
            branchName = remoteBranchName.substring(remoteBranchName.indexOf('/') + 1);
        } else {
            branchName = remoteBranchName;
        }
        checkoutRemoteBranch(remoteBranchName, branchName);
    }

    public static class GitStateException extends GitAPIException {
        protected GitStateException(String message) {
            super(message);
        }
    }

    /**
     * Create a new branch from an existing one and check it out
     */
    public void createAndCheckoutBranch(String newBranchName, String sourceBranchName) throws GitAPIException {
        if (listLocalBranches().contains(newBranchName)) {
            throw new GitStateException("Branch '" + newBranchName + "' already exists");
        }

        logger.debug("Creating new branch '{}' from '{}'", newBranchName, sourceBranchName);
        git.checkout()
                .setCreateBranch(true)
                .setName(newBranchName)
                .setStartPoint(sourceBranchName)
                .call();
        logger.debug("Successfully created and checked out branch '{}'", newBranchName);

        refresh();
    }

    /**
     * Checkout a remote branch, creating a local tracking branch with a specified name
     */
    public void checkoutRemoteBranch(String remoteBranchName, String localBranchName) throws GitAPIException {
        boolean remoteBranchExists = false;
        try {
            var remoteRef = repository.findRef("refs/remotes/" + remoteBranchName);
            remoteBranchExists = (remoteRef != null);
            logger.debug("Checking if remote branch exists: {} -> {}", remoteBranchName,
                         remoteBranchExists ? "yes" : "no");
        } catch (Exception e) {
            logger.warn("Error checking remote branch: {}", e.getMessage());
        }

        if (!remoteBranchExists) {
            throw new GitStateException("Remote branch '" + remoteBranchName + "' not found. Ensure the remote exists and has been fetched.");
        }

        if (listLocalBranches().contains(localBranchName)) {
            throw new GitStateException("Local branch '" + localBranchName + "' already exists. Choose a different name.");
        }

        logger.debug("Creating local branch '{}' from remote '{}'", localBranchName, remoteBranchName);
        git.checkout()
                .setCreateBranch(true)
                .setName(localBranchName)
                .setStartPoint(remoteBranchName)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
        logger.debug("Successfully created and checked out branch '{}'", localBranchName);

        refresh();
    }

    /**
     * Rename a branch
     */
    public void renameBranch(String oldName, String newName) throws GitAPIException {
        git.branchRename().setOldName(oldName).setNewName(newName).call();
        refresh();
    }

    /**
     * Check if a branch is fully merged into HEAD
     */
    public boolean isBranchMerged(String branchName) throws GitAPIException {
        var mergedBranches = git.branchList()
                .setListMode(ListBranchCommand.ListMode.ALL)
                .setContains("HEAD")
                .call();
        for (var ref : mergedBranches) {
            var name = ref.getName().replaceFirst("^refs/heads/", "");
            if (name.equals(branchName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Delete a branch
     */
    public void deleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to delete branch: {}", branchName);
        var result = git.branchDelete().setBranchNames(branchName).call();
        if (result.isEmpty()) {
            logger.warn("Branch deletion returned empty result for branch: {}", branchName);
            throw new GitStateException("Branch deletion failed: No results returned. Branch may not exist or requires force delete.");
        }
        for (var deletedRef : result) {
            logger.debug("Successfully deleted branch reference: {}", deletedRef);
        }
    }

    /**
     * Force delete a branch even if it's not fully merged
     */
    public void forceDeleteBranch(String branchName) throws GitAPIException {
        logger.debug("Attempting to force delete branch: {}", branchName);
        var result = git.branchDelete().setBranchNames(branchName).setForce(true).call();
        if (result.isEmpty()) {
            logger.warn("Force branch deletion returned empty result for branch: {}", branchName);
            throw new GitStateException("Force branch deletion failed: No results returned. Branch may not exist.");
        }
        for (var deletedRef : result) {
            logger.debug("Successfully force deleted branch reference: {}", deletedRef);
        }
    }

    public static class GitRepoException extends GitAPIException {
        protected GitRepoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Merge a branch into HEAD
     */
    public void mergeIntoHead(String branchName) throws GitAPIException {
        git.merge().include(resolve(branchName)).call();
        refresh();
    }

    /**
     * Revert a specific commit
     */
    public void revertCommit(String commitId) throws GitAPIException {
        try {
            git.revert().include(repository.resolve(commitId)).call();
        } catch (IOException e) {
            throw new GitRepoException("Unable to resolve" + commitId, e);
        }
        refresh();
    }

    /**
     * Perform a soft reset to a specific commit
     */
    public void softReset(String commitId) throws GitAPIException {
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                .setRef(commitId)
                .call();
        refresh();
    }

    /**
     * Get current branch name
     */
    public String getCurrentBranch() throws GitAPIException {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * A record to hold commit details
     */
    public record CommitInfo(String id, String message, String author, Date date) {}

    /**
     * List commits with detailed information for a specific branch
     */
    public List<CommitInfo> listCommitsDetailed(String branchName) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        var logCommand = git.log();

        if (branchName != null && !branchName.isEmpty()) {
            try {
                logCommand.add(resolve(branchName));
            } catch (MissingObjectException | IncorrectObjectTypeException e) {
                throw new GitWrappedIOException(e);
            }
        }

        for (var commit : logCommand.call()) {
            var id = commit.getName();
            var message = commit.getShortMessage();
            var author = commit.getAuthorIdent().getName();
            var date = commit.getAuthorIdent().getWhen();
            commits.add(new CommitInfo(id, message, author, date));
        }
        return commits;
    }

    /**
     * List changed RepoFiles in a commit range.
     */
    public List<ProjectFile> listChangedFilesInCommitRange(String firstCommitId, String lastCommitId) throws GitAPIException {
        var firstCommitObj = resolve(firstCommitId);
        var lastCommitObj = resolve(lastCommitId + "^");
        try (var revWalk = new RevWalk(repository)) {
            var firstCommit = revWalk.parseCommit(firstCommitObj);
            var lastCommit = revWalk.parseCommit(lastCommitObj);
            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(lastCommit.getTree(), firstCommit.getTree());
                var fileSet = new HashSet<String>();
                for (var diff : diffs) {
                    if (diff.getNewPath() != null && !"/dev/null".equals(diff.getNewPath())) {
                        fileSet.add(diff.getNewPath());
                    }
                    if (diff.getOldPath() != null && !"/dev/null".equals(diff.getOldPath())) {
                        fileSet.add(diff.getOldPath());
                    }
                }
                return fileSet.stream()
                        .map(path -> new ProjectFile(root, path))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Show diff between two commits (or a commit and the working directory if newCommitId == HEAD).
     */
    public String showDiff(String newCommitId, String oldCommitId) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            logger.debug("Generating diff from {} to {}", oldCommitId, newCommitId);

            var oldTreeIter = prepareTreeParser(oldCommitId);
            if (oldTreeIter == null) {
                logger.warn("Old commit/tree {} not found. Returning empty diff.", oldCommitId);
                return "";
            }

            if ("HEAD".equals(newCommitId)) {
                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(null) // Working tree
                        .setOutputStream(out)
                        .call();
            } else {
                var newTreeIter = prepareTreeParser(newCommitId);
                if (newTreeIter == null) {
                    logger.warn("New commit/tree {} not found. Returning empty diff.", newCommitId);
                    return "";
                }

                git.diff()
                        .setOldTree(oldTreeIter)
                        .setNewTree(newTreeIter)
                        .setOutputStream(out)
                        .call();
            }

            var result = out.toString(StandardCharsets.UTF_8);
            logger.debug("Generated diff of {} bytes", result.length());
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Retrieves the contents of {@code file} at a given commit ID, or returns an empty string if not found.
     */
    public String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        if (commitId == null || commitId.isBlank()) {
            logger.debug("getFileContent called with blank commitId; returning empty string");
            return "";
        }

        var objId = resolve(commitId);
        if (objId == null) {
            logger.debug("Could not resolve commitId '{}' to an object; returning empty string", commitId);
            return "";
        }

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var tree = commit.getTree();
            try (var treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    if (treeWalk.getPathString().equals(toGitPath(file.toString()))) {
                        var blobId = treeWalk.getObjectId(0);
                        var loader = repository.open(blobId);
                        return new String(loader.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
        logger.debug("File '{}' not found at commit '{}'", file, commitId);
        return "";
    }

    @Override
    public ObjectId resolve(String revstr) throws GitAPIException {
        try {
            return repository.resolve(revstr);
        } catch (IOException e) {
            throw new GitRepoException("Unable to resolve " + revstr, e);
        }
    }

    /**
     * Show diff for a specific file between two commits.
     */
    public String showFileDiff(String commitIdA, String commitIdB, ProjectFile file) throws GitAPIException {
        try (var out = new ByteArrayOutputStream()) {
            var pathFilter = PathFilter.create(toGitPath(file.toString()));
            if ("HEAD".equals(commitIdA)) {
                git.diff()
                        .setOldTree(prepareTreeParser(commitIdB))
                        .setNewTree(null) // Working tree
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            } else {
                git.diff()
                        .setOldTree(prepareTreeParser(commitIdB))
                        .setNewTree(prepareTreeParser(commitIdA))
                        .setPathFilter(pathFilter)
                        .setOutputStream(out)
                        .call();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Prepares an AbstractTreeIterator for the given commit-ish string.
     */
    private CanonicalTreeParser prepareTreeParser(String objectId) throws GitAPIException {
        if (objectId == null || objectId.isBlank()) {
            logger.warn("prepareTreeParser called with blank ref. Returning null iterator.");
            return null;
        }

        var objId = resolve(objectId);
        if (objId == null) {
            logger.warn("Could not resolve ref: {}. Returning null iterator.", objectId);
            return null;
        }

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var treeId = commit.getTree().getId();
            try (var reader = repository.newObjectReader()) {
                return new CanonicalTreeParser(null, reader, treeId);
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }
    }

    /**
     * Create a stash from the current changes
     */
    public void createStash(String message) throws GitAPIException {
        assert message != null && !message.isEmpty();
        logger.debug("Creating stash with message: {}", message);
        var stashId = git.stashCreate()
                .setWorkingDirectoryMessage(message)
                .call();
        logger.debug("Stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));
        refresh();
    }

    /**
     * Create a stash containing only the specified files.
     */
    public void createPartialStash(String message, List<ProjectFile> filesToStash) throws GitAPIException {
        assert message != null && !message.isEmpty();
        assert filesToStash != null && !filesToStash.isEmpty();

        logger.debug("Creating partial stash with message: {} for {} files", message, filesToStash.size());

        var allUncommittedFiles = getModifiedFiles();
        if (!new HashSet<>(allUncommittedFiles).containsAll(filesToStash)) {
            throw new GitStateException("Files to stash are not actually uncommitted!?");
        }

        Set<String> filesToStashPaths = filesToStash.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.toSet());

        // Files NOT to stash
        var filesToCommit = allUncommittedFiles.stream()
                .filter(file -> !filesToStashPaths.contains(file.toString()))
                .collect(Collectors.toList());

        if (filesToCommit.isEmpty()) {
            // If all changed files are selected for stashing, just do a regular stash
            logger.debug("All changed files selected for stashing, using regular stash");
            createStash(message);
            return;
        }

        // Remember the original branch
        String originalBranch = getCurrentBranch();
        String tempBranchName = "temp-stash-branch-" + System.currentTimeMillis();

        // Add the UN-selected files to the index
        add(filesToCommit);

        // Create a temporary branch and commit those files
        logger.debug("Creating temporary branch: {}", tempBranchName);
        git.branchCreate().setName(tempBranchName).call();

        logger.debug("Committing UN-selected files to temporary branch");
        git.commit()
                .setMessage("Temporary commit to facilitate partial stash")
                .call();

        // Now stash the remaining changes
        logger.debug("Creating stash with only the selected files");
        var stashId = git.stashCreate().setWorkingDirectoryMessage(message).call();
        logger.debug("Partial stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));

        // Soft reset to restore uncommitted files
        logger.debug("Soft resetting to restore UN-selected files as uncommitted");
        git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
                .setRef("HEAD~1")
                .call();

        // Checkout the original branch
        logger.debug("Checking out original branch: {}", originalBranch);
        git.checkout().setName(originalBranch).call();

        // Delete the temporary branch
        logger.debug("Deleting temporary branch");
        git.branchDelete().setBranchNames(tempBranchName).setForce(true).call();

        refresh();
    }

    /**
     * A record to hold stash details
     */
    public record StashInfo(String id, String message, String author, Date date, int index) {}

    /**
     * Lists all stashes in the repository
     */
    public List<StashInfo> listStashes() throws GitAPIException {
        var stashes = new ArrayList<StashInfo>();
        var collection = git.stashList().call();
        int index = 0;

        for (var stash : collection) {
            var id = stash.getName();
            var message = stash.getShortMessage();
            if (message.startsWith("WIP on ")) {
                message = message.substring(7);
            } else if (message.startsWith("On ")) {
                var colonPos = message.indexOf(':', 3);
                if (colonPos > 3) {
                    message = message.substring(colonPos + 2);
                }
            }
            var author = stash.getAuthorIdent().getName();
            var date = stash.getAuthorIdent().getWhen();
            stashes.add(new StashInfo(id, message, author, date, index));
            index++;
        }
        return stashes;
    }

    /**
     * Gets additional commits from a stash (index, untracked files)
     */
    public Map<String, CommitInfo> listAdditionalStashCommits(String stashRef) throws GitAPIException {
        Map<String, CommitInfo> additionalCommits = new HashMap<>();
        var rev = resolve(stashRef);

        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(rev);

            // stash@{0} - main stash commit (merge).
            // stash@{0}^1 - original HEAD
            // stash@{0}^2 - index changes
            // stash@{0}^3 - untracked changes (only if stash was created with -u / -a)

            if (commit.getParentCount() < 2) {
                logger.warn("Stash {} is not a merge commit, which is unexpected", stashRef);
                return additionalCommits;
            }

            var headCommit = commit.getParent(0);
            var indexCommit = commit.getParent(1);
            revWalk.parseHeaders(headCommit);
            revWalk.parseHeaders(indexCommit);

            // Compare HEAD tree vs index tree
            try (var diffFormatter = new DiffFormatter(new ByteArrayOutputStream())) {
                diffFormatter.setRepository(repository);
                var diffs = diffFormatter.scan(headCommit.getTree(), indexCommit.getTree());
                if (!diffs.isEmpty()) {
                    additionalCommits.put("index", new CommitInfo(
                            indexCommit.getName(),
                            "Index (staged) changes in " + stashRef,
                            indexCommit.getAuthorIdent().getName(),
                            indexCommit.getAuthorIdent().getWhen()
                    ));
                }
            }

            // Check for untracked commit
            if (commit.getParentCount() > 2) {
                var untrackedCommit = commit.getParent(2);
                revWalk.parseHeaders(untrackedCommit);
                boolean hasFiles = false;
                try (var treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(untrackedCommit.getTree());
                    treeWalk.setRecursive(true);
                    hasFiles = treeWalk.next();
                }
                if (hasFiles) {
                    additionalCommits.put("untracked", new CommitInfo(
                            untrackedCommit.getName(),
                            "Untracked files in " + stashRef,
                            untrackedCommit.getAuthorIdent().getName(),
                            untrackedCommit.getAuthorIdent().getWhen()
                    ));
                }
            }
        } catch (IOException e) {
            throw new GitWrappedIOException(e);
        }

        return additionalCommits;
    }

    /**
     * Apply a stash to the working directory without removing it from the stash list
     */
    public void applyStash(int stashIndex) throws GitAPIException {
        String stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Applying stash: {}", stashRef);
        git.stashApply()
                .setStashRef(stashRef)
                .call();
        logger.debug("Stash applied successfully");
        refresh();
    }

    /**
     * Pop a stash – apply it, then drop it
     */
    public void popStash(int stashIndex) throws GitAPIException {
        var stashRef = "stash@{" + stashIndex + "}";
        logger.debug("Popping stash {}", stashRef);

        // Try applying stash with index
        try {
            applyStash(stashRef, true);
        } catch (StashApplyFailureException ex) {
            logger.info("Stash apply with index failed ({}). Retrying without index …", ex.getMessage());
            applyStash(stashRef, false);
        }

        // Drop
        git.stashDrop().setStashRef(stashIndex).call();
        logger.debug("Stash pop completed successfully");
        refresh();
    }

    /**
     * Helper used by popStash(int)
     */
    private void applyStash(String stashRef, boolean restoreIndex) throws GitAPIException {
        git.stashApply()
                .setStashRef(stashRef)
                .setRestoreIndex(restoreIndex)
                .setRestoreUntracked(true)
                .ignoreRepositoryState(true)
                .call();
    }

    /**
     * Drop a stash without applying it
     */
    public void dropStash(int stashIndex) throws GitAPIException {
        logger.debug("Dropping stash at index: {}", stashIndex);
        git.stashDrop()
                .setStashRef(stashIndex)
                .call();
        logger.debug("Stash dropped successfully");
        refresh();
    }

    /**
     * Get the commit history for a specific file
     */
    public List<CommitInfo> getFileHistory(ProjectFile file) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        for (var commit : git.log().addPath(toGitPath(file.toString())).call()) {
            var id = commit.getName();
            var message = commit.getShortMessage();
            var author = commit.getAuthorIdent().getName();
            var date = commit.getAuthorIdent().getWhen();
            commits.add(new CommitInfo(id, message, author, date));
        }
        return commits;
    }

    /**
     * Get the URL of the specified remote (defaults to "origin")
     */
    public String getRemoteUrl(String remoteName) {
        try {
            var config = repository.getConfig();
            return config.getString("remote", remoteName, "url");
        } catch (Exception e) {
            logger.warn("Failed to get remote URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the URL of the origin remote
     */
    public String getRemoteUrl() {
        return getRemoteUrl("origin");
    }

    /**
     * Search commits by text in message/author/email
     */
    public List<CommitInfo> searchCommits(String query) throws GitAPIException {
        var commits = new ArrayList<CommitInfo>();
        var lowerQuery = query.toLowerCase();

        for (var commit : git.log().call()) {
            var cMessage = commit.getFullMessage().toLowerCase();
            var cAuthorName = commit.getAuthorIdent().getName().toLowerCase();
            var cAuthorEmail = commit.getAuthorIdent().getEmailAddress().toLowerCase();

            if (cMessage.contains(lowerQuery)
                    || cAuthorName.contains(lowerQuery)
                    || cAuthorEmail.contains(lowerQuery))
            {
                var id = commit.getName();
                var message = commit.getShortMessage();
                var author = commit.getAuthorIdent().getName();
                var date = commit.getAuthorIdent().getWhen();
                commits.add(new CommitInfo(id, message, author, date));
            }
        }
        return commits;
    }

    /**
     * Normalizes a file path to use forward slashes as Git expects
     */
    private String toGitPath(String path) {
        return path.replace('\\', '/');
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }

    private class GitWrappedIOException extends GitAPIException {
        public GitWrappedIOException(IOException e) {
            super(e.getMessage(), e);
        }
    }
}
