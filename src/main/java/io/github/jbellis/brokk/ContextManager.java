package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.PreparePrompts;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.Candidate;
import org.msgpack.core.annotations.VisibleForTesting;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages the current and previous context, along with other state like prompts and message history.
 */
// TODO standardize handling of paths -- should they be relative or absolute and does Context manage that or do we
public class ContextManager implements IContextManager {
    private static List<RepoFile> gitTrackedFilesCache = null;

    public static synchronized List<RepoFile> getTrackedFiles() {
        if (gitTrackedFilesCache != null) {
            return gitTrackedFilesCache;
        }
        gitTrackedFilesCache = Environment.instance.getGitTrackedFiles();
        return gitTrackedFilesCache;
    }

    private final Analyzer analyzer;
    final Path root;
    private ConsoleIO io;
    private Coder coder;
    private final ExecutorService backgroundTasks = Executors.newCachedThreadPool();
    private Future<BuildCommand> buildCommand;
    
    private final Project project;
    private Context currentContext;
    private final List<Context> previousContexts = new ArrayList<>();
    private static final int MAX_UNDO_DEPTH = 100;

    private final List<ChatMessage> historyMessages;

    /**
     * List of commands; typically constructed in Brokk.buildCommands(...) and passed in.
     * We store them here so we can execute them in handleCommand(...) below.
     */
    private final List<Command> commands;

    // ------------------------------------------------------------------
    // Constructor and core fields
    // ------------------------------------------------------------------

    public ContextManager(Analyzer analyzer,
                          Path root,
                          ConsoleIO io,
                          Coder coder) {
        this.analyzer = analyzer;
        this.root = root.toAbsolutePath();
        this.io = io;
        this.commands = buildCommands(analyzer);

        // Start with an empty Context
        this.currentContext = new Context(analyzer);
        this.coder = coder;

        this.historyMessages = new ArrayList<>();
        project = new Project(root, io);
    }

    /**
     * Command construction.
     */
    private List<Command> buildCommands(Analyzer analyzer) {
        return List.of(
                new Command(
                        "add",
                        "Add files by name or by fragment references",
                        this::cmdAdd,
                        "<files>|<fragment>",
                        this::completeAdd
                ),
                new Command(
                        "ask",
                        "Ask a question about the session context",
                        this::cmdAsk,
                        "<question>",
                        args -> List.of()
                ),
                new Command(
                        "autosummaries",
                        "Number of related classes to summarize (0 to disable)",
                        this::cmdAutoContext,
                        "<count>",
                        args -> List.of()
                ),
                new Command(
                        "clear",
                        "Clear chat history",
                        args -> cmdClear()
                ),
                new Command(
                        "commit",
                        "Generate commit message and commit changes",
                        args -> cmdCommit()
                ),
                new Command(
                        "copy",
                        "Copy current context to clipboard (or specific fragment if given)",
                        this::cmdCopy,
                        "[fragment]",
                        this::completeDrop
                ),
                new Command(
                        "drop",
                        "Drop files from chat (all if no args)",
                        this::cmdDrop,
                        "<files>|<fragment>",
                        this::completeDrop
                ),
                new Command(
                        "help",
                        "Show this help",
                        args -> cmdHelp()
                ),
                new Command(
                        "read",
                        "Add files by name or by fragment references",
                        this::cmdReadOnly,
                        "<files>|<fragment>",
                        this::completeRead
                ),
                new Command(
                        "refresh",
                        "Refresh code intelligence data",
                        args -> cmdRefresh(analyzer)
                ),
                new Command(
                        "undo",
                        "Undo last context changes (/add, /read, /drop)",
                        args -> cmdUndo()
                ),
                new Command(
                        "paste",
                        "Paste content to add as read-only snippet",
                        args -> cmdPaste()
                ),
                new Command(
                        "usage",
                        "Capture the source code of usages of the target method or field",
                        this::cmdUsage,
                        "<method/field>",
                        input -> completeUsage(input, this.analyzer)
                ),
                new Command(
                        "stacktrace",
                        "Parse Java stacktrace and extract relevant methods",
                        args -> cmdStacktrace()
                ),
                new Command(
                        "summarize",
                        "Generate a skeleton summary of the named class or fragment",
                        this::cmdSummarize,
                        "<classname>",
                        this::completeSummarize
                ),
                new Command(
                        "prepare",
                        "Evaluate context for the given request",
                        this::cmdPrepare,
                        "<request>",
                        args -> List.of()
                )
        );
    }

    /**
     * always an absolute path
     */
    public Path getRoot() {
        return root;
    }

    @VisibleForTesting
    static List<Candidate> completeUsage(String input, IAnalyzer analyzer) {
        return completeClassesAndMembers(input, analyzer, true);
    }

    private List<Candidate> completeSummarize(String input) {
        List<Candidate> candidates = new ArrayList<>();
                candidates.addAll(completeDrop(input)); // Add fragment completion
                candidates.addAll(completePaths(input, getTrackedFiles())); // Add path completion
                return candidates;
    }

    private OperationResult cmdSummarize(String input) {
        var trimmed = input.trim();
        if (trimmed.isBlank()) {
            return OperationResult.error("Please provide a file path or fragment reference");
        }

        // First check if it's a virtual fragment reference
        try {
            var fragment = currentContext.toFragment(input);
            if (fragment instanceof ContextFragment.AutoContext) {
                return OperationResult.error("Autocontext is already summarized");
            }
            if (fragment != null) {
                return summarizeClasses(fragment.classnames(analyzer));
            }
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }

        // If not a fragment reference, try as a file path
        var files = expandPath(trimmed);
        if (files.isEmpty()) {
            return OperationResult.error("No files found matching: " + trimmed);
        }
        if (files.size() > 1) {
            return OperationResult.error("Multiple files match '%s'. Please specify one of: %s"
                    .formatted(trimmed, files.stream()
                            .map(f -> "\n  " + f)
                            .collect(Collectors.joining())));
        }

        return summarizeFile(files.getFirst());
    }

    private OperationResult summarizeFile(RepoFile file) {
        var classesInFile = analyzer.classesInFile(file).stream()
                .filter(name -> !name.contains("$"))
                .collect(Collectors.toSet());
        if (classesInFile.isEmpty()) {
            classesInFile = analyzer.classesInFile(file);
        }

        return summarizeClasses(classesInFile);
    }

