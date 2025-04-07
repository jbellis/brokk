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
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The main orchestrator for sending requests to an LLM, possibly with tools, collecting
 * streaming responses, etc.
 */
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
     * Sends a user query to the LLM with streaming. Tools are not used.
     * Writes to conversation history. Optionally echoes partial tokens to the console.
     *
     * @param model The LLM model to use
     * @param messages The messages to send
     * @param echo Whether to echo LLM responses to the console as they stream
     * @return The final response from the LLM as a record containing ChatResponse, errors, etc.
     */
    public StreamingResult sendStreaming(StreamingChatLanguageModel model, List<ChatMessage> messages, boolean echo) {
        return sendMessageWithRetry(model, messages, List.of(), ToolChoice.AUTO, echo, MAX_ATTEMPTS);
    }

    /**
     * Transcribes an audio file using an STT model. Returns text or empty if error.
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

    /**
     * Actually performs one streaming call to the LLM, returning once the response
     * is done or there's an error. If 'echo' is true, partial tokens go to console.
     */
    private StreamingResult doSingleStreamingCall(StreamingChatLanguageModel model,
                                                  ChatRequest request,
                                                  boolean echo)
    {
        // latch for awaiting the complete response
        var latch = new CountDownLatch(1);
        var canceled = new AtomicBoolean(false);
        var lock = new ReentrantLock();
        var errorRef = new AtomicReference<Throwable>(null);
        var outputTokenCountRef = new AtomicReference<>(-1);
        var atomicResponse = new AtomicReference<ChatResponse>();

        Consumer<Runnable> ifNotCancelled = (r) -> {
            lock.lock();
            try {
                if (!canceled.get()) {
                    r.run();
                }
            } finally {
                lock.unlock();
            }
        };

        // Write request details to history
        var tools = request.parameters().toolSpecifications();

        writeRequestToHistory(request.messages(), tools);

        if (Thread.currentThread().isInterrupted()) {
            return new StreamingResult(null, true, null);
        }
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        boolean isEditToolCall = tools != null && tools.stream().anyMatch(tool ->
                                "replaceFile".equals(tool.name()) || "replaceLines".equals(tool.name()));
                        boolean isEmulatedEditToolCall = LLMTools.requiresEmulatedTools(model) && emulatedEditToolInstructionsPresent(request.messages());
                        if (isEditToolCall || isEmulatedEditToolCall) {
                            io.showOutputSpinner("Editing files ...");
                            if (!isEmulatedEditToolCall) {
                                io.llmOutput(token);
                            }
                        } else {
                            io.llmOutput(token);
                            io.hideOutputSpinner();
                        }
                    }
                });
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                ifNotCancelled.accept(() -> {
                    io.hideOutputSpinner();
                    if (echo) {
                        io.llmOutput("\n");
                    }
                    atomicResponse.set(response);
                    if (response == null) {
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        errorRef.set(new HttpException(400, "BadRequestError"));
                    } else {
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
                    io.hideOutputSpinner();
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

        var streamingError = errorRef.get();
        var outputTokenCount = outputTokenCountRef.get();
        if (streamingError != null) {
            // Return an error result
            return new StreamingResult(null, outputTokenCount, false, streamingError);
        }

        var cr = atomicResponse.get();
        if (cr == null) {
            // also an error
            return new StreamingResult(null, outputTokenCount, false, new IllegalStateException("No ChatResponse from model"));
        }
        return new StreamingResult(cr, outputTokenCount, false, null);
    }

    /**
     * Convenience method to send messages to the "quick" model without tools or streaming echo.
     */
    public String sendMessage(List<ChatMessage> messages) {
        var result = sendMessage(Models.quickModel(), messages, List.of(), ToolChoice.AUTO, false);
        if (result.cancelled() || result.error() != null || result.chatResponse() == null) {
            throw new IllegalStateException("LLM returned null or error: " + result.error());
        }
        return result.chatResponse().aiMessage().text().trim();
    }

    /**
     * Sends messages to a given model, no tools, no streaming echo.
     */
    public StreamingResult sendMessage(StreamingChatLanguageModel model, List<ChatMessage> messages) {
        return sendMessage(model, messages, List.of(), ToolChoice.AUTO, false);
    }

    /**
     * Sends messages to a model with possible tools and a chosen tool usage policy.
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
        // Also needed for our emulation if it returns a response without a tool call
        while (!result.cancelled
                && result.error == null
                && !tools.isEmpty()
                && !cr.aiMessage().hasToolExecutionRequests()
                && toolChoice == ToolChoice.REQUIRED)
        {
            logger.debug("Enforcing tool selection");
            io.systemOutput("Enforcing tool selection");

            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(cr.aiMessage());
            extraMessages.add(new UserMessage("At least one tool execution request is REQUIRED. Please call a tool."));

            result = sendMessageWithRetry(model, extraMessages, tools, toolChoice, echo, MAX_ATTEMPTS);
            cr = result.chatResponse();
        }

        return result;
    }

    /**
     * Retries a request up to maxAttempts times on connectivity or empty-result errors,
     * using exponential backoff. Responsible for writeToHistory.
     */
    private StreamingResult sendMessageWithRetry(StreamingChatLanguageModel model,
                                                 List<ChatMessage> messages,
                                                 List<ToolSpecification> tools,
                                                 ToolChoice toolChoice,
                                                 boolean echo,
                                                 int maxAttempts)
    {
        Throwable lastError = null;
        int attempt = 0;

        while (attempt++ < maxAttempts) {
            logger.debug("Sending request to {} attempt {} [only last message shown]: {}",
                         Models.nameOf(model), attempt, messages.getLast());

            if (echo) {
                io.showOutputSpinner("Thinking...");    
            }
            
            var response = doSingleSendMessage(model, messages, tools, toolChoice, echo);
            if (response.cancelled) {
                writeToHistory("Cancelled", "LLM request cancelled by user");
                return response;
            }
            if (response.error == null) {
                // Check if we got a non-empty response
                var cr = response.chatResponse;
                boolean isEmpty = (cr.aiMessage().text() == null || cr.aiMessage().text().isBlank())
                        && !cr.aiMessage().hasToolExecutionRequests();
                if (!isEmpty) {
                    // success!
                    writeToHistory("Response", cr.aiMessage().text());
                    return response;
                }
            }
            // some error or empty response
            lastError = response.error;
            if (lastError != null && lastError.getMessage().contains("BadRequestError")) {
                // don't retry on bad request errors
                break;
            }

            logger.debug("LLM error / empty message. Will retry. Attempt={}", attempt);
            if (attempt == maxAttempts) {
                break; // done
            }
            // wait between attempts
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16L);

            // Busywait with countdown
            io.systemOutput(String.format("LLM issue on attempt %d/%d (retrying in %d seconds).", attempt, maxAttempts, backoffSeconds));
            try {
                long endTime = System.currentTimeMillis() + backoffSeconds * 1000;
                while (System.currentTimeMillis() < endTime) {
                    long remain = endTime - System.currentTimeMillis();
                    if (remain <= 0) break;
                    io.actionOutput("Retrying in %.1f seconds...".formatted(remain / 1000.0));
                    Thread.sleep(Math.min(remain, 100));
                }
            } catch (InterruptedException e) {
                io.systemOutput("Interrupted!");
                return new StreamingResult(null, true, null);
            }
        }

        // If we get here, we failed all attempts
        if (lastError == null) {
            // LLM returned empty or null
            writeToHistory("Error", "LLM returned empty response after max retries");
            var dummy = ChatResponse.builder().aiMessage(new AiMessage("Empty response after max retries")).build();
            return new StreamingResult(dummy, false, new IllegalStateException("LLM empty or null after max retries"));
        }
        // Return last error
        writeToHistory("Error", lastError.getClass() + ": " + lastError.getMessage());
        var cr = ChatResponse.builder().aiMessage(new AiMessage("Error: " + lastError.getMessage())).build();
        return new StreamingResult(cr, false, lastError);
    }

    /**
     * Sends messages to model in a single attempt. If the model doesn't natively support
     * function calling for these tools, we emulate it using a JSON Schema approach.
     */
    private StreamingResult doSingleSendMessage(StreamingChatLanguageModel model,
                                                List<ChatMessage> messages,
                                                List<ToolSpecification> tools,
                                                ToolChoice toolChoice,
                                                boolean echo)
    {
        writeRequestToHistory(messages, tools);

        if (!tools.isEmpty() && LLMTools.requiresEmulatedTools(model)) {
            return emulateToolsUsingStructuredOutput(model, messages, tools, toolChoice, echo);
        }

        // If no tools, or model can do native function calling, do normal.
        var builder = ChatRequest.builder().messages(messages);
        if (!tools.isEmpty()) {
            logger.debug("Performing native tool calls");
            var params = OpenAiChatRequestParameters.builder()
                    .toolSpecifications(tools)
                    .parallelToolCalls(true)
                    .toolChoice(toolChoice)
                    .build();
            builder.parameters(params);
        }

        var request = builder.build();
        return doSingleStreamingCall(model, request, echo);
    }

    /**
     * Emulates function calling for models that don't natively support it.
     * We have two approaches:
     * 1. Schema-based: For models that support JSON schema in response_format
     * 2. Text-based: For models without schema support, using text instructions
     */
    private StreamingResult emulateToolsUsingStructuredOutput(StreamingChatLanguageModel model,
                                                              List<ChatMessage> messages,
                                                              List<ToolSpecification> tools,
                                                              ToolChoice toolChoice,
                                                              boolean echo)
    {
        if (Models.supportsJsonSchema(model)) {
            return emulateToolsUsingStructuredSchema(model, messages, tools, toolChoice, echo);
        } else {
            return emulateToolsUsingJsonObject(model, messages, tools, toolChoice, echo);
        }
    }

    /**
     * Common helper for emulating function calling tools using JSON output
     */
    private StreamingResult emulateToolsCommon(StreamingChatLanguageModel model,
                                              List<ChatMessage> messages,
                                              List<ToolSpecification> tools,
                                              ToolChoice toolChoice,
                                              boolean echo,
                                              Function<List<ChatMessage>, ChatRequest> requestBuilder,
                                              Function<Throwable, String> retryInstructionsProvider)
    {
        assert !tools.isEmpty();

        // We'll do up to 3 tries
        int maxTries = 3;
        ObjectMapper mapper = new ObjectMapper();
        ChatResponse lastResponse = null;
        Throwable lastError = null;
        int outputTokenCount = -1;
        List<ChatMessage> attemptMessages = new ArrayList<>(messages);

        for (int attempt = 1; attempt <= maxTries; attempt++) {
            var request = requestBuilder.apply(attemptMessages);
            var singleCallResult = doSingleStreamingCall(model, request, echo);

            if (singleCallResult.cancelled) {
                return singleCallResult; // user interrupt
            }

            lastResponse = singleCallResult.chatResponse;
            lastError = singleCallResult.error;
            outputTokenCount = singleCallResult.outputTokenCount;

            // If an error occurred (like connectivity or 400) let's bail early
            if (lastError != null) {
                break;
            }

            // If there's no AI message, we can't parse
            if (lastResponse == null || lastResponse.aiMessage() == null) {
                lastError = new IllegalArgumentException("No valid ChatResponse or AiMessage from model");
                break;
            }

            // Now parse the JSON
            try {
                StreamingResult parseResult = parseJsonToToolRequests(singleCallResult, mapper);
                // If we got tool calls, we are done
                if (parseResult.chatResponse.aiMessage().hasToolExecutionRequests()) {
                    return parseResult;
                }
                // else if no tool calls, but toolChoice= AUTO => it's acceptable
                if (toolChoice == ToolChoice.AUTO) {
                    return singleCallResult;
                }
                // else we wanted at least 1 tool call
                throw new IllegalArgumentException("No 'tool_calls' found in JSON");
            } catch (IllegalArgumentException e) {
                logger.debug("JSON parse failed on attempt {}: {}", attempt, e.getMessage());
                if (attempt == maxTries) {
                    // last try
                    lastError = new RuntimeException("Failed to produce valid tool_calls after " + maxTries + " attempts", e);
                    break;
                }

                // Otherwise, add a hint and re-try
                io.systemOutput("Retry " + attempt + "/" + (maxTries-1) + ": Invalid JSON response, requesting proper format.");
                attemptMessages.add(new AiMessage(lastResponse.aiMessage().text()));
                String instructions = retryInstructionsProvider.apply(e);
                attemptMessages.add(new UserMessage(instructions));

                // Record retry in history
                writeToHistory("Retry Attempt " + attempt,
                               "Invalid JSON: " + lastResponse.aiMessage().text() + "\n\nRetry with: " + instructions);
            }
        }

        // If we get here, we have an error or invalid final response
        if (lastResponse == null) {
            // No final response at all
            var failMsg = "No valid response after " + maxTries + " attempts: " + (lastError != null ? lastError.getMessage() : "Unknown");
            logger.error(failMsg);
            var dummyResponse = ChatResponse.builder().aiMessage(new AiMessage(failMsg)).build();
            return new StreamingResult(dummyResponse, outputTokenCount, false, new RuntimeException(failMsg));
        }
        // Otherwise we have some final ChatResponse with an error
        var fail = ChatResponse.builder()
                .aiMessage(new AiMessage("Error: " + lastError.getMessage()))
                .build();
        logger.error("Emulated function calling failed: {}", lastError.getMessage());
        return new StreamingResult(fail, outputTokenCount, false, lastError);
    }

    /**
     * Emulates function calling for models that support structured output with JSON schema
     */
    private StreamingResult emulateToolsUsingStructuredSchema(StreamingChatLanguageModel model,
                                                              List<ChatMessage> messages,
                                                              List<ToolSpecification> tools,
                                                              ToolChoice toolChoice,
                                                              boolean echo)
    {
        // Build a top-level JSON schema with "tool_calls" as an array of objects
        var toolNames = tools.stream().map(ToolSpecification::name).distinct().toList();
        var schema = buildToolCallsSchema(toolNames);
        var responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
        var requestParams = ChatRequestParameters.builder()
                .responseFormat(responseFormat)
                .build();

        // We'll add a user reminder to produce a JSON that matches the schema
        var instructions = getInstructions(tools);
        var modified = new UserMessage(Models.getText(messages.getLast()) + "\n\n" + instructions);
        var initialMessages = new ArrayList<>(messages);
        initialMessages.set(initialMessages.size() - 1, modified);
        logger.debug("Modified messages are {}", initialMessages);

        // Build request creator function
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages ->
            ChatRequest.builder()
                .messages(attemptMessages)
                .parameters(requestParams)
                .build();

        // Function to generate retry instructions
        Function<Throwable, String> retryInstructionsProvider = e -> """
            Your previous response was invalid or did not contain tool_calls: %s
            Please ensure you only return a JSON object matching the schema:
              {
                "tool_calls": [
                  {
                    "name": "...",
                    "arguments": { ... }
                  }
                ]
              }
            """.formatted(e.getMessage()).stripIndent();

        return emulateToolsCommon(model, initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider);
    }

    /**
     * Emulates function calling for models that don't support schema but can output JSON based on text instructions
     */
    private StreamingResult emulateToolsUsingJsonObject(StreamingChatLanguageModel model,
                                                        List<ChatMessage> messages,
                                                        List<ToolSpecification> tools,
                                                        ToolChoice toolChoice,
                                                        boolean echo)
    {
        var instructionsPresent = emulatedToolInstructionsPresent(messages);
        logger.debug("Tool emulation sending {} messages with {}", messages.size(), instructionsPresent);

        List<ChatMessage> initialMessages = new ArrayList<>(messages);
        if (!instructionsPresent) {
            // Inject instructions for the model re how to format function calls
            var instructions = getInstructions(tools);
            var modified = new UserMessage(Models.getText(messages.getLast()) + "\n\n" + instructions);
            initialMessages.set(initialMessages.size() - 1, modified);
            logger.debug("Modified messages are {}", initialMessages);
        }

        // Build request creator function
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages ->
            ChatRequest.builder()
                .messages(attemptMessages)
                .parameters(ChatRequestParameters.builder()
                             .responseFormat(ResponseFormat.builder()
                                     .type(ResponseFormatType.JSON)
                                     .build())
                             .build())
                .build();

        // Function to generate retry instructions
        Function<Throwable, String> retryInstructionsProvider = e -> """
            Your previous response was not valid: %s
            You MUST respond ONLY with a valid JSON object containing a 'tool_calls' array. Do not include any other text or explanation.
            
            IMPORTANT: Try solving a smaller piece of the problem if you're hitting token limits.
            
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
            """.formatted(e.getMessage()).stripIndent();

        return emulateToolsCommon(model, initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider);
    }

    private static boolean emulatedToolInstructionsPresent(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> {
            var t = Models.getText(m);
            return t.contains("tool_calls")
                    && t.matches("(?s).*\\d+ available tools:.*")
                    && t.contains("top-level JSON");
        });
    }
    
    private static boolean emulatedEditToolInstructionsPresent(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> {
            var t = Models.getText(m);
            return t.contains("tool_calls") && t.contains("replaceFile") && t.contains("replaceLines")
                    && t.matches("(?s).*\\d+ available tools:.*")
                    && t.contains("top-level JSON");
        });
    }

    /**
     * Builds a JSON schema describing exactly:
     * {
     *   "tool_calls": [
     *     {
     *       "name": "oneOfTheTools",
     *       "arguments": { ... arbitrary object ...}
     *     }
     *   ]
     * }
     * We do not attempt fancy anyOf references here (not all providers support them).
     */
    private static JsonSchema buildToolCallsSchema(List<String> toolNames) {
        // name => enum of tool names
        var nameSchema = JsonEnumSchema.builder()
                .enumValues(toolNames)
                .description("Name of the tool to call; must be one of: " + String.join(", ", toolNames))
                .build();

        // arguments => free-form object
        var argumentsSchema = JsonObjectSchema.builder()
                .description("Tool arguments object (specific structure depends on the tool).")
                .build();

        // each item => { name, arguments }
        var itemSchema = JsonObjectSchema.builder()
                .addProperty("name", nameSchema)
                .addProperty("arguments", argumentsSchema)
                .required("name", "arguments")
                .build();

        // array property "tool_calls"
        var toolCallsArray = JsonArraySchema.builder()
                .description("All tool calls to be made in sequence.")
                .items(itemSchema)
                .build();

        // top-level object
        var rootSchema = JsonObjectSchema.builder()
                .addProperty("tool_calls", toolCallsArray)
                .required("tool_calls")
                .description("Top-level object containing a 'tool_calls' array describing calls to be made.")
                .build();

        return JsonSchema.builder()
                .name("ToolCalls")
                .rootElement(rootSchema)
                .build();
    }

    /**
     * Parse the model's JSON response into a ChatResponse that includes ToolExecutionRequests.
     * Expects the top-level to have a "tool_calls" array (or the root might be that array).
     */
    private static StreamingResult parseJsonToToolRequests(StreamingResult result, ObjectMapper mapper) {
        String rawText = result.chatResponse.aiMessage().text();
        logger.debug("parseJsonToToolRequests: rawText={}", rawText);

        JsonNode root;
        try {
            root = mapper.readTree(rawText);
        } catch (JsonProcessingException e) {
            logger.debug("Invalid JSON", e);
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }

        JsonNode toolCallsNode;
        if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
            // happy path, this is what we asked for
            toolCallsNode = root.get("tool_calls");
        } else if (root.isArray()) {
            // some models like to give a top-level array instead
            toolCallsNode = root;
        } else {
            throw new IllegalArgumentException("Response does not contain a 'tool_calls' array");
        }

        // Transform json into list of tool execution requests
        var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();
        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode toolCall = toolCallsNode.get(i);
            if (!toolCall.has("name") || !toolCall.has("arguments")) {
                throw new IllegalArgumentException("Tool call object is missing 'name' or 'arguments' field");
            }

            String toolName = toolCall.get("name").asText();
            JsonNode arguments = toolCall.get("arguments");

            if (!arguments.isObject()) {
                throw new IllegalArgumentException("tool_calls[" + i + "] provided non-object arguments " + arguments);
            }
            String argsStr = arguments.toString();
            var toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(String.valueOf(i))
                    .name(toolName)
                    .arguments(argsStr)
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
                                var schema = entry.getValue();
                                String description;
                                String type;

                                // this seems unnecessarily clunky but the common interface does not offer anything useful
                                switch (schema) {
                                    case JsonStringSchema jsSchema -> {
                                        description = jsSchema.description();
                                        type = "string";
                                    }
                                    case JsonArraySchema jaSchema -> {
                                        description = jaSchema.description();
                                        var itemSchema = jaSchema.items();
                                        String itemType = switch (itemSchema) {
                                            case JsonStringSchema __ -> "string";
                                            case JsonIntegerSchema __ -> "integer";
                                            case JsonNumberSchema __ -> "number";
                                            case JsonBooleanSchema __ -> "boolean";
                                            default -> throw new IllegalArgumentException("Unsupported array item type: " + itemSchema);
                                        };
                                        type = "array of %s".formatted(itemType);
                                    }
                                    case JsonIntegerSchema jiSchema -> {
                                        description = jiSchema.description();
                                        type = "integer";
                                    }
                                    case JsonNumberSchema jnSchema -> {
                                        description = jnSchema.description();
                                        type = "number";
                                    }
                                    case JsonBooleanSchema jbSchema -> {
                                        description = jbSchema.description();
                                        type = "boolean";
                                    }
                                    default -> throw new IllegalArgumentException("Unsupported schema type: " + schema);
                                }
                                assert description != null;

                                return """
                                <parameter name="%s" type="%s" required="%s">
                                %s
                                </parameter>
                                """.formatted(
                                        entry.getKey(),
                                        type,
                                        tool.parameters().required().contains(entry.getKey()),
                                        description
                                );
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                    <tool name="%s">
                    %s
                    %s
                    </tool>
                    """.formatted(tool.name(),
                                  tool.description(),
                                  parametersInfo.isEmpty() ? "(No parameters)" : parametersInfo);
                }).collect(Collectors.joining("\n"));

        // if you change this you probably also need to change emulatedToolInstructionsPresent
        return """
        %d available tools:
        %s

        ONLY return a top-level JSON object with this structure:
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

        Include all the tool calls necessary to satisfy the request in a single array!
        """.formatted(tools.size(), toolsDescription);
    }

    /**
     * Writes messages and (optionally) tool specs to the .brokk/llm.log for debugging.
     */
    private void writeRequestToHistory(List<ChatMessage> messages, List<ToolSpecification> tools) {
        String text = messages.stream()
                .map(m -> m.type() + ": " + Models.getText(m))
                .collect(Collectors.joining("\n"));

        if (tools != null && !tools.isEmpty()) {
            text += "\n\nTools:\n" + tools.stream()
                    .map(t -> " - " + t.name() + ": " + t.description())
                    .collect(Collectors.joining("\n"));
        }

        writeToHistory("Request", text);
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
     */
    public String emulateToolResults(
            List<LLMTools.ValidatedToolRequest> validatedRequests,
            List<dev.langchain4j.data.message.ToolExecutionResultMessage> resultMessages) {
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
     * The result of a streaming call. Usually you only need the final ChatResponse
     * unless cancelled or error is non-null.
     */
    public record StreamingResult(
            ChatResponse chatResponse,
            ChatResponse originalResponse,
            int outputTokenCount,
            boolean cancelled,
            Throwable error) {
        public StreamingResult(ChatResponse chatResponse, boolean cancelled, Throwable error) {
            this(chatResponse, chatResponse, -1, cancelled, error);
        }

        public StreamingResult(ChatResponse chatResponse, int outputTokenCount, boolean cancelled, Throwable error) {
            this(chatResponse, chatResponse, outputTokenCount, cancelled, error);
        }

        public StreamingResult {
            // Must have either a chatResponse or an error/cancel
            assert cancelled || error != null || chatResponse != null;
            assert (originalResponse == null) == (chatResponse == null);
        }
    }
}
