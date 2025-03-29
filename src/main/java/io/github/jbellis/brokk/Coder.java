package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Coder {
    private final Logger logger = LogManager.getLogger(Coder.class);

    private final IConsoleIO io;
    private final Path historyFile;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public final Models models;
    final IContextManager contextManager;
    private int MAX_ATTEMPTS = 8;

    public Coder(Models models, IConsoleIO io, Path sourceRoot, IContextManager contextManager) {
        this.models = models;
        this.io = io;
        this.contextManager = contextManager;
        this.historyFile = sourceRoot.resolve(".brokk").resolve("llm.log");
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
    public StreamingResult sendStreaming(StreamingChatLanguageModel model, List<ChatMessage> messages, boolean echo) {
        return sendMessageWithRetry(model, messages, List.of(), echo, MAX_ATTEMPTS);
    }

    /**
     * Transcribes an audio file using the STT model (OpenAI Whisper if available).
     * Returns the transcribed text or an error message on failure.
     */
    public String transcribeAudio(Path audioFile) {
        if (models.sttModel() instanceof Models.UnavailableSTT) {
            io.toolError("STT is unavailable. OpenAI API key is required.");
            return "STT unavailable";
        }
        try {
            String transcript = models.sttModel().transcribe(audioFile);
            return transcript;
        } catch (Exception e) {
            logger.error("Failed to transcribe audio: {}", e.getMessage(), e);
            io.toolError("Error transcribing audio: " + e.getMessage());
            return "";
        }
    }
    
    private StreamingResult doSingleStreamingCall(StreamingChatLanguageModel model,
                                                 ChatRequest request,
                                                 boolean echo)
    {
        // latch for awaiting the complete response
        var latch = new CountDownLatch(1);
        // locking for cancellation -- we don't want to show any output after cancellation
        AtomicBoolean canceled = new AtomicBoolean(false);
        var lock = new ReentrantLock();
        AtomicReference<Throwable> errorRef = new AtomicReference<>(null);
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
        writeRequestToHistory(request.messages(), request.toolSpecifications());

        AtomicReference<ChatResponse> atomicResponse = new AtomicReference<>();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput(token);
                    }
                });
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
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
                    writeToHistory("Error", error.getClass() + ": " + error.getMessage());
                    io.toolErrorRaw("LLM error: " + error.getMessage());
                    // Instead of interrupting, just record it so we can retry from the caller
                    errorRef.set(error);
                    latch.countDown();
                });
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            lock.lock();
            canceled.set(true);
            lock.unlock();
            // We were interrupted while waiting. That is cancellation.
            return new StreamingResult(null, true, null);
        }

        if (Thread.currentThread().isInterrupted()) {
            // Another chance to detect cancellation
            return new StreamingResult(null, true, null);
        }

        Throwable streamingError = errorRef.get();
        if (streamingError != null) {
            // Return an error result
            return new StreamingResult(null, false, streamingError);
        }

        // No error, not cancelled => success
        return new StreamingResult(atomicResponse.get(), false, null);
    }

    /**
     * Send a message to the default quick model
     *
     * @param messages The messages to send
     * @return The LLM response as a string
     */
    public String sendMessage(List<ChatMessage> messages) {
        var R = sendMessage(models.quickModel(), messages, List.of());
        return R.chatResponse.aiMessage().text().trim();
    }

    public StreamingResult sendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages) {
        return sendMessage(model, messages, List.of());
    }
    

    /**
     * Send a message to a specific model with tool support
     *
     * @param model    The model to use
     * @param messages The messages to send
     * @param tools    List of tools to enable for the LLM
     * @return The LLM response as a string
     */
    public StreamingResult sendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools) {

        var result = sendMessageWithRetry(model, messages, tools, false, MAX_ATTEMPTS);
        var cr = result.chatResponse();
        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for Anthropic)
        // Also needed for our DeepSeek emulation if it returns a response without a tool call
        while (!result.cancelled && result.error == null && !tools.isEmpty() && !cr.aiMessage().hasToolExecutionRequests()) {
            io.systemOutput("Enforcing tool selection");
            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(cr.aiMessage());

            // Add a stronger instruction for DeepSeek models
            if (model.toString().toLowerCase().contains("deepseek")) {
                extraMessages.add(new UserMessage("You MUST call one of the available tools. Format your response as JSON with a tool_calls array."));
            } else {
                extraMessages.add(new UserMessage("At least one tool execution request is required"));
            }

            result = sendMessageWithRetry(model, extraMessages, tools, false, MAX_ATTEMPTS);
        }

        if (model instanceof AnthropicChatModel) {
            var tu = (AnthropicTokenUsage) cr.tokenUsage();
            writeToHistory("Cache usage", "%s, %s".formatted(tu.cacheCreationInputTokens(), tu.cacheReadInputTokens()));
        }

        return result;
    }

    /**
     * Wrapper for chat calls with retry logic and exponential backoff
     */
    private StreamingResult sendMessageWithRetry(StreamingChatLanguageModel model,
                                              List<ChatMessage> messages,
                                              List<ToolSpecification> tools,
                                              boolean echo,
                                              int maxAttempts)
    {
        Throwable error = null;
        int attempt = 0;
        while (attempt++ < maxAttempts) {
            logger.debug("Sending request to LLM attempt {} [only last message shown]: {}", attempt, messages.getLast());
            var response = doSingleSendMessage(model, messages, tools, echo);
            if (response.cancelled) {
                writeToHistory("Cancelled", "LLM request cancelled by user");
                return response;
            }

            error = response.error;
            if (error == null) {
                var cr = response.chatResponse;
                boolean isEmpty = (cr.aiMessage().text() == null || cr.aiMessage().text().isBlank())
                        && !cr.aiMessage().hasToolExecutionRequests();
                if (!isEmpty) {
                    writeToHistory("Response", cr.aiMessage().text());
                    return response;
                }
            }

            // wait between retries
            logger.debug("LLM error / empty message in {}", response);
            if (attempt == maxAttempts) {
                // don't sleep if this is the last attempt
                break;
            }

            // Exponential backoff
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16);

            io.systemOutput(String.format("LLM issue on attempt %d of %d (will retry in %.1f seconds).",
                                  attempt, maxAttempts, (double) backoffSeconds));

            // Busywait with countdown
            long startTime = System.currentTimeMillis();
            long endTime = startTime + (backoffSeconds * 1000L);
            try {
                while (System.currentTimeMillis() < endTime) {
                    double remainingSeconds = (endTime - System.currentTimeMillis()) / 1000.0;
                    if (remainingSeconds <= 0) break;
                    io.actionOutput(String.format("Retrying in %.1f seconds...", remainingSeconds));
                    Thread.sleep(100); // Update every 100ms
                }
            } catch (InterruptedException e) {
                io.systemOutput("Interrupted!");
                return new StreamingResult(null, true, null);
            }
        }

        // Return the final result
        if (error == null) {
            // Return a minimal ChatResponse indicating empty
            writeToHistory("Error", "LLM returned empty or null after max retries");
            var cr = ChatResponse.builder().aiMessage(new AiMessage("Empty response after max retries")).build();
            return new StreamingResult(cr, false, new IllegalStateException("LLM returned empty or null"));
        }
        writeToHistory("Error", error.getClass() + ": " + error.getMessage());
        // Return a minimal ChatResponse with an error text so caller knows
        var cr = ChatResponse.builder().aiMessage(new AiMessage("Error: " + error.getMessage())).build();
        return new StreamingResult(cr, false, error);
    }

    /**
     * Performs a single message call without retries
     */
    private StreamingResult doSingleSendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools, boolean echo) {
        writeRequestToHistory(messages, tools);
        var builder = ChatRequest.builder().messages(messages);

        if (!tools.isEmpty()) {
            // Check if this is a DeepSeek model that needs function calling emulation
            if (models.nameOf(model).toLowerCase().contains("deepseek")) {
                return emulateToolsUsingStructuredOutput(model, messages, tools);
            }

            // For models with native function calling
            var params = ChatRequestParameters.builder()
                    .toolSpecifications(tools)
                    .build();
            builder = builder.parameters(params);
        }

        var request = builder.build();
        var response = doSingleStreamingCall(model, request, echo);
        writeToHistory("Response", response.toString());
        return response;
    }

    /**
     * Emulates function calling for models that support structured output but not native function calling
     * Used primarily for DeepSeek models
     */
    private StreamingResult emulateToolsUsingStructuredOutput(StreamingChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools) {
        assert !tools.isEmpty();
        for (var tool: tools) {
            assert tool.parameters() != null;
            assert tool.parameters().properties() != null;
        }

        ObjectMapper mapper = new ObjectMapper();

        var instructionsPresent = messages.stream().anyMatch(m -> {
            var t = Models.getText(m);
            return t.contains("tool_calls") && t.contains("Available tools:") && t.contains("Response format:");
        });
        logger.debug("sending {} messages with {}", messages.size(), instructionsPresent);
        if (!instructionsPresent) {
            // Inject  instructions for the model re how to format function calls
            messages = new ArrayList<>(messages); // so we can modify it
            String toolsDescription = tools.stream()
                    .map(tool -> {
                        var parametersInfo = tool.parameters().properties().entrySet().stream()
                                .map(entry -> {
                                    try {
                                        var node = mapper.valueToTree(entry.getValue());
                                        var description = node.has("description") ? ": " + node.get("description").asText() : "";
                                        return "    - %s%s".formatted(entry.getKey(), description);
                                    } catch (Exception e) {
                                        return "    - %s".formatted(entry.getKey());
                                    }
                                })
                                .collect(Collectors.joining("\n"));
    
                        return """
                               - %s: %s
                                 Parameters:
                               %s
                               """.formatted(tool.name(), tool.description(), parametersInfo);
                    })
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            var jsonPrompt = """
                You must respond in JSON format with one or more tool calls.
                Available tools:
                %s

                Response format:
                {
                  "tool_calls": [
                    {
                      "name": "tool_name",
                      "arguments": {
                        "arg1": "value1",
                        "arg2": "value2"
                      }
                    }
                  ]
                }
                """.formatted(toolsDescription);
            // Add the system message to the beginning of the messages list
            messages.set(messages.size() - 1, new UserMessage(Models.getText(messages.getLast()) + "\n\n" + jsonPrompt));
            logger.debug("Modified messages are {}", messages);
        }

        // Create a request with JSON response format
        var requestParams = ChatRequestParameters.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .build())
                .build();

        var request = ChatRequest.builder()
                .messages(messages)
                .parameters(requestParams)
                .build();

        var response = doSingleStreamingCall(model, request, false);

        // Parse the JSON response and convert it to a tool execution request
        try {
            String jsonResponse = response.chatResponse.aiMessage().text();
            logger.debug("Raw JSON response from model: {}", jsonResponse);
                        JsonNode root = mapper.readTree(jsonResponse);

            // Check for tool_calls array format
            if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
                JsonNode toolCalls = root.get("tool_calls");

                // Create list of tool execution requests
                var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();

                for (int i = 0; i < toolCalls.size(); i++) {
                    JsonNode toolCall = toolCalls.get(i);
                    if (toolCall.has("name") && toolCall.has("arguments")) {
                        String toolName = toolCall.get("name").asText();
                        JsonNode arguments = toolCall.get("arguments");
                        
                        String argumentsJson;
                        if (arguments.isObject()) {
                            argumentsJson = arguments.toString();
                        } else {
                            // Handle case where arguments might be a string instead of object
                            try {
                                JsonNode parsedArgs = mapper.readTree(arguments.asText());
                                argumentsJson = parsedArgs.toString();
                            } catch (Exception e) {
                                // If parsing fails, use as-is
                                argumentsJson = arguments.toString();
                            }
                        }
                        
                        var toolExecutionRequest = ToolExecutionRequest.builder()
                                .id(String.valueOf(i))
                                .name(toolName)
                                .arguments(argumentsJson)
                                .build();
                        
                        toolExecutionRequests.add(toolExecutionRequest);
                    }
                }

                assert !toolExecutionRequests.isEmpty();
                logger.debug("Generated tool execution requests: {}", toolExecutionRequests);

                // Create a properly formatted AiMessage with tool execution requests
                var aiMessage = new AiMessage("[json]", toolExecutionRequests);
                var cr = ChatResponse.builder().aiMessage(aiMessage).build();
                return new StreamingResult(cr, false, null);
            }
            // If no tool_call found, return original response
            logger.debug("No tool calls found in response, returning original");
            return response;
        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON response: " + e.getMessage(), e);
            // Return the original response if parsing fails
            // TODO better fix for internal errors?
            return response;
        }
    }

    private void writeRequestToHistory(List<ChatMessage> messages, List<ToolSpecification> tools) {
        String requestText = messages.stream()
                .map(m -> "%s: %s\n".formatted(m.type(), Models.getText(m)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
                
        if (tools != null && !tools.isEmpty()) {
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

    /**
     * Represents the outcome of a streaming request.
     */
    public record StreamingResult(ChatResponse chatResponse, boolean cancelled, Throwable error) {
        public StreamingResult {
            assert cancelled || error != null || chatResponse != null;
        }
    }
}
