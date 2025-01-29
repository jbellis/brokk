package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import io.github.jbellis.brokk.ContextFragment.StacktraceFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Math.min;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    public static final int MAX_AUTO_CONTEXT_FILES = 100;

    private final Analyzer analyzer;
    private final List<ContextFragment.PathFragment> editableFiles;
    private final List<ContextFragment.PathFragment> readonlyFiles;
    private final List<ContextFragment.VirtualFragment> virtualFragments;

    private final ContextFragment.AutoContext autoContext;
    private final int autoContextFileCount;

    /**
     * Default constructor, with empty files/fragments and autoContext on, and a default of 5 files.
     */
    public Context(Analyzer analyzer) {
        this(analyzer, List.of(), List.of(), List.of(), ContextFragment.AutoContext.EMPTY, 5);
    }

    /**
     * Full constructor; call this from any factory methods so we preserve immutability.
     *
     * @param analyzer             The Analyzer instance
     * @param editableFiles        The editable filename fragments
     * @param readonlyFiles        The read-only filename fragments
     * @param virtualFragments     Arbitrary read-only string fragments
     * @param autoContext         The auto-context instance
     * @param autoContextFileCount The number of files to include from pagerank
     */
    public Context(
            Analyzer analyzer,
            List<ContextFragment.PathFragment> editableFiles,
            List<ContextFragment.PathFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            ContextFragment.AutoContext autoContext,
            int autoContextFileCount
    ) {
        assert analyzer != null;
        assert autoContext != null;
        assert autoContextFileCount >= 0;
        this.analyzer = analyzer;
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.autoContext = autoContext;
        this.autoContextFileCount = autoContextFileCount;
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !editableFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);
        return withEditableFiles(newEditable).refresh();
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !readonlyFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(paths);
        return withReadonlyFiles(newReadOnly).refresh();
    }

    public Context removeEditableFile(ContextFragment.PathFragment fragment) {
        return removeEditableFiles(List.of(fragment));
    }

    public Context removeEditableFiles(List<ContextFragment.PathFragment> fragments) {
        List<ContextFragment.PathFragment> newEditable = new ArrayList<>(editableFiles);
        newEditable.removeAll(fragments);
        if (newEditable.equals(editableFiles)) {
            return this;
        }
        return withEditableFiles(newEditable).refresh();
    }

    public Context removeReadonlyFiles(List<ContextFragment.PathFragment> fragments) {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.removeAll(fragments);
        if (newReadOnly.equals(readonlyFiles)) {
            return this;
        }
        return withReadonlyFiles(newReadOnly).refresh();
    }

    public Context removeVirtualFragments(List<? extends ContextFragment.VirtualFragment> fragments) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.removeAll(fragments);
        if (newFragments.equals(virtualFragments)) {
            return this;
        }
        return withVirtualFragments(newFragments).refresh();
    }
    
    public Context removeReadonlyFile(ContextFragment.PathFragment path) {
        return removeReadonlyFiles(List.of(path));
    }

    public Context addStringFragment(String description, String content) {
        var fragment = new ContextFragment.StringFragment(virtualFragments.size(), content, description);
        return addVirtualFragment(fragment);
    }

    public Context addStacktraceFragment(Set<String> classnames, String original, String exception, String methods) {
        var fragment = new StacktraceFragment(virtualFragments.size(), classnames, original, exception, methods);
        return addVirtualFragment(fragment);
    }

    public Context addPasteFragment(String content, Future<String> descriptionFuture) {
        var fragment = new ContextFragment.PasteFragment(virtualFragments.size(), content, descriptionFuture);
        return addVirtualFragment(fragment);
    }

    private Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);
        return withVirtualFragments(newFragments).refresh();
    }

    public Context removeVirtualFragment(int humanPosition) {
        int index = humanPosition - 1;
        if (index < 0 || index >= virtualFragments.size()) {
            throw new IllegalArgumentException("String fragment out of range: " + humanPosition);
        }

        List<ContextFragment.VirtualFragment> newFragments = new ArrayList<>();
        for (int i = 0; i < virtualFragments.size(); i++) {
            if (i == index) {
                continue;
            }
            var current = virtualFragments.get(i);
            // reindex everything that comes after it
            if (i > index) {
                current = new ContextFragment.StringFragment(i - 1, current.text(), current.description());
            }
            newFragments.add(current);
        }
        var updated = new Context(analyzer, editableFiles, readonlyFiles, newFragments, autoContext, autoContextFileCount);
        return updated.refresh();
    }

    public Context convertAllToReadOnly() {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(editableFiles);
        return withEditableFiles(List.of())
                .withReadonlyFiles(newReadOnly);
    }

    public Context removeBadFragment(ContextFragment f) {
        if (f instanceof ContextFragment.PathFragment pf) {
            Context tmp = removeEditableFile(pf);
            if (tmp == this) {
                tmp = removeReadonlyFile(pf);
            }
            return tmp.refresh();
        } else if (f instanceof ContextFragment.StringFragment sf) {
            return removeVirtualFragment(sf.position() + 1);
        } else {
            throw new IllegalArgumentException("Unknown fragment type: " + f);
        }
    }

    /**
     * Sets how many files are pulled out of pagerank results. Uses 2*n in the pagerank call to
     * account for any out-of-project exclusions. Rebuilds autoContext if toggled on.
     */
    public Context setAutoContextFiles(int fileCount) {
        return withAutoContextFileCount(fileCount).refresh();
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Build a multiline skeleton text for the top autoContextFileCount results
     * 4) Return the new AutoContext instance
     */
    private ContextFragment.AutoContext buildAutoContext() {
        if (!isAutoContextEnabled()) {
            debug("pagerank disabled");
            return ContextFragment.AutoContext.DISABLED;
        }
        var seeds = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream().filter(f -> !(f instanceof ContextFragment.AutoContext)))
                .flatMap(f -> f.classnames(analyzer).stream())
                .collect(Collectors.toSet());

        if (seeds.isEmpty()) {
            debug("Empty pagerank seeds");
            return ContextFragment.AutoContext.EMPTY;
        }

        // request 3*autoContextFileCount from pagerank to account for out-of-project filtering
        var pagerankResults = analyzer.getPagerank(seeds, 3 * MAX_AUTO_CONTEXT_FILES);

        // build skeleton lines
        var skeletons = new ArrayList<String>();
        Set<String> fullNames = new HashSet<>();
        List<String> shortNames = new ArrayList<>();
        pagerankResults.forEach(pair -> {
            String fullName = pair._1;
            fullNames.add(fullName);
            String shortName = fullName.substring(fullName.lastIndexOf('.') + 1);
            if (analyzer.classInProject(fullName)) {
                var opt = analyzer.getSkeleton(fullName);
                if (opt.isDefined()) {
                    skeletons.add(opt.get());
                    shortNames.add(shortName);
                }
            }
        });
        if (shortNames.isEmpty()) {
            return ContextFragment.AutoContext.EMPTY;
        }

        int limit = min(autoContextFileCount, skeletons.size());
        String joinedSkeletons = skeletons.stream()
                .limit(limit)
                .collect(Collectors.joining("\n"));

        return new ContextFragment.AutoContext(shortNames.subList(0, limit), fullNames, joinedSkeletons);
    }

    private static void debug(String msg) {
//        System.out.println("[debug] " + msg);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment.PathFragment> editableFiles() {
        return editableFiles.stream();
    }

    public Stream<ContextFragment.PathFragment> readonlyFiles() {
        return readonlyFiles.stream();
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        return virtualFragments.stream();
    }

    public Stream<? extends ContextFragment> allFragments() {
        return Streams.concat(editableFiles.stream(),
                              readonlyFiles.stream(),
                              virtualFragments.stream());
    }

    public boolean hasReadonlyFragments() {
        return !readonlyFiles.isEmpty() || !virtualFragments.isEmpty();
    }

    public boolean isAutoContextEnabled() {
        return autoContextFileCount > 0;
    }

    public ContextFragment.AutoContext getAutoContext() {
        return autoContext;
    }

    public int getAutoContextFileCount() {
        return autoContextFileCount;
    }

    private Context withEditableFiles(List<ContextFragment.PathFragment> newEditableFiles) {
        return new Context(analyzer, newEditableFiles, readonlyFiles, virtualFragments, autoContext, autoContextFileCount);
    }

    private Context withReadonlyFiles(List<ContextFragment.PathFragment> newReadonlyFiles) {
        return new Context(analyzer, editableFiles, newReadonlyFiles, virtualFragments, autoContext, autoContextFileCount);
    }

    private Context withVirtualFragments(List<ContextFragment.VirtualFragment> newVirtualFragments) {
        return new Context(analyzer, editableFiles, readonlyFiles, newVirtualFragments, autoContext, autoContextFileCount);
    }

    private Context withAutoContextFileCount(int newAutoContextFileCount) {
        return new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, autoContext, newAutoContextFileCount);
    }

    public Context removeAll() {
        return withEditableFiles(List.of())
                .withReadonlyFiles(List.of())
                .withVirtualFragments(List.of())
                .refresh();
    }

    /**
     * Produces a new Context object with a fresh AutoContext if enabled.
     */
    public Context refresh() {
        debug("refreshing autocontext: " + isAutoContextEnabled());
        ContextFragment.AutoContext newAutoContext = isAutoContextEnabled() ? buildAutoContext() : ContextFragment.AutoContext.DISABLED;
        return new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, newAutoContext, autoContextFileCount);
    }

    /**
     * Return the String or Path Fragment corresponding to the given target,
     * or null if not found.
     */
    public ContextFragment toFragment(String target) {
        try {
            int ordinal = Integer.parseInt(target);
            if (ordinal == 0) {
                if (!isAutoContextEnabled()) {
                    return null;
                }
                return autoContext;
            }

            if (ordinal > virtualFragments.size() || ordinal < 1) {
                return null;
            }
            return virtualFragments.get(ordinal - 1);
        } catch (NumberFormatException e) {
            return Streams.concat(editableFiles.stream(), readonlyFiles.stream())
                    .filter(f -> f.source().equals(target))
                    .findFirst()
                    .orElse(null);
        }
    }

    public boolean isEmpty() {
        return editableFiles.isEmpty() && readonlyFiles.isEmpty() && virtualFragments.isEmpty();
    }

    public boolean hasEditableFiles() {
        return !editableFiles.isEmpty();
    }

    public Context addUsageFragment(String identifier, Set<String> classnames, String code) {
        var fragment = new ContextFragment.UsageFragment(virtualFragments.size(), identifier, classnames, code);
        return addVirtualFragment(fragment);
    }

    public Context addSkeletonFragment(List<String> shortClassnames, Set<String> classnames, String skeleton) {
        var fragment = new ContextFragment.SkeletonFragment(virtualFragments.size(), shortClassnames, classnames, skeleton);
        return addVirtualFragment(fragment);
    }
}
