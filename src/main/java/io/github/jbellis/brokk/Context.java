package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.ContextFragment.AutoContext;
import io.github.jbellis.brokk.ContextFragment.SkeletonFragment;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context implements Serializable {
    private static final Logger logger = LogManager.getLogger(Context.class);
    private static final AtomicInteger idCounter = new AtomicInteger(0);
    private static int newId() {
        return idCounter.incrementAndGet();
    }

    @Serial
    private static final long serialVersionUID = 1L;

    public static final int MAX_AUTO_CONTEXT_FILES = 100;
    private static final String WELCOME_ACTION = "Welcome to Brokk";
    private static final String WELCOME_BACK = "Welcome back";
    public static final String SUMMARIZING = "(Summarizing)";

    transient final IContextManager contextManager;
    final List<ContextFragment.ProjectPathFragment> editableFiles;
    final List<ContextFragment.PathFragment> readonlyFiles;
    final List<ContextFragment.VirtualFragment> virtualFragments;

    final AutoContext autoContext;
    final int autoContextFileCount;
    /** history messages are unusual because they are updated in place.  See comments to addHistory and clearHistory */
    transient final List<ChatMessage> historyMessages;

    /** backup of original contents for /undo, does not carry forward to Context children */
    transient final Map<ProjectFile, String> originalContents;

    /** LLM output or other parsed content, with optional fragment. May be null */
    transient final ParsedOutput parsedOutput;

    /** description of the action that created this context, can be a future (like PasteFragment) */
    transient final Future<String> action;
    /** Unique transient identifier for this context instance */
    transient final int id;

    public record ParsedOutput(String output, ContextFragment.VirtualFragment parsedFragment) {
        public ParsedOutput {
            assert output != null;
            assert !output.isBlank(); // at the very least we should have the preamble we populate it with
            assert parsedFragment != null;
        }
    }

    /**
     * Constructor for initial empty context
     */
    public Context(IContextManager contextManager, int autoContextFileCount, String initialOutputText) {
        this(newId(), // Generate a new ID for the initial context
             contextManager, List.of(), List.of(), List.of(), AutoContext.EMPTY, autoContextFileCount, new ArrayList<>(), Map.of(),
             getWelcomeOutput(initialOutputText),
             CompletableFuture.completedFuture(WELCOME_ACTION));
    }

    private static @NotNull ParsedOutput getWelcomeOutput(String initialOutputText) {
        return new ParsedOutput(initialOutputText, new ContextFragment.StringFragment(initialOutputText, "Welcome"));
    }

    /**
     * Constructor for initial empty context with empty output. Tests only
     */
    Context(IContextManager contextManager, int autoContextFileCount) {
        this(contextManager, autoContextFileCount, "placeholder");
    }

    private Context(int id,
                    IContextManager contextManager,
                    List<ContextFragment.ProjectPathFragment> editableFiles,
                    List<ContextFragment.PathFragment> readonlyFiles,
                    List<ContextFragment.VirtualFragment> virtualFragments,
                    AutoContext autoContext,
                    int autoContextFileCount,
                    List<ChatMessage> historyMessages,
                    Map<ProjectFile, String> originalContents,
                    ParsedOutput parsedOutput,
                    Future<String> action)
    {
        assert id > 0;
        assert contextManager != null;
        assert editableFiles != null;
        assert readonlyFiles != null;
        assert virtualFragments != null;
        assert autoContext != null;
        assert autoContextFileCount >= 0;
        assert historyMessages != null;
        assert originalContents != null;
        assert action != null;
        this.id = id;
        this.contextManager = contextManager;
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.autoContext = autoContext;
        this.autoContextFileCount = autoContextFileCount;
        this.historyMessages = historyMessages;
        this.originalContents = originalContents;
        this.parsedOutput = parsedOutput;
        this.action = action;
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.ProjectPathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !editableFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Edit " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !readonlyFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(toAdd);

        String actionDetails = toAdd.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Read " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeEditableFiles(List<ContextFragment.PathFragment> fragments) {
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.removeAll(fragments);
        if (newEditable.equals(editableFiles)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context removeReadonlyFiles(List<? extends ContextFragment.PathFragment> fragments) {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.removeAll(fragments);
        if (newReadOnly.equals(readonlyFiles)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeVirtualFragments(List<? extends ContextFragment.VirtualFragment> fragments) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.removeAll(fragments);
        if (newFragments.equals(virtualFragments)) {
            return this;
        }

        String actionDetails = fragments.stream()
                .map(ContextFragment::shortDescription)
                .collect(Collectors.joining(", "));
        String action = "Removed " + actionDetails;
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        String action = "Added " + fragment.shortDescription();
        return getWithFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    /**
     * Adds a virtual fragment and uses the same future for both fragment description and action
     */
    public Context addPasteFragment(ContextFragment.PasteFragment fragment, Future<String> summaryFuture) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);

        // Create a future that prepends "Added " to the summary
        Future<String> actionFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return "Added paste of " + summaryFuture.get();
            } catch (Exception e) {
                return "Added paste";
            }
        });

        return withFragments(editableFiles, readonlyFiles, newFragments, actionFuture);
    }

    public Context addSearchFragment(Future<String> query, ParsedOutput parsed) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(parsed.parsedFragment);
        return new Context(newId(), contextManager, editableFiles, readonlyFiles, newFragments, autoContext, autoContextFileCount, historyMessages, Map.of(), parsed, query).refresh();
    }

    public Context removeBadFragment(ContextFragment f) {
        if (f instanceof ContextFragment.PathFragment pf) {
            var inEditable = editableFiles.contains(pf);
            var inReadonly = readonlyFiles.contains(pf);

            if (inEditable) {
                var newEditable = new ArrayList<>(editableFiles);
                newEditable.remove(pf);
                return getWithFragments(newEditable, readonlyFiles, virtualFragments,
                                        "Removed unreadable " + pf.description());
            } else if (inReadonly) {
                var newReadonly = new ArrayList<>(readonlyFiles);
                newReadonly.remove(pf);
                return getWithFragments(editableFiles, newReadonly, virtualFragments,
                                        "Removed unreadable " + pf.description());
            }
            return this;
        } else if (f instanceof ContextFragment.VirtualFragment vf) {
            var newFragments = new ArrayList<>(virtualFragments);
            if (newFragments.remove(vf)) {
                return getWithFragments(editableFiles, readonlyFiles, newFragments,
                                        "Removed unreadable " + vf.description());
            }
            return this;
        } else {
            throw new IllegalArgumentException("Unknown fragment type: " + f);
        }
    }

    @NotNull
    private Context getWithFragments(List<ContextFragment.ProjectPathFragment> newEditableFiles,
                                     List<ContextFragment.PathFragment> newReadonlyFiles,
                                     List<ContextFragment.VirtualFragment> newVirtualFragments,
                                     String action) {
        return withFragments(newEditableFiles, newReadonlyFiles, newVirtualFragments, CompletableFuture.completedFuture(action));
    }

    /**
     * Sets how many files are pulled out of pagerank results. Uses 2*n in the pagerank call to
     * account for any out-of-project exclusions. Rebuilds autoContext if toggled on.
     */
    public Context setAutoContextFiles(int fileCount) {
        Future<String> action;
        if (fileCount == 0 && autoContextFileCount > 0) {
            action = CompletableFuture.completedFuture("Disabled auto-context");
        } else if (fileCount > 0 && autoContextFileCount == 0) {
            action = CompletableFuture.completedFuture("Enabled auto-context of " + fileCount + " files");
        } else if (fileCount > 0) {
            action = CompletableFuture.completedFuture("Auto-context size -> " + fileCount);
        } else {
            // No change in state - auto-context remains disabled
            return this;
        }

        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           autoContext,
                           fileCount,
                           historyMessages,
                           Map.of(),
                           null,
                           action).refresh();
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Build a multiline skeleton text for the top autoContextFileCount results
     * 4) Return the new AutoContext instance
     */
    private AutoContext buildAutoContext() {
        if (!isAutoContextEnabled()) {
            return AutoContext.DISABLED;
        }

        var analyzer = contextManager.getAnalyzer();

        // Collect ineligible classnames from fragments not eligible for auto-context
        var project = contextManager.getProject();
        var ineligibleSources = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream())
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.sources(project).stream())
                .collect(Collectors.toSet());

        // Collect initial seeds
        var weightedSeeds = new HashMap<String, Double>();
        // editable files have a weight of 1.0, each
        editableFiles.stream().flatMap(f -> f.sources(project).stream()).forEach(unit -> {
            weightedSeeds.put(unit.fqName(), 1.0);
        });
        // everything else splits a weight of 1.0
        Streams.concat(readonlyFiles.stream(), virtualFragments.stream())
                .flatMap(f -> f.sources(project).stream())
                .forEach(unit ->
                         {
                             weightedSeeds.merge(unit.fqName(), 1.0 / (readonlyFiles.size() + virtualFragments.size()), Double::sum);
                         });

        // If no seeds, we can't compute pagerank
        if (weightedSeeds.isEmpty()) {
            return AutoContext.EMPTY;
        }

        var sf = buildAutoContext(analyzer, weightedSeeds, ineligibleSources, autoContextFileCount);
        return sf.skeletons.isEmpty() ? AutoContext.EMPTY : new AutoContext(sf);
    }

    public static SkeletonFragment buildAutoContext(IAnalyzer analyzer, Map<String, Double> weightedSeeds, Set<CodeUnit> ineligibleSources, int topK) {
        var pagerankResults = AnalyzerUtil.combinedPagerankFor(analyzer, weightedSeeds);

        // build skeleton map
        var skeletonMap = new HashMap<CodeUnit, String>();
        for (var codeUnit : pagerankResults) {
            var fqcn = codeUnit.fqName();
            var sourceFileOption = analyzer.getFileFor(fqcn);
            if (sourceFileOption.isEmpty()) {
                logger.warn("No source file found for class {}", fqcn);
                continue;
            }
            var sourceFile = sourceFileOption.get();
            // Check if the class or its parent is in ineligible classnames
            boolean eligible = !(ineligibleSources.contains(codeUnit)
                    || (fqcn.contains("$") && ineligibleSources.contains(CodeUnit.cls(sourceFile, fqcn.substring(0, fqcn.indexOf('$'))))));

            if (eligible) {
                var opt = analyzer.getSkeleton(fqcn);
                if (opt.isDefined()) {
                    skeletonMap.put(codeUnit, opt.get());
                }
            }
            if (skeletonMap.size() >= topK) {
                break;
            }
        }

        return new SkeletonFragment(skeletonMap);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment.ProjectPathFragment> editableFiles() {
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

    public boolean isAutoContextEnabled() {
        return autoContextFileCount > 0;
    }

    public AutoContext getAutoContext() {
        return autoContext;
    }

    public int getAutoContextFileCount() {
        return autoContextFileCount;
    }

    /**
     * Creates a new context with custom collections and action description,
     * refreshing auto-context if needed.
     */
    private Context withFragments(List<ContextFragment.ProjectPathFragment> newEditableFiles,
                                  List<ContextFragment.PathFragment> newReadonlyFiles,
                                  List<ContextFragment.VirtualFragment> newVirtualFragments,
                                  Future<String> action) {
        return new Context(
                newId(),
                contextManager,
                newEditableFiles,
                newReadonlyFiles,
                newVirtualFragments,
                autoContext,
                autoContextFileCount,
                historyMessages,
                Map.of(),
                null,
                action
        ).refresh();
    }

    public Context removeAll() {
        String action = "Dropped all context";
        return clearHistory().getWithFragments(List.of(), List.of(), List.of(), action);
    }

    /**
     * Produces a new Context object with a fresh AutoContext if enabled.
     * This new context will retain the ID of the original context.
     */
    public Context refresh() {
        AutoContext acPlaceholder = isAutoContextEnabled() ? AutoContext.REBUILDING : AutoContext.DISABLED;
        // Create placeholder context, preserving the current ID
        var newContext = new Context(this.id, // Preserve the ID
                                     contextManager,
                                     editableFiles,
                                     readonlyFiles,
                                     virtualFragments,
                                     acPlaceholder,
                                     autoContextFileCount,
                                     historyMessages,
                                     originalContents,
                                     parsedOutput,
                                     action);
        contextManager.submitBackgroundTask("Computing AutoContext", () -> {
            var newAutoContext = buildAutoContext();
            // Construct the final replacement context, preserving the ID from the placeholder context (which is the original ID)
            var replacement = new Context(newContext.id, // Preserve the ID
                                          contextManager,
                                          editableFiles,
                                          readonlyFiles,
                                          virtualFragments,
                                          newAutoContext,
                                          autoContextFileCount,
                                          historyMessages,
                                          originalContents,
                                          parsedOutput,
                                          action);
            contextManager.replaceContext(newContext, replacement);
            return null;
        });

        return newContext;
    }

    // Method removed in favor of toFragment(int position)

    public boolean isEmpty() {
        return editableFiles.isEmpty()
                && readonlyFiles.isEmpty()
                && virtualFragments.isEmpty()
                && historyMessages.isEmpty();
    }

    public Context addHistory(List<ChatMessage> newMessages, Map<ProjectFile, String> originalContents, ParsedOutput parsed, Future<String> action) {
        var newHistory = new ArrayList<>(historyMessages);
        newHistory.addAll(newMessages);
        return new Context(
                newId(),
                contextManager,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                List.copyOf(newHistory),
                originalContents,
                parsed,
                action
        ).refresh();
    }

    public Context clearHistory() {
        return new Context(
                newId(), // New ID for cleared history
                contextManager,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                List.of(),
                Map.of(),
                null,
                CompletableFuture.completedFuture("Cleared conversation history")
        );
    }

    public Context withOriginalContents(Map<ProjectFile, String> fileContents) {
        // This context is temporary/internal for undo, does not represent a new user action,
        // so it retains the same ID and does not call refresh.
        return new Context(
                this.id, // Retain the same ID
                contextManager,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                historyMessages,
                fileContents,
                this.parsedOutput,
                this.action
        );
    }

    /**
     * @return an immutable copy of the history messages
     */
    public List<ChatMessage> getHistory() {
        return List.copyOf(historyMessages);
    }

    /**
     * Get the action that created this context
     */
    public String getAction() {
        if (action.isDone()) {
            try {
                return action.get();
            } catch (Exception e) {
                return "(Error retrieving action)";
            }
        }
        return SUMMARIZING;
    }

    /**
     * Get the unique transient identifier for this context instance.
     */
    public int getId() {
        return id;
    }
    
    /**
     * Returns all fragments in display order:
     * 0 => conversation history (if not empty)
     * 1 => autoContext (always present, even when DISABLED)
     * next => read-only (readonlyFiles + virtualFragments)
     * finally => editable
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        // First include conversation history if not empty
        if (!historyMessages.isEmpty()) {
            result.add(new ContextFragment.ConversationFragment(historyMessages));
        }

        // Then include autoContext
        result.add(autoContext);

        // then read-only
        result.addAll(readonlyFiles);
        result.addAll(virtualFragments);

        // then editable
        result.addAll(editableFiles);

        return result;
    }

    public Context withParsedOutput(ParsedOutput parsedOutput, Future<String> action) {
        return new Context(newId(),
                           contextManager,
                           editableFiles,
                           readonlyFiles,
                           virtualFragments,
                           autoContext,
                           autoContextFileCount,
                           historyMessages,
                           originalContents,
                           parsedOutput,
                           action).refresh();
    }

    public ParsedOutput getParsedOutput() {
        return parsedOutput;
    }

    /**
     * Serializes a Context object to a byte array
     */
    public static byte[] serialize(Context ctx) throws IOException {
        try (var baos = new java.io.ByteArrayOutputStream();
             var oos = new java.io.ObjectOutputStream(baos)) {
            oos.writeObject(ctx);
            return baos.toByteArray();
        }
    }

    /**
     * Deserializes a Context object from a byte array
     */
    public static Context deserialize(byte[] data, String welcomeMessage) throws IOException, ClassNotFoundException {
        try (var bais = new java.io.ByteArrayInputStream(data);
             var ois = new java.io.ObjectInputStream(bais))
        {
            var ctx = (Context) ois.readObject();
            // inject our welcome message as parsed output
            var parsedOutputField = Context.class.getDeclaredField("parsedOutput");
            parsedOutputField.setAccessible(true);
            parsedOutputField.set(ctx, getWelcomeOutput(welcomeMessage));
            return ctx;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Serial
    private void writeObject(java.io.ObjectOutputStream oos) throws IOException {
        // Write non-transient fields
        oos.defaultWriteObject();
    }

    @Serial
    private void readObject(java.io.ObjectInputStream ois) throws IOException, ClassNotFoundException {
        // Read non-transient fields
        ois.defaultReadObject();

        try {
            // Use reflection to set final fields
            var historyField = Context.class.getDeclaredField("historyMessages");
            historyField.setAccessible(true);
            historyField.set(this, new ArrayList<>());

            var originalContentsField = Context.class.getDeclaredField("originalContents");
            originalContentsField.setAccessible(true);
            originalContentsField.set(this, Map.of());

            var contextManagerField = Context.class.getDeclaredField("contextManager");
            contextManagerField.setAccessible(true);
            contextManagerField.set(this, null); // This will need to be set externally after deserialization

            var actionField = Context.class.getDeclaredField("action");
            actionField.setAccessible(true);
            actionField.set(this, CompletableFuture.completedFuture(WELCOME_BACK));

            var idField = Context.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(this, newId()); // Assign new ID on deserialization
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IOException("Failed to initialize fields during deserialization", e);
        }
    }

    /**
     * Creates a new Context with the specified context manager.
     * Used to initialize the context manager reference after deserialization.
     * This does not represent a new state, so it retains the ID.
     */
    public Context withContextManager(IContextManager contextManager) {
        return new Context(
                this.id, // Retain ID from deserialized object
                contextManager,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                historyMessages,
                originalContents,
                parsedOutput,
                action
        );
    }

    /**
     * Creates a new Context that copies specific elements from the provided context.
     * This creates a reset point by:
     * - Using the files and fragments from the source context
     * - Keeping the history messages from the current context
     * - Setting up properly for rebuilding autoContext
     * - Clearing parsed output and original contents
     * - Setting a suitable action description
     */
    public static Context createFrom(Context sourceContext, Context currentContext) {
        assert sourceContext != null;
        assert currentContext != null;

        return new Context(newId(), // New ID for the reset point
                           currentContext.contextManager,
                           sourceContext.editableFiles,
                           sourceContext.readonlyFiles,
                           sourceContext.virtualFragments,
                           AutoContext.REBUILDING,
                           sourceContext.autoContextFileCount,
                           currentContext.historyMessages,
                           Map.of(),
                           null,
                           CompletableFuture.completedFuture("Reset context to historical state")).refresh();
    }
}
