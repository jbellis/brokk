package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private List<RepoFile> trackedFilesCache = null;

    /**
     * Returns true if the directory has a .git folder.
     */
    static boolean hasGitRepo(Path dir) {
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
            throw new UncheckedIOException("Failed to open repository", e);
        }
    }

    @Override
    public Path getRoot() {
        return root;
    }

    public synchronized void refresh() {
        repository.getRefDatabase().refresh();
        trackedFilesCache = null;
    }

    /**
     * Adds files to staging.
     */
    public synchronized void add(List<RepoFile> files) throws IOException
    {
        try {
            var addCommand = git.add();

            // Add each file pattern to the command
            for (var file : files) {
                // Use toString() on RepoFile to get its relative path
                addCommand.addFilepattern(file.toString());
            }

            // Execute the command once for all files
            addCommand.call();
        } catch (GitAPIException e) {
            throw new IOException("Unable to add files to git: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository,
     * including unchanged files from HEAD and any files with staged or unstaged modifications
     * (changed, modified, added, removed) from the working directory.
     */
    @Override
    public synchronized List<RepoFile> getTrackedFiles() {
        if (trackedFilesCache != null) {
            return trackedFilesCache;
        }
        var trackedPaths = new HashSet<String>();
        try {
            // HEAD (unchanged) files
            var headTreeId = repository.resolve("HEAD^{tree}");
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
            throw new UncheckedIOException(new IOException(e));
        }
        trackedFilesCache = trackedPaths.stream()
                .map(path -> new RepoFile(root, path))
                .collect(Collectors.toList());
        return trackedFilesCache;
    }

    /**
     * Get the current commit ID (HEAD)
     * @return The full commit hash of HEAD
     * @throws IOException If there's an error getting the commit ID
     */
    public String getCurrentCommitId() throws IOException {
        var head = repository.resolve("HEAD");
        return head != null ? head.getName() : "";
    }

    /**
     * Get diff for specific files only
     * @param filePaths List of file paths to include in the diff
     * @return String containing the diff output
     */
    /**
     * Produces a combined diff of staged + unstaged changes
     * but only for the given list of RepoFiles.
     */
    public synchronized String diffFiles(List<RepoFile> files)
    {
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
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
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
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Gets a list of uncommitted file paths
     * @return List of file paths relative to git root
     */
    /**
     * Returns a list of uncommitted RepoFiles.
     */
    public List<RepoFile> getModifiedFiles() {
        try {
            var status = git.status().call();
            var filePaths = new HashSet<String>();
            filePaths.addAll(status.getModified());
            filePaths.addAll(status.getChanged());
            filePaths.addAll(status.getAdded());
            filePaths.addAll(status.getRemoved());
            filePaths.addAll(status.getMissing());
            return filePaths.stream()
                    .map(path -> new RepoFile(root, path))
                    .collect(Collectors.toList());
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Commit a specific list of files
     * @return The commit ID of the new commit
     */
    /**
     * Commit a specific list of RepoFiles.
     * @return The commit ID of the new commit
     */
    public String commitFiles(List<RepoFile> files, String message) throws IOException
    {
        try {
            add(files);
            var commitCommand = git.commit()
                              .setMessage(message);
            
            // When a specific list of files is provided, only commit those
            if (!files.isEmpty()) {
                // First need to add each file to the commit command
                for (var file : files) {
                    commitCommand.setOnly(file.toString());
                }
            }
            
            var commitResult = commitCommand.call();
            var commitId = commitResult.getId().getName();

            refresh();
            return commitId;
        } catch (GitAPIException e) {
            throw new IOException("Failed to commit files: " + e.getMessage(), e);
        }
    }

    /**
     * Push the committed changes to the remote repository
     */
    public void push() throws IOException {
        try {
            git.push().call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to push changes: " + e.getMessage(), e);
        }
    }

    /**
     * Get a set of commit IDs that exist in the local branch but not in its remote tracking branch
     *
     * @param branchName Name of the local branch to check
     * @return Set of commit IDs that haven't been pushed
     * @throws IOException If there's a problem accessing the repository
     */
    public Set<String> getUnpushedCommitIds(String branchName) throws IOException {
        try {
            var unpushedCommits = new HashSet<String>();
            var trackingBranch = getTrackingBranch(branchName);
            if (trackingBranch == null) {
                // No tracking branch, so all commits are considered unpushed or we consider returning empty
                return unpushedCommits;
            }

            var branchRef = "refs/heads/" + branchName;
            var trackingRef = "refs/remotes/" + trackingBranch;

            var localObjectId = repository.resolve(branchRef);
            var remoteObjectId = repository.resolve(trackingRef);

            if (localObjectId == null || remoteObjectId == null) {
                return unpushedCommits;
            }

            var revWalk = new RevWalk(repository);
            revWalk.markStart(revWalk.parseCommit(localObjectId));
            revWalk.markUninteresting(revWalk.parseCommit(remoteObjectId));

            revWalk.forEach(commit -> unpushedCommits.add(commit.getId().getName()));
            revWalk.dispose();

            return unpushedCommits;
        } catch (IOException e) {
            throw new IOException("Failed to get unpushed commits: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a local branch has a configured upstream (tracking) branch
     *
     * @param branchName Name of the local branch to check
     * @return true if the branch has an upstream, false otherwise
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
            return null;
        }
    }

    /**
     * List all local branches
     */
    public List<String> listLocalBranches() {
        try {
            var branches = new ArrayList<String>();
            for (var ref : git.branchList().call()) {
                branches.add(ref.getName().replaceFirst("^refs/heads/", ""));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to list local branches", e));
        }
    }

    /**
     * List all remote branches
     */
    public List<String> listRemoteBranches() {
        try {
            var branches = new ArrayList<String>();
            for (var ref : git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()) {
                branches.add(ref.getName().replaceFirst("^refs/remotes/", ""));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to list remote branches", e));
        }
    }

    /**
     * Checkout a specific branch
     */
    public void checkout(String branchName) throws IOException {
        try {
            git.checkout().setName(branchName).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to checkout branch: " + e.getMessage(), e);
        }
    }

    /**
     * Checkout a remote branch, creating a local tracking branch
     * with the default naming convention (using the remote branch name)
     */
    public void checkoutRemoteBranch(String remoteBranchName) throws IOException {
        String branchName;
        if (remoteBranchName.contains("/")) {
            branchName = remoteBranchName.substring(remoteBranchName.indexOf('/') + 1);
        } else {
            branchName = remoteBranchName;
        }
        
        checkoutRemoteBranch(remoteBranchName, branchName);
    }
    
    /**
     * Checkout a remote branch, creating a local tracking branch with a specified name
     * 
     * @param remoteBranchName The remote branch to checkout (e.g. "origin/main" or "pr-25/master")
     * @param localBranchName The name to use for the local branch
     * @throws IOException If there's an error creating the branch
     */
    public void checkoutRemoteBranch(String remoteBranchName, String localBranchName) throws IOException {
        try {
            // Check if the remote branch actually exists
            boolean remoteBranchExists = false;
            try {
                var remoteRef = repository.findRef("refs/remotes/" + remoteBranchName);
                remoteBranchExists = (remoteRef != null);
                logger.debug("Checking if remote branch exists: {} -> {}",
                             remoteBranchName, remoteBranchExists ? "yes" : "no");
            } catch (Exception e) {
                logger.warn("Error checking remote branch: {}", e.getMessage());
            }

            if (!remoteBranchExists) {
                throw new IOException("Remote branch '" + remoteBranchName + "' not found. Ensure the remote exists and has been fetched.");
            }

            if (listLocalBranches().contains(localBranchName)) {
                throw new IOException("Local branch '" + localBranchName + "' already exists. Choose a different name.");
            }

            logger.debug("Creating local branch '{}' from remote '{}'", localBranchName, remoteBranchName);
            git.checkout()
                    .setCreateBranch(true)
                    .setName(localBranchName)
                    .setStartPoint(remoteBranchName)
                    .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
            logger.debug("Successfully created and checked out branch '{}'", localBranchName);

            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to checkout remote branch: " + e.getMessage(), e);
        }
    }

    /**
     * Rename a branch
     */
    public void renameBranch(String oldName, String newName) throws IOException {
        try {
            git.branchRename().setOldName(oldName).setNewName(newName).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to rename branch: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a branch is fully merged into HEAD
     */
    public boolean isBranchMerged(String branchName) throws IOException {
        try {
            var mergedBranches = git.branchList()
                    .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL)
                    .setContains("HEAD")
                    .call();
            for (var ref : mergedBranches) {
                var name = ref.getName().replaceFirst("^refs/heads/", "");
                if (name.equals(branchName)) {
                    return true;
                }
            }
            return false;
        } catch (GitAPIException e) {
            throw new IOException("Failed to check if branch is merged: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a branch
     */
    public void deleteBranch(String branchName) throws IOException {
        try {
            logger.debug("Attempting to delete branch: {}", branchName);
            var result = git.branchDelete().setBranchNames(branchName).call();
            
            if (result.isEmpty()) {
                logger.warn("Branch deletion returned empty result for branch: {}", branchName);
                throw new IOException("Branch deletion failed: No results returned. Branch may not exist or requires force delete.");
            }
            
            for (var deletedRef : result) {
                logger.debug("Successfully deleted branch reference: {}", deletedRef);
            }
        } catch (GitAPIException e) {
            logger.error("Git exception when deleting branch {}: {}", branchName, e.getMessage());
            throw new IOException("Failed to delete branch: " + e.getMessage(), e);
        }
    }

    /**
     * Force delete a branch even if it's not fully merged
     */
    public void forceDeleteBranch(String branchName) throws IOException {
        try {
            logger.debug("Attempting to force delete branch: {}", branchName);
            var result = git.branchDelete().setBranchNames(branchName).setForce(true).call();
            
            if (result.isEmpty()) {
                logger.warn("Force branch deletion returned empty result for branch: {}", branchName);
                throw new IOException("Force branch deletion failed: No results returned. Branch may not exist.");
            }
            
            for (var deletedRef : result) {
                logger.debug("Successfully force deleted branch reference: {}", deletedRef);
            }
        } catch (GitAPIException e) {
            logger.error("Git exception when force deleting branch {}: {}", branchName, e.getMessage());
            throw new IOException("Failed to force delete branch: " + e.getMessage(), e);
        }
    }

    /**
     * Merge a branch into HEAD
     */
    public void mergeIntoHead(String branchName) throws IOException {
        try {
            git.merge().include(repository.resolve(branchName)).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to merge branch: " + e.getMessage(), e);
        }
    }

    /**
     * Revert a specific commit
     */
    public void revertCommit(String commitId) throws IOException {
        try {
            git.revert().include(repository.resolve(commitId)).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to revert commit: " + e.getMessage(), e);
        }
    }
    
    /**
     * Perform a soft reset to a specific commit
     * This resets HEAD to the specified commit but keeps the changes as unstaged
     */
    public void softReset(String commitId) throws IOException {
        try {
            git.reset()
               .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.SOFT)
               .setRef(commitId)
               .call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to perform soft reset: " + e.getMessage(), e);
        }
    }

    /**
     * Get current branch name
     * @return The current branch name
     */
    public String getCurrentBranch() {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to get current branch", e);
        }
    }

    /**
     * A record to hold commit details
     */
    public record CommitInfo(String id, String message, String author, java.util.Date date) {}

    /**
     * List commits with detailed information for a specific branch
     * @param branchName The branch to list commits for
     * @return List of detailed commit information
     */
    public List<CommitInfo> listCommitsDetailed(String branchName) {
        try {
            var commits = new ArrayList<CommitInfo>();
            var logCommand = git.log();

            if (branchName != null && !branchName.isEmpty()) {
                logCommand.add(repository.resolve(branchName));
            }

            for (var commit : logCommand.call()) {
                var id = commit.getName();
                var message = commit.getShortMessage();
                var author = commit.getAuthorIdent().getName();
                var date = commit.getAuthorIdent().getWhen();

                commits.add(new CommitInfo(id, message, author, date));
            }
            return commits;
        } catch (GitAPIException | IOException e) {
            throw new UncheckedIOException(new IOException("Failed to list commits", e));
        }
    }

    /**
     * List changed files in a specific commit
     */
    /**
     * List changed RepoFiles in a specific commit.
     */
    public List<RepoFile> listChangedFilesInCommit(String commitId)
    {
        try {
            var commitObj = repository.resolve(commitId);
            try (var revWalk = new RevWalk(repository)) {
                var commit = revWalk.parseCommit(commitObj);
                var parentCommit = commit.getParentCount() > 0 ? commit.getParent(0) : null;

                var files = new ArrayList<String>();
                if (parentCommit == null) {
                    // Root commit, just list everything in this tree
                    try (var treeWalk = new TreeWalk(repository)) {
                        treeWalk.addTree(commit.getTree());
                        treeWalk.setRecursive(true);
                        while (treeWalk.next()) {
                            files.add(treeWalk.getPathString());
                        }
                    }
                } else {
                    parentCommit = revWalk.parseCommit(parentCommit.getId());
                    try (var diffFormatter =
                                 new org.eclipse.jgit.diff.DiffFormatter(new ByteArrayOutputStream()))
                    {
                        diffFormatter.setRepository(repository);
                        var diffs = diffFormatter.scan(parentCommit.getTree(), commit.getTree());
                        for (var diff : diffs) {
                            if (diff.getNewPath() != null
                                && !diff.getNewPath().equals("/dev/null"))
                            {
                                files.add(diff.getNewPath());
                            }
                            if (diff.getOldPath() != null
                                && !diff.getOldPath().equals("/dev/null")
                                && !files.contains(diff.getOldPath()))
                            {
                                files.add(diff.getOldPath());
                            }
                        }
                    }
                }
                revWalk.dispose();
                return files.stream()
                            .map(path -> new RepoFile(root, path))
                            .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list changed files in commit", e);
        }
    }

    /**
     * Show diff between two commits
     * Special handling for when HEAD is used as a reference in the working directory
     * @param newCommitId The newer commit (or HEAD for working directory)
     * @param oldCommitId The older commit to compare against
     * @return String containing the diff output
     */
    public String showDiff(String newCommitId, String oldCommitId) {
        try (var out = new ByteArrayOutputStream()) {
            logger.debug("Generating diff from {} to {}", oldCommitId, newCommitId);
            if ("HEAD".equals(newCommitId)) {
                git.diff()
                        .setOldTree(prepareTreeParser(oldCommitId))
                        .setNewTree(null) // Working tree
                        .setOutputStream(out)
                        .call();
            } else {
                git.diff()
                        .setOldTree(prepareTreeParser(oldCommitId))
                        .setNewTree(prepareTreeParser(newCommitId))
                        .setOutputStream(out)
                        .call();
            }
            var result = out.toString(StandardCharsets.UTF_8);
            logger.debug("Generated diff of {} bytes", result.length());
            return result;
        } catch (IOException | GitAPIException e) {
            logger.error("Failed to show diff between {} and {}: {}", 
                       oldCommitId, newCommitId, e.getMessage());
            throw new UncheckedIOException(new IOException("Failed to show diff", e));
        }
    }

    /**
     * Show diff for a specific file between two commits
     */
    /**
     * Show diff for a specific file between two commits.
     */
    public String showFileDiff(String commitIdA, String commitIdB, RepoFile file)
    {
        try (var out = new ByteArrayOutputStream()) {
            var pathFilter = PathFilter.create(file.toString());

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
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to show file diff", e));
        }
    }

    private org.eclipse.jgit.treewalk.AbstractTreeIterator prepareTreeParser(String objectId) throws IOException {
        var objId = repository.resolve(objectId);
        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var treeId = commit.getTree().getId();

            try (var reader = repository.newObjectReader()) {
                return new org.eclipse.jgit.treewalk.CanonicalTreeParser(null, reader, treeId);
            }
        }
    }

    /**
     * Create a stash from the current changes
     */
    public void createStash(String message) throws IOException {
        assert message != null && !message.isEmpty();
        try {
            logger.debug("Creating stash with message: {}", message);
            var stashId = git.stashCreate()
                    .setWorkingDirectoryMessage(message)
                    .call();
            logger.debug("Stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));
            refresh();
        } catch (GitAPIException e) {
            logger.error("Stash creation failed: {}", e.getMessage());
            throw new IOException("Failed to create stash: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a stash containing only the specified files.
     * This involves a more complex workflow:
     * 1. Get all uncommitted files
     * 2. Add UN-selected files to index (i.e., everything EXCEPT the files we want to stash)
     * 3. Commit those unselected files to a temporary branch
     * 4. Stash what's left (which will be only our selected files)
     * 5. Soft-reset back to restore the working directory with the UN-selected files uncommitted
     * 6. Clean up the temporary branch
     *
     * @param message The stash message
     * @param filesToStash The specific files to include in the stash
     * @throws IOException If there's an error during the stash process
     */
    public void createPartialStash(String message, List<RepoFile> filesToStash) throws IOException {
        assert message != null && !message.isEmpty();
        assert filesToStash != null && !filesToStash.isEmpty();

        try {
            logger.debug("Creating partial stash with message: {} for {} files", message, filesToStash.size());
            
            // Get all modified files including deleted ones
            var allUncommittedFiles = getModifiedFiles();
            // Sanity check
            if (!new HashSet<>(allUncommittedFiles).containsAll(filesToStash)) {
                throw new IOException("Files to stash are not actually uncommitted!?");
            }
            
            // Create a set of files to stash for faster lookups
            Set<String> filesToStashPaths = filesToStash.stream()
                                              .map(RepoFile::toString)
                                              .collect(Collectors.toSet());
            
            // Filter to get the complement - files NOT to stash (i.e., to temporarily commit)
            var filesToCommit = allUncommittedFiles.stream()
                                 .filter(file -> !filesToStashPaths.contains(file.toString()))
                                 .collect(Collectors.toList());
            
            logger.debug("Files to temporarily commit {}", filesToCommit);
            
            if (filesToCommit.isEmpty()) {
                // If all changed files are selected for stashing, just do a regular stash
                logger.debug("All changed files are selected for stashing, using regular stash");
                createStash(message);
                return;
            }
            
            // Remember the original branch
            String originalBranch = getCurrentBranch();
            String tempBranchName = "temp-stash-branch-" + System.currentTimeMillis();
            
            // First, add the UN-selected files to the index
            add(filesToCommit);
            
            // Create a temporary branch and commit those files
            logger.debug("Creating temporary branch: {}", tempBranchName);
            git.branchCreate().setName(tempBranchName).call();
            
            logger.debug("Committing UN-selected files to temporary branch");
            git.commit()
                .setMessage("Temporary commit to facilitate partial stash")
                .call();
            
            // Now stash the remaining changes (which are only our selected files)
            logger.debug("Creating stash with only the selected files");
            var stashId = git.stashCreate()
                    .setWorkingDirectoryMessage(message)
                    .call();
            
            logger.debug("Partial stash created with ID: {}", (stashId != null ? stashId.getName() : "none"));
            
            // Do a soft reset to get back the uncommitted files that weren't stashed
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
        } catch (GitAPIException e) {
            logger.error("Partial stash creation failed: {}", e.getMessage());
            throw new IOException("Failed to create partial stash: " + e.getMessage(), e);
        }
    }

    /**
     * Lists all stashes in the repository
     * @return List of stash entries with their info
     * @throws IOException If there's an error accessing stashes
     */
    public List<StashInfo> listStashes() throws IOException {
        try {
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
        } catch (GitAPIException e) {
            throw new IOException("Failed to list stashes: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gets additional commits from a stash (index, untracked files)
     * @param stashRef The stash reference (e.g., "stash@{0}")
     * @return Map of commit type to CommitInfo
     * @throws IOException If there's an error accessing the stash
     */
    public Map<String, CommitInfo> listAdditionalStashCommits(String stashRef) throws IOException {
        try {
            Map<String, CommitInfo> additionalCommits = new HashMap<>();
            var rev = repository.resolve(stashRef);

            try (var revWalk = new RevWalk(repository)) {
                var commit = revWalk.parseCommit(rev);

                // Per git stash internals:
                // stash@{0} - Main stash commit (merge commit) containing modified tracked files in working dir
                // stash@{0}^1 - Original HEAD commit
                // stash@{0}^2 - Index commit (staged changes)
                // stash@{0}^3 - Untracked files commit (only exists when stash was created with -u or -a)

                // First, check if this is really a stash commit by confirming it's a merge commit
                if (commit.getParentCount() < 2) {
                    logger.warn("Stash {} is not a merge commit, which is unexpected", stashRef);
                    return additionalCommits;
                }

                // Now check if the Index (second parent) contains any changes
                // by comparing it with the original HEAD (first parent)
                var headCommit = commit.getParent(0);
                var indexCommit = commit.getParent(1);
                revWalk.parseHeaders(headCommit);
                revWalk.parseHeaders(indexCommit);

                // Compare trees to see if the index has any changes from HEAD
                try (var diffFormatter = new org.eclipse.jgit.diff.DiffFormatter(new ByteArrayOutputStream())) {
                    diffFormatter.setRepository(repository);
                    var diffs = diffFormatter.scan(headCommit.getTree(), indexCommit.getTree());
                    
                    // Only add index commit if it has actual changes
                    if (!diffs.isEmpty()) {
                        additionalCommits.put("index", new CommitInfo(
                            indexCommit.getName(),
                            "Index (staged) changes in " + stashRef,
                            indexCommit.getAuthorIdent().getName(),
                            indexCommit.getAuthorIdent().getWhen()
                        ));
                    }
                }

                // Check for untracked files (third parent)
                if (commit.getParentCount() > 2) {
                    var untrackedCommit = commit.getParent(2);
                    revWalk.parseHeaders(untrackedCommit);
                    
                    // We only need to verify there are files in this tree
                    boolean hasFiles = false;
                    try (var treeWalk = new TreeWalk(repository)) {
                        treeWalk.addTree(untrackedCommit.getTree());
                        treeWalk.setRecursive(true);
                        hasFiles = treeWalk.next(); // Check if there's at least one file
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
            }

            return additionalCommits;
        } catch (IOException e) {
            throw new IOException("Failed to get additional stash commits: " + e.getMessage(), e);
        }
    }

    /**
     * Apply a stash to the working directory without removing it from the stash list
     */
    public void applyStash(int stashIndex) throws IOException {
        try {
            String stashRef = "stash@{" + stashIndex + "}";
            logger.debug("Applying stash: {}", stashRef);
            git.stashApply()
                    .setStashRef(stashRef)
                    .call();
            logger.debug("Stash applied successfully");
            refresh();
        } catch (GitAPIException e) {
            logger.error("Stash apply failed: {}", e.getMessage());
            throw new IOException("Failed to apply stash: " + e.getMessage(), e);
        }
    }

    /**
     * Pop a stash - apply it to the working directory and remove it from the stash list
     */
    public void popStash(int stashIndex) throws IOException {
        try {
            String stashRef = "stash@{" + stashIndex + "}";
            logger.debug("Popping stash: {}", stashRef);

            // First apply the stash
            logger.debug("Applying stash content");
            git.stashApply()
                    .setStashRef(stashRef)
                    .call();

            // Then drop it
            logger.debug("Dropping stash from list");
            git.stashDrop()
                    .setStashRef(stashIndex)
                    .call();

            logger.debug("Stash pop completed successfully");
            refresh();
        } catch (GitAPIException e) {
            logger.error("Stash pop failed: {}", e.getMessage());
            throw new IOException("Failed to pop stash: " + e.getMessage(), e);
        }
    }

    /**
     * Drop a stash without applying it
     */
    public void dropStash(int stashIndex) throws IOException {
        try {
            logger.debug("Dropping stash at index: {}", stashIndex);
            git.stashDrop()
                    .setStashRef(stashIndex)
                    .call();
            logger.debug("Stash dropped successfully");
            refresh();
        } catch (GitAPIException e) {
            logger.error("Stash drop failed: {}", e.getMessage());
            throw new IOException("Failed to drop stash: " + e.getMessage(), e);
        }
    }

    /**
     * A record to hold stash details
     */
    public record StashInfo(String id, String message, String author, java.util.Date date, int index) {}

    /**
     * Get the commit history for a specific file
     *
     * @param filePath Path to the file relative to repository root
     * @return List of commits that modified the file
     */
    /**
     * Get the commit history for a specific RepoFile.
     */
    public List<CommitInfo> getFileHistory(RepoFile file)
    {
        try {
            var commits = new ArrayList<CommitInfo>();
            // Use addPath(...) with the relative path
            for (var commit : git.log().addPath(file.toString()).call()) {
                var id     = commit.getName();
                var message= commit.getShortMessage();
                var author = commit.getAuthorIdent().getName();
                var date   = commit.getAuthorIdent().getWhen();
                commits.add(new CommitInfo(id, message, author, date));
            }
            return commits;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to get file history", e));
        }
    }

    /**
     * Get the URL of the specified remote (defaults to "origin")
     * @param remoteName The name of the remote (typically "origin")
     * @return The URL of the remote, or null if not found
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
     * @return The URL of the origin remote, or null if not found
     */
    public String getRemoteUrl() {
        return getRemoteUrl("origin");
    }

    /**
     * Search commits
     * @param query Text to search for in commit messages, author names, and email addresses
     */
    public List<CommitInfo> searchCommits(String query) {
        try {
            var commits = new ArrayList<CommitInfo>();
            for (var commit : git.log().call()) {
                var lowerQuery = query.toLowerCase();
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
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to search commits", e));
        }
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }
}
