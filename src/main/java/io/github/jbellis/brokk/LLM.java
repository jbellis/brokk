package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.BuildPrompts;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class LLM {
    private static final Logger logger = LogManager.getLogger(LLM.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;

    /**
     * Implementation of the LLM session that runs in a separate thread.
     * Uses the provided model for the initial request, performing a two-pass approach for
     * any requested code edits (tool calls):
     *
     *  1) **Preview** and validate each request. Automatically add any new files that are
     *     not read-only. If a file is read-only, mark that request as failed.
     *  2) **Apply** only the requests that previewed successfully, saving original file contents
     *     before each change.
     *  3) Attempt a build. On success, stop. On build failure, prompt the LLM for fixes.
     *  4) Repeat until no further progress can be made.
     */
    public static void runSession(Coder coder, IConsoleIO io, StreamingChatLanguageModel model, String userInput) {
        // Track original contents of files before any changes
        var originalContents = new HashMap<ProjectFile, String>();

        var sessionMessages = new ArrayList<ChatMessage>();
        var nextRequest = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // track repeated tool failures
        int parseErrorAttempts = 0;
        List<String> buildErrors = new ArrayList<>();
        boolean isComplete = false;

        // give user some feedback -- this isn't in the main loop because after the first iteration
        // we give more specific feedback when we need to make another request
        io.systemOutput("Request sent");

        // apply edits with this
        var tools = new LLMTools(coder.contextManager);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted");
                break;
            }

            // Gather context from the context manager -- need to refresh this since we may have made edits in the last pass
            var contextManager = (ContextManager) coder.contextManager;
            var reminder = DefaultPrompts.reminderForModel(model);
            var allMessages = DefaultPrompts.instance.collectMessages(contextManager, sessionMessages, reminder);
            allMessages.add(nextRequest);

            // Actually send the message to the LLM and get the response
            var toolSpecs = ToolSpecifications.toolSpecificationsFrom(tools);
            var streamingResult = coder.sendMessage(model, allMessages, toolSpecs);
            if (streamingResult.cancelled()) {
                io.systemOutput("Session interrupted");
                break;
            }
            if (streamingResult.error() != null) {
                logger.warn("Error from LLM: {}", streamingResult.error().getMessage());
                io.systemOutput("LLM returned an error even after retries.");
                break;
            }
            var llmResponse = streamingResult.chatResponse();
            if (llmResponse == null || llmResponse.aiMessage() == null) {
                io.systemOutput("Empty LLM response even after retries. Stopping session.");
                break;
            }

            String llmText = llmResponse.aiMessage().text();
            boolean hasTools = llmResponse.aiMessage().hasToolExecutionRequests();
            if (llmText.isBlank() && !hasTools) {
                io.systemOutput("Blank LLM response. Stopping session.");
                break;
            }

            // We got a valid response
            logger.debug("response:\n{}", llmResponse);
            sessionMessages.add(nextRequest);
            sessionMessages.add(llmResponse.aiMessage());

            // 1. Preview (validate) all tool requests
            var toolRequests = llmResponse.aiMessage().toolExecutionRequests();
            if (!toolRequests.isEmpty()) {
                var previewResults = tools.validateToolRequests(toolRequests);

                // Collect any that failed
                var failedMessages = previewResults.stream().filter(p -> !p.success()).count();
                if (failedMessages > 0) {
                    parseErrorAttempts++;
                    // If everything failed, we might reflect and continue
                    if (failedMessages == previewResults.size()) {
                        // If everything failed, reflect or stop
                        if (parseErrorAttempts >= MAX_PARSE_ATTEMPTS) {
                            io.systemOutput("Tool request failures keep repeating. Stopping.");
                            break;
                        }
                        io.systemOutput("All tool requests failed; requesting fix from LLM...");
                    } else {
                        parseErrorAttempts = 0; // we had partial success
                        // We'll still reflect about the failures
                        io.systemOutput("Some tool requests failed; continuing with others...");
                    }
                } else {
                    // If none failed, reset the "parseErrorAttempts"
                    parseErrorAttempts = 0;
                }

                // Save original content for validated calls
                previewResults.stream()
                        .filter(LLMTools.ToolOperationPreview::success)
                        .forEach(sp -> {
                    var pf = sp.targetFile();
                    if (pf != null && !originalContents.containsKey(pf)) {
                        try {
                            originalContents.put(pf, pf.exists() ? pf.read() : "");
                        } catch (IOException e) {
                            io.toolError("Failed reading file before applying changes: " + e.getMessage());
                        }
                    }
                });

                // Actually apply changes
                try {
                    var toolMessages = tools.executeTools(previewResults);
                    sessionMessages.addAll(toolMessages);
                    logger.info("Validated tool operations applied");
                } catch (Exception ex) {
                    logger.error("Tool apply error", ex);
                    io.systemOutput("Fatal tool error: " + ex.getMessage());
                    break;
                }

                if (failedMessages > 0) {
                    nextRequest = new UserMessage("""
                    Some edit request tool calls failed, possibly because other edits conflicted with them.
                    Please look carefully at the current state of the editable files and issue corrected edits, if they are still necessary.
                    """.stripIndent());
                    continue; // don't bother building
                }
            }

            // Attempt to build
            var buildReflection = getBuildReflection(contextManager, io, buildErrors);
            if (buildReflection.isEmpty()) {
                // success!
                isComplete = true;
                break;
            }

            // Check if we should continue trying
            if (!shouldContinue(coder, parseErrorAttempts, buildErrors, io)) {
                break;
            }

            io.systemOutput("Attempting to fix build errors...");
            nextRequest = new UserMessage(buildReflection);
        }

        // Write conversation to history if anything happened
        if (!sessionMessages.isEmpty()) {
            if (!isComplete) {
                userInput += " [incomplete]";
            }
            coder.contextManager.addToHistory(sessionMessages, originalContents, userInput);
        }
    }

    /**
     * Runs a quick-edit session where we:
     * 1) Gather the entire file content plus related context (buildAutoContext)
     * 2) Use QuickEditPrompts to ask for a single fenced code snippet
     * 3) Replace the old text with the new snippet in the file
     */
    public static void runQuickSession(ContextManager cm,
                                       IConsoleIO io,
                                       ProjectFile file,
                                       String oldText,
                                       String instructions)
    {
        var coder = cm.getCoder();
        var analyzer = cm.getAnalyzer();

        // Use up to 5 related classes as context
        var seeds = analyzer.getClassesInFile(file).stream()
                .collect(Collectors.toMap(CodeUnit::fqName, cls -> 1.0));
        var relatedCode = Context.buildAutoContext(analyzer, seeds, Set.of(), 5);

        String fileContents;
        try {
            fileContents = file.read();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        var styleGuide = cm.getProject().getStyleGuide();

        // Build the prompt messages
        var messages = QuickEditPrompts.instance.collectMessages(fileContents, relatedCode, styleGuide);

        // The user instructions
        var instructionsMsg = QuickEditPrompts.instance.formatInstructions(oldText, instructions);
        messages.add(new UserMessage(instructionsMsg));

        // Record the original content so we can undo if necessary
        var originalContents = Map.of(file, fileContents);
        
        // Initialize pending history with the instruction
        var pendingHistory = new ArrayList<ChatMessage>();
        pendingHistory.add(new UserMessage(instructionsMsg));

        // No echo for Quick Edit, use static quickModel
        var result = coder.sendStreaming(Models.quickModel(), messages, false);

        if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
            io.systemOutput("Quick edit failed or was cancelled.");
            // Add to history even if canceled, so we can potentially undo any partial changes
            cm.addToHistory(pendingHistory, originalContents, "Quick Edit (canceled): " + file.getFileName());
            return;
        }
        var responseText = result.chatResponse().aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            io.systemOutput("LLM returned empty response for quick edit.");
            // Add to history even if it failed
            cm.addToHistory(pendingHistory, originalContents, "Quick Edit (failed): " + file.getFileName());
            return;
        }
        
        // Add the response to pending history
        pendingHistory.add(new AiMessage(responseText));

        // Extract the new snippet
        var newSnippet = EditBlock.extractCodeFromTripleBackticks(responseText).trim();
        if (newSnippet.isEmpty()) {
            io.systemOutput("Could not parse a fenced code snippet from LLM response.");
            return;
        }

        // Attempt to replace old snippet in the file
        // If oldText not found, do nothing
        String updatedFileContents;
        try {
            if (!fileContents.contains(oldText)) {
                io.systemOutput("The selected snippet was not found in the file. No changes applied.");
                // Add to history even if it failed
                cm.addToHistory(List.of(new UserMessage(instructionsMsg)), originalContents, "Quick Edit (failed): " + file.getFileName());
                return;
            }
            updatedFileContents = fileContents.replaceFirst(
                    java.util.regex.Pattern.quote(oldText.stripLeading()),
                    java.util.regex.Matcher.quoteReplacement(newSnippet.stripLeading())
            );
        } catch (Exception ex) {
            io.systemOutput("Failed to replace text: " + ex.getMessage());
            // Add to history even if it failed
            cm.addToHistory(List.of(new UserMessage(instructionsMsg)), originalContents, "Quick Edit (failed): " + file.getFileName());
            return;
        }

        // (5) Wrap write() in UncheckedIOException
        try {
            file.write(updatedFileContents);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        // Save to context history - pendingHistory already contains both the instruction and the response
        cm.addToHistory(pendingHistory, originalContents, "Quick Edit: " + file.getFileName(), responseText);
    }

    /**
     * Generates a reflection message for build errors
     */
    private static String getBuildReflection(IContextManager cm, IConsoleIO io, List<String> buildErrors) {
        var cmd = cm.getProject().getBuildCommand();
        if (cmd == null || cmd.isBlank()) {
            io.systemOutput("No build command configured");
            return "";
        }

        io.systemOutput("Running " + cmd);
        var result = Environment.instance.captureShellCommand(cmd);
        logger.debug("Build command result: {}", result);
        if (result.error() == null) {
            io.systemOutput("Build successful");
            buildErrors.clear();
            return "";
        }

        io.llmOutput("""
            %s
            ```
            %s
            ```
            """.stripIndent().formatted(result.error(), result.output()));
        io.systemOutput("Build failed (details above)");
        buildErrors.add(result.error() + "\n\n" + result.output());

        StringBuilder query = new StringBuilder("The build failed. Here is the history of build attempts:\n\n");
        for (int i = 0; i < buildErrors.size(); i++) {
            query.append("=== Attempt ").append(i + 1).append(" ===\n")
                    .append(buildErrors.get(i))
                    .append("\n\n");
        }
        query.append("Please fix these build errors.");
        return query.toString();
    }

    /**
     * Determines whether to continue with reflection passes
     */
    private static boolean shouldContinue(Coder coder, int parseErrorAttempts, List<String> buildErrors, IConsoleIO io) {
        // If we have parse errors, limit to MAX_PARSE_ATTEMPTS attempts
        if (parseErrorAttempts >= MAX_PARSE_ATTEMPTS) {
            io.systemOutput("Parse retry limit reached, stopping.");
            return false;
        }

        // For build errors, check if we're making progress
        if (buildErrors.size() > 1) {
            if (isBuildProgressing(coder, buildErrors)) {
                return true;
            }
            io.systemOutput("Build errors are not improving, stopping.");
            return false;
        }

        return true;
    }

    private static boolean isBuildProgressing(Coder coder, List<String> buildResults) {
        var messages = BuildPrompts.instance.collectMessages(buildResults);
        var response = coder.sendMessage(messages);

        // Keep trying until we get one of our expected tokens
        while (!response.contains("BROKK_PROGRESSING") && !response.contains("BROKK_FLOUNDERING")) {
            messages = new ArrayList<>(messages);
            messages.add(new AiMessage(response));
            messages.add(new UserMessage("Please indicate either BROKK_PROGRESSING or BROKK_FLOUNDERING."));
            response = coder.sendMessage(messages);
        }

        return response.contains("BROKK_PROGRESSING");
    }
}