    @NotNull
    private OperationResult summarizeClasses(Set<String> classesInFile) {
        // coalesce inner classes when a parent class is present
        // {A, A$B} -> {A}
        var coalescedClassnames = classesInFile.stream()
                .filter(className -> {
                    // Keep this class if:
                    // 1. It has no $ (not an inner class)
                    // 2. OR its parent class is not in the set
                    if (!className.contains("$")) {
                        return true;
                    }
                    String parentClass = className.substring(0, className.indexOf('$'));
                    return !classesInFile.contains(parentClass);
                })
                .collect(Collectors.toSet());

        // Build combined skeleton of all classes
        List<String> shortClassnames = new ArrayList<>();
        StringBuilder combinedSkeleton = new StringBuilder();
        for (String fqcn : coalescedClassnames) {
            var skeleton = analyzer.getSkeleton(fqcn);
            if (skeleton.isDefined()) {
                shortClassnames.add(getShortClassName(fqcn));
                if (!combinedSkeleton.isEmpty()) {
                    combinedSkeleton.append("\n\n");
                }
                combinedSkeleton.append(skeleton.get());
            }
        }

        if (combinedSkeleton.isEmpty()) {
            return OperationResult.error("Unable to read source to summarize");
        }

        pushContext();
        currentContext = currentContext.addSkeletonFragment(shortClassnames, coalescedClassnames, combinedSkeleton.toString());
        return OperationResult.success();
    }

    @VisibleForTesting
    static List<Candidate> completeClassesAndMembers(String input, IAnalyzer analyzer, boolean returnFqn) {
        var allClasses = analyzer.getAllClasses();
        String partial = input.trim();

        var matchingClasses = findClassesForMemberAccess(input, allClasses);
        if (matchingClasses.size() == 1) {
            // find matching members
            List<Candidate> results = new ArrayList<>();
            for (var matchedClass : matchingClasses) {
                String memberPrefix = partial.substring(partial.lastIndexOf(".") + 1);
                // Add members
                var trueMembers = analyzer.getMembersInClass(matchedClass).stream().filter(m -> !m.contains("$")).toList();
                for (String fqMember : trueMembers) {
                    String shortMember = fqMember.substring(fqMember.lastIndexOf('.') + 1);
                    if (shortMember.startsWith(memberPrefix)) {
                        String display = returnFqn ? fqMember : getShortClassName(matchedClass) + "." + shortMember;
                        results.add(new Candidate(display, display, null, null, null, null, true));
                    }
                }
            }
            return results;
        }

        // Otherwise, we're completing class names
        String partialLower = partial.toLowerCase();
        Set<String> matchedClasses = new TreeSet<>();

        // Gather matching classes
        if (partial.isEmpty()) {
            matchedClasses.addAll(allClasses);
        } else {
            var st = returnFqn ? allClasses.stream() : allClasses.stream().map(ContextManager::getShortClassName);
            st.forEach(name -> {
                if (name.toLowerCase().startsWith(partialLower)
                    || getShortClassName(name).toLowerCase().startsWith(partialLower))
                {
                    matchedClasses.add(name);
                }
            });

            matchedClasses.addAll(getClassnameMatches(partial, allClasses));
        }

        // Return just the class names
        return matchedClasses.stream()
                .map(fqClass -> {
                    String display = returnFqn ? fqClass : getShortClassName(fqClass);
                    return new Candidate(display, display, null, null, null, null, false);
                })
                .collect(Collectors.toList());
    }

    /**
     * Return the FQCNs corresponding to input if it identifies an unambiguous class in [the FQ] allClasses
     */
    static Set<String> findClassesForMemberAccess(String input, List<String> allClasses) {
        // suppose allClasses = [a.b.Do, a.b.Do$Re, d.Do, a.b.Do$Re$Sub]
        // then we want
        // a -> []
        // a.b -> []
        // a.b.Do -> []
        // a.b.Do. -> [a.b.Do]
        // Do -> []
        // Do. -> [a.b.Do, d.Do]
        // Do.foo -> [a.b.Do, d.Do]
        // foo -> []
        // Do$Re -> []
        // Do$Re. -> [a.b.Do$Re]
        // Do$Re$Sub -> [a.b.Do$ReSub]

        // Handle empty or null inputs
        if (input == null || input.isEmpty() || allClasses == null) {
            return Set.of();
        }

        // first look for an unambiguous match to the entire input
        var lowerCase = input.toLowerCase();
        var prefixMatches = allClasses.stream()
                .filter(className -> className.toLowerCase().startsWith(lowerCase)
                        || getShortClassName(className).toLowerCase().startsWith(lowerCase))
                .collect(Collectors.toSet());
        if (prefixMatches.size() == 1) {
            return prefixMatches;
        }

        if (input.lastIndexOf(".") < 0) {
            return Set.of();
        }

        // see if the input-before-dot is a classname
        String possibleClassname = input.substring(0, input.lastIndexOf("."));
        return allClasses.stream()
                .filter(className -> className.equalsIgnoreCase(possibleClassname)
                        || getShortClassName(className).equalsIgnoreCase(possibleClassname))
                .collect(Collectors.toSet());
    }

    /**
     * This only does syntactic parsing, if you need to verify whether the parsed element
     * is actually a class, getUniqueClass() may be what you want
     */
    static String getShortClassName(String fqClass) {
        // a.b.C -> C
        // a.b.C. -> C
        // C -> C
        // a.b.C$D -> C$D
        // a.b.C$D. -> C$D.

        int lastDot = fqClass.lastIndexOf('.');
        if (lastDot == -1) {
            return fqClass;
        }

        // Handle trailing dot
        if (lastDot == fqClass.length() - 1) {
            int nextToLastDot = fqClass.lastIndexOf('.', lastDot - 1);
            return fqClass.substring(nextToLastDot + 1, lastDot);
        }

        return fqClass.substring(lastDot + 1);
    }

