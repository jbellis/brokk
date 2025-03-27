package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.GitRepo;
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * A JTree component specialized for displaying file hierarchies, either from a Git repository
 * or the local file system, with support for lazy loading.
 */
public class FileTree extends JTree {
    private static final Logger logger = LogManager.getLogger(FileTree.class);

    private final Path projectPath; // Store for potential use (like expansion)

    /**
     * Constructs a FileTree based on a Project.
     *
     * @param project            The current project.
     * @param allowExternalFiles If true, shows the full file system; otherwise, shows project repo files.
     * @param fileFilter         Optional predicate to filter files shown in the tree (external mode only).
     */
    public FileTree(Project project, boolean allowExternalFiles, Predicate<File> fileFilter) {
        this(project.getRoot().toAbsolutePath(), project.getRepo(), allowExternalFiles, fileFilter);
    }

    /**
     * Constructs a FileTree with explicit parameters.
     *
     * @param projectPath        Absolute path to the project root. Used as root in repo mode, and for initial expansion target in external mode. Can be null if allowExternalFiles is true and no specific path needs expansion.
     * @param repo               The Git repository (used if allowExternalFiles is false and repo is not null).
     * @param allowExternalFiles If true, shows the full file system; otherwise, shows project files (from repo or filesystem walk).
     * @param fileFilter         Optional predicate to filter files shown in the tree (external mode only).
     */
    public FileTree(Path projectPath, GitRepo repo, boolean allowExternalFiles, Predicate<File> fileFilter) {
        this.projectPath = projectPath;

        if (!allowExternalFiles && projectPath == null) {
            logger.error("Project root path cannot be null when showing project files only");
            throw new IllegalArgumentException("Project root path must be provided if allowExternalFiles is false");
        }

        if (allowExternalFiles) {
            setupExternalFileSystem(fileFilter);
            // Attempt initial expansion to project path after setup
            if (projectPath != null && Files.exists(projectPath)) {
                logger.debug("Attempting initial expansion to project root: {}", projectPath);
                expandTreeToPath(projectPath);
            } else if (projectPath != null) {
                logger.warn("Project root path does not exist, cannot expand: {}", projectPath);
            }
        } else {
            setupProjectFileSystem(projectPath, repo);
        }

        setRootVisible(true);
        setShowsRootHandles(true);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        setCellRenderer(new FileTreeCellRenderer()); // Use for both modes

        logger.debug("FileTree initialization complete");
    }

