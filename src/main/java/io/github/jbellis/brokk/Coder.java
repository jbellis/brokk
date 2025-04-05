package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.github.jbellis.brokk.analyzer.CodeUnit;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Coder {
    private static final Logger logger = LogManager.getLogger(Coder.class);

    private final IConsoleIO io;
    private final Path historyFile;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    final IContextManager contextManager;
    private final int MAX_ATTEMPTS = 8; // Keep retry logic for now

    public Coder(IConsoleIO io, Path sourceRoot, IContextManager contextManager) {
        this.io = io;
        this.contextManager = contextManager;
        this.historyFile = sourceRoot.resolve(".brokk").resolve("llm.log");
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
        return sendMessageWithRetry(model, messages, List.of(), ToolChoice.AUTO, echo, MAX_ATTEMPTS);
    }

    /**
     * Transcribes an audio file using the configured STT model (OpenAI Whisper if available).
     * Returns the transcribed text or an error message on failure.
     */
    public String transcribeAudio(Path audioFile, Set<String> symbols) {
        var sttModel = Models.sttModel();
        try {
            return sttModel.transcribe(audioFile, symbols);
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
        AtomicReference<Integer> outputTokenCountRef = new AtomicReference<>(-1);
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
        var tools = request.parameters().toolSpecifications();
        writeRequestToHistory(request.messages(), tools);

        AtomicReference<ChatResponse> atomicResponse = new AtomicReference<>();

        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        if (LLMTools.requiresEmulatedTools(model) && emulatedToolInstructionsPresent(request.messages())) {
                            io.llmOutput(". ");
                        } else {
                            io.llmOutput(token);
                        }
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
                    if (response == null) {
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        errorRef.set(new HttpException(400, "BadRequestError"));
                    } else {
                        writeToHistory("Response", response.toString());
                        if (response.tokenUsage() == null) {
                            logger.warn("Null token usage !? in {}", response);
                        } else {
                            outputTokenCountRef.set(response.tokenUsage().outputTokenCount());
                        }
                    }
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
            // We were interrupted while waiting
            return new StreamingResult(null, true, null);
        }
        if (Thread.currentThread().isInterrupted()) {
            // Another chance to detect cancellation
            return new StreamingResult(null, true, null);
        }

        Throwable streamingError = errorRef.get();
        int outputTokenCount = outputTokenCountRef.get();
        if (streamingError != null) {
            // Return an error result
            return new StreamingResult(null, outputTokenCount, false, streamingError);
        }

        // No error, not cancelled => success
        return new StreamingResult(atomicResponse.get(), outputTokenCount, false, null);
    }

    /**
     * Send a message to the default quick model without tools.
     *
     * @param messages The messages to send
     * @return The LLM response text, trimmed. Returns an empty string on error/cancellation.
     */
    public String sendMessage(List<ChatMessage> messages) {
        var result = sendMessage(Models.quickModel(), messages, List.of(), ToolChoice.AUTO, false);
        if (result.cancelled() || result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            throw new IllegalStateException();
        }
        return result.chatResponse().aiMessage().text().trim();
    }

    /**
     * Send a message to a specific model without tools.
     */
    public StreamingResult sendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages) {
        return sendMessage(model, messages, List.of(), ToolChoice.AUTO, false);
    }


    /**
     * Send a message to a specific model with tool support
     *
     * @param model    The model to use
     * @param messages The messages to send
     * @param tools    List of tools to enable for the LLM
     * @return The LLM response as a string
     */
    public StreamingResult sendMessage(StreamingChatLanguageModel model,
                                       List<ChatMessage> messages,
                                       List<ToolSpecification> tools,
                                       ToolChoice toolChoice,
                                       boolean echo)
    {
        var result = sendMessageWithRetry(model, messages, tools, toolChoice, echo, MAX_ATTEMPTS);
        var cr = result.chatResponse();
        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for Anthropic)
        // Also needed for our DeepSeek emulation if it returns a response without a tool call
        while (!result.cancelled && result.error == null && !tools.isEmpty() && !cr.aiMessage().hasToolExecutionRequests() && toolChoice == ToolChoice.REQUIRED) {
            logger.debug("Enforcing tool selection");
            io.systemOutput("Enforcing tool selection");
            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(cr.aiMessage());

            // Add a stronger instruction for DeepSeek models
            if (model.toString().toLowerCase().contains("deepseek")) {
                extraMessages.add(new UserMessage("You MUST call one of the available tools. Format your response as JSON with a tool_calls array."));
            } else {
                extraMessages.add(new UserMessage("At least one tool execution request is required"));
            }

            result = sendMessageWithRetry(model, extraMessages, tools, toolChoice, echo, MAX_ATTEMPTS);
            cr = result.chatResponse();
        }

        return result;
    }

    /**
     * Wrapper for chat calls with retry logic and exponential backoff
     */
    private StreamingResult sendMessageWithRetry(StreamingChatLanguageModel model,
                                                 List<ChatMessage> messages,
                                                 List<ToolSpecification> tools,
                                                 ToolChoice toolChoice,
                                                 boolean echo,
                                                 int maxAttempts)
    {
        Throwable error = null;
        int attempt = 0;
        while (attempt++ < maxAttempts) {
            logger.debug("Sending request to {} attempt {} [only last message shown]: {}", Models.nameOf(model), attempt, messages.getLast());
            var response = doSingleSendMessage(model, messages, tools, toolChoice, echo);
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
            if (error != null && error.getMessage().contains("BadRequestError")) {
                // don't retry on bad request errors
                break;
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
    private StreamingResult doSingleSendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools, ToolChoice toolChoice, boolean echo) {
        writeRequestToHistory(messages, tools);
        var builder = ChatRequest.builder().messages(messages);

        if (!tools.isEmpty()) {
            // Check if this is a DeepSeek model that needs function calling emulation
            if (LLMTools.requiresEmulatedTools(model)) {
                return emulateToolsUsingStructuredOutput(model, messages, tools, toolChoice, echo);
            }

            // For models with native function calling
            logger.debug("Performing native tool calls");
            var params = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(tools)
                    .parallelToolCalls(true)
                    .toolChoice(toolChoice)
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
    private StreamingResult emulateToolsUsingStructuredOutput(StreamingChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools, ToolChoice toolChoice, boolean echo) {
        assert !tools.isEmpty();
        for (var tool : tools) {
            assert tool.parameters() != null;
            assert tool.parameters().properties() != null;
        }

        var instructionsPresent = emulatedToolInstructionsPresent(messages);
        logger.debug("Tool emulation sending {} messages with {}", messages.size(), instructionsPresent);
        ObjectMapper mapper = new ObjectMapper();
        if (!instructionsPresent) {
            // Inject instructions for the model re how to format function calls
            var instructions = getInstructions(tools);
            var modified = new UserMessage(Models.getText(messages.getLast()) + "\n\n" + instructions);
            messages = new ArrayList<>(messages); // so we can modify it
            messages.set(messages.size() - 1, modified);
            logger.debug("Modified messages are {}", messages);
        }

        // Create a request with JSON response format
        var requestParams = ChatRequestParameters.builder()
                .responseFormat(ResponseFormat.builder()
                                        .type(ResponseFormatType.JSON)
                                        .build())
                .build();

        // Try up to 3 times to get a valid JSON response
        int maxRetries = 3;
        List<ChatMessage> currentMessages = new ArrayList<>(messages);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            var request = ChatRequest.builder()
                    .messages(currentMessages)
                    .parameters(requestParams)
                    .build();
            var result = doSingleStreamingCall(model, request, echo);

            // If cancelled or no chat response, return immediately
            if (result.cancelled) {
                return result;
            }

            if (result.chatResponse == null || result.chatResponse.aiMessage() == null) {
                logger.debug("No chat response or AI message in response on attempt {}", attempt);
                continue;
            }

            // Try to parse the JSON response
            try {
                StreamingResult parsedResponse = parseJsonToTools(result, mapper);
                if (parsedResponse.chatResponse.aiMessage().hasToolExecutionRequests()) {
                    // Successfully parsed tool calls, return the result
                    logger.debug("Successfully parsed tool calls on attempt {}", attempt);
                    return parsedResponse;
                }
                // If we get here, the response was valid JSON but didn't contain tool calls
                if (toolChoice == ToolChoice.AUTO) {
                    // If tools are optional, return the original response
                    logger.debug("Tools are optional, returning original response on attempt {}", attempt);
                    return result;
                }
                // else throw so catch retries
                throw new IllegalArgumentException("Response contained valid JSON but no tool_calls");
            } catch (IllegalArgumentException e) {
                // If this is the last attempt, throw the exception
                if (attempt == maxRetries) {
                    var msg = "Failed to get valid tool calls after %d attempts: %s".formatted(maxRetries, e.getMessage());
                    logger.error(msg);
                    return new StreamingResult(result.chatResponse, result.outputTokenCount, false, new RuntimeException(msg));
                }

                // Add the invalid response to the messages
                String invalidResponse = result.chatResponse.aiMessage().text();
                logger.debug("Invalid JSON response on attempt {}: {}", attempt, e);
                io.systemOutput("Retry " + attempt + "/" + maxRetries + ": Invalid JSON response, requesting proper format.");

                // Add the invalid response as an assistant message
                currentMessages.add(new AiMessage(invalidResponse));

                // Add a clearer instruction for the next attempt
                String additionalInstruction = "Include all the tool calls necessary to satisfy the request in a single array!";
                if (result.outputTokenCount > 0) {
                    int maxTokens = Models.getMaxOutputTokens(Models.nameOf(model));
                    if (maxTokens > 0 && result.outputTokenCount >= maxTokens) {
                        logger.debug("Max token limit hit: output={} >= max={}", result.outputTokenCount, maxTokens);
                        additionalInstruction = "\n\nIMPORTANT: Your previous response reached the token limit.  Try solving a smaller piece of the problem this time.";
                    }
                }

                String retryMessage = """
                Your previous response was not valid: %s
                You MUST respond ONLY with a valid JSON object containing a 'tool_calls' array. Do not include any other text or explanation.
                %s
                REMEMBER that you are to provide a JSON object containing a 'tool_calls' array, NOT top-level array.
                Here is the format, where $foo indicates that you will make appropriate substitutions for the given tool call
                {
                  "tool_calls": [
                    {
                      "name": "$tool_name",
                      "arguments": {
                        "$arg1": "$value1",
                        "$arg2": "$value2",
                        ...
                      }
                    }
                  ]
                }
                """.formatted(e.getMessage(), additionalInstruction).stripIndent();

                currentMessages.add(new UserMessage(retryMessage));
                writeToHistory("Retry Attempt " + attempt, "Invalid JSON: " + invalidResponse + "\n\nRetry with: " + retryMessage);
            }
        }

        // If we reach here, all retries failed
        logger.error("All {} attempts to get valid JSON tool calls failed", maxRetries);
        var msg = new AiMessage("Failed to generate valid tool calls after " + maxRetries + " attempts");
        return new StreamingResult(ChatResponse.builder().aiMessage(msg).build(),
                                   false,
                                   new IllegalArgumentException("Failed to generate valid tool calls after " + maxRetries + " attempts")
        );
    }

    private static boolean emulatedToolInstructionsPresent(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> {
            var t = Models.getText(m);
            return t.contains("tool_calls")
                    && t.matches("(?s).*\\d+ available tools:.*")
                    && t.contains("Response format:");
        });
    }

    private static StreamingResult parseJsonToTools(StreamingResult result, ObjectMapper mapper) {
        String jsonResponse = result.chatResponse.aiMessage().text();
        logger.debug("Raw JSON response from model: {}", jsonResponse);
        JsonNode root;
        try {
            root = mapper.readTree(jsonResponse);
        } catch (JsonProcessingException e) {
            logger.debug("Invalid JSON", e);
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }

        JsonNode toolCalls;
        // Check for tool_calls array format
        if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
            // happy path, this is what we asked for
            toolCalls = root.get("tool_calls");
        } else if (root.isArray()) {
            // gemini 2.5 REALLY likes to give a top-level array instead
            toolCalls = root;
        } else {
            throw new IllegalArgumentException("Response does not contain a 'tool_calls' array");
        }

        // Transform json into list of tool execution requests
        var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonNode toolCall = toolCalls.get(i);
            if (!toolCall.has("name") || !toolCall.has("arguments")) {
                throw new IllegalArgumentException("Tool call object is missing 'name' or 'arguments' field");
            }

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
                    // If parsing fails, use as-is or log warning
                    logger.warn("Argument is not valid JSON object for tool '{}', treating as string: {}", toolName, arguments.asText(), e);
                    argumentsJson = arguments.toString(); // Keep original if parsing fails
                }
            }

            var toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(String.valueOf(i))
                    .name(toolName)
                    .arguments(argumentsJson)
                    .build();

            toolExecutionRequests.add(toolExecutionRequest);
        }

        assert !toolExecutionRequests.isEmpty();
        logger.debug("Generated tool execution requests: {}", toolExecutionRequests);

        // Create a properly formatted AiMessage with tool execution requests
        var aiMessage = new AiMessage("[json]", toolExecutionRequests);
        var cr = ChatResponse.builder().aiMessage(aiMessage).build();
        return new StreamingResult(cr, result.originalResponse, result.outputTokenCount, false, null);
    }

    private static String getInstructions(List<ToolSpecification> tools) {
        String toolsDescription = tools.stream()
                .map(tool -> {
                    var parametersInfo = tool.parameters().properties().entrySet().stream()
                            .map(entry -> {
                                // tool parameters only have a string `description` property
                                var p = (JsonStringSchema) entry.getValue();
                                return """
                                <parameter name="%s" type="%s" required="%s">
                                %s
                                </parameter>
                                """.formatted(
                                        entry.getKey(),
                                        "String",
                                        tool.parameters().required().contains(entry.getKey()),
                                        p.description());
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                           <tool name="%s">
                           %s
                           %s
                           </tool>
                           """.formatted(tool.name(), tool.description(), parametersInfo.isEmpty() ? "(No parameters)" : parametersInfo);
                })
                .collect(Collectors.joining("\n")); // Use collect instead of reduce for safer empty handling

        return """
        You MUST respond ONLY with a valid JSON object containing a 'tool_calls' array. Your first call should be to `explain`.
        Include all the tool calls necessary to satisfy the request in a single array!
        REMEMBER that you are to provide a JSON object containing a 'tool_calls' array, NOT a top-level array.

        %d available tools:
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
        """.formatted(tools.size(), toolsDescription);
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
     * Combines tool request validations and their execution results into a summary string.
     * Each line is in the format: "description: result text"
     *
     * @param validatedRequests the list of validated tool requests
     * @param resultMessages the corresponding list of tool execution result messages
     * @return a combined summary string
     */
    public String emulateToolResults(List<LLMTools.ValidatedToolRequest> validatedRequests, List<dev.langchain4j.data.message.ToolExecutionResultMessage> resultMessages) {
        var sb = new StringBuilder();
        for (int i = 0; i < validatedRequests.size() && i < resultMessages.size(); i++) {
            var vr = validatedRequests.get(i);
            var term = resultMessages.get(i);
            sb.append(vr.description())
                    .append(": ")
                    .append(term.text())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Represents the outcome of a streaming request.
     */
    public record StreamingResult(ChatResponse chatResponse, ChatResponse originalResponse, int outputTokenCount, boolean cancelled, Throwable error) {
        public StreamingResult(ChatResponse chatResponse, boolean cancelled, Throwable error) {
            this(chatResponse, -1, cancelled, error);
        }

        public StreamingResult(ChatResponse chatResponse, int outputTokenCount, boolean cancelled, Throwable error) {
            this(chatResponse, chatResponse, outputTokenCount, cancelled, error);
        }

        public StreamingResult {
            assert cancelled || error != null || chatResponse != null;
            assert (originalResponse == null) == (chatResponse == null);
        }
    }
}