    /**
     * Given a non-fully qualified classname, complete it with camel case or prefix matching 
     * to a FQCN
     */
    static Set<String> getClassnameMatches(String partial, List<String> allClasses) {
        var partialLower = partial.toLowerCase();
        var nameMatches = new HashSet<String>();
        for (String fqClass : allClasses) {
            // fqClass = a.b.c.FooBar$LedZep

            // Extract the portion after the last '.' and the last '$' if present
            // simpleName = FooBar$LedZep
            String simpleName = fqClass;
            int lastDot = fqClass.lastIndexOf('.');
            if (lastDot >= 0) {
                simpleName = fqClass.substring(lastDot + 1);
            }

            // Now also strip off nested classes for simpler matching
            // simpleName = LedZep
            int lastDollar = simpleName.lastIndexOf('$');
            if (lastDollar >= 0) {
                simpleName = simpleName.substring(lastDollar + 1);
            }

            // Check for simple prefix match
            if (simpleName.toLowerCase().startsWith(partialLower)) {
                nameMatches.add(fqClass);
            } else {
                var capitals = extractCapitals(simpleName);
                if (capitals.toLowerCase().startsWith(partialLower)) {
                    nameMatches.add(fqClass);
                }
            }
        }
        return nameMatches;
    }

    /**
     * Processes a command argument as a virtual fragment position and returns any associated files.
     * @return The files referenced by the fragment, or null if the argument wasn't a valid position.
     */
    private Set<RepoFile> getFilesFromVirtualFragment(String args) {
        if (!args.trim().matches("\\d+")) {
            return null;
        }

        int position = Integer.parseInt(args.trim()) - 1; // UI shows 1-based positions
        var fragment = currentContext.virtualFragments()
                .filter(f -> f.position() == position)
                .findFirst();
        if (fragment.isEmpty()) {
            throw new IllegalArgumentException("No virtual fragment found at position " + (position + 1));
        }
        
        // Get classnames from fragment and convert to files
        var classnames = fragment.get().classnames(analyzer);
        var files = classnames.stream()
                .map(analyzer::pathOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found for fragment at position " + (position + 1));
        }

        return files;
    }

    private OperationResult cmdAdd(String args) {
        if (args.isBlank()) {
            return OperationResult.error("Please provide filename(s) or a git commit");
        }

        try {
            var fragmentFiles = getFilesFromVirtualFragment(args);
            if (fragmentFiles != null) {
                addFiles(fragmentFiles);
                return OperationResult.success();
            }
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }

        var filenames = parseQuotedFilenames(args);
        for (String token : filenames) {
            var matches = expandPath(token);
            if (matches.isEmpty()) {
                if (io.confirmAsk("No files matched '%s'. Create?".formatted(token))) {
                    try {
                        var newFile = createFile(token);
                        addFiles(List.of(newFile));
                        Environment.instance.gitAdd(newFile.toString());
                    } catch (Exception e) {
                        return OperationResult.error(
                                "Error creating filename %s: %s".formatted(token, e.getMessage()));
                    }
                }
            } else {
                addFiles(matches);
            }
        }
        return OperationResult.success();
    }

    private RepoFile createFile(String relName) throws IOException {
        var file = toFile(relName);
        file.create();
        return file;
    }

    @Override
    public RepoFile toFile(String relName) {
        return new RepoFile(root, relName);
    }

    /**
     * /read: same logic as /add, but any matched or created files become read-only.
     * If the user provides a commit ref, it fetches those changed files and adds them read-only.
     */
    private OperationResult cmdReadOnly(String args) {
        if (args.isBlank()) {
            convertAllToReadOnly();
            return OperationResult.success();
        }

        try {
            var fragmentFiles = getFilesFromVirtualFragment(args);
            if (fragmentFiles != null) {
                addReadOnlyFiles(fragmentFiles);
                return OperationResult.success();
            }
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }

        var filenames = parseQuotedFilenames(args);
        for (String token : filenames) {
            var matches = expandPath(token);
            if (matches.isEmpty()) {
                return OperationResult.error("No matches found for: " + token);
            } else {
                addReadOnlyFiles(matches);
            }
        }
        return OperationResult.success();
    }



    /**
     * /add autocompleter:
     *   1. filename candidates
     *   2. short commit hashes
     */
    private List<Candidate> completeAdd(String partial) {
        List<Candidate> pathCandidates = completePaths(partial, getTrackedFiles());
        List<Candidate> commitCandidates = completeCommits(partial);

        List<Candidate> all = new ArrayList<>(pathCandidates.size() + commitCandidates.size());
        all.addAll(pathCandidates);
        all.addAll(commitCandidates);
        return all;
    }

    /**
     * /read autocompleter: same logic as completeAdd.
     */
    private List<Candidate> completeRead(String partial) {
        List<Candidate> pathCandidates = completePaths(partial, getTrackedFiles());
        List<Candidate> commitCandidates = completeCommits(partial);

        List<Candidate> all = new ArrayList<>(pathCandidates.size() + commitCandidates.size());
        all.addAll(pathCandidates);
        all.addAll(commitCandidates);
        return all;
    }

    /**
     * Re-usable method for short-hash commit autocompletion.
     */
    private List<Candidate> completeCommits(String partial) {
        List<String> lines = Environment.instance.gitLogShort(); // e.g. short commits
        return lines.stream()
                .filter(line -> line.startsWith(partial))
                .map(Candidate::new)
                .collect(Collectors.toList());
    }

    private OperationResult cmdDrop(String args) {
        if (args.isBlank()) {
            dropAll();
            return OperationResult.success();
        }

        var filenames = parseQuotedFilenames(args);
        var fragments = new ArrayList<>();
        for (String filename : filenames) {
            var fragment = currentContext.toFragment(filename);
            if (fragment instanceof ContextFragment.AutoContext) {
                return cmdAutoContext("0");
            }
            if (fragment != null) {
                fragments.add(fragment);
            }
        }
        var paths = fragments.stream().filter(f -> f instanceof PathFragment).map(f -> (PathFragment) f).toList();
        var virtual = fragments.stream().filter(f -> f instanceof VirtualFragment).map(f -> (VirtualFragment) f).toList();

        pushContext();
        currentContext = currentContext.removeEditableFiles(paths).removeReadonlyFiles(paths).removeVirtualFragments(virtual);
        boolean dropped = previousContexts.getLast() != currentContext;
        return dropped
                ? OperationResult.success()
                : OperationResult.error("No matching content to drop");
    }

