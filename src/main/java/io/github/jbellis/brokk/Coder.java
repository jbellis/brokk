package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import io.github.jbellis.brokk.ReflectionManager.FailedBlock;
import io.github.jbellis.brokk.prompts.BuildPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Coder {
    private final IConsoleIO io;
    private final Path historyFile;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Models models;
    final IContextManager contextManager;

    private int totalLinesOfCode = 0;
    private int totalInputTokens = 0;

    public Coder(Models models, IConsoleIO io, Path sourceRoot, IContextManager contextManager) {
        this.models = models;
        this.io = io;
        this.contextManager = contextManager;
        this.historyFile = sourceRoot.resolve(".brokk").resolve("conversations.md");
    }

    /**
     * The main entry point that makes one or more requests to the LLM with reflection.
     *
     * @param userInput The original user message you want to send.
     */
    public void runSession(String userInput) {
        // Add user input to context
        var sessionMessages = new ArrayList<ChatMessage>();
        sessionMessages.add(new UserMessage(userInput.trim()));

        // Reflection loop: up to reflectionManager.maxReflections passes
        var reflectionManager = new ReflectionManager(io, this);
        while (true) {
            // Collect messages from context
            var cmMessages = DefaultPrompts.instance.collectMessages((ContextManager) contextManager);
            List<ChatMessage> messages = Streams.concat(cmMessages.stream(), sessionMessages.stream()).toList();

            // Actually send the message to the LLM and get the response
            String llmResponse = sendMessage(messages);
            if (llmResponse == null) {
                // Interrupted or error.  sendMessage is responsible for giving feedback to user
                return;
            }

            if (llmResponse.isEmpty()) {
                io.toolError("Empty response from LLM, will retry");
                continue;
            }

            // Add the assistant reply to context
            sessionMessages.add(new AiMessage(llmResponse));

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.findOriginalUpdateBlocks(llmResponse, contextManager.getEditableFiles());
            var blocks = parseResult.blocks();

            // ask user if he wants to add any files referenced in search/replace blocks that are not editable
            var blocksNotEditable = blocks.stream()
                    .filter(block -> block.filename() != null)
                    .filter(block -> !contextManager.getEditableFiles().contains(contextManager.toFile(block.filename())))
                    .toList();
            var blocksToAdd = blocksNotEditable.stream()
                    .filter(block -> io.confirmAsk("Add as editable %s?".formatted(block.filename())))
                    .toList();
            var filesToAdd = blocksToAdd.stream()
                    .map(block -> contextManager.toFile(block.filename()))
                    .toList();
            contextManager.addFiles(filesToAdd);
            // Filter out blocks that the user declined adding
            blocks = blocks.stream()
                    .filter(block -> !blocksNotEditable.contains(block) || blocksToAdd.contains(block))
                    .toList();

            // Attempt to apply any code edits from the LLM
            var failedBlocks = applyEditBlocks(blocks);
            
            // Decide if we need a reflection pass
            var reflectionMsg = reflectionManager.getReflectionMessage(parseResult, failedBlocks, blocks, contextManager);

            // If no reflection or we've exhausted attempts, break out
            if (reflectionMsg.isEmpty()) {
                break;
            }

            // If the reflection manager has also signaled "stop" (maybe user said no),
            // or we've reached reflectionManager's maximum tries:
            if (!reflectionManager.shouldContinue()) {
                io.toolOutput("Reflection limit reached; stopping.\n");
                break;
            }

            // Another iteration with the new reflection message
            sessionMessages.add(new UserMessage(reflectionMsg.toString()));
        }

        // Move conversation to history
        var filteredSession = Streams.concat(Stream.of(sessionMessages.getFirst()),
                                             sessionMessages.stream().filter(m -> m instanceof AiMessage))
                .toList();
        contextManager.moveToHistory(filteredSession);
    }

    /**
     * Actually sends a user query to the LLM (with streaming),
     * writes to conversation history, etc.
     */
    public String sendMessage(List<ChatMessage> messages) {
        int userLineCount = messages.stream()
                .mapToInt(m -> ContextManager.getText(m).split("\n", -1).length).sum();

        io.toolOutput("LLM request sent");
        // Write request
        String requestText = messages.stream()
                .map(m -> "%s: %s\n".formatted(m.type(), ContextManager.getText(m)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        writeToHistory("# Request " + LocalDateTime.now().format(dtf) + "\n\n" + requestText + "\n");

        StringBuilder currentResponse = new StringBuilder();
        var latch = new CountDownLatch(1);
        var streamThread = Thread.currentThread();

        // Handle ctrl-c
        Signal sig = new Signal("INT");
        SignalHandler oldHandler = Signal.handle(sig, signal -> streamThread.interrupt());
        AtomicBoolean canceled = new AtomicBoolean(false);
        var lock = new ReentrantLock();
        Consumer<Runnable> ifNotCancelled = (r -> {
            lock.lock();
            try {
                if (canceled.get()) return;
                r.run();
            } finally {
                lock.unlock();
            }
        });

        models.editModelStreaming().generate(messages, new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                ifNotCancelled.accept(() -> {
                    currentResponse.append(token);
                    io.llmOutput(token);
                });
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                ifNotCancelled.accept(() -> {
                    io.llmOutput("\n");
                    var now = LocalDateTime.now().format(dtf);
                    writeToHistory("\n# Response %d tokens at %s\n\n%s\n".formatted(
                            response.tokenUsage().outputTokenCount(), now, currentResponse));
                    if (response.tokenUsage() != null) {
                        totalInputTokens += response.tokenUsage().inputTokenCount();
                        totalLinesOfCode += userLineCount;
                    }
                    latch.countDown();
                });
            }

            @Override
            public void onError(Throwable error) {
                ifNotCancelled.accept(() -> {
                    writeToHistory("\n# Response " + LocalDateTime.now().format(dtf)
                                           + "\n\n" + "Error: " + error.getMessage() + "\n");
                    io.toolErrorRaw("LLM error: " + error.getMessage());
                    streamThread.interrupt();
                });
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            lock.lock();
            canceled.set(true);
            lock.unlock();
            io.toolErrorRaw("\nInterrupted!");
            return null;
        } finally {
            Signal.handle(sig, oldHandler);
        }
        return currentResponse.toString();
    }

    /**
     * Parse the LLM response for SEARCH/REPLACE blocks (or shell blocks, etc.) and apply them.
     */
    public List<FailedBlock> applyEditBlocks(Collection<EditBlock.SearchReplaceBlock> blocks) {
        if (blocks.isEmpty()) {
            return Collections.emptyList();
        }

        // Track which blocks succeed or fail during application
        List<FailedBlock> failed = new ArrayList<>();
        List<EditBlock.SearchReplaceBlock> succeeded = new ArrayList<>();

        for (EditBlock.SearchReplaceBlock block : blocks) {
            // Shell commands remain unchanged
            if (block.shellCommand() != null) {
                io.toolOutput("Shell command from LLM:\n" + block.shellCommand());
                continue;
            }

            // Attempt to apply to the specified file
            RepoFile file = block.filename() == null ? null : contextManager.toFile(block.filename());
            boolean isCreateNew = block.beforeText().trim().isEmpty();

            String finalUpdated = null;
            if (file != null) {
                // if the user gave a valid file name, try to apply it there first
                try {
                    finalUpdated = EditBlock.doReplace(file, block.beforeText(), block.afterText());
                } catch (IOException e) {
                    io.toolError("Failed reading/writing " + file + ": " + e.getMessage());
                }
            }

            // Fallback: if finalUpdated is still null and 'before' is not empty, try each known file
            if (finalUpdated == null && !isCreateNew) {
                for (RepoFile altFile : contextManager.getEditableFiles()) {
                    try {
                        String updatedContent = EditBlock.doReplace(altFile.read(), block.beforeText(), block.afterText());
                        if (updatedContent != null) {
                            file = altFile; // Found a match
                            finalUpdated = updatedContent;
                            break;
                        }
                    } catch (IOException ignored) {
                        // keep trying
                    }
                }
            }
            
            // if we still haven't found a matching file, we have to give up
            if (file == null) {
                failed.add(new FailedBlock(block, ReflectionManager.EditBlockFailureReason.NO_MATCH));
                continue;
            }

            if (finalUpdated == null) {
                // "Did you mean" + "already present?" suggestions
                String fileContent;
                try {
                    fileContent = file.read();
                } catch (IOException e) {
                    io.toolError("Could not read files: " + e.getMessage());
                    failed.add(new FailedBlock(block, ReflectionManager.EditBlockFailureReason.IO_ERROR
                    ));
                    continue;
                }

                String snippet = EditBlock.findSimilarLines(block.beforeText(), fileContent, 0.6);
                StringBuilder suggestion = new StringBuilder();
                if (!snippet.isEmpty()) {
                    suggestion.append("Did you mean:\n").append(snippet).append("\n");
                }
                if (fileContent.contains(block.afterText().trim())) {
                    suggestion.append("Note: The replacement text is already present in the file.\n");
                }

                failed.add(new FailedBlock(
                        block,
                        ReflectionManager.EditBlockFailureReason.NO_MATCH,
                        suggestion.toString()
                ));
            } else {
                // Actually write the file if it changed
                var error = false;
                try {
                    file.write(finalUpdated);
                } catch (IOException e) {
                    io.toolError("Failed writing " + file + ": " + e.getMessage());
                    failed.add(new FailedBlock(block, ReflectionManager.EditBlockFailureReason.IO_ERROR));
                    error = true;
                }
                if (!error) {
                    succeeded.add(block);
                    if (isCreateNew) {
                        try {
                            Environment.instance.gitAdd(file.toString());
                            io.toolOutput("Added to git " + file);
                        } catch (IOException e) {
                            io.toolError("Failed to git add " + file + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        if (!succeeded.isEmpty()) {
            io.toolOutput(succeeded.size() + " SEARCH/REPLACE blocks applied.");
        }
        return failed;
    }

    /**
     * Summarize changes in a single line for ContextManager
     */
    public String summarizeOneline(String request) {
        int lineCount = request.split("\n", -1).length;
        var messages = List.of(
                new UserMessage("Please summarize these changes in a single line:"),
                new AiMessage("Ok, let's see them."),
                new UserMessage(request)
        );
        var response = models.quickModel().generate(messages);
        if (response.tokenUsage() != null) {
            totalInputTokens += response.tokenUsage().inputTokenCount();
            totalLinesOfCode += lineCount;
        }
        return response.content().text().trim();
    }

    /**
     * A helper for generating a commit message from a chunk of context (the changes).
     */
    public String getCommitMessage() {
        var messages = CommitPrompts.instance.collectMessages((ContextManager) contextManager);
        var response = models.quickModel().generate(messages);
        return response.content().text().trim();
    }

    /**
     * Helper to get a quick response from the LLM without streaming
     */
    public boolean isBuildProgressing(List<String> buildResults) {
        var messages = BuildPrompts.instance.collectMessages(buildResults);
        String response = models.quickModel().generate(messages).content().text().trim();
        
        // Keep trying until we get one of our expected tokens
        while (!response.contains("BROKK_PROGRESSING") && !response.contains("BROKK_FLOUNDERING")) {
            messages = new ArrayList<>(messages);
            messages.add(new AiMessage(response));
            messages.add(new UserMessage("Please indicate either BROKK_PROGRESSING or BROKK_FLOUNDERING."));
            response = models.quickModel().generate(messages).content().text().trim();
        }
        
        return response.contains("BROKK_PROGRESSING");
    }

    /**
     * Approximate tokens for N lines of code, based on observed ratio so far.
     * If we haven't measured enough yet, returns null.
     */
    public Integer approximateTokens(int linesOfCode) {
        if (totalLinesOfCode == 0) return null;
        double ratio = (double) totalInputTokens / totalLinesOfCode;
        return (int)Math.round(ratio * linesOfCode);
    }

    private void writeToHistory(String text) {
        try {
            Files.createDirectories(historyFile.getParent());
            Files.writeString(historyFile, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            io.toolErrorRaw("Failed to write to history: " + e.getMessage());
        }
    }
}
