package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        // We'll collect the conversation as ChatMessages to store in context history.
        var sessionMessages = new ArrayList<ChatMessage>();
        // The user's initial request
        var nextRequest = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // track repeated tool failures
        int parseErrorAttempts = 0;
        // track build errors to check if we are making progress.
        var buildErrors = new ArrayList<String>();

        // whether session completed normally
        boolean isComplete = false;

        // Provide an initial note to the user (or logs) that the session started
        io.systemOutput("Request sent to LLM. Processing...");

        var tools = new LLMTools(coder.contextManager);
        outer:
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                io.systemOutput("Session interrupted.");
                break;
            }

            // Gather context from the context manager -- need to refresh this since we may have made edits in the last pass
            var contextManager = (ContextManager) coder.contextManager;
            var reminder = DefaultPrompts.reminderForModel(model);
            var allMessages = DefaultPrompts.instance.collectMessages(contextManager, sessionMessages, reminder);
            allMessages.add(nextRequest);

            //
            // Actually send the request to the LLM
            //
            var toolSpecs = tools.getToolSpecifications(model);
            var streamingResult = coder.sendMessage(model, allMessages, toolSpecs, ToolChoice.AUTO, true);
            if (streamingResult.cancelled()) {
                io.systemOutput("Session cancelled.");
                break;
            }
            if (streamingResult.error() != null) {
                logger.warn("Error from LLM: {}", streamingResult.error().getMessage());
                io.systemOutput("LLM returned an error even after retries. Ending session.");
                break;
            }
            var llmResponse = streamingResult.chatResponse();
            if (llmResponse == null || llmResponse.aiMessage() == null) {
                io.systemOutput("Empty LLM response even after retries. Ending session.");
                break;
            }

            String llmText = llmResponse.aiMessage().text();
            boolean hasTools = llmResponse.aiMessage().hasToolExecutionRequests();
            if (llmText == null || llmText.isBlank() && !hasTools) {
                io.systemOutput("Blank LLM response. Ending session.");
                break;
            }

            // The LLM has responded successfully, so record both sides of the conversation
            sessionMessages.add(nextRequest);
            sessionMessages.add(streamingResult.originalResponse().aiMessage());

            var toolRequests = llmResponse.aiMessage().toolExecutionRequests();
            if (isToolsEmpty(toolRequests)) {
                // LLM thinks we're done
                isComplete = true;
            }

            //
            // Process tool calls
            //
            var validatedRequests = new ArrayList<LLMTools.ValidatedToolRequest>();
            for (var req : toolRequests == null ? List.<ToolExecutionRequest>of() : toolRequests) {
                var parsed = tools.parseToolRequest(req);
                if (parsed.error() != null) {
                    // Track the error so we can include a Tool Response
                    logger.debug("Tool request parse error: {}", parsed.error());
                    validatedRequests.add(parsed);
                    continue;
                }

                // If we get here, we have a valid ProjectFile
                var pf = parsed.file();
                if (pf != null) {
                    // Check read-only status
                    if (contextManager.getReadonlyFiles().contains(pf)) {
                        io.systemOutput("Request attempts to edit read-only file: " + pf + ". You should make it editable, or clarify your instructions so the AI doesn't think it needs to edit it.");
                        sessionMessages.add(new AiMessage("Session aborted: attempt to edit read-only file " + pf));
                        break outer;
                    }
                    // Store original content for potential history or revert
                    if (!originalContents.containsKey(pf)) {
                        try {
                            originalContents.put(pf, pf.exists() ? pf.read() : "");
                        } catch (IOException e) {
                            io.toolError("Failed to read source file while applying changes: " + e.getMessage());
                            // We can either skip or mark an error and break
                            sessionMessages.add(new AiMessage("Session aborted: unable to read file " + pf));
                            break outer;
                        }
                    }
                }
                validatedRequests.add(parsed);
            }

            // Execute tools
            io.llmOutput("\n");
            int failures = 0;
            var resultMessages = new ArrayList<ToolExecutionResultMessage>();
            var output = new StringBuilder();
            for (var validated : validatedRequests) {
                // Attempt actual edit. (Handles validation errors internally)
                var result = tools.executeTool(validated);
                if (result.toolName().equals("explain") && validated.error() == null) {
                    io.llmOutput("\n\n%s".formatted(result.text()));
                } else {
                    // TODO make this fancier! like, an actual graphical representation of the diff
                    output.append("\n%s: %s".formatted(result.toolName(), result.text()));
                    if (!result.text().equals("SUCCESS")) {
                        logger.warn("Tool application failure: {}", result.text());
                        failures++;
                    }
                }
                resultMessages.add(result);
            }
            if (isComplete) {
                // stop now that we've processed any `explain` tools
                break;
            }
            if (!output.isEmpty()) {
                io.llmOutput("\n\n```" + output + "```\n\n");
            }
            if (!LLMTools.requiresEmulatedTools(model)) {
                // need this whether success or failure or the LLM gets confused seeing that it made a call but no results
                sessionMessages.addAll(resultMessages);
            }

            // If every single request was invalid or failed, increment parseErrorAttempts
            // as an indication that the LLM's instructions might be problematic.
            if (failures == validatedRequests.size()) {
                parseErrorAttempts++;
                if (parseErrorAttempts >= MAX_PARSE_ATTEMPTS) {
                    io.systemOutput("Repeated tool request failures. Stopping session.");
                    break;
                }
            }
            if (failures > 0) {
                if (failures < validatedRequests.size()) {
                    // If at least one succeeded, reset parseErrorAttempts -- we're making progress!
                    parseErrorAttempts = 0;
                }
                logger.debug("Tool requests had errors. Asking LLM to correct them...");
                io.systemOutput("Tool requests had errors. Asking LLM to correct them...");
                String msg;
                if (LLMTools.requiresEmulatedTools(model)) {
                    msg = """
                    Some of your tool calls could not be applied. Please revisit your changes
                    and provide corrected tool usage or updated instructions.  Here are the tool results,
                    in the same order that you provided them:
                    
                    %s
                    """.formatted(coder.emulateToolResults(validatedRequests, resultMessages)).stripIndent().stripIndent();
                } else {
                    msg = """
                    Some of your tool calls could not be applied. Please revisit your changes
                    and provide corrected tool usage or updated instructions.
                    """.stripIndent();
                }
                nextRequest = new UserMessage(msg);
                continue;
            }

            //
            // Attempt to build and see if we are done
            //
            String buildReflection = getBuildReflection(contextManager, io, buildErrors);
            if (buildReflection.isEmpty()) {
                // Build succeeded!
                isComplete = true;
                break;
            }

            // If we got here, we have build errors. Decide if we keep trying or not.
            if (!shouldContinue(coder, parseErrorAttempts, buildErrors, io)) {
                break;
            }

            // prompt the LLM to fix build errors
            io.systemOutput("Requesting the LLM to fix build failures...");
            nextRequest = new UserMessage(buildReflection);
        }

        // If we had any conversation at all, store it in the context history
        if (!sessionMessages.isEmpty()) {
            String finalUserInput = isComplete ? userInput : userInput + " [incomplete]";
            // Tool execution result messages are super ugly but leaving them out means Sonnet won't execute
            // the next request that tries to include the tool-use messages, so we just throw it in unfiltered
            coder.contextManager.addToHistory(sessionMessages, originalContents, finalUserInput);
        }
        if (isComplete) {
            io.systemOutput("Session complete!");
        } else {
            io.systemOutput("Session ended without success.");
        }
    }

    private static boolean isToolsEmpty(List<ToolExecutionRequest> toolRequests) {
        return toolRequests == null || toolRequests.stream().allMatch(tr -> tr.name().equals("explain"));
    }

    /**
     * Runs a quick-edit session where we:
     * 1) Gather the entire file content plus related context (buildAutoContext)
     * 2) Use QuickEditPrompts to ask for a single fenced code snippet
     * 3) Replace the old text with the new snippet in the file
     *
     * @return
     */
    public static String runQuickSession(ContextManager cm,
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
            io.toolErrorRaw("Quick edit failed or was cancelled.");
            // Add to history even if canceled, so we can potentially undo any partial changes
            cm.addToHistory(pendingHistory, originalContents, "Quick Edit (canceled): " + file.getFileName());
            return fileContents;
        }
        var responseText = result.chatResponse().aiMessage().text();
        if (responseText == null || responseText.isBlank()) {
            io.toolErrorRaw("LLM returned empty response for quick edit.");
            // Add to history even if it failed
            cm.addToHistory(pendingHistory, originalContents, "Quick Edit (failed): " + file.getFileName());
            return fileContents;
        }
        
        // Add the response to pending history
        pendingHistory.add(new AiMessage(responseText));

        // Extract the new snippet
        var newSnippet = EditBlock.extractCodeFromTripleBackticks(responseText).trim();
        if (newSnippet.isEmpty()) {
            io.toolErrorRaw("Could not parse a fenced code snippet from LLM response.");
            return fileContents;
        }

        // Attempt to replace old snippet in the file
        // If oldText not found, do nothing
        String updatedFileContents;
        String newStripped = newSnippet.stripLeading();
        try {
            if (!fileContents.contains(oldText)) {
                io.toolErrorRaw("The selected snippet was not found in the file. No changes applied.");
                // Add to history even if it failed
                cm.addToHistory(List.of(new UserMessage(instructionsMsg)), originalContents, "Quick Edit (failed): " + file.getFileName());
                return fileContents;
            }
            updatedFileContents = fileContents.replaceFirst(Pattern.quote(oldText.stripLeading()),
                                                            Matcher.quoteReplacement(newStripped));
        } catch (Exception ex) {
            io.toolErrorRaw("Failed to replace text: " + ex.getMessage());
            // Add to history even if it failed
            cm.addToHistory(List.of(new UserMessage(instructionsMsg)), originalContents, "Quick Edit (failed): " + file.getFileName());
            return fileContents;
        }

        // Write the updated file to disk
        try {
            file.write(updatedFileContents);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        // Save to context history - pendingHistory already contains both the instruction and the response
        var parsed = new Context.ParsedOutput(responseText, new ContextFragment.StringFragment(responseText, "ai Response"));
        cm.pushContext(ctx -> ctx.addHistory(messages, originalContents, parsed, cm.submitSummarizeTaskForConversation("Quick Edit: " + instructions)));
        return newStripped;
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
        var result = Environment.instance.captureShellCommand(cmd, cm.getProject().getRoot());
        logger.debug("Build command result: {}", result);
        if (result.error() == null) {
            io.systemOutput("Build successful");
            buildErrors.clear();
            return "";
        }

        var msg = """
            %s
            ```
            %s
            ```
            """.stripIndent().formatted(result.error(), result.output());
        io.llmOutput(msg);
        io.systemOutput("Build failed (details above)");
        buildErrors.add(result.error() + "\n\n" + result.output());

        return "The build failed:\n%s\n\nPlease fix these build errors.".formatted(msg);
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