    /**
     * Sets up the tree to display the full external file system with lazy loading.
     */
    private void setupExternalFileSystem(Predicate<File> fileFilter) {
        logger.debug("Setting up external file system view");
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("File System");
        File[] roots = File.listRoots();
        if (roots != null) {
            logger.debug("Found {} file system roots", roots.length);
            for (File root : roots) {
                logger.trace("Adding file system root: {}", root.getAbsolutePath());
                FileTreeNode fileUserObject = new FileTreeNode(root);
                DefaultMutableTreeNode driveNode = new DefaultMutableTreeNode(fileUserObject);
                // Add placeholder *only* if it's a potentially expandable directory
                if (root.isDirectory() && root.canRead()) {
                    driveNode.add(new DefaultMutableTreeNode(LazyLoadingTreeModel.LOADING_PLACEHOLDER));
                }
                rootNode.add(driveNode);
            }
        } else {
            logger.warn("No file system roots found");
        }

        LazyLoadingTreeModel model = new LazyLoadingTreeModel(rootNode, fileFilter, logger);
        setModel(model);

        // Add listener for lazy loading on user expansion
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (node.getUserObject() instanceof FileTreeNode fileNode && node.getChildCount() > 0 &&
                        node.getFirstChild() instanceof DefaultMutableTreeNode child &&
                        LazyLoadingTreeModel.LOADING_PLACEHOLDER.equals(child.getUserObject()))
                {
                    // Start loading children in the background
                    model.loadChildrenInBackground(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
                // No action needed on collapse
            }
        });
        logger.debug("External file system view setup complete");
    }

    /**
     * Sets up the tree to display the project's file hierarchy (from Git or file system walk).
     */
    private void setupProjectFileSystem(Path projectRootPath, GitRepo repo) {
        logger.debug("Setting up project file system view for: {}", projectRootPath);
        String rootDisplayName = projectRootPath.getFileName() != null ? projectRootPath.getFileName().toString() : projectRootPath.toString();
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(rootDisplayName);
        // Use a map to efficiently find parent nodes during construction
        Map<Path, DefaultMutableTreeNode> dirNodes = new HashMap<>();
        dirNodes.put(projectRootPath, rootNode); // Map absolute path to node

        List<Path> filesToAdd = new ArrayList<>();

        if (repo != null) {
            logger.debug("Using Git tracked files.");
            repo.getTrackedFiles().stream()
                    .map(RepoFile::absPath)
                    .forEach(filesToAdd::add);
            logger.debug("Found {} tracked files.", filesToAdd.size());
        } else {
            logger.debug("Walking file system from project root (no Git repo provided).");
            try {
                Files.walk(projectRootPath)
                        .filter(Files::isRegularFile) // Only process files directly
                        .forEach(filesToAdd::add);
                logger.debug("Found {} files by walking.", filesToAdd.size());
            } catch (IOException e) {
                logger.error("Error walking file system from root {}", projectRootPath, e);
                rootNode.add(new DefaultMutableTreeNode("Error reading directory"));
            }
        }

        // Sort paths for consistent tree order
        filesToAdd.sort(Path::compareTo);

        // Build the tree structure efficiently
        for (Path absFilePath : filesToAdd) {
            Path parentPath = absFilePath.getParent();
            if (parentPath == null) continue; // Should not happen within project

            // Ensure parent directories exist in the tree
            DefaultMutableTreeNode parentNode = findOrCreateParentNode(projectRootPath, parentPath, rootNode, dirNodes);

            // Add the file node
            String filename = absFilePath.getFileName().toString();
            DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(filename); // Leaf node stores filename string
            parentNode.add(fileNode);
        }

        setModel(new DefaultTreeModel(rootNode));
        logger.debug("Project file system view setup complete.");
    }

    /** Helper for setupProjectFileSystem to recursively find or create parent nodes */
    private DefaultMutableTreeNode findOrCreateParentNode(Path projectRoot, Path targetParentDirAbs,
                                                          DefaultMutableTreeNode rootNode,
                                                          Map<Path, DefaultMutableTreeNode> dirNodes)
    {
        if (targetParentDirAbs.equals(projectRoot)) {
            return rootNode;
        }
        DefaultMutableTreeNode existingNode = dirNodes.get(targetParentDirAbs);
        if (existingNode != null) {
            return existingNode;
        }

        // Parent node doesn't exist, create it and its parents recursively
        Path parentOfTarget = targetParentDirAbs.getParent();
        if (parentOfTarget == null) { // Should not happen if targetParentDirAbs is under projectRoot
            logger.error("Unexpected null parent for path: {}", targetParentDirAbs);
            return rootNode; // Fallback
        }

        DefaultMutableTreeNode parentTreeNode = findOrCreateParentNode(projectRoot, parentOfTarget, rootNode, dirNodes);

        // Create the node for targetParentDirAbs
        String dirName = targetParentDirAbs.getFileName().toString();
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(dirName);
        parentTreeNode.add(newNode);
        dirNodes.put(targetParentDirAbs, newNode); // Add to map

        return newNode;
    }

    /**
     * Expands the tree view to show the specified target path and selects it.
     * This method now uses a simpler background worker that loads all segments
     * in one pass instead of step-by-step. It still relies on LazyLoadingTreeModel
     * for loading child directories on demand.
     *
     * @param targetPath The absolute path to expand to.
     */
    public void expandTreeToPath(Path targetPath)
    {
        logger.info("Attempting to expand tree to path: {}", targetPath);
        if (!(getModel() instanceof LazyLoadingTreeModel model)) {
            logger.warn("expandTreeToPath called but model is not LazyLoadingTreeModel. Skipping.");
            return;
        }
        if (targetPath == null) {
            logger.warn("expandTreeToPath called with null targetPath. Skipping.");
            return;
        }

        var absoluteTargetPath = targetPath.toAbsolutePath().normalize();
        logger.debug("Normalized target path: {}", absoluteTargetPath);

        new ExpansionWorker(model, absoluteTargetPath, this).execute();
    }

    // --- Inner Worker Class for Path Expansion ---

    /**
     * A simpler SwingWorker for path expansion: we find the drive node,
     * load all child segments in a single background pass, then expand
     * and select the final node on the EDT.
     */
    private static class ExpansionWorker extends SwingWorker<TreePath, Void>
    {
        private final LazyLoadingTreeModel model;
        private final Path targetPath;
        private final FileTree tree;

        ExpansionWorker(LazyLoadingTreeModel model, Path targetPath, FileTree tree)
        {
            this.model = model;
            this.targetPath = targetPath;
            this.tree = tree;
        }

        @Override
        protected TreePath doInBackground() throws Exception
        {
            logger.debug("[ExpansionWorker] Starting expansion for {}", targetPath);
            var rootNode = (DefaultMutableTreeNode) model.getRoot();

            // 1. Find the drive (root) node that contains targetPath
            var driveNode = findDriveNode(rootNode, targetPath);
            if (driveNode == null) {
                logger.warn("[ExpansionWorker] Could not find matching root for path: {}", targetPath);
                return null;
            }

            // 2. Initial TreePath includes the overall root + the drive node
            var currentTreePath = new TreePath(new Object[]{rootNode, driveNode});

            // Ensure the drive node's children are loaded before proceeding
            if (!model.ensureChildrenLoadedSync(driveNode)) {
                logger.warn("[ExpansionWorker] Failed to load children for drive node: {}", driveNode);
                return currentTreePath;
            }

            // 3. Relativize targetPath against the drive node's absolute path
            var drivePath = ((FileTree.FileTreeNode) driveNode.getUserObject()).getFile().toPath().toAbsolutePath();
            Path relativePath;
            try {
                relativePath = drivePath.relativize(targetPath);
                logger.debug("[ExpansionWorker] Relative path segments to traverse: {}", relativePath);
            } catch (IllegalArgumentException e) {
                logger.warn("[ExpansionWorker] Could not relativize {} against {}: {}", targetPath, drivePath, e.getMessage());
                return currentTreePath;
            }

            // 4. Traverse each segment, loading children as needed
            var currentNode = driveNode;
            for (var segment : relativePath) {
                if (!model.ensureChildrenLoadedSync(currentNode)) {
                    logger.warn("[ExpansionWorker] Failed to load children for node: {}", currentNode);
                    break;
                }
                var nextNode = findChildNode(currentNode, segment);
                if (nextNode == null) {
                    logger.warn("[ExpansionWorker] Segment not found: {}", segment);
                    break;
                }
                currentNode = nextNode;
                currentTreePath = currentTreePath.pathByAddingChild(currentNode);
            }

            return currentTreePath;
        }

        @Override
        protected void done()
        {
            try {
                var finalPath = get();
                if (finalPath != null) {
                    logger.debug("[ExpansionWorker] Expansion complete, selecting: {}", finalPath.getLastPathComponent());
                    tree.expandPath(finalPath);
                    tree.setSelectionPath(finalPath);
                    tree.scrollPathToVisible(finalPath);
                } else {
                    logger.warn("[ExpansionWorker] Expansion yielded no final path.");
                }
            } catch (InterruptedException | CancellationException e) {
                logger.warn("[ExpansionWorker] Expansion was interrupted or cancelled.", e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                logger.error("[ExpansionWorker] Error expanding path.", e.getCause());
            }
        }

        private DefaultMutableTreeNode findDriveNode(DefaultMutableTreeNode rootNode, Path target)
        {
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                var child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
                if (child.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
                    var childPath = fileNode.getFile().toPath().toAbsolutePath();
                    try {
                        if (target.startsWith(childPath)) {
                            return child;
                        }
                    } catch (InvalidPathException ex) {
                        logger.warn("[ExpansionWorker] Invalid path comparison: {} vs {}", target, childPath, ex);
                    }
                }
            }
            return null;
        }

        private DefaultMutableTreeNode findChildNode(DefaultMutableTreeNode parent, Path segment)
        {
            var segmentName = segment.getFileName().toString();
            for (int i = 0; i < parent.getChildCount(); i++) {
                var child = (DefaultMutableTreeNode) parent.getChildAt(i);
                if (child.getUserObject() instanceof FileTree.FileTreeNode fileNode) {
                    if (fileNode.getFile().getName().equals(segmentName)) {
                        return child;
                    }
                }
            }
            return null;
        }
    }

    // --- Inner Classes for Tree Nodes and Model ---

    /**
     * A node object representing a File, holding the loading state.
     */
    public static class FileTreeNode { // Keep public static if potentially useful elsewhere
        private final File file;
        private volatile boolean childrenLoaded = false; // volatile for visibility across threads

        public FileTreeNode(File file) {
            Objects.requireNonNull(file, "File cannot be null for FileTreeNode");
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public boolean areChildrenLoaded() {
            return childrenLoaded;
        }

        public void setChildrenLoaded(boolean childrenLoaded) {
            this.childrenLoaded = childrenLoaded;
        }

        @Override
        public String toString() {
            // Special display for root drives
            javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
            String name = fsv.getSystemDisplayName(file);
            return name != null && !name.isEmpty() ? name : file.getAbsolutePath(); // Fallback to absolute path
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FileTreeNode that = (FileTreeNode) o;
            return file.equals(that.file);
        }

        @Override
        public int hashCode() {
            return file.hashCode();
        }
    }

    /**
     * Custom cell renderer for the file tree.
     */
    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            // Let the default renderer handle text, selection background, etc.
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                Icon icon = null;
                String toolTip = null;

                if (userObject instanceof FileTreeNode fileNode) {
                    // External file system node
                    File file = fileNode.getFile();
                    javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
                    icon = fsv.getSystemIcon(file); // Preferred way to get OS-specific icons
                    toolTip = file.getAbsolutePath();
                    // Text is already set by super call using fileNode.toString()
                } else if (userObject instanceof String name) {
                    // Project file/folder node (repo mode or root "File System" node)
                    javax.swing.filechooser.FileSystemView fsv = javax.swing.filechooser.FileSystemView.getFileSystemView();
                    if (node.isRoot() && name.equals("File System")) {
                        // Maybe a computer icon? Default folder is okay.
                        icon = fsv.getSystemIcon(new File(".")); // Generic folder/file icon
                    } else if (leaf) {
                        icon = fsv.getSystemIcon(new File("dummy.txt")); // Generic file icon
                    } else {
                        icon = fsv.getSystemIcon(new File(".")); // Generic folder icon
                    }
                    toolTip = name; // Tooltip is just the name
                    setText(name); // Ensure text is set correctly
                } else if (LazyLoadingTreeModel.LOADING_PLACEHOLDER.equals(userObject)) {
                    setText(LazyLoadingTreeModel.LOADING_PLACEHOLDER);
                    toolTip = "Loading directory contents...";
                    // icon remains null (or set a loading icon)
                } else if (userObject != null) {
                    // Handle unexpected node types gracefully
                    setText(userObject.toString());
                }

                setIcon(icon);
                setToolTipText(toolTip);
            }
            return this;
        }
    }

    /**
     * Tree model that loads directory contents lazily upon expansion.
     */
    private static class LazyLoadingTreeModel extends DefaultTreeModel {
        public static final String LOADING_PLACEHOLDER = "Loading...";
        private final Predicate<File> fileFilter;
        private final Logger modelLogger;

        public LazyLoadingTreeModel(DefaultMutableTreeNode root, Predicate<File> fileFilter, Logger parentLogger) {
            super(root);
            this.fileFilter = fileFilter; // Can be null
            this.modelLogger = parentLogger; // Use the parent logger
        }

        @Override
        public boolean isLeaf(Object node) {
            if (node instanceof DefaultMutableTreeNode treeNode) {
                Object userObject = treeNode.getUserObject();
                if (userObject instanceof FileTreeNode fileNode) {
                    File f = fileNode.getFile();
                    // It's a leaf if it's not a directory OR if it's an unreadable/inaccessible directory
                    return !f.isDirectory() || !f.canRead();
                }
                // The placeholder "Loading..." node is technically not a leaf until replaced
                if (LOADING_PLACEHOLDER.equals(userObject)) {
                    return false; // Treat placeholder as non-leaf so expansion is possible
                }
                // Check if it's a non-FileTreeNode node (e.g. repo mode) - default behavior based on children
                return treeNode.isLeaf();
            }
            return super.isLeaf(node); // Fallback
        }

        /**
         * Loads children for the given node in a background thread.
         * Called by the TreeWillExpandListener.
         */
        public void loadChildrenInBackground(DefaultMutableTreeNode node) {
            if (!(node.getUserObject() instanceof FileTreeNode fileNode) || fileNode.areChildrenLoaded()) {
                return; // Not expandable or already loaded
            }

            modelLogger.debug("Queueing background load for: {}", fileNode.getFile().getAbsolutePath());

            SwingWorker<List<DefaultMutableTreeNode>, Void> worker = new SwingWorker<>() {
                @Override
                protected List<DefaultMutableTreeNode> doInBackground() throws Exception {
                    return performLoadChildren(fileNode);
                }

                @Override
                protected void done() {
                    try {
                        List<DefaultMutableTreeNode> children = get();
                        updateModelWithChildren(node, fileNode, children);
                    } catch (InterruptedException | CancellationException e) {
                        modelLogger.warn("Background loading cancelled for: {}", fileNode.getFile().getAbsolutePath());
                        Thread.currentThread().interrupt();
                        // Optionally reset node state on EDT if needed
                    } catch (ExecutionException e) {
                        modelLogger.error("Error loading children for: {}", fileNode.getFile().getAbsolutePath(), e.getCause());
                        // Update node to show error state on EDT
                        SwingUtilities.invokeLater(() -> updateModelWithError(node, fileNode));
                    }
                }
            };
            worker.execute();
        }

        /**
         * Ensures children are loaded synchronously relative to the calling thread.
         * Uses SwingUtilities.invokeAndWait for model updates, so MUST NOT be called from EDT.
         * Returns true if successful, false otherwise.
         */
        public boolean ensureChildrenLoadedSync(DefaultMutableTreeNode node) {
            if (!(node.getUserObject() instanceof FileTreeNode fileNode)) {
                modelLogger.trace("ensureChildrenLoadedSync called on non-FileTreeNode: {}", node.getUserObject());
                return true; // Nothing to load
            }
            if (fileNode.areChildrenLoaded()) {
                modelLogger.trace("Children already loaded for: {}", fileNode.getFile().getAbsolutePath());
                return true; // Already loaded
            }

            modelLogger.debug("Performing synchronous load for: {}", fileNode.getFile().getAbsolutePath());
            try {
                // Perform file I/O in the current (worker) thread
                List<DefaultMutableTreeNode> children = performLoadChildren(fileNode);

                // Update the Swing model synchronously on the EDT
                SwingUtilities.invokeAndWait(() -> updateModelWithChildren(node, fileNode, children));
                modelLogger.debug("Synchronous load complete for: {}", fileNode.getFile().getAbsolutePath());
                return true;
            } catch (Exception e) { // Catch exceptions from performLoadChildren or invokeAndWait
                modelLogger.error("Error during synchronous load for: {}", fileNode.getFile().getAbsolutePath(), e);
                // Update node to show error state on EDT (use invokeLater if invokeAndWait failed)
                try {
                    SwingUtilities.invokeAndWait(() -> updateModelWithError(node, fileNode));
                } catch (Exception edtEx) {
                    modelLogger.error("Failed to update model with error state for {}", fileNode.getFile().getAbsolutePath(), edtEx);
                }
                return false;
            }
        }


        /**
         * Performs the actual file listing and node creation. Runs in background thread.
         * Returns list of child nodes or throws exception on error.
         */
        private List<DefaultMutableTreeNode> performLoadChildren(FileTreeNode fileNode) {
            File dir = fileNode.getFile();
            modelLogger.trace("performLoadChildren executing for: {}", dir.getAbsolutePath());

            if (!dir.isDirectory() || !dir.canRead()) {
                modelLogger.warn("Cannot load children: Not a readable directory: {}", dir.getAbsolutePath());
                return List.of(); // Return empty list, state will be updated by caller
            }

            File[] files = dir.listFiles();
            if (files == null) {
                modelLogger.warn("listFiles() returned null for: {}", dir.getAbsolutePath());
                // Consider throwing an exception here to indicate failure
                throw new RuntimeException("Failed to list files for directory: " + dir.getAbsolutePath());
                // return List.of(); // Or return empty list
            }

            // Sort: directories first, then files, case-insensitive
            Arrays.sort(files, (f1, f2) -> {
                boolean f1IsDir = f1.isDirectory();
                boolean f2IsDir = f2.isDirectory();
                if (f1IsDir && !f2IsDir) return -1;
                if (!f1IsDir && f2IsDir) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            List<DefaultMutableTreeNode> childNodes = new ArrayList<>();
            for (File file : files) {
                // Apply custom filter (if provided) only to files
                if (fileFilter != null && !file.isDirectory() && !fileFilter.test(file)) {
                    continue;
                }

                FileTreeNode childUserObject = new FileTreeNode(file);
                DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childUserObject);

                // Add placeholder for expandable subdirectories
                if (file.isDirectory() && file.canRead()) {
                    // Check if dir is empty - if so, don't add placeholder? Optional optimization.
                    // For simplicity, always add placeholder if readable dir.
                    childNode.add(new DefaultMutableTreeNode(LOADING_PLACEHOLDER));
                }
                childNodes.add(childNode);
            }
            modelLogger.trace("performLoadChildren found {} children for {}", childNodes.size(), dir.getAbsolutePath());
            return childNodes;
        }

        /**
         * Updates the tree model on the EDT after children have been loaded.
         */
        private void updateModelWithChildren(DefaultMutableTreeNode node, FileTreeNode fileNode, List<DefaultMutableTreeNode> children) {
            modelLogger.trace("[EDT] Updating model for {} with {} children", fileNode.getFile().getName(), children.size());
            node.removeAllChildren(); // Remove "Loading..." or previous content
            for (DefaultMutableTreeNode child : children) {
                node.add(child); // Add the new child nodes
            }
            // It's often better to signal structure change after all additions
            nodeStructureChanged(node); // Inform the tree view about the update
            fileNode.setChildrenLoaded(true); // Mark as loaded *after* updates are applied
            modelLogger.trace("[EDT] Model update complete for {}", fileNode.getFile().getName());
        }

        /**
         * Updates the tree model on the EDT to show an error state for a node.
         */
        private void updateModelWithError(DefaultMutableTreeNode node, FileTreeNode fileNode) {
            modelLogger.trace("[EDT] Updating model with error state for {}", fileNode.getFile().getName());
            node.removeAllChildren();
            node.add(new DefaultMutableTreeNode("Error listing files"));
            nodeStructureChanged(node);
            fileNode.setChildrenLoaded(true); // Mark as loaded (to prevent retries) even though it failed
        }
    }
}