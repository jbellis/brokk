package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.agents.BuildAgent.BuildDetails;
import io.github.jbellis.brokk.Llm.StreamingResult;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.prompts.QuickEditPrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.LogDescription;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Manages interactions with a Language Model (LLM) to generate and apply code modifications
 * based on user instructions. It handles parsing LLM responses, applying edits to files,
 * verifying changes through build/test commands, and managing the conversation history.
 * It supports both iterative coding sessions (potentially involving multiple LLM interactions
 * and build attempts) and quick, single-shot edits.
 */
public class CodeAgent {
    private static final Logger logger = LogManager.getLogger(CodeAgent.class);
    private static final int MAX_PARSE_ATTEMPTS = 3;
    private final ContextManager contextManager;
    private final StreamingChatLanguageModel model;
    private final IConsoleIO io;

    public CodeAgent(ContextManager contextManager, StreamingChatLanguageModel model) {
        this.contextManager = contextManager;
        this.model = model;
        this.io = contextManager.getIo();
    }

    /**
     * Implementation of the LLM session that runs in a separate thread.
     * Uses the provided model for the initial request and potentially switches for fixes.
     *
     * @param userInput The user's goal/instructions.
     * @return A SessionResult containing the conversation history and original file contents
     */
    public SessionResult runSession(String userInput, boolean forArchitect) {
        var io = contextManager.getIo();
        // Create Coder instance with the user's input as the task description
        var coder = contextManager.getLlm(model, "Code: " + userInput);

        // Track original contents of files before any changes
        var originalContents = new HashMap<ProjectFile, String>();

        // Start verification command inference concurrently
        var verificationCommandFuture = determineVerificationCommandAsync(contextManager);

        // Retry-loop state tracking
        int parseFailures = 0;
        int applyFailures = 0;
        int blocksAppliedWithoutBuild = 0;

        String buildError = "";
        var blocks = new ArrayList<EditBlock.SearchReplaceBlock>();

        io.systemOutput("Code Agent engaged: `%s...`".formatted(LogDescription.getShortDescription(userInput)));
        SessionResult.StopDetails stopDetails;

        var parser = contextManager.getParserForWorkspace();
        // We'll collect the conversation as ChatMessages to store in context history.
        var sessionMessages = new ArrayList<ChatMessage>();
        UserMessage nextRequest = CodePrompts.instance.codeRequest(userInput.trim(),
                                                                   CodePrompts.reminderForModel(contextManager.getModels(), model),
                                                                   parser);

        while (true) {
            // Prepare and send request to LLM
            var allMessages = CodePrompts.instance.collectCodeMessages(contextManager,
                                                                       model,
                                                                       parser,
                                                                       sessionMessages,
                                                                       nextRequest);
            StreamingResult streamingResult = null;
            try {
                streamingResult = coder.sendRequest(allMessages, true);
                stopDetails = checkLlmResult(streamingResult, io);
            } catch (InterruptedException e) {
                logger.debug("CodeAgent interrupted during sendRequest");
                stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.INTERRUPTED);
            }
            if (stopDetails != null) break;

            // Append request/response to session history
            var llmResponse = streamingResult.chatResponse();
            sessionMessages.add(nextRequest);
            sessionMessages.add(llmResponse.aiMessage());

            String llmText = llmResponse.aiMessage().text();
            logger.debug("got response");

            // Parse any edit blocks from LLM response
            var parseResult = parser.parseEditBlocks(llmText, contextManager.getRepo().getTrackedFiles());
            var newlyParsedBlocks = parseResult.blocks();
            blocks.addAll(newlyParsedBlocks);

            if (parseResult.parseError() == null) {
                // No parse errors
                parseFailures = 0;
            } else {
                if (newlyParsedBlocks.isEmpty()) {
                    // No blocks parsed successfully
                    parseFailures++;
                    if (parseFailures > MAX_PARSE_ATTEMPTS) {
                        stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.PARSE_ERROR);
                        io.systemOutput("Parse error limit reached; ending session");
                        break;
                    }
                    nextRequest = new UserMessage(parseResult.parseError());
                    io.llmOutput("Failed to parse LLM response; retrying", ChatMessageType.CUSTOM);
                } else {
                    // Partial parse => ask LLM to continue from last parsed block
                    parseFailures = 0;
                    var partialMsg = """
                            It looks like we got cut off. The last block I successfully parsed was:
                            
                            <block>
                            %s
                            </block>
                            
                            Please continue from there (WITHOUT repeating that one).
                            """.stripIndent().formatted(newlyParsedBlocks.getLast());
                    nextRequest = new UserMessage(partialMsg);
                    io.llmOutput("Incomplete response after %d blocks parsed; retrying".formatted(newlyParsedBlocks.size()),
                                 ChatMessageType.CUSTOM);
                }
                continue;
            }

