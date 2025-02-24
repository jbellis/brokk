package io.github.jbellis.brokk;

import io.github.jbellis.brokk.ContextManager.OperationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Brokk is the main entry point containing the REPL.
 */
public class Brokk {
    private static ConsoleIO io;
    private static ContextManager contextManager;
    private static Coder coder;
    private static Commands commands;

    public static void main(String[] args) {
        // Find the repository root
        Path sourceRoot = null;
        try {
            sourceRoot = GitRepo.instance.getRoot();
        } catch (Throwable th) {
            System.out.println("Please run Brokk from within a git repository");
            System.exit(1);
        }

        // Make sure we can create .brokk/
        var brokkDir = sourceRoot.resolve(".brokk");
        try {
            Environment.createDirIfNotExists(brokkDir);
            // Set up debug logging to .brokk/debug.log
            String logPath = brokkDir.resolve("debug.log").toAbsolutePath().toString();
            System.setProperty("logfile.path", logPath);
        } catch (IOException e) {
            System.out.println("Unable to create " + brokkDir);
            System.exit(1);
        }

        // Create the ContextManager (holds chat context, code references, etc.)
        contextManager = new ContextManager(sourceRoot);

        // Create Commands object and build command list
        commands = new Commands(contextManager);
        var commandList = commands.buildCommands();
        io = new ConsoleIO(sourceRoot, commandList, (s -> Completions.completeClassesAndMembers(s, contextManager.getAnalyzer(), false)));
        // Output header as soon as `io` is available
        String version;
        try {
            Properties props = new Properties();
            props.load(Brokk.class.getResourceAsStream("/version.properties"));
            version = props.getProperty("version");
        } catch (IOException | NullPointerException e) {
            version = "[unknown]";
        }
        io.toolOutput("Brokk %s".formatted(version));

        // Create a Coder that deals with LLM calls/streaming
        Models models;
        try {
            models = Models.load();
        } catch (Throwable th) {
            io.toolError("Error loading models: " + th.getMessage());
            io.toolError("AI will not be available this session");
            models = Models.disabled();
        }
        coder = new Coder(models, io, sourceRoot, contextManager);
        
        contextManager.resolveCircularReferences(io, coder);
        commands.resolveCircularReferences(io, coder);

        // MOTD
        io.toolOutput("Editor model: " + models.editModelName());
        io.toolOutput("Apply model: " + models.applyModelName());
        io.toolOutput("Quick model: " + models.quickModelName());
        io.toolOutput("Git repo found at %s with %d files".formatted(sourceRoot, GitRepo.instance.getTrackedFiles().size()));
        maybeShowMotd();

        // kick off repl
        contextManager.show();
        runLoop();
    }

    private static void maybeShowMotd() {
        Path configDir = Path.of(System.getProperty("user.home"), ".config", "brokk");
        if (configDir.toFile().exists()) {
            return;
        }

        try {
            Files.createDirectories(configDir);
            // Show welcome message
            try (var welcomeStream = Brokk.class.getResourceAsStream("/WELCOME.md")) {
                if (welcomeStream != null) {
                    io.toolOutput("-".repeat(io.getTerminalWidth()));
                    io.toolOutput(new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            io.toolError("Failed to create config directory: " + e.getMessage());
        }
    }

    /**
     * Main REPL
     */
    private static void runLoop() {
        String prefill = "";
        while (true) {
            // If we have a constructed user message, send it to the LLM immediately.
            // Otherwise, prompt for user input as usual
            String constructed = contextManager.getAndResetConstructedMessage();
            String input = constructed == null ? io.getInput(prefill) : constructed;
            prefill = "";
            if (input == null || input.isEmpty()) {
                continue;
            }

            OperationResult result;
            if (commands.isCommand(input)) {
                result = commands.handleCommand(input);
            } else {
                coder.runSession(input);
                // coder handles its own feedback
                result = OperationResult.success();
            }

            switch (result.status()) {
                case ERROR -> {
                    if (result.message() != null) {
                        io.toolError(result.message());
                    }
                }
                case SUCCESS -> {
                    if (result.message() != null) {
                        io.toolOutput(result.message());
                    }
                    contextManager.show();
                }
                case PREFILL -> {
                    prefill = result.message();
                }
                case SKIP_SHOW -> {
                    // do nothing
                }
            }
        }
    }
}
