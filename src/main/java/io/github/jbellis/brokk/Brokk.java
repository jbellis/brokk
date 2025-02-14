package io.github.jbellis.brokk;

import io.github.jbellis.brokk.ContextManager.OperationResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Brokk is the main entry point containing the REPL.
 */
public class Brokk {
    private static ConsoleIO io;
    private static ContextManager contextManager;
    private static Coder coder;

    public static void main(String[] args) {
        // Find the repository root
        Path sourceRoot = Environment.instance.getRoot();

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

        // Create an Analyzer
        Analyzer analyzer = new Analyzer(sourceRoot);
        analyzer.writeGraphAsync();

        // Create the ContextManager (holds chat context, code references, etc.)
        contextManager = new ContextManager(
                analyzer,
                sourceRoot,
                null,
                null
        );

        // Create the console with references to commands (we'll build them below)
        var commands = new ArrayList<>(contextManager.getCommands());
        // Dummy command to wire up completion for within-chat identifiers
        commands.add(new ContextManager.Command("chat", null, null, null, (s -> ContextManager.completeClassesAndMembers(s, analyzer, false))));
        io = new ConsoleIO(sourceRoot, commands);

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

        // MOTD
        String version;
        try {
            Properties props = new Properties();
            props.load(Brokk.class.getResourceAsStream("/version.properties"));
            version = props.getProperty("version");
        } catch (IOException e) {
            version = "[unknown]";
        }
        io.toolOutput("Editor model: " + models.editModelName());
        io.toolOutput("Quick model: " + models.quickModelName());
        io.toolOutput("Git repo found at %s with %d files".formatted(sourceRoot, ContextManager.getTrackedFiles().size()));
        io.toolOutput("Brokk %s initialized".formatted(version));
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
                    String welcome = new String(welcomeStream.readAllBytes(), StandardCharsets.UTF_8);
                    io.toolOutput("\n" + welcome);
                    io.toolOutput("-".repeat(io.getTerminalWidth()));
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
            String input = io.getInput(prefill);
            prefill = "";
            if (input == null || input.isEmpty()) {
                continue;
            }

            OperationResult result;
            if (contextManager.isCommand(input)) {
                result = contextManager.handleCommand(input);
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