            logger.debug("{} total unapplied blocks", blocks.size());

            // If no blocks are pending and we haven't applied anything yet, we're done
            if (blocks.isEmpty() && blocksAppliedWithoutBuild == 0) {
                io.systemOutput("No edits found in response, and no changes since last build; ending session");
                if (!buildError.isEmpty()) {
                    // Previous build failed and LLM provided no fixes
                    stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.BUILD_ERROR, buildError);
                } else {
                    stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS, llmText);
                }
                break;
            }

            // Pre-create empty files for any new files before context updates
            // This prevents UI race conditions with file existence checks
            EditBlock.preCreateNewFiles(newlyParsedBlocks, contextManager);

            // Auto-add newly referenced files as editable (but error out if trying to edit an explicitly read-only file)
            var readOnlyFiles = autoAddReferencedFiles(blocks, contextManager, !forArchitect);
            if (!readOnlyFiles.isEmpty()) {
                var filenames = readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","));
                stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.READ_ONLY_EDIT, filenames);
                break;
            }

            // Apply all accumulated blocks
            var editResult = EditBlock.applyEditBlocks(contextManager, io, blocks);
            if (editResult.hadSuccessfulEdits()) {
                int succeeded = blocks.size() - editResult.failedBlocks().size();
                io.llmOutput("\n" + succeeded + " SEARCH/REPLACE blocks applied.", ChatMessageType.CUSTOM);
            }
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            int succeededCount = (blocks.size() - editResult.failedBlocks().size());
            blocksAppliedWithoutBuild += succeededCount;
            blocks.clear(); // Clear them out: either successful or moved to editResult.failed

            // Check for interruption before potentially blocking build verification
            if (Thread.currentThread().isInterrupted()) {
                logger.debug("CodeAgent interrupted after applying edits.");
                stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.INTERRUPTED);
                break;
            }

            // Handle any failed blocks
            if (!editResult.failedBlocks().isEmpty()) {
                // If all blocks failed => increment applyErrors
                if (editResult.hadSuccessfulEdits()) {
                    applyFailures = 0;
                } else {
                    applyFailures++;
                }

                var parseRetryPrompt = getApplyFailureMessage(editResult.failedBlocks(),
                                                              parser,
                                                              succeededCount,
                                                              io,
                                                              contextManager);
                if (!parseRetryPrompt.isEmpty()) {
                    if (applyFailures >= MAX_PARSE_ATTEMPTS) {
                        io.systemOutput("Diff-apply retry limit reached; ending session");
                        // Capture filenames from the failed blocks for details
                        var failedFilenames = editResult.failedBlocks().stream()
                                .map(fb -> fb.block().filename())
                                .filter(Objects::nonNull) // Ensure filename is not null
                                .distinct()
                                .collect(Collectors.joining(","));
                        stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.APPLY_ERROR, failedFilenames);
                        break;
                    }
                    nextRequest = new UserMessage(parseRetryPrompt);
                    continue;
                }
            } else {
                // If we had successful apply, reset applyErrors
                applyFailures = 0;
            }

            // Attempt build/verification
            try {
                buildError = attemptBuildVerification(verificationCommandFuture, contextManager, io);
                blocksAppliedWithoutBuild = 0; // reset after each build attempt
            } catch (InterruptedException e) {
                logger.debug("CodeAgent interrupted during build verification.");
                stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.INTERRUPTED);
                break;
            }

            if (buildError.isEmpty()) {
                stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS);
                break;
            }

            // If the build failed after applying edits, create the next request for the LLM
            // (formatBuildErrorsForLLM includes instructions to stop if not progressing)
            nextRequest = new UserMessage(formatBuildErrorsForLLM(buildError));
        }

        // Conclude session
        assert stopDetails != null; // Ensure a stop reason was set before exiting the loop
        // create the Result for history
        String finalActionDescription = (stopDetails.reason() == SessionResult.StopReason.SUCCESS)
                                        ? userInput
                                        : userInput + " [" + stopDetails.reason().name() + "]";
        // architect auto-compresses the task entry so let's give it the full history to work with, quickModel is cheap
        var finalMessages = forArchitect ? List.copyOf(io.getLlmRawMessages()) : getMessagesForHistory(parser);
        return new SessionResult("Code: " + finalActionDescription,
                                 new ContextFragment.TaskFragment(finalMessages, userInput),
                                 Map.copyOf(originalContents),
                                 stopDetails);
    }

    private List<ChatMessage> getMessagesForHistory(EditBlockParser parser) {
        var rawMessages = io.getLlmRawMessages();
        var trackedFiles = contextManager.getRepo().getTrackedFiles();

        // Identify messages needing summarization (because they have no plaintext) and submit tasks
        var summarizationFutures = new HashMap<AiMessage, Future<String>>();
        for (var message : rawMessages) {
            if (message.type() == ChatMessageType.AI) {
                var aiMessage = (AiMessage) message;
                var parseResult = parser.parse(aiMessage.text(), trackedFiles);
                boolean hasNonBlankText = parseResult.blocks().stream()
                        .anyMatch(outputBlock -> outputBlock.block() == null && !outputBlock.text().strip().isBlank());

                if (!hasNonBlankText && !parseResult.blocks().isEmpty()) { // Has blocks, but no actual text
                    logger.debug("Submitting message for summarization as it contains only edit blocks/whitespace.");
                    // Use get() for now, consider async handling if performance becomes an issue
                    var worker = contextManager.submitSummarizeTaskForConversation(aiMessage.text());
                    summarizationFutures.put(aiMessage, worker);
                }
            }
        }
        // Wait for summaries and collect results
        var summaries = new HashMap<AiMessage, String>();
        summarizationFutures.forEach((message, future) -> {
            try {
                // Use a reasonable timeout
                String summary = future.get(30, TimeUnit.SECONDS);
                summaries.put(message, summary);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.debug("Summarization interrupted for message.", e);
            } catch (TimeoutException e) {
                logger.warn("Summarization timed out for message.", e);
                future.cancel(true);
            } catch (ExecutionException e) {
                logger.error("Summarization failed for message.", e.getCause());
            }
        });

        // Process messages, using summaries or inserting placeholders ---
        return rawMessages.stream()
                .flatMap(message -> {
                    switch (message.type()) {
                        case USER, CUSTOM:
                            return Stream.of(message);
                        case AI:
                            var aiMessage = (AiMessage) message;
                            // Check if we have a summary for this message
                            if (summaries.containsKey(aiMessage)) {
                                return Stream.of(new AiMessage(summaries.get(aiMessage)));
                            }

                            // No summary needed/available, process normally with placeholders
                            var outputTexts = new ArrayList<String>();
                            var parseResult = parser.parse(aiMessage.text(), trackedFiles);
                            boolean lastBlockWasEdit = false; // Track if the immediately preceding block was an edit block

                            for (var outputBlock : parseResult.blocks()) {
                                if (outputBlock.block() != null) { // It's an edit block
                                    if (!outputTexts.isEmpty() && !lastBlockWasEdit) {
                                        // Append placeholder to the last text block added
                                        int lastIndex = outputTexts.size() - 1;
                                        outputTexts.set(lastIndex, outputTexts.get(lastIndex) + " [elided SEARCH/REPLACE block]");
                                    } else {
                                        // Insert a new placeholder text block if last was edit or list is empty
                                        outputTexts.add("[elided SEARCH/REPLACE block]");
                                    }
                                    lastBlockWasEdit = true; // Mark that this block was an edit block
                                } else { // It's a text block
                                    outputTexts.add(outputBlock.text());
                                    lastBlockWasEdit = false; // Mark that this block was a text block
                                }
                            }
                            // Join text, strip ends, and filter out blank results
                            var joinedText = String.join("", outputTexts).strip();
                            return joinedText.isBlank() ? Stream.empty() : Stream.of(new AiMessage(joinedText));

                        // Ignore SYSTEM/TOOL messages for history purposes
                        case SYSTEM, TOOL_EXECUTION_RESULT:
                        default:
                            return Stream.empty();
                    }
                })
                .toList(); // Collect the filtered messages into an immutable list
    }

    /**
     * Checks if a streaming result is empty or errored. If so, logs and returns StopDetails;
     * otherwise returns null to proceed.
     */
    private static SessionResult.StopDetails checkLlmResult(StreamingResult streamingResult, IConsoleIO io) {
        if (streamingResult.error() != null) {
            io.systemOutput("LLM returned an error even after retries. Ending session");
            return new SessionResult.StopDetails(SessionResult.StopReason.LLM_ERROR, streamingResult.error().getMessage());
        }

        var llmResponse = streamingResult.chatResponse();
        assert llmResponse != null; // enforced by SR
        var text = llmResponse.aiMessage().text();
        if (text.isBlank()) {
            io.systemOutput("Blank LLM response even after retries. Ending session.");
            return new SessionResult.StopDetails(SessionResult.StopReason.EMPTY_RESPONSE);
        }

        return null;
    }


    /**
     * Attempts to add as editable any project files that the LLM wants to edit but are not yet editable.
     * If `rejectReadonlyEdits` is true, it returns a list of read-only files attempted to be edited.
     * Otherwise, it returns an empty list.
     *
     * @return A list of ProjectFile objects representing read-only files the LLM attempted to edit,
     * or an empty list if no read-only files were targeted or if `rejectReadonlyEdits` is false.
     */
    private static List<ProjectFile> autoAddReferencedFiles(
            List<EditBlock.SearchReplaceBlock> blocks,
            ContextManager cm,
            boolean rejectReadonlyEdits
    )
    {
        // Use the passed coder instance directly
        var filesToAdd = blocks.stream()
                .map(EditBlock.SearchReplaceBlock::filename)
                .filter(Objects::nonNull)
                .distinct()
                .map(cm::toFile)
                .filter(file -> !cm.getEditableFiles().contains(file))
                .toList();

        if (filesToAdd.isEmpty()) {
            return List.of();
        }

        if (rejectReadonlyEdits) {
            var readOnlyFiles = filesToAdd.stream()
                    .filter(f -> cm.getReadonlyFiles().contains(f))
                    .toList();
            if (!readOnlyFiles.isEmpty()) {
                cm.getIo().systemOutput(
                        "LLM attempted to edit read-only file(s): %s.\nNo edits applied. Mark the file(s) editable or clarify the approach."
                                .formatted(readOnlyFiles.stream().map(ProjectFile::toString).collect(Collectors.joining(","))));
                // Return the list of read-only files that caused the issue
                return readOnlyFiles;
            }
        }

        // Add the files regardless of rejectReadonlyEdits (unless we returned early due to read-only conflicts)
        cm.getIo().systemOutput("Editing additional files " + filesToAdd);
        cm.editFiles(filesToAdd);

        // Return empty list if no read-only files were rejected
        return List.of();
    }

    /**
     * Runs the build verification command (once available) and appends any build error text to buildErrors list.
     * Returns empty string if build is successful, error message otherwise.
     */
    private static String attemptBuildVerification(CompletableFuture<String> verificationCommandFuture,
                                                   ContextManager contextManager,
                                                   IConsoleIO io) throws InterruptedException
    {
        String verificationCommand;
        try {
            verificationCommand = verificationCommandFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.warn("Failed to get verification command", e);
            var bd = contextManager.getProject().getBuildDetails();
            verificationCommand = (bd == null ? null : bd.buildLintCommand());
        }

        return checkBuild(verificationCommand, contextManager, io);
    }

    /**
     * Asynchronously determines the best verification command based on the user goal,
     * workspace summary, and stored BuildDetails.
     * Runs on the ContextManager's background task executor.
     * Determines the command by checking for relevant test files in the workspace
     * and the availability of a specific test command in BuildDetails.
     *
     * @param cm The ContextManager instance.
     * @return A CompletableFuture containing the suggested verification command string (either specific test command or build/lint command),
     * or null if BuildDetails are unavailable.
     */
    private static CompletableFuture<String> determineVerificationCommandAsync(ContextManager cm) {
        // Runs asynchronously on the background executor provided by ContextManager
        return CompletableFuture.supplyAsync(() -> {
            // Retrieve build details from the project associated with the ContextManager
            BuildDetails details = cm.getProject().getBuildDetails();
            if (details == null) {
                logger.warn("No build details available, cannot determine verification command.");
                return null;
            }

            // Get the set of files currently loaded in the workspace (both editable and read-only ProjectFiles)
            var workspaceFiles = Stream.concat(cm.getEditableFiles().stream(), cm.getReadonlyFiles().stream())
                    .collect(Collectors.toSet());

            // Check if any of the identified project test files are present in the current workspace set
            var projectTestFiles = cm.getTestFiles();
            var workspaceTestFiles = projectTestFiles.stream().filter(workspaceFiles::contains).toList();

            // Decide which command to use
            if (workspaceTestFiles.isEmpty()) {
                logger.debug("No relevant test files found in workspace, using build/lint command: {}", details.buildLintCommand());
                return details.buildLintCommand();
            }

            // Construct the prompt for the LLM
            logger.debug("Found relevant tests {}, asking LLM for specific command.", workspaceTestFiles);
            var prompt = """
                    Given the build details and the list of test files modified or relevant to the recent changes,
                    give the shell command to run *only* these specific tests. (You may chain multiple
                    commands with &&, if necessary.)
                    
                    Build Details:
                    Test All Command: %s
                    Build/Lint Command: %s
                    Other Instructions: %s
                    
                    Test Files to execute:
                    %s
                    
                    Provide *only* the command line string to execute these specific tests.
                    Do not include any explanation or formatting.
                    If you cannot determine a more specific command, respond with an empty string.
                    """.formatted(details.testAllCommand(),
                                  details.buildLintCommand(),
                                  details.instructions(),
                                  workspaceTestFiles.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"))).stripIndent();
            // Need a coder instance specifically for this task
            var inferTestCoder = cm.getLlm(cm.getModels().quickModel(), "Infer tests");
            // Ask the LLM
            StreamingResult llmResult = null;
            try {
                llmResult = inferTestCoder.sendRequest(List.of(new UserMessage(prompt)));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            var suggestedCommand = llmResult.chatResponse() != null && llmResult.chatResponse().aiMessage() != null
                                   ? llmResult.chatResponse().aiMessage().text().trim()
                                   : null; // Handle potential nulls from LLM response

            // Use the suggested command if valid, otherwise fallback
            if (suggestedCommand != null && !suggestedCommand.isBlank()) {
                logger.info("LLM suggested specific test command: '{}'", suggestedCommand);
                return suggestedCommand;
            } else {
                logger.warn("LLM did not suggest a specific test command. Falling back to default");
                return details.buildLintCommand();
            }
        }, cm.getBackgroundTasks());
    }

    /**
     * Runs a quick-edit session where we:
     * 1) Gather the entire file content plus related context (buildAutoContext)
     * 2) Use QuickEditPrompts to ask for a single fenced code snippet
     * 3) Replace the old text with the new snippet in the file
     *
     * @return A SessionResult containing the conversation and original content.
     */
    public SessionResult runQuickSession(ProjectFile file,
                                         String oldText,
                                         String instructions) throws InterruptedException
    {
        var coder = contextManager.getLlm(model, "QuickEdit: " + instructions);
        var analyzer = contextManager.getAnalyzer();

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

        var styleGuide = contextManager.getProject().getStyleGuide();

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

        // No echo for Quick Edit, use instance quickModel
        var result = coder.sendRequest(messages, false);

        // Determine stop reason based on LLM response
        SessionResult.StopDetails stopDetails;
        if (result.error() != null) {
            stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.LLM_ERROR, result.error().getMessage());
            io.toolErrorRaw("Quick edit failed: " + result.error().getMessage());
        } else if (result.chatResponse() == null || result.chatResponse().aiMessage() == null || result.chatResponse().aiMessage().text() == null || result.chatResponse().aiMessage().text().isBlank()) {
            stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.EMPTY_RESPONSE);
            io.toolErrorRaw("LLM returned empty response for quick edit.");
        } else {
            // Success from LLM perspective
            String responseText = result.chatResponse().aiMessage().text();
            pendingHistory.add(new AiMessage(responseText));
            stopDetails = new SessionResult.StopDetails(SessionResult.StopReason.SUCCESS);
        }

        // Return SessionResult containing conversation and original content
        return new SessionResult("Quick Edit: " + file.getFileName(),
                                 pendingHistory,
                                 originalContents,
                                 stopDetails);
    }

    /**
     * Formats the most recent build error for the LLM retry prompt.
     */
    private static String formatBuildErrorsForLLM(String latestBuildError) {
        return """
                The build failed with the following error:
                
                %s
                
                Please analyze the error message, review the conversation history for previous attempts, and provide SEARCH/REPLACE blocks to fix the error.
                
                IMPORTANT: If you determine that the build errors are not improving or are going in circles after reviewing the history,
                do your best to explain the problem but DO NOT provide any edits.
                Otherwise, provide the edits as usual.
                """.stripIndent().formatted(latestBuildError);
    }

    /**
     * Generates a message based on parse/apply errors from failed edit blocks
     */
    private static String getApplyFailureMessage(List<EditBlock.FailedBlock> failedBlocks,
                                                 EditBlockParser parser,
                                                 int succeededCount,
                                                 IConsoleIO io,
                                                 ContextManager cm)
        {
            if (failedBlocks.isEmpty()) {
                return "";
            }
    
            // Group failed blocks by filename
            var failuresByFile = failedBlocks.stream()
                    .filter(fb -> fb.block().filename() != null) // Only include blocks with filenames
                    .collect(Collectors.groupingBy(fb -> fb.block().filename()));
    
            int totalFailCount = failedBlocks.size();
            boolean singularFail = (totalFailCount == 1);
            var pluralizeFail = singularFail ? "" : "s";
    
            String fileDetails = failuresByFile.entrySet().stream()
                    .map(entry -> {
                        var filename = entry.getKey();
                        var fileFailures = entry.getValue();
                        var file = cm.toFile(filename);
                        String currentContentBlock;
                        try {
                            var content = file.read();
                            currentContentBlock = """
                                    <current_content>
                                    %s
                                    </current_content>
                                    """.formatted(content.isBlank() ? "[File is empty]" : content).stripIndent();
                        } catch (java.io.IOException e) {
                            logger.warn("Could not read file {} to include in failure message: {}", filename, e.getMessage());
                            currentContentBlock = "<current_content>[Could not read current file content: %s]</current_content>"
                                    .formatted(e.getMessage());
                        }
    
                        String failedBlocksXml = fileFailures.stream()
                                .map(f -> """
                                        <failed_block reason="%s">
                                          <block>
                                          %s
                                          </block>
                                          <commentary>%s</commentary>
                                        </failed_block>
                                        """.formatted(f.reason(), parser.repr(f.block()), f.commentary()).stripIndent())
                                .collect(Collectors.joining("\n"));
    
                        return """
                               <file name="%s">
                               %s
                               
                                 <failed_blocks>
                                 %s
                                 </failed_blocks>
                               </file>
                               """.formatted(filename, currentContentBlock, failedBlocksXml).stripIndent();
                    })
                    .collect(Collectors.joining("\n\n")); // Join details for different files
    
            // Instructions for the LLM
            String instructions = """
                    <instructions>
                    # %d SEARCH/REPLACE block%s failed to match in %d files!
                    
                    Take a look at the CURRENT state of the relevant file%s provided above in the `<current_content>` tags.
                    If the failed edits listed in the `<failed_blocks>` tags are still needed, please correct them based on the current content.
                    Remember that the SEARCH text within a `<block>` must match EXACTLY the lines in the file -- but
                    I can match across whitespace changes, so if you think the only difference is whitespace, you need to look closer.
                    If the SEARCH text looks correct, double-check the filename too.
                    
                    Provide corrected SEARCH/REPLACE blocks for the failed edits only.
                    </instructions>
                    """.formatted(totalFailCount, pluralizeFail, failuresByFile.size(), pluralizeFail).stripIndent();

            // Add info about successful blocks, if any
            String successNote = "";
            if (succeededCount > 0) {
                boolean singularSuccess = (succeededCount == 1);
                var pluralizeSuccess = singularSuccess ? "" : "s";
                successNote = """
                        <note>
                        The other %d SEARCH/REPLACE block%s applied successfully. Do not re-send them. Just fix the failing blocks detailed above.
                        </note>
                        """.formatted(succeededCount, pluralizeSuccess).stripIndent();
            }

            // we don't send file details to the output
            var summary = """
                    \nFailed to apply %s block(s), retrying
                    """.stripIndent().formatted(failedBlocks.size());
            io.llmOutput(summary, ChatMessageType.CUSTOM);

            // Construct the full message for the LLM
            return """
                    %s
                    
                    %s
                    %s
                    """.formatted(instructions, fileDetails, successNote).stripIndent();
        }

    /**
     * Executes the verification command and updates build error history.
     *
     * @return empty string if the build was successful or skipped, error message otherwise.
     */
    private static String checkBuild(String verificationCommand, IContextManager cm, IConsoleIO io) throws InterruptedException {
        if (verificationCommand == null) {
            io.llmOutput("\nNo verification command specified, skipping build/check.", ChatMessageType.CUSTOM);
            return "";
        }

        io.llmOutput("Running verification command: " + verificationCommand, ChatMessageType.CUSTOM);
        var result = Environment.instance.captureShellCommand(verificationCommand, cm.getProject().getRoot());
        logger.debug("Verification command result: {}", result);

        if (result.error() == null) {
            io.llmOutput("\n## Verification successful", ChatMessageType.CUSTOM);
            return "";
        }

        // Build failed
        io.llmOutput("""
                             \n**Verification Failed:** %s
                             ```
                             %s
                             ```
                             """.stripIndent().formatted(result.error(), result.output()), ChatMessageType.CUSTOM);
        // Add the combined error and output to the history for the next request
        return result.error() + "\n\n" + result.output();
    }
}