    private OperationResult cmdPrepare(String msg) {
        if (msg.isBlank()) {
            return OperationResult.error("Please provide a message");
        }

        var messages = PreparePrompts.instance.collectMessages(this);
        var st = """
        <instructions>
        Here is the request to evaluate.  Do NOT write code yet!
        Just evaluate whether you have the right summaries and files available.
        
        %s
        </instructions>
        """.formatted(msg.trim()).stripIndent();
        messages.add(new UserMessage(st.formatted(msg)));
        String response = coder.sendStreaming(messages);
        if (response != null) {
            moveToHistory(List.of(messages.getLast(), new AiMessage(response)));
        }
        var mentioned = findMissingFileMentions(response);
        confirmAddRequestedFiles(mentioned);

        return OperationResult.success();
    }

    public OperationResult cmdCopy(String args) {
        try {
            String content;
            if (args == null || args.trim().isEmpty()) {
                // Original behavior - copy everything
                var msgs = ArchitectPrompts.instance.collectMessages(this);
                content = msgs.stream()
                        .filter(m -> !(m instanceof AiMessage))
                        .map(ContextManager::getText)
                        .collect(Collectors.joining("\n\n"));
            } else {
                // Try to find matching fragment
                var fragment = currentContext.toFragment(args.trim());
                if (fragment == null) {
                    return OperationResult.error("No matching fragment found for: " + args);
                }
                    content = fragment.text();
                }
            
                var sel = new StringSelection(content);
                var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                cb.setContents(sel, sel);
                io.toolOutput("Content copied to clipboard");
            } catch (Exception e) {
                return OperationResult.error("Failed to copy to clipboard: " + e.getMessage());
            }
            return OperationResult.skipShow();
        }

    private OperationResult cmdHelp() {
        String cmdText = commands.stream()
                .sorted(Comparator.comparing(Command::name))
                .map(cmd -> {
                    var commandArgs = cmd.args().isEmpty() ? "" : " " + cmd.args();
                    var cmdString = "/" + cmd.name() + commandArgs;
                    return formatCmdHelp(cmdString, cmd.description);
                })
                .collect(Collectors.joining("\n"));
        io.toolOutput("Available commands:\n");
        io.toolOutput(formatCmdHelp("$[cmd]", "Execute cmd in a shell and show its output"));
        io.toolOutput(formatCmdHelp("$$[cmd]", "Execute cmd in a shell and capture its stdout as context"));
        io.toolOutput(cmdText);
        io.toolOutput("TAB or Ctrl-space autocompletes in /add, /read, /drop, /edit commands");
        return OperationResult.skipShow();
    }

    private static String formatCmdHelp(String cmdString, String cmdDescription) {
        return String.format("%-20s - %s", cmdString, cmdDescription);
    }

    private OperationResult cmdAsk(String input) {
        if (input.isBlank()) {
            return OperationResult.error("Please provide a question");
        }

        var messages = AskPrompts.instance.collectMessages(this);
        messages.add(new UserMessage("<question>\n%s\n</question>".formatted(input.trim())));
        
        String response = coder.sendStreaming(messages);
        if (response != null) {
            moveToHistory(List.of(messages.getLast(), new AiMessage(response)));
        }
        
        return OperationResult.success();
    }

    private OperationResult cmdAutoContext(String args) {
        int fileCount;
        try {
            fileCount = Integer.parseInt(args);
        } catch (NumberFormatException e) {
            return OperationResult.error("/autocontext requires an integer parameter");
        }
        if (fileCount < 0) {
            return OperationResult.error("/autocontext requires a non-negative integer parameter");
        }
        if (fileCount > Context.MAX_AUTO_CONTEXT_FILES) {
            return OperationResult.error("/autocontext cannot be more than " + Context.MAX_AUTO_CONTEXT_FILES);
        }
        setAutoContextFiles(fileCount);
        return OperationResult.success("Autocontext size set to " + fileCount);
    }

    private OperationResult cmdClear() {
        // TODO move history into context so we can undo this
        historyMessages.clear();
        return OperationResult.success();
    }

    private OperationResult cmdCommit() {
        var messages = CommitPrompts.instance.collectMessages((ContextManager) coder.contextManager);
        String commitMsg = coder.sendMessage("Inferring commit suggestion", messages);

        if (commitMsg.isEmpty()) {
            return OperationResult.error("nothing to commit");
        }

        return OperationResult.prefill("$git commit -a -m \"%s\"".formatted(commitMsg));
    }

    private OperationResult cmdRefresh(Analyzer analyzer) {
        Environment.instance.gitRefresh();
        analyzer.rebuild();
        analyzer.writeGraphAsync();
        onRefresh();
        io.toolOutput("Code intelligence refresh complete");
        return OperationResult.skipShow();
    }

    private OperationResult cmdUndo() {
        loadPreviousContext();
        return OperationResult.success();
    }

    private OperationResult cmdUsage(String identifier) {
        identifier = identifier.trim();
        if (identifier.isBlank()) {
            return OperationResult.error("Please provide a symbol name to search for");
        }

        SymbolUsages uses;
        try {
            uses = analyzer.getUses(identifier);
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }

        // Check if we found any uses
        if (uses.getMethodUses().isEmpty() && uses.getTypeUses().isEmpty()) {
            return OperationResult.success("No uses found for " + identifier);
        }

        // Build code block containing all uses
        StringBuilder code = new StringBuilder();
        var classnames = new HashSet<String>();

        // Method uses
        if (!uses.getMethodUses().isEmpty()) {
            var sortedMethods = uses.getMethodUses().stream().sorted().toList();
            code.append("Method uses:\n\n");
            
            // Group methods by classname
            String currentClass = null;
            for (String method : sortedMethods) {
                String classname = ContextFragment.toClassname(method);
                if (!classname.equals(currentClass)) {
                    // Print class header when we switch to a new class
                    code.append("In ").append(classname).append(":\n\n");
                    currentClass = classname;
                }

                var source = analyzer.getMethodSource(method);
                if (source.isDefined()) {
                    classnames.add(classname);
                    code.append(source.get()).append("\n\n");
                }
            }
        }

        // Type uses
        if (!uses.getTypeUses().isEmpty()) {
            code.append("Type uses:\n\n");
            for (String className : uses.getTypeUses()) {
                var skeletonHeader = analyzer.getSkeletonHeader(className);
                if (skeletonHeader.isEmpty()) {
                    // TODO can we do better than just skipping anonymous classes that Analyzer doesn't know how to skeletonize?
                    // io.github.jbellis.brokk.Coder.sendMessage.StreamingResponseHandler$0.<init>' not found
                    continue;
                }
                code.append(skeletonHeader.get()).append("\n");
                classnames.add(className);
            }
        }

        pushContext();
        currentContext = currentContext.addUsageFragment(identifier, classnames, code.toString());
        return OperationResult.success();
    }

