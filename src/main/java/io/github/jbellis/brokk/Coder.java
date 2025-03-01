package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
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
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class Coder {
    private final Logger logger = LogManager.getLogger(Coder.class);

    private final IConsoleIO io;
    private final Path historyFile;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public final Models models;
    final IContextManager contextManager;

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
    public void runSession(StreamingChatLanguageModel model, String userInput) {
        // Track original contents of files before any changes
        var originalContents = new HashMap<RepoFile, String>(); 
        List<ChatMessage> pendingHistory = new ArrayList<>();
        if (!isLlmAvailable()) {
            io.toolError("No LLM available (missing API keys)");
            return;
        }

        var requestMsg = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // Reflection loop: up to reflectionManager.maxReflections passes
        var reflectionManager = new ReflectionManager(io, this);
        while (true) {
            // Collect messages from context
            var messages = DefaultPrompts.instance.collectMessages((ContextManager) contextManager);
            messages.add(requestMsg);

            // Actually send the message to the LLM and get the response
            logger.debug("Sending to LLM [only last message shown]: {}", requestMsg);
            var llmResponse = sendStreaming(model, messages, true);
            logger.debug("response:\n{}", llmResponse);
            if (llmResponse == null) {
                // Interrupted or error.  sendMessage is responsible for giving feedback to user
                break;
            }

            var llmText = llmResponse.content().text();
            if (llmText.isBlank()) {
                io.toolError("Empty response from LLM, will retry");
                continue;
            }

            // Add the request/response to pending history
            pendingHistory.addAll(List.of(requestMsg, llmResponse.content()));

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.findOriginalUpdateBlocks(llmText, contextManager.getEditableFiles());
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
            if (!filesToAdd.isEmpty()) {
                contextManager.addFiles(filesToAdd);
            }
            // Filter out blocks that the user declined adding
            blocks = blocks.stream()
                    .filter(block -> !blocksNotEditable.contains(block) || blocksToAdd.contains(block))
                    .toList();
            if (blocks.isEmpty()) {
                break;
            }

            // Attempt to apply any code edits from the LLM
            var editResult = EditBlock.applyEditBlocks(contextManager, io, blocks);
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            logger.debug("Failed blocks: {}", editResult.blocks());

            // Check for parse/match failures first 
            var parseReflection = reflectionManager.getParseReflection(parseResult, editResult.blocks(), blocks, contextManager);
            if (!parseReflection.isEmpty()) {
                io.toolOutput("Attempting to fix parse/match errors...");
                model = models.applyModel();
                requestMsg = new UserMessage(parseReflection);
                continue;
            }

            // If parsing succeeded, check build
            var buildReflection = reflectionManager.getBuildReflection(contextManager);
            if (buildReflection.isEmpty()) {
                break;
            }
            // If the reflection manager has also signaled "stop" (maybe user said no),
            // or we've reached reflectionManager's maximum tries:
            if (!reflectionManager.shouldContinue()) {
                break;
            }
            io.toolOutput("Attempting to fix build errors...");
            // Use EDIT model (smarter) for build fixes
            model = models.editModel();
            requestMsg = new UserMessage(buildReflection);
        }

        // Add all pending messages to history in one batch
        if (!pendingHistory.isEmpty()) {
            contextManager.addToHistory(pendingHistory, originalContents);
        }
    }

    public boolean isLlmAvailable() {
        return !(models.editModel() instanceof Models.UnavailableStreamingModel);
    }

    /**
     * Sends a user query to the LLM (with streaming),
     * writes to conversation history, etc.
     *
     * @param model    The LLM model to use
     * @param messages The messages to send
     * @param echo     Whether to echo LLM responses to the console
     * @return The final response from the LLM as a string
     */
    public Response<AiMessage> sendStreaming(StreamingChatLanguageModel model, List<ChatMessage> messages, boolean echo) {
        if (echo) {
            io.toolOutput("Request sent");
        }

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

        // Write request with tools to history
        writeRequestToHistory(messages, List.of());

        var atomicResponse = new AtomicReference<Response<AiMessage>>();
        model.generate(messages, new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput(token);
                    }
                });
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput("\n");
                    }
                    atomicResponse.set(response);
                    writeToHistory("Response", response.toString());
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
        return atomicResponse.get();
    }

    /**
     * Send a message to the default quick model
     * 
     * @param messages The messages to send
     * @return The LLM response as a string
     */
    public String sendMessage(List<ChatMessage> messages) {
        return sendMessage((String) null, messages);
    }

    /**
     * Send a message to the default quick model with a description
     * 
     * @param description Description of the request (logged to console)
     * @param messages The messages to send
     * @return The LLM response as a string
     */
    public String sendMessage(String description, List<ChatMessage> messages) {
        var R = sendMessage(models.quickModel(), description, messages, List.of());
        return R.content().text().trim();
    }

    public Response<AiMessage> sendMessage(ChatLanguageModel model, List<ChatMessage> messages) {
        return sendMessage(model, null, messages, List.of());
    }
    

    /**
     * Send a message to a specific model with tool support
     *
     * @param model       The model to use
     * @param description Description of the request (logged to console)
     * @param messages    The messages to send
     * @param tools       List of tools to enable for the LLM
     * @return The LLM response as a string
     */
    public Response<AiMessage> sendMessage(ChatLanguageModel model, String description, List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (description != null) {
            io.toolOutput(description);
        }
        writeRequestToHistory(messages, tools);
        Response<AiMessage> response;
        response = model.generate(messages, tools);

        writeToHistory("Response", response.toString());

        return response;
    }

    private void writeRequestToHistory(List<ChatMessage> messages, List<ToolSpecification> tools) {
        String requestText = messages.stream()
                .map(m -> "%s: %s\n".formatted(m.type(), Models.getText(m)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
                
        if (!tools.isEmpty()) {
            requestText += "\nTools:\n" + tools.stream()
                .map(t -> "- %s: %s".formatted(t.name(), t.description()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        }
        
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
