package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
     * Uses the provided model for the initial request and potentially switches for fixes.
     *
     * @param coder The Coder instance.
     * @param io Console IO handler.
     * @param model The model selected by the user for the main task.
     * @param userInput The user's goal/instructions.
     */
    public static void runSession(Coder coder, IConsoleIO io, StreamingChatLanguageModel model, String userInput)
    {
        // This map tracks original contents of changed files for final history
        var originalContents = new HashMap<ProjectFile, String>();

        // Keep a conversation record that we'll add to the context history
        List<ChatMessage> pendingHistory = new ArrayList<>();
        var requestMessages = new ArrayList<ChatMessage>();
        requestMessages.add(new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim())));

        int parseErrorAttempts = 0;
        List<String> buildErrors = new ArrayList<>();
        boolean isComplete = false;

        io.systemOutput("Request sent");

        var tools = new LLMTools(coder.contextManager);
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted");
                break;
            }
            // Gather context from the context manager
            var contextManager = (ContextManager) coder.contextManager;
            var reminder = Models.isLazy(model) ? DefaultPrompts.LAZY_REMINDER : DefaultPrompts.OVEREAGER_REMINDER;
            var contextMessages = DefaultPrompts.instance.collectMessages(contextManager, reminder);

            var allMessages = new ArrayList<>(contextMessages);
            allMessages.addAll(requestMessages);

            // Provide the LLM with our new Tools
            var toolSpecs = dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationsFrom(
                    new LLMTools(contextManager)
            );

            // Send with tools
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
                io.systemOutput("No valid LLM response. Stopping session.");
                break;
            }

            String llmText = llmResponse.aiMessage().text();
            if (llmText.isBlank() && !llmResponse.aiMessage().hasToolExecutionRequests()) {
                io.systemOutput("Blank LLM response. Stopping session.");
                break;
            }

            // We got a valid response
            logger.debug("response:\n{}", llmResponse);

            // Add to pending conversation
            pendingHistory.add(requestMessages.get(requestMessages.size() - 1));
            pendingHistory.add(llmResponse.aiMessage());
            // also add so the next loop sees it
            requestMessages.add(llmResponse.aiMessage());

            // The LLM wants to execute some tools. We'll do so and return any results/errors
            var toolRequests = llmResponse.aiMessage().toolExecutionRequests();
            var toolResults = new ArrayList<ChatMessage>();

            // TODO add a two-pass apply like we had before
            // first pass: validate edits and collect failed blocks
            // second pass: apply the successful blocks
            // this will let us add back the "auto-add files referenced in search/replace blocks that are not already editable"
            // code, and the abort-if-attempting-edit-of-read-only logic
            for (var toolRequest : toolRequests) {
                var resultText = "(no result)";
                try {
                    var toolResult = tools.execute(toolRequest);
                    resultText = toolResult.output();
                    if (toolResult.changedFile() != null && !originalContents.containsKey(toolResult.changedFile())) {
                        // Save original content
                        var changed = toolResult.changedFile();
                        try {
                            originalContents.put(
                                    changed,
                                    changed.exists() ? changed.read() : ""
                            );
                        } catch (IOException e) {
                            io.toolError("Failed reading " + changed + ": " + e.getMessage());
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("Tool execution failed: {}", ex.getMessage());
                    resultText = "ERROR: " + ex.getMessage();
                }

                // TODO collect feedback on failed edits
            }
            // TODO continue the outer loop with feedback for LLM if any edits failed

            // If no further instructions or tool calls, attempt a build
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
            requestMessages.add(new UserMessage(buildReflection));
        }

        // Write conversation to history if anything happened
        if (!pendingHistory.isEmpty()) {
            if (!isComplete) {
                userInput += " [incomplete]";
            }
            coder.contextManager.addToHistory(pendingHistory, originalContents, userInput);
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
                .collect(Collectors.toMap(
                        CodeUnit::fqName,   // use the class name as the key
                        cls -> 1.0    // assign a default weight of 1.0 to each class
                ));
        var relatedCode = Context.buildAutoContext(analyzer, seeds, Set.of(), 5);

        // (5) Wrap read() in UncheckedIOException
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
     * Generates a reflection message based on parse errors from failed edit blocks
     */
    private static String getParseReflection(List<EditBlock.FailedBlock> failedBlocks,
                                            List<EditBlock.SearchReplaceBlock> blocks,
                                            IContextManager contextManager,
                                            IConsoleIO io) {
        assert !blocks.isEmpty();

        if (failedBlocks.isEmpty()) {
            return "";
        }

        var reflectionMsg = new StringBuilder();

        var suggestions = EditBlock.collectSuggestions(failedBlocks, contextManager);
        var failedApplyMessage = handleFailedBlocks(suggestions, blocks.size() - failedBlocks.size());
        io.llmOutput("\n" + failedApplyMessage);
        reflectionMsg.append(failedApplyMessage);

        return reflectionMsg.toString();
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
            buildErrors.clear(); // Reset on successful build
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

    /**
     * Helper to get a quick response from the LLM without streaming to determine if build errors are improving
     */
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

    /**
     * Generates a reflection message for failed edit blocks
     */
    private static String handleFailedBlocks(Map<EditBlock.FailedBlock, String> failed, int succeededCount) {
        if (failed.isEmpty()) {
            return "";
        }

        // build an error message
        int count = failed.size();
        boolean singular = (count == 1);
        var failedText = failed.entrySet().stream()
                .map(entry -> {
                    var f = entry.getKey();
                    String fname = (f.block().filename() == null ? "(none)" : f.block().filename());
                    return """
                    ## Failed to match in file: `%s`
                    ```
                    <<<<<<< SEARCH
            %s
                    =======
                    %s
                    >>>>>>> REPLACE
                    ```

                    %s
                    """.stripIndent().formatted(fname,
                                  f.block().beforeText(),
                                  f.block().afterText(),
                                  entry.getValue());
                })
                .collect(Collectors.joining("\n"));
        var successfulText = succeededCount > 0
                ? "\n# The other %d SEARCH/REPLACE block%s %s applied successfully. Don't re-send them. Just fix the failing blocks above.\n"
                .formatted(
                        succeededCount,
                        succeededCount == 1 ? " was" : "s were",
                        succeededCount
                )
                : "";
        return """
        # %d SEARCH/REPLACE block%s failed to match!
        
        %s
        
        The SEARCH text must match exactly the lines in the file.
        %s
        """.stripIndent().formatted(count,
                      singular ? " " : "s",
                      failedText,
                      successfulText);
    }
}