    private OperationResult cmdStacktrace() {
        io.toolOutput("Paste your stacktrace below and press Enter when done:");
        String stacktrace = io.getRawInput();
        if (stacktrace == null || stacktrace.isBlank()) {
            return OperationResult.error("No stacktrace pasted");
        }

        String[] lines = stacktrace.split("\n");
        String exception = "Unknown";
        Pattern exceptionPattern = Pattern.compile("^\\s*(\\S+):\\s*");
        for (String line : lines) {
            var matcher = exceptionPattern.matcher(line);
            if (matcher.find()) {
                exception = matcher.group(1);
                break;
            }
        }
        if (exception.equals("Unknown")) {
            io.toolOutput("Warning: Could not identify exception type from stacktrace");
        }
        StringBuilder content = new StringBuilder();
        var classnames = new HashSet<String>();

        // Process each line
        for (String line : lines) {
            // check that line looks like a stack entry
            if (!line.trim().startsWith("at ")) {
                continue;
            }
            String methodLine = line.trim().substring(3);
            int parenIndex = methodLine.indexOf('(');
            if (parenIndex <= 0) {
                continue;
            }

            String methodFullName = methodLine.substring(0, parenIndex);
            var methodSource = analyzer.getMethodSource(methodFullName);
            if (methodSource.isDefined()) {
                classnames.add(ContextFragment.toClassname(methodFullName));
                content.append(methodFullName).append(":\n");
                content.append(methodSource).append("\n\n");
            }
        }

        if (content.isEmpty()) {
            return OperationResult.error("no relevant methods found in stacktrace");
        }

        pushContext();
        currentContext = currentContext.addStacktraceFragment(classnames, stacktrace, exception, content.toString());
        return OperationResult.success();
    }

    private OperationResult cmdPaste() {
        io.toolOutput("Paste your content below and press Enter when done:");
        String pastedContent = io.getRawInput();
        if (pastedContent == null || pastedContent.isBlank()) {
            return OperationResult.error("No content pasted");
        }

        // Submit summarization task
        var summaryFuture = backgroundTasks.submit(() -> {
            var messages = List.of(
                    new UserMessage("Please summarize these changes in a single line:"),
                    new AiMessage("Ok, let's see them."),
                    new UserMessage(pastedContent)
            );
            return coder.sendMessage(messages);
        });

        pushContext();
        currentContext = currentContext.addPasteFragment(pastedContent, summaryFuture);
        return OperationResult.success("Added pasted content");
    }

    private List<Candidate> completeDrop(String partial) {
        String partialLower = partial.toLowerCase();

        return Streams.concat(currentContext.editableFiles(),
                              currentContext.readonlyFiles(),
                              currentContext.virtualFragments())
                .filter(f -> f.source().toLowerCase().startsWith(partialLower))
                .map(f -> new Candidate(f.source()))
                .collect(Collectors.toList());
    }

    private List<Candidate> completePaths(String partial, Collection<RepoFile> paths) {
        String partialLower = partial.toLowerCase();
        Map<String, RepoFile> baseToFullPath = new HashMap<>();
        List<Candidate> completions = new ArrayList<>();

        paths.forEach(p -> baseToFullPath.put(p.getFileName(), p));

        // Matching base filenames
        baseToFullPath.forEach((base, path) -> {
            if (base.toLowerCase().startsWith(partialLower)) {
                completions.add(fileCandidate(path));
            }
        });

        // Camel-case completions
        baseToFullPath.forEach((base, path) -> {
            var capitals = extractCapitals(base);
            if (capitals.toLowerCase().startsWith(partialLower)) {
                completions.add(fileCandidate(path));
            }
        });

        // Matching full paths
        paths.forEach(p -> {
            if (p.toString().toLowerCase().startsWith(partialLower)) {
                completions.add(fileCandidate(p));
            }
        });

        return completions;
    }

    private static String extractCapitals(String base) {
        StringBuilder capitals = new StringBuilder();
        for (char c : base.toCharArray()) {
            if (Character.isUpperCase(c)) {
                capitals.append(c);
            }
        }
        return capitals.toString();
    }

    private Candidate fileCandidate(RepoFile file) {
        return new Candidate(file.toString(), file.getFileName(), null, file.toString(), null, null, true);
    }

    public List<Command> getCommands() {
        return commands;
    }

    public void resolveCircularReferences(ConsoleIO io, Coder coder) {
        this.io = io;
        this.coder = coder;

        // infer build command from properties
        String loadedCommand = project.loadBuildCommand();
        if (loadedCommand != null) {
            buildCommand = CompletableFuture.completedFuture(BuildCommand.success(loadedCommand));
            io.toolOutput("Using saved build command: " + loadedCommand);
            return;
        }

        List<String> filenames = getTrackedFiles().stream()
                .map(RepoFile::toString)
                .filter(string -> !string.contains(File.separator))
                .collect(Collectors.toList());

        // corner case: if no top-level files identified
        if (filenames.isEmpty()) {
            filenames = getTrackedFiles().stream().map(RepoFile::toString).toList();
        }

        // prepare LLM prompt with the file list
        List<ChatMessage> messages = List.of(
                new SystemMessage("You are a build assistant that suggests a single command to perform a quick compile check."),
                new UserMessage(
                        "We have these files:\n\n" + filenames
                                + "\n\nSuggest a minimal single-line shell command to compile them incrementally, not a full build.  Respond with JUST the command, no commentary."
                )
        );

        // Submit the inference task to our background executor
        buildCommand = backgroundTasks.submit(() -> {
            String response;
            try {
                response = coder.sendMessage("Inferring build progress", messages);
            } catch (Throwable th) {
                return BuildCommand.failure(th.getMessage());
            }
            if (response.equals(Models.UNAVAILABLE)) {
                return BuildCommand.failure(Models.UNAVAILABLE);
            }

            String inferredCommand = response.trim();
            project.saveBuildCommand(inferredCommand);

            io.toolOutput("Inferred build command: " + response.trim());
            return BuildCommand.success(inferredCommand);
        });
    }


