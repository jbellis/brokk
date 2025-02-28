package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager.OperationResult;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.PreparePrompts;
import org.jline.reader.Candidate;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Commands parses user input for slash-commands, calls the appropriate ContextManager methods
 * to manipulate context or perform other business logic, and provides autocompletion.
 *
 * If you plan to replace the CLI with a GUI, these commands become optional,
 * since the ContextManager now holds the primary "business logic."
 */
public class Commands {
    @FunctionalInterface
    public interface CommandHandler {
        OperationResult handle(String args);
    }

    @FunctionalInterface
    public interface ArgumentCompleter {
        List<Candidate> complete(String partial);
    }

    public record Command(
            String name,
            String description,
            CommandHandler handler,
            String args,
            ArgumentCompleter argumentCompleter
    ) {}

    private final ContextManager cm;
    private ConsoleIO io;
    private Coder coder;

    public Commands(ContextManager contextManager) {
        this.cm = contextManager;
    }

    public void resolveCircularReferences(ConsoleIO io, Coder coder) {
        this.io = io;
        this.coder = coder;
    }

    /**
     * Builds and returns all slash-commands.
     */
    public List<Command> buildCommands() {
        return new ArrayList<>(List.of(
                new Command(
                        "add",
                        "Add editable files by name or by fragment references",
                        this::cmdAdd,
                        "<files|fragment>",
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
                        args -> cmdClear(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "commit", 
                        "Generate commit message and commit changes",
                        args -> cmdCommit(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "mode",
                        "Set LLM request mode",
                        this::cmdMode,
                        "<EDIT|APPLY>",
                        this::completeApply
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
                        "[files|fragment]",
                        this::completeDrop
                ),
                new Command(
                        "help",
                        "Show this help",
                        args -> cmdHelp(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "read",
                        "Add read-only files by name or by fragment references",
                        this::cmdReadOnly,
                        "<files|fragment>",
                        this::completeRead
                ),
                new Command(
                        "refresh",
                        "Refresh code intelligence data (should not be necessary, file a bug if it is)",
                        args -> cmdRefresh(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "search",
                        "Perform agentic code search for a given query",
                        this::cmdSearch,
                        "<query>",
                        args -> List.of()
                ),
                new Command(
                        "undo",
                        "Undo last context changes (/add, /read, /drop, /clear)",
                        args -> cmdUndo(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "redo",
                        "Redo last undone changes",
                        args -> cmdRedo(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "paste",
                        "Paste content as read-only snippet",
                        args -> cmdPaste(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "usage",
                        "Capture source code of usages of the target class, method, or field",
                        this::cmdUsage,
                        "<class|member>",
                        input -> Completions.completeClassesAndMembers(input, cm.getAnalyzer(), true)
                ),
                new Command(
                        "stacktrace",
                        "Parse Java stacktrace and extract relevant methods",
                        args -> cmdStacktrace(),
                        "[no parameters]",
                        args -> List.of()
                ),
                new Command(
                        "summarize",
                        "Generate a skeleton summary of the named class or fragment",
                        this::cmdSummarize,
                        "<class|fragment>",
                        this::completeSummarize
                ),
                new Command(
                        "prepare",
                        "Evaluate context for the given request",
                        this::cmdPrepare,
                        "<request>",
                        args -> List.of()
                ),
                new Command(
                        "send",
                        "Send last shell output to LLM as constructed request",
                        this::cmdSend,
                        "[instructions]",
                        args -> List.of()
                )
        ));
    }

    private OperationResult cmdSearch(String query) {
        if (query.isBlank()) {
            return OperationResult.error("Please provide a search query");
        }

        // Create and run the search agent
        SearchAgent agent = new SearchAgent(query, cm, coder, io);
        io.spin("");
        var result = agent.execute();
        io.spinComplete();

        io.llmOutput(result + "\n");
        return OperationResult.success();
    }

    /**
     * Determines if the input starts with "/" or "$" (a command).
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
            if (result.status() == ContextManager.OperationStatus.SUCCESS) {
                // Add result to read-only snippet with the command as description
                if (!result.message().isBlank()) {
                    cm.addStringFragment(command, result.message());
                }
            } else {
                assert result.status() == ContextManager.OperationStatus.ERROR;
                io.toolError(result.message());
            }
            return OperationResult.success();
        }
        // $ => run shell command + show output
        else if (input.startsWith("$")) {
            var command = input.substring(1).trim();
            OperationResult result = Environment.instance.captureShellCommand(command);
            // Store the shell output invisibly
            cm.setLastShellOutput(result.message().isBlank() ? null : result.message());
            // If it succeeded, show the output in yellow
            if (result.status() == ContextManager.OperationStatus.SUCCESS) {
                io.shellOutput(result.message().isBlank() ? "[operation completed successfully with no output]" : result.message());
            } else {
                assert result.status() == ContextManager.OperationStatus.ERROR;
                io.toolError(result.message());
            }
            return OperationResult.success(); // don't show output a second time
        }
        // /command => handle built-in commands
        else if (input.startsWith("/")) {
            return handleSlashCommand(input);
        }
        // If it's not one of those, skip
        return OperationResult.skipShow();
    }

    /**
     * Handle slash-command input, e.g. "/add file.java".
     */
    public OperationResult handleSlashCommand(String input) {
        // remove the leading '/'
        String noSlash = input.substring(1);
        String[] parts = noSlash.split("\\s+", 2);
        String typedCmd = parts[0];
        String args = (parts.length > 1) ? parts[1] : "";

        // find all commands whose name starts with typedCmd
        var matching = buildCommands().stream()
                .filter(c -> c.name().startsWith(typedCmd))
                .toList();

        if (matching.isEmpty()) {
            return OperationResult.error("Unknown command: " + typedCmd);
        }
        if (matching.size() > 1) {
            String possible = matching.stream()
                    .map(Command::name)
                    .collect(Collectors.joining(", "));
            return OperationResult.error(
                    "Ambiguous command '%s' (matches: %s)".formatted(typedCmd, possible)
            );
        }

        Command cmd = matching.get(0);
        return cmd.handler().handle(args);
    }

    // ---------------------------------------------------------------
    // Below are the command handler methods, which parse user input
    // and call business-logic methods on ContextManager.
    // ---------------------------------------------------------------

    private OperationResult cmdAdd(String args) {
        if (args.isBlank()) {
            return OperationResult.error("Please provide filename(s) or a git commit");
        }

        // Check if user typed a numeric fragment (virtual fragment index)
        // to file resolution, otherwise parse as filenames/commit
        Set<RepoFile> resolved;
        try {
            if (args.trim().matches("\\d+")) {
                // numeric reference => might be a virtual fragment
                resolved = cm.getFilesFromVirtualFragmentIndex(Integer.parseInt(args.trim()));
                cm.addFiles(resolved);
                return OperationResult.success();
            }
        } catch (Exception e) {
            return OperationResult.error(e.getMessage());
        }

        // Otherwise parse as filenames or commit references
        var filenames = Completions.parseQuotedFilenames(args);
        List<BrokkFile> aggregateFiles = new ArrayList<>();
        for (String token : filenames) {
            var matches = Completions.expandPath(cm.getRoot(), token);
            if (matches.isEmpty()) {
                // Prompt to create if user wants
                if (io.confirmAsk("No files matched '%s'. Create?".formatted(token))) {
                    try {
                        var newFile = cm.toFile(token);
                        newFile.create();
                        GitRepo.instance.add(newFile.toString());
                        aggregateFiles.add(newFile);
                    } catch (Exception ex) {
                        return OperationResult.error("Error creating %s: %s".formatted(token, ex.getMessage()));
                    }
                }
            } else {
                for (var file : matches) {
                    if (file instanceof ExternalFile) {
                        io.toolError("Cannot add external file: " + token);
                        continue;
                    }
                    aggregateFiles.add(file);
                }
            }
        }
        if (aggregateFiles.isEmpty()) {
            return OperationResult.success(); // nothing found or created
        }

        // Convert to RepoFile only
        var repoFiles = aggregateFiles.stream()
                .filter(f -> f instanceof RepoFile)
                .map(f -> (RepoFile) f)
                .collect(Collectors.toList());
        cm.addFiles(repoFiles);
        return OperationResult.success();
    }

    private List<Candidate> completeAdd(String partial) {
        // similar logic as old code: pathCandidates + commitCandidates
        List<Candidate> pathCandidates = completePaths(partial, GitRepo.instance.getTrackedFiles());
        List<Candidate> commitCandidates = completeCommits(partial);

        List<Candidate> all = new ArrayList<>(pathCandidates.size() + commitCandidates.size());
        all.addAll(pathCandidates);
        all.addAll(commitCandidates);
        return all;
    }

    private OperationResult cmdReadOnly(String args) {
        if (args.isBlank()) {
            // convert all to read-only
            return cm.convertAllToReadOnly();
        }

        // check if numeric virtual fragment index
        try {
            if (args.trim().matches("\\d+")) {
                var resolved = cm.getFilesFromVirtualFragmentIndex(Integer.parseInt(args.trim()));
                if (resolved != null) {
                    cm.addReadOnlyFiles(resolved);
                    return OperationResult.success();
                }
            }
        } catch (Exception e) {
            return OperationResult.error(e.getMessage());
        }

        // Otherwise parse as filenames or commit references
        var filenames = Completions.parseQuotedFilenames(args);
        List<BrokkFile> aggregateFiles = new ArrayList<>();
        for (String token : filenames) {
            var matches = Completions.expandPath(cm.getRoot(), token);
            if (matches.isEmpty()) {
                return OperationResult.error("No matches found for: " + token);
            }
            aggregateFiles.addAll(matches);
        }

        // Convert to RepoFile only
        var repoFiles = aggregateFiles.stream()
                .filter(f -> f instanceof RepoFile)
                .map(f -> (RepoFile) f)
                .collect(Collectors.toList());
        cm.addReadOnlyFiles(repoFiles);
        return OperationResult.success();
    }

    private List<Candidate> completeRead(String partial) {
        List<Candidate> pathCandidates = completePaths(partial, GitRepo.instance.getTrackedFiles());
        List<Candidate> commitCandidates = completeCommits(partial);

        List<Candidate> all = new ArrayList<>(pathCandidates.size() + commitCandidates.size());
        all.addAll(pathCandidates);
        all.addAll(commitCandidates);
        return all;
    }

    private OperationResult cmdDrop(String args) {
        if (args.isBlank()) {
            return cm.dropAll();
        }

        return dropFiles(args);
    }

    /** For "/drop something", parse the user string against known fragments, then drop them. */
    public OperationResult dropFiles(String rawArgs) {
        var filenames = Completions.parseQuotedFilenames(rawArgs);
        var matchedSomething = false;

        // gather pathfragments and virtualfragments to remove
        var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
        var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();

        var context = cm.currentContext();
        for (String fn : filenames) {
            var fragment = context.toFragment(fn);
            if (fragment instanceof ContextFragment.AutoContext) {
                // same logic as /autocontext 0
                cm.setAutoContextFiles(0);
                matchedSomething = true;
                continue;
            }
            if (fragment instanceof ContextFragment.PathFragment pf) {
                pathFragsToRemove.add(pf);
                matchedSomething = true;
            } else if (fragment instanceof ContextFragment.VirtualFragment vf) {
                virtualToRemove.add(vf);
                matchedSomething = true;
            }
        }

        if (!matchedSomething) {
            return OperationResult.error("No matching content to drop");
        }
        cm.drop(pathFragsToRemove, virtualToRemove);
        return OperationResult.success();
    }

    private List<Candidate> completeDrop(String partial) {
        // originally Streams.concat(...).map(...) in ContextManager
        // but now we just request them from contextManager
        return cm.getAllFragmentSources()
                .stream()
                .filter(src -> src.startsWith(partial))
                .map(Candidate::new)
                .collect(Collectors.toList());
    }

    private OperationResult cmdAsk(String input) {
        if (input.isBlank()) {
            return OperationResult.error("Please provide a question");
        }

        // Provide the prompt messages
        var messages = AskPrompts.instance.collectMessages(cm);
        messages.add(new UserMessage("<question>\n%s\n</question>".formatted(input.trim())));

        String response = coder.sendStreaming(cm.getCurrentModel(coder.models), messages, true);
        if (response != null) {
            cm.addToHistory(List.of(messages.getLast(), new AiMessage(response)));
        }

        return OperationResult.success();
    }

    private OperationResult cmdAutoContext(String args) {
        int fileCount;
        try {
            fileCount = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            return OperationResult.error("/autocontext requires an integer parameter");
        }
        if (fileCount < 0) {
            return OperationResult.error("/autocontext requires a non-negative integer parameter");
        }
        return cm.setAutoContextFiles(fileCount);
    }

    private OperationResult cmdClear() {
        return cm.clearHistory();
    }

    private OperationResult cmdCommit() {
        var messages = CommitPrompts.instance.collectMessages(cm);
        if (messages.isEmpty()) {
            return OperationResult.error("nothing to commit");
        }
        String commitMsg = coder.sendMessage("Inferring commit suggestion", messages);
        if (commitMsg.isEmpty()) {
            return OperationResult.error("LLM did not provide a commit message");
        }
        // prefill a "git commit -a -m ..." command
        return OperationResult.prefill("$git commit -a -m \"%s\"".formatted(commitMsg));
    }

    private OperationResult cmdMode(String args) {
        String modeArg = args.trim().toUpperCase();
        if ("EDIT".equals(modeArg)) {
            cm.setMode(ContextManager.Mode.EDIT);
            return OperationResult.success("Mode set to EDIT");
        } else if ("APPLY".equals(modeArg)) {
            cm.setMode(ContextManager.Mode.APPLY);
            return OperationResult.success("Mode set to APPLY");
        } else {
            return OperationResult.error("Invalid mode. Valid modes are EDIT and APPLY.");
        }
    }

    private List<Candidate> completeApply(String partial) {
        return Stream.of("EDIT", "APPLY")
                .filter(s -> s.startsWith(partial.toUpperCase()))
                .map(Candidate::new)
                .toList();
    }

    private OperationResult cmdCopy(String args) {
        String content;
        if (args.isBlank()) {
            // copy entire user context (minus some AI responses)
            // or some approach like the old code
            List<ChatMessage> msgs = ArchitectPrompts.instance.collectMessages(cm);
            var combined = new StringBuilder();
            msgs.forEach(m -> {
                if (!(m instanceof AiMessage)) {
                    combined.append(Models.getText(m)).append("\n\n");
                }
            });
            content = combined + "\n<goal>\n\n</goal>";
        } else {
            // find matching fragment
            var fragment = cm.currentContext().toFragment(args.trim());
            if (fragment == null) {
                return OperationResult.error("No matching fragment found for: " + args);
            }
            try {
                content = fragment.text();
            } catch (IOException e) {
                cm.removeBadFragment(fragment, e);
                return OperationResult.success(); // removeBadFragment already handled the error
            }
        }

        // copy to clipboard
        try {
            var sel = new StringSelection(content);
            var cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(sel, sel);
            io.toolOutput("Content copied to clipboard");
        } catch (Exception e1) {
            return OperationResult.error("Failed to copy to clipboard: " + e1.getMessage());
        }

        if (args.isBlank() && cm.getMode() != ContextManager.Mode.APPLY) {
            cm.setMode(ContextManager.Mode.APPLY);
            io.toolOutput("/mode set to APPLY by /copy");
        }

        return OperationResult.skipShow();
    }

    private OperationResult cmdHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available commands:\n");
        sb.append("<> denotes a required parameter, [] denotes optional\n");
        // show $ help first
        sb.append(String.format("%-16s %s\n", "$", "<cmd>"));
        sb.append(String.format("%16s - %s\n", "", "Execute cmd in a shell and show its output"));
        sb.append(String.format("%-16s %s\n", "$$", "<cmd>"));
        sb.append(String.format("%16s - %s\n", "", "Execute cmd in a shell and capture its output as context"));

        // then slash commands
        for (Command c : buildCommands().stream().sorted(Comparator.comparing(Command::name)).toList()) {
            String cmdName = "/" + c.name();
            String cmdArgs = c.args().isEmpty() ? "[no parameters]" : c.args();
            // show two lines
            sb.append(String.format("%-16s %s\n", cmdName, cmdArgs));
            sb.append(String.format("%16s - %s\n", "", c.description()));
        }
        sb.append("TAB or Ctrl-space autocompletes\n");
        io.toolOutput(sb.toString());
        return OperationResult.skipShow();
    }

    private OperationResult cmdRefresh() {
        GitRepo.instance.refresh();
        cm.requestRebuild();
        io.toolOutput("Code intelligence will refresh in the background");
        return OperationResult.skipShow();
    }

    private OperationResult cmdUndo() {
        return cm.undoContext();
    }

    private OperationResult cmdRedo() {
        return cm.redoContext();
    }

    private OperationResult cmdPaste() {
        io.toolOutput("Paste your content below and press Enter when done:");
        String pastedContent = io.getRawInput();
        if (pastedContent == null || pastedContent.isBlank()) {
            return OperationResult.error("No content pasted");
        }

        // optionally do some summary call
        Future<String> summaryFuture = cm.submitSummarizeTaskForPaste(coder, pastedContent);
        return cm.addPasteFragment(pastedContent, summaryFuture);
    }

    private OperationResult cmdUsage(String identifier) {
        if (identifier.isBlank()) {
            return OperationResult.error("Please provide a symbol name to search for");
        }
        return cm.usageForIdentifier(identifier.trim());
    }

    private OperationResult cmdStacktrace() {
        io.toolOutput("Paste your stacktrace below and press Enter when done:");
        String stacktraceText = io.getRawInput();
        if (stacktraceText == null || stacktraceText.isBlank()) {
            return OperationResult.error("no stacktrace pasted");
        }
        return cm.parseStacktrace(stacktraceText);
    }

    private OperationResult cmdSummarize(String input) {
        if (input.trim().isBlank()) {
            return OperationResult.error("Please provide a file path or fragment reference");
        }
        return summarize(input);
    }

    private OperationResult summarize(String input) {
        String trimmedInput = input.trim();
        Set<CodeUnit> sources = null;

        // first see if it is a fragment
        try {
            var fragment = cm.currentContext().toFragment(trimmedInput);
            if (fragment instanceof ContextFragment.AutoContext) {
                return OperationResult.error("Autocontext is already summarized");
            }
            if (fragment != null) {
                sources = fragment.sources(cm.getAnalyzer());
            }
            // else fragment == null => not a recognized fragment
        } catch (IllegalArgumentException e) {
            return OperationResult.error(e.getMessage());
        }

        // else parse as path(s)
        if (sources == null) {
            sources = new HashSet<>();
            for (var rawName : Completions.parseQuotedFilenames(trimmedInput)) {
                var matches = Completions.expandPath(cm.getRoot(), rawName);
                if (matches.isEmpty()) {
                    io.toolError("no files matched '%s'".formatted(rawName));
                    continue;
                }
                for (var file : matches) {
                    if (file instanceof ExternalFile) {
                        io.toolError("Cannot summarize external file: " + rawName);
                        continue;
                    }
                    cm.getAnalyzer().getClassesInFile((RepoFile) file).stream()
                            .filter(cu -> !cu.reference().contains("$"))
                            .forEach(sources::add);
                }
            }
        }

        var success = cm.summarizeClasses(sources);
        if (!success) {
            return OperationResult.error("Unable to read source to summarize");
        }
        return OperationResult.success();
    }

    private List<Candidate> completeSummarize(String input) {
        // combine fragment completion + path completion
        List<Candidate> c1 = completeDrop(input);
        List<Candidate> c2 = completePaths(input, GitRepo.instance.getTrackedFiles());
        List<Candidate> all = new ArrayList<>(c1.size() + c2.size());
        all.addAll(c1);
        all.addAll(c2);
        return all;
    }

    private OperationResult cmdPrepare(String msg) {
        if (msg.isBlank()) {
            return OperationResult.error("Please provide a message");
        }
        // original logic from cmdPrepare
        // in our new design, we do it here
        var messages = PreparePrompts.instance.collectMessages(cm);

        var st = """
                <task>
                %s
                </task>
                <goal>
                Evaluate whether you have the right summaries and files available to complete the task.
                DO NOT write code yet, just summarize the task and list any additional files you need.
                </goal>
                """.formatted(msg.trim()).stripIndent();
        messages.add(new UserMessage(st));

        String response = coder.sendStreaming(cm.getCurrentModel(coder.models), messages, true);
        if (response != null) {
            cm.addToHistory(List.of(messages.getLast(), new AiMessage(response)));
            var missing = cm.findMissingFileMentions(response);
            confirmAddRequestedFiles(missing);
        }

        return OperationResult.success();
    }

    private void confirmAddRequestedFiles(Set<RepoFile> missing) {
        if (missing.isEmpty()) {
            return;
        }

        cm.show(); // remind user what we have
        var toAdd = new HashSet<RepoFile>();
        var toRead = new HashSet<RepoFile>();
        var toSummarize = new HashSet<RepoFile>();

        boolean continueProcessing = true;
        for (var file : missing) {
            if (!continueProcessing) break;
            char choice = io.askOptions("Action for %s?".formatted(file),
                                        "(A)dd, (R)ead, (S)ummarize, (I)gnore, ig(N)ore all remaining");
            switch (choice) {
                case 'a' -> toAdd.add(file);
                case 'r' -> toRead.add(file);
                case 's' -> toSummarize.add(file);
                case 'n' -> continueProcessing = false;
                default -> {}
            }
        }

        if (!toAdd.isEmpty()) {
            cm.addFiles(toAdd);
        }
        if (!toRead.isEmpty()) {
            cm.addReadOnlyFiles(toRead);
        }
        for (var file : toSummarize) {
            summarize(file.toString());
        }
    }

    private OperationResult cmdSend(String args) {
        // old cmdSend => if we had lastShellOutput in context manager, or in Brokk
        // but the code originally stored lastShellOutput in Brokk,
        // so presumably that logic remains in Brokk.
        // We'll just replicate the approach:
        String lastShellOutput = cm.getLastShellOutput();
        if (lastShellOutput == null) {
            return OperationResult.error("No shell output to send.");
        }
        if (!args.isBlank()) {
            // constructed message => store in context manager
            cm.setConstructedMessage(args.trim() + "\n\n" + lastShellOutput);
        } else {
            cm.setConstructedMessage(lastShellOutput);
        }
        return OperationResult.skipShow();
    }

    // ---------------------------------------------------------------
    // Autocompletion helpers
    // ---------------------------------------------------------------

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
            var capitals = Completions.extractCapitals(base);
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

    private Candidate fileCandidate(RepoFile file) {
        return new Candidate(file.toString(),
                             file.getFileName(),
                             null,
                             file.toString(),
                             null,
                             null,
                             true);
    }

    private List<Candidate> completeCommits(String partial) {
        List<String> lines = GitRepo.instance.logShort(); // e.g. short commits
        return lines.stream()
                .filter(line -> line.startsWith(partial))
                .map(Candidate::new)
                .collect(Collectors.toList());
    }
}
