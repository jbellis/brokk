package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Coder {
    private final Logger logger = LogManager.getLogger(Coder.class);

    public enum Mode {
        EDIT,
        APPLY
    }

    Mode mode = Mode.EDIT;
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
        if (!isLlmAvailable()) {
            io.toolError("No LLM available (missing API keys)");
            return;
        }

        var beginMode = mode;

        var requestMsg = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // Reflection loop: up to reflectionManager.maxReflections passes
        var reflectionManager = new ReflectionManager(io, this);
        while (true) {
            // Collect messages from context
            var messages = DefaultPrompts.instance.collectMessages((ContextManager) contextManager);
            messages.add(requestMsg);

            // Actually send the message to the LLM and get the response
            logger.debug("Sending to LLM [only last message shown]: {}", requestMsg);
            String llmResponse = sendStreaming(messages);
            logger.debug("response:\n{}", llmResponse);
            if (llmResponse == null) {
                // Interrupted or error.  sendMessage is responsible for giving feedback to user
                return;
            }

            if (llmResponse.isEmpty()) {
                io.toolError("Empty response from LLM, will retry");
                continue;
            }

            // Add the request/response to history
            contextManager.addToHistory(List.of(requestMsg, new AiMessage(llmResponse)));

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.findOriginalUpdateBlocks(llmResponse, contextManager.getEditableFiles());
            var blocks = parseResult.blocks();
            logger.debug("Parsed {} blocks", blocks.size());

            // ask user if he wants to add any files referenced in search/replace blocks that are not editable
            var blocksNotEditable = blocks.stream()
                    .filter(block -> block.filename() != null)
                    .filter(block -> !contextManager.getEditableFiles().contains(contextManager.toFile(block.filename())))
                    .toList();
            var uniqueFilenames = blocksNotEditable.stream()
                    .map(EditBlock.SearchReplaceBlock::filename)
                    .distinct()
                    .toList();
            var confirmedFilenames = uniqueFilenames.stream()
                    .filter(filename -> io.confirmAsk("Add as editable %s?".formatted(filename)))
                    .toList();
            var blocksToAdd = blocksNotEditable.stream()
                    .filter(block -> confirmedFilenames.contains(block.filename()))
                    .toList();
            var filesToAdd = confirmedFilenames.stream()
                    .map(contextManager::toFile)
                    .toList();
            logger.debug("files to add: {}", filesToAdd);
            contextManager.addFiles(filesToAdd);
            // Filter out blocks that the user declined adding
            blocks = blocks.stream()
                    .filter(block -> !blocksNotEditable.contains(block) || blocksToAdd.contains(block))
                    .toList();
            if (blocks.isEmpty()) {
                break;
            }

            // Attempt to apply any code edits from the LLM
            var failedBlocks = EditBlock.applyEditBlocks(contextManager, io, blocks);
            logger.debug("Failed blocks: {}", failedBlocks);
            
            // Check for parse/match failures first
            var parseReflection = reflectionManager.getParseReflection(parseResult, failedBlocks, blocks);
            if (!parseReflection.isEmpty()) {
                io.toolOutput("Attempting to fix parse/match errors...");
                mode = Mode.APPLY; // faster
                requestMsg = new UserMessage(parseReflection);
                continue;
            }

            // If parsing succeeded, check build
            var buildReflection = reflectionManager.getBuildReflection(contextManager);
            if (buildReflection.isEmpty()) {
                break;
            }

            io.toolOutput("Attempting to fix build errors...");
            mode = Mode.EDIT; // smarter
            requestMsg = new UserMessage(buildReflection);

            // If the reflection manager has also signaled "stop" (maybe user said no),
            // or we've reached reflectionManager's maximum tries:
            if (!reflectionManager.shouldContinue()) {
                break;
            }
        }

        // Reset mode back to what the user had it set to
        mode = beginMode;
    }

    public boolean isLlmAvailable() {
        return !(models.editModel() instanceof Models.UnavailableStreamingModel);
    }

    /**
     * Actually sends a user query to the LLM (with streaming),
     * writes to conversation history, etc.
     */
    public String sendStreaming(List<ChatMessage> messages) {
        int userLineCount = messages.stream()
                .mapToInt(m -> ContextManager.getText(m).split("\n", -1).length).sum();

        io.toolOutput("Request sent");
        writeRequestToHistory(messages);

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

        var model = mode == Mode.EDIT ? models.editModel() : models.applyModel();
        model.generate(messages, new StreamingResponseHandler<>() {
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
                    writeToHistory("Response", currentResponse.toString());
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
                    writeToHistory("Error", error.getMessage());
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

    public String sendMessage(List<ChatMessage> messages) {
        return sendMessage(null, messages);
    }

    /** currently hardcoded to quick model */
    public String sendMessage(String description, List<ChatMessage> messages) {
        if (description != null) {
            io.toolOutput(description);
        }
        writeRequestToHistory(messages);
        Response<AiMessage> response;
        try {
            response = models.quickModel().generate(messages);
        } catch (Throwable th) {
            writeToHistory("Error", th.getMessage());
            return "";
        }

        writeToHistory("Response", response.toString());
        if (response.tokenUsage() != null) {
            totalLinesOfCode += messages.stream()
                    .mapToInt(m -> ContextManager.getText(m).split("\n", -1).length).sum();
            totalInputTokens += response.tokenUsage().inputTokenCount();
        }

        return response.content().text().trim();
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

    private void writeRequestToHistory(List<ChatMessage> messages) {
        String requestText = messages.stream()
                .map(m -> "%s: %s\n".formatted(m.type(), ContextManager.getText(m)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        writeToHistory("Request", requestText);
    }

    private void writeToHistory(String header, String text) {
        var formatted = "\n# %s at %s\n\n%s\n".formatted(header, LocalDateTime.now().format(dtf), text);

        try {
            Files.createDirectories(historyFile.getParent());
            Files.writeString(historyFile, formatted, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            io.toolErrorRaw("Failed to write to history: " + e.getMessage());
        }
    }
}