    @FunctionalInterface
    public interface CommandHandler {
        OperationResult handle(String args);
    }

    @FunctionalInterface
    public interface ArgumentCompleter {
        List<Candidate> complete(String partial);
    }

    public record Command(String name,
                          String description,
                          CommandHandler handler,
                          String args,
                          ArgumentCompleter argumentCompleter) {
        public Command(String name, String description, CommandHandler handler) {
            this(name, description, handler, "", (partial) -> List.of());
        }
    }

    public enum OperationStatus {
        SUCCESS,
        SKIP_SHOW,
        PREFILL,
        ERROR
    }

    public record OperationResult(OperationStatus status, String message) {
        public static OperationResult prefill(String msg) {
            return new OperationResult(OperationStatus.PREFILL, msg);
        }

        public static OperationResult success() {
            return new OperationResult(OperationStatus.SUCCESS, null);
        }

        public static OperationResult skipShow() {
            return new OperationResult(OperationStatus.SKIP_SHOW, null);
        }

        public static OperationResult error(String msg) {
            return new OperationResult(OperationStatus.ERROR, msg);
        }

        public static OperationResult success(String msg) {
            return new OperationResult(OperationStatus.SUCCESS, msg);
        }
    }

    /**
     * A small utility to join multiple strings with a delimiter, skipping empties.
     */
    private static class StreamUtils {
        static String joinNonEmpty(String s1, String s2, String delimiter) {
            if (s1.isEmpty() && s2.isEmpty()) return "";
            if (s1.isEmpty()) return s2;
            if (s2.isEmpty()) return s1;
            return s1 + delimiter + s2;
        }
    }

    // ------------------------------------------------------------------
    // Commands / Shell logic
    // ------------------------------------------------------------------


