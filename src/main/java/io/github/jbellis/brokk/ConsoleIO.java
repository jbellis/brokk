package io.github.jbellis.brokk;

import io.github.jbellis.brokk.ContextManager.Command;
import org.jline.keymap.KeyMap;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public class ConsoleIO implements AutoCloseable, IConsoleIO {
    private final Terminal terminal;
    private final LineReader reader;

    public ConsoleIO(Path sourceRoot, Collection<Command> commands) {
        try {
            var historyFile = sourceRoot.resolve(".brokk/linereader.txt");
            this.terminal = TerminalBuilder.terminal();

            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName("brokk")
                    .variable(LineReader.HISTORY_FILE, historyFile)
                    .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                    .variable(LineReader.LIST_MAX, 40)
                    // Automatically list all completions (without requiring another Tab) when multiple matches exist
                    .option(LineReader.Option.AUTO_LIST, true)
                    // Optionally show a completion menu instead of a raw listing
                    .option(LineReader.Option.AUTO_MENU, true)
                    // Enable camel-case completions (CDA -> CassandraDiskAnn)
                    .option(LineReader.Option.COMPLETE_MATCHER_CAMELCASE, true)
                    // Our custom Completer will parse input lines
                    // and return a list of possible completions.
                    .completer((rdr, parsedLine, candidates) -> {
                        autocomplete(commands, parsedLine, candidates);
                    })
                    .build();

            // Grab the default main keymap
            var keyMaps = reader.getKeyMaps();
            var mainKeyMap = keyMaps.get(LineReader.MAIN);
            // add ctrl-space as a completion trigger
            mainKeyMap.bind(new Reference(LineReader.EXPAND_OR_COMPLETE), KeyMap.ctrl(' '));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize console: " + e.getMessage(), e);
        }
    }

    private static void autocomplete(Collection<Command> commands,
                                     ParsedLine parsedLine,
                                     List<Candidate> candidates)
    {
        String line = parsedLine.line();
        // Non-command input: delegate to chat command completer.
        if (!line.startsWith("/")) {
            var chatCmd = commands.stream().filter(cmd -> "chat".equals(cmd.name())).findFirst().orElseThrow();
            // Only complete if we're in the middle of a word.
            if (!line.endsWith(" ")) {
                candidates.addAll(chatCmd.argumentCompleter().complete(parsedLine.word()));
            }
            return;
        }

        // Command input
        List<String> words = parsedLine.words();
        String cmdInput = words.get(0).startsWith("/") ? words.get(0).substring(1) : words.get(0);

        // For single-word input (command name), use StringsCompleter
        if (words.size() == 1) {
            commands.forEach(cmd -> {
                String cmdName = "/" + cmd.name();
                candidates.add(new Candidate(cmdName, cmdName, null, cmd.description(), null, null, true));
            });
            return;
        }

        // For arguments, find the exact command and delegate to its completer
        var foundCmd = commands.stream()
                .filter(cmd -> cmd.name().equals(cmdInput))
                .findFirst()
                .orElse(null);
        if (foundCmd == null) {
            return;
        }
        // If there is a second token already, use it; otherwise, use an empty string.
        String argInput = words.size() > 1 ? parsedLine.word() : "";
        candidates.addAll(foundCmd.argumentCompleter().complete(argInput));
    }

    public String getInput(String prefill) {
        return readLineInternal("\n> ", prefill);
    }

    public String getRawInput() {
        return readLineInternal("", "");
    }

    private String readLineInternal(String prompt, String prefill) {
        try {
            // Prompt
            return reader.readLine(prompt, null, prefill);
        } catch (UserInterruptException e) {
            // User pressed Ctrl-C
            toolOutput("Canceled with ^C (use ^D to exit)");
            return "";
        } catch (EndOfFileException e) {
            // Ctrl-D => exit
            System.exit(0);
            return "";
        }
    }

    public void context(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[96m sets text to cyan, \u001B[0m resets the color
        writer.println("\u001B[96m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public void toolOutput(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[94m sets text to blue, \u001B[0m resets the color
        writer.println("\u001B[94m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public void toolErrorRaw(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[31m sets text to red, \u001B[0m resets the color
        writer.println("\u001B[31m" + msg + "\u001B[0m");
        writer.flush();
    }

    public void llmOutput(String token) {
        var writer = terminal.writer();
        writer.print("\u001B[32m" + token + "\u001B[0m");
        writer.flush();
    }

    public void shellOutput(String msg) {
        var writer = terminal.writer();
        writer.println("\u001B[33m" + msg + "\u001B[0m");
        writer.flush();
    }

    @Override
    public boolean confirmAsk(String msg) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[35m sets text to magenta, \u001B[0m resets the color
        writer.println("\u001B[35m" + "%s (y/N) ".formatted(msg) + "\u001B[0m");
        writer.flush();
        try {
            var response = reader.readLine();
            return response.trim().equalsIgnoreCase("y");
        } catch (UserInterruptException e) {
            return false;
        }
    }

    public char askOptions(String msg, String options) {
        var writer = terminal.writer();
        // ANSI escape code \u001B[35m sets text to magenta, \u001B[0m resets the color
        writer.println("\u001B[35m" + "%s (%s) ".formatted(msg, options) + "\u001B[0m");
        writer.flush();
        try {
            var response = reader.readLine();
            if (response == null || response.isEmpty()) {
                return options.toLowerCase().charAt(options.length() - 1);  // default to last option
            }
            char choice = response.trim().toLowerCase().charAt(0);
            return options.toLowerCase().indexOf(choice) >= 0 ? choice : options.toLowerCase().charAt(options.length() - 1);
        } catch (UserInterruptException e) {
            return options.toLowerCase().charAt(options.length() - 1); // default to last option on interrupt
        }
    }

    @Override
    public void close() {
        try {
            reader.getHistory().save();
        } catch (Exception e) {
            terminal.writer().println("Warning: Could not save history: " + e.getMessage());
        } finally {
            try {
                terminal.close();
            } catch (Exception e) {
                // Ignore closure errors
            }
        }
    }

    public int getTerminalWidth() {
        return terminal.getWidth();
    }
}
