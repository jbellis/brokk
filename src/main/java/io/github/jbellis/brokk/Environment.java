package io.github.jbellis.brokk;

import sun.misc.Signal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public class Environment {
    public static final Environment instance = new Environment();

    private Environment() {
    }
    
    /**
     * Runs a shell command using /bin/sh, returning {stdout, stderr}.
     */
    public ProcessResult runShellCommand(String command) throws IOException {
        var process = createProcessBuilder("/bin/sh", "-c", command).start();
        var sig = new Signal("INT");
        var oldHandler = Signal.handle(sig, signal -> process.destroy());

        try {
            var out = new StringBuilder();
            var err = new StringBuilder();
            try (var scOut = new Scanner(process.getInputStream());
                 var scErr = new Scanner(process.getErrorStream()))
            {
                while (scOut.hasNextLine()) {
                    out.append(scOut.nextLine()).append("\n");
                }
                while (scErr.hasNextLine()) {
                    err.append(scErr.nextLine()).append("\n");
                }
            }

            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new ProcessResult(exitCode, out.toString(), err.toString());
        } finally {
            Signal.handle(sig, oldHandler);
        }
    }

    private static ProcessBuilder createProcessBuilder(String... command) {
        // Redirect input to /dev/null so interactive prompts fail fast
        var pb = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        // Remove environment variables that might interfere with non-interactive operation
        pb.environment().remove("EDITOR");
        pb.environment().remove("VISUAL");
        pb.environment().put("TERM", "dumb");
        return pb;
    }

    /**
     * Run a shell command, returning stdout or stderr in an OperationResult.
     */
    public ContextManager.OperationResult captureShellCommand(String command) {
        ProcessResult result;
        try {
            result = runShellCommand(command);
        } catch (Exception e) {
            return ContextManager.OperationResult.error("Error executing command: " + e.getMessage());
        }

        var ANSI_ESCAPE_PATTERN = "\\x1B(?:\\[[;\\d]*[ -/]*[@-~]|\\]\\d+;[^\\x07]*\\x07)";
        var stdout = result.stdout().trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        var stderr = result.stderr().trim().replaceAll(ANSI_ESCAPE_PATTERN, "");
        var combinedOut = new StringBuilder();
        if (!stdout.isEmpty()) {
            if (!stderr.isEmpty()) {
                combinedOut.append("stdout: ");
            }
            combinedOut.append(stdout);
        }
        if (!stderr.isEmpty()) {
            if (!stdout.isEmpty()) {
                combinedOut.append("\n\n").append("stderr: ");
            }
            combinedOut.append(stderr);
        }
        var output = combinedOut.toString();

        if (result.status() > 0) {
            return ContextManager.OperationResult.error("`%s` returned code %d\n%s".formatted(command, result.status(), output));
        }
        return ContextManager.OperationResult.success(output);
    }

    public static void createDirIfNotExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        if (!path.toFile().mkdir()) {
            throw new IOException("mkdir failed");
        }
    }

    public record ProcessResult(int status, String stdout, String stderr) {}
}