    @Override
    public OperationResult runBuild() {
        try {
            if (buildCommand.get().command == null) {
                io.toolOutput("No build command configured");
                return OperationResult.success();
            }

            BuildCommand cmd = buildCommand.get();
            io.toolOutput("Running " + cmd.command);
            return Environment.instance.captureShellCommand(cmd.command);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Determines if the given input is a "command" (including $ / $$ shell calls).
     */
    public boolean isCommand(String input) {
        return input.startsWith("/") || input.startsWith("$");
    }

    /**
     * Handles the input if it is recognized as a command or shell invocation.
     */
    public OperationResult handleCommand(String input) {
        // $$ => run shell command (stdout only), then store output as read-only snippet
        if (input.startsWith("$$")) {
            var command = input.substring(2).trim();
            OperationResult result = Environment.instance.captureShellCommand(command);
            if (result.status() == OperationStatus.SUCCESS) {
                // Add result to read-only snippet with the command as description
                if (result.message() != null) {
                    addStringFragment(command, result.message());
                }
            }
            else {
                assert result.status() == OperationStatus.ERROR;
                io.toolError(result.message());
            }
            return OperationResult.success();
        }
        // $ => run shell command + show output
        else if (input.startsWith("$")) {
            var command = input.substring(1).trim();
            OperationResult result = Environment.instance.captureShellCommand(command);
            // If it succeeded, show the output in yellow
            if (result.status() == OperationStatus.SUCCESS) {
                if (result.message() != null) {
                    io.shellOutput(result.message());
                }
            }
            else {
                assert result.status() == OperationStatus.ERROR;
                io.toolError(result.message());
            }
            return OperationResult.success(); // don't show output a second time
        }
        // /command => handle built-in commands
        else if (input.startsWith("/")) {
            return dispatchSlashCommand(input);
        }
        // If it's not one of those, skip
        return OperationResult.skipShow();
    }

    /**
     * Dispatch /command by matching against known commands.
     */
    private OperationResult dispatchSlashCommand(String input) {
        // Remove the leading '/'
        String noSlash = input.substring(1);
        String[] parts = noSlash.split("\\s+", 2);
        String typedCmd = parts[0];
        String args = (parts.length > 1) ? parts[1] : "";

        // Find all commands whose names start with typedCmd
        var matching = commands.stream()
                .filter(c -> c.name().startsWith(typedCmd))
                .toList();

        if (matching.isEmpty()) {
            return OperationResult.error("Unknown command: " + typedCmd);
        }
        if (matching.size() > 1) {
            String possible = matching.stream()
                    .map(Command::name)
                    .collect(Collectors.joining(", "));
            return OperationResult.error("Ambiguous command '%s' (matches: %s)".formatted(typedCmd, possible));
        }

        Command cmd = matching.getFirst();
        return cmd.handler().handle(args);
    }

    /**
     * Splits the given string by quoted or unquoted segments.
     */
    private static List<String> parseQuotedFilenames(String args) {
        var pattern = Pattern.compile("\"([^\"]+)\"|\\S+");
        return pattern.matcher(args)
                .results()
                .map(m -> m.group(1) != null ? m.group(1) : m.group())
                .toList();
    }

    /**
     * Expand paths that may contain wildcards (*, ?), returning all matches.
     */
    private List<RepoFile> expandPath(String pattern) {
        // First check if it exists as a relative filename from root
        var file = new RepoFile(root, pattern);
        if (file.exists()) {
            return List.of(file);
        }

        // Handle glob patterns
        if (pattern.contains("*") || pattern.contains("?")) {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try {
                return Files.walk(root)
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(root.relativize(p)))
                        .map(p -> new RepoFile(root, p))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // If not a glob and doesn't exist directly, look for matches in git tracked files
        var filename = Path.of(pattern).getFileName().toString();
        var matches = getTrackedFiles().stream()
                .filter(p -> p.getFileName().equals(filename))
                .toList();

        if (matches.isEmpty()) {
            return List.of();
        }
        if (matches.size() > 1) {
            return List.of();
        }

        return matches;
    }

    /**
     * Find filename references in user text, e.g. "fileName.java" etc.
     */
    @Override
    public Set<RepoFile> findMissingFileMentions(String text) {
        var byFilename = getTrackedFiles().stream().parallel()
                .filter(f -> currentContext.editableFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> currentContext.readonlyFiles().noneMatch(p -> f.equals(p.file())))
                .filter(f -> text.contains(f.getFileName()));
        var byClassname = analyzer.getAllClasses().stream()
                .filter(fqcn -> text.contains(List.of(fqcn.split("\\.")).getLast())) // simple classname in text
                .filter(fqcn -> currentContext.allFragments().noneMatch(fragment ->  // not already in context
                    fragment.classnames(analyzer).contains(fqcn)))
                .map(analyzer::pathOf)
                .filter(Objects::nonNull);

        return Streams.concat(byFilename, byClassname)
                .collect(Collectors.toSet());
    }

    /**
     * Optionally parse the LLM response for new filename references not already in context,
     * then add them, returning a reflection query.
     */
    @Override
    public Set<RepoFile> confirmAddRequestedFiles(Set<RepoFile> mentioned) {
        if (mentioned.isEmpty()) {
            return Set.of();
        }

        // remind user what current state is
        show();

        var toAdd = new HashSet<RepoFile>();
        var toRead = new HashSet<RepoFile>();
        var toSummarize = new HashSet<RepoFile>();

        boolean continueProcessing = true;
        for (var file : mentioned) {
            if (!continueProcessing) break;

            char choice = io.askOptions("Action for %s?".formatted(file),
                                        "(A)dd, (R)ead, (S)ummarize, (I)gnore, ig(N)ore all remaining");
            switch (choice) {
                case 'a' -> toAdd.add(file);
                case 'r' -> toRead.add(file);
                case 's' -> toSummarize.add(file);
                case 'n' -> continueProcessing = false;
                default -> {} // ignore
            }
        }

        // Process add and read in bulk
        if (!toAdd.isEmpty()) {
            addFiles(toAdd);
        }
        if (!toRead.isEmpty()) {
            addReadOnlyFiles(toRead);
        }

        // Process summarize one by one
        for (var file : toSummarize) {
            cmdSummarize(file.toString());
        }

        // Return all files that were processed in some way
        var allProcessed = new HashSet<RepoFile>();
        allProcessed.addAll(toAdd);
        allProcessed.addAll(toRead);
        allProcessed.addAll(toSummarize);
        return allProcessed;
    }

    @NotNull
    public String getReadOnlySummary() {
        return Streams.concat(currentContext.readonlyFiles().map(f -> f.file().toString()),
                              currentContext.virtualFragments().map(vf -> "'" + vf.description() + "'"))
                .collect(Collectors.joining(", "));
    }

    @NotNull
    public String getEditableSummary() {
        return currentContext.editableFiles()
                .map(p -> p.file().toString())
                .collect(Collectors.joining(", "));
    }

    public void dropAll() {
        pushContext();
        currentContext = currentContext.removeAll();
    }

    public void convertAllToReadOnly() {
        pushContext();
        currentContext = currentContext.convertAllToReadOnly();
    }

    @Override
    public void addFiles(Collection<RepoFile> files) {
        var fragments = toFragments(files);
        pushContext();
        currentContext = currentContext.removeReadonlyFiles(fragments).addEditableFiles(fragments);
    }

    private ArrayList<PathFragment> toFragments(Collection<RepoFile> files) {
        var fragments = new ArrayList<PathFragment>();
        for (var file : files) {
            fragments.add(new PathFragment(file));
        }
        return fragments;
    }

    public void addReadOnlyFiles(Collection<RepoFile> files) {
        var fragments = toFragments(files);
        pushContext();
        currentContext = currentContext.removeEditableFiles(fragments).addReadonlyFiles(fragments);
    }

    public void addStringFragment(String description, String content) {
        pushContext();
        currentContext = currentContext.addStringFragment(description, content);
    }

    public List<ChatMessage> getHistoryMessages() {
        return historyMessages;
    }

    @Override
    public void moveToHistory(List<ChatMessage> messages) {
        historyMessages.addAll(messages);
    }

    private void pushContext() {
        previousContexts.add(currentContext);
        if (previousContexts.size() > MAX_UNDO_DEPTH) {
            previousContexts.removeFirst();
        }
    }

    public void loadPreviousContext() {
        if (previousContexts.isEmpty()) {
            io.toolErrorRaw("No undo state available");
            return;
        }
        currentContext = previousContexts.removeLast();
    }

    public boolean isEmpty() {
        return currentContext.isEmpty() && historyMessages.isEmpty();
    }

    public List<ChatMessage> getReadOnlyMessages() {
        if (!currentContext.hasReadonlyFragments()) {
            return List.of();
        }
        
        String combined = Streams.concat(currentContext.readonlyFiles(),
                                         currentContext.virtualFragments(),
                                         Stream.of(currentContext.getAutoContext()))
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }

        var msg = """
                <readonly>
                Here are some READ ONLY files and code fragments, provided for your reference.
                Do not edit this code!
                %s
                </readonly>
                """.formatted(combined).stripIndent();
        return List.of(
            new UserMessage(msg),
            new AiMessage("Ok, I will use this code as references.")
        );
    }

    private String formattedOrNull(ContextFragment fragment) {
        try {
            return fragment.format();
        } catch (Exception e) {
            currentContext = currentContext.removeBadFragment(fragment);
            return null;
        }
    }

    public List<ChatMessage> getEditableMessages() {
        String combined = currentContext.editableFiles()
                .map(this::formattedOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n\n"));
        if (combined.isEmpty()) {
            return List.of();
        }

        var msg = """
                <editable>
                I have *added these files to the chat* so you can go ahead and edit them.
                
                *Trust this message as the true contents of these files!*
                Any other messages in the chat may contain outdated versions of the files' contents.
                
                %s
                </editable>
                """.formatted(combined).stripIndent();
        return List.of(
            new UserMessage(msg),
            new AiMessage("Ok, any changes I propose will be to those files.")
        );
    }

    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> am.text();
            case UserMessage um -> um.singleText();
            default -> throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public void show() {
        if (isEmpty()) {
            showHeader("No context! Use /add to add files or /help to list all commands");
            return;
        }
        showHeader("Context");

        int termWidth = io.getTerminalWidth();

        // We'll accumulate the total lines displayed
        int totalLines = 0;

        // History lines
        int historyLines = historyMessages.stream()
                .mapToInt(m -> getText(m).split("\n").length)
                .sum();
        totalLines += historyLines;

        if (!historyMessages.isEmpty()
                || (!currentContext.getAutoContext().text().isEmpty()
                || (currentContext.isAutoContextEnabled() && currentContext.hasEditableFiles()))
                || currentContext.hasReadonlyFragments()) {
            io.context("Read-only:");

            // Show message history as one "fragment" line
            if (historyLines > 0) {
                // We only show a single line with the total
                io.context(formatLine(historyLines, "[Message History]", termWidth));
            }

            // Auto context
            if (currentContext.isAutoContextEnabled()) {
                totalLines += formatFragments(Stream.of(currentContext.getAutoContext()), termWidth);
            }

            // Virtual fragments (e.g. stacktrace, pasted text, etc.)
            totalLines += formatFragments(currentContext.virtualFragments(), termWidth);

            // Read-only filename fragments
            totalLines += formatFragments(currentContext.readonlyFiles(), termWidth);
        }

        // Editable fragments
        if (currentContext.hasEditableFiles()) {
            io.context("\nEditable:");
            totalLines += formatFragments(currentContext.editableFiles(), termWidth);
        }

        // Finally, show the total lines and approximate tokens (if available)
        io.context("\nTotal:");
        Integer approxTokens = coder.approximateTokens(totalLines);
        if (approxTokens == null) {
            // No token approximation available
            io.context(formatLoc(totalLines) + " lines");
        } else {
            io.context(String.format("%s lines, about %,dk tokens",
                                     formatLoc(totalLines),
                                     approxTokens / 1000));
        }
    }

    private void showHeader(String label) {
        int width = io.getTerminalWidth();
        String line = "%s %s ".formatted("-".repeat(8), label);
        if (width - line.length() > 0) {
            line += "-".repeat(width - line.length());
        }
        io.toolOutput(line);
    }

    /**
     * Formats and prints each fragment with:
     *   - the line count on the left (6 chars, right-justified)
     *   - the fragment filename/description (possibly wrapped) on the right
     * Returns the sum of lines across all fragments printed.
     */
    private int formatFragments(Stream<? extends ContextFragment> fragments, int termWidth) {
        AtomicInteger sum = new AtomicInteger(0);
        fragments.forEach(f -> {
            try {
                String content = f.text();
                int lines = content.isEmpty() ? 0 : content.split("\n").length;
                sum.addAndGet(lines);

                // The "source", i.e. the filename or virtual fragment position
                String source = f.source() + ": ";
                // We do naive wrapping of the fragment's short description
                List<String> wrapped = wrapOnSpace(f.description(), termWidth - (LOC_FIELD_WIDTH + 3 + f.source().length()));

                if (wrapped.isEmpty()) {
                    // No description, just print lines + prefix
                    io.context(formatLine(lines, source, termWidth));
                } else {
                    // First line includes the actual lines count
                    io.context(formatLine(lines, source + wrapped.getFirst(), termWidth));

                    // Remaining lines: pass 0 so we don't repeat the line count
                    String indent = " ".repeat(source.length());
                    for (int i = 1; i < wrapped.size(); i++) {
                        io.context(formatLine(0, indent + wrapped.get(i), termWidth));
                    }
                }
            } catch (IOException e) {
                io.toolErrorRaw("Removing unreadable fragment %s".formatted(f.source()));
                currentContext = currentContext.removeBadFragment(f);
            }
        });
        return sum.get();
    }

    /**
     * Prints a single line with the integer 'loc' (if > 0) on the left, right-justified to LOC_FIELD_WIDTH, then 'text'.
     */
    private static final int LOC_FIELD_WIDTH = 9;

    private static String formatLine(int loc, String text, int width) {
        var locStr = formatLoc(loc);

        // We'll trim or leave the right side as needed
        int spaceForText = width - (locStr.length() + 1);
        if (spaceForText < 1) {
            spaceForText = 1;
        }
        String trimmed = (text.length() > spaceForText)
                ? text.substring(0, spaceForText)
                : text;

        return locStr + "  " + trimmed;
    }

    private static String formatLoc(int loc) {
        if (loc == 0) {
            return " ".repeat(LOC_FIELD_WIDTH - 2);
        }

        int width = LOC_FIELD_WIDTH - 2; // 2 padding spaces
        String locStr = String.format("%,d", loc);
        var formatStr = "%" + width + "s";
        return String.format(formatStr, locStr);
    }

    /**
     * Wrap text on spaces up to maxWidth; returns multiple lines if needed.
     */
    private static List<String> wrapOnSpace(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxWidth <= 0) {
            return List.of(text);
        }

        List<String> lines = new ArrayList<>();
        String[] tokens = text.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String token : tokens) {
            if (current.isEmpty()) {
                current.append(token);
            } else if (current.length() + 1 + token.length() <= maxWidth) {
                current.append(" ").append(token);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(token);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    public void setAutoContextFiles(int fileCount) {
        pushContext();
        currentContext = currentContext.setAutoContextFiles(fileCount);
    }

    public Set<RepoFile> getEditableFiles() {
        return currentContext.editableFiles().map(PathFragment::file).collect(Collectors.toSet());
    }

    public void onRefresh() {
        synchronized (ContextManager.class) {
            gitTrackedFilesCache = null;
        }
        currentContext = currentContext.refresh();
    }

    private record BuildCommand(String command, String message) {
        public static BuildCommand success(String cmd) {
            return new BuildCommand(cmd, cmd);
        }

        public static BuildCommand failure(String message) {
            return new BuildCommand(null, message);
        }
    }
}
