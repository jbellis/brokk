package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import okhttp3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.Math.min;

/**
 * Manages dynamically loaded models via LiteLLM.
 */
public final class Models {
    public static final String TOP_UP_URL = "https://brokk.ai/dashboard";
    public static float MINIMUM_PAID_BALANCE = 0.20f;
    public static float LOW_BALANCE_WARN_AT = 2.00f;
    /**
     * Represents the parsed Brokk API key components.
     */
    public record KeyParts(java.util.UUID userId, String token) {}

    /**
     * Parses a Brokk API key of the form 'brk+<userId>+<proToken>+<freeToken>'.
     * The userId must be a valid UUID. The `sk-` prefix is added implicitly to tokens.
     *
     * @param key the raw key string
     * @return KeyParts containing userId, proToken, and freeToken
     * @throws IllegalArgumentException if the key is invalid
     */
    public static KeyParts parseKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        var parts = key.split("\\+");
        if (parts.length != 3 || !"brk".equals(parts[0])) {
            throw new IllegalArgumentException("Key must have format 'brk+<userId>+<token>'");
        }

        java.util.UUID userId;
        try {
            userId = java.util.UUID.fromString(parts[1]);
        } catch (Exception e) {
            throw new IllegalArgumentException("User ID (part 2) must be a valid UUID", e);
        }

        // Tokens no longer have sk- prefix in the raw key, prepend it here
        return new KeyParts(userId, "sk-" + parts[2]);
    }

    private final Logger logger = LogManager.getLogger(Models.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // Share OkHttpClient across instances for efficiency
    // Model name constants
    public static final String O3 = "o3";
    public static final String GEMINI_2_5_PRO_PREVIEW = "gemini-2.5-pro-preview";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    public static final String UNAVAILABLE = "AI is unavailable";

    // these models are defined for low-ltency use cases that don't require high intelligence,
    // they are not suitable for writing code
    private static final Set<String> SYSTEM_ONLY_MODELS = Set.of("gemini-2.0-flash-lite", "gpt-4.1-nano");

    // Cached model storage
    private final ConcurrentHashMap<String, StreamingChatLanguageModel> loadedModels = new ConcurrentHashMap<>();
    // display name -> location
    private final ConcurrentHashMap<String, String> modelLocations = new ConcurrentHashMap<>();
    // location -> model info
    private final ConcurrentHashMap<String, Map<String, Object>> modelInfoMap = new ConcurrentHashMap<>();

    // Default models - now instance fields
    private StreamingChatLanguageModel quickModel;
    private volatile StreamingChatLanguageModel quickestModel = null;
    private volatile SpeechToTextModel sttModel = null;
    private volatile boolean isFreeTierOnly = false; // Store balance status

    // Constructor - could potentially take project-specific config later
    public Models() {
    }

    /**
     * Returns the display name for a given model instance
     */
    public String nameOf(StreamingChatLanguageModel model) {
        // langchain4j "name" corresponds to our "location"
        var location = model.defaultRequestParameters().modelName();
        // Find the key (display name) in the modelLocations map corresponding to the location value
        return modelLocations.entrySet().stream()
                .filter(entry -> entry.getValue().equals(location))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model location not found in known models: " + location));
    }

    /**
     * Initializes models by fetching available models from LiteLLM.
     * Call this after constructing the Models instance.
     *
     * @param project The project whose settings (like data retention policy) should be used.
     */
    public void reinit(IProject project) {
        // Get and handle data retention policy
        var policy = project.getDataRetentionPolicy();
        if (policy == Project.DataRetentionPolicy.UNSET) {
            // Handle unset policy: default to MINIMAL for now. Consider prompting later.
            logger.warn("Data Retention Policy is UNSET for project {}. Defaulting to MINIMAL.", project.getRoot().getFileName());
            policy = Project.DataRetentionPolicy.MINIMAL;
        }

        String proxyUrl = Project.getLlmProxy(); // Get full URL (including scheme) from project setting
        logger.info("Initializing models using policy: {} and proxy: {}", policy, proxyUrl);
        // isLowBalance is now an instance field, set within fetchAvailableModels
        try {
            fetchAvailableModels(policy);
        } catch (IOException e) {
            logger.error("Failed to connect to LiteLLM at {} or parse response: {}",
                         proxyUrl, e.getMessage(), e); // Log the exception details
            modelLocations.clear();
            modelInfoMap.clear();
        }

        if (modelLocations.isEmpty()) {
            // No models? LiteLLM must be down. Add a placeholder.
            logger.warn("No chat models available, cannot set defaults or override.");
            modelLocations.put(UNAVAILABLE, "not_a_model");
            loadedModels.put(UNAVAILABLE, new UnavailableStreamingModel());
        } else {
            // Check configured models against available ones and temporarily override if needed
            // Choose the first available chat model as the default fallback
            var availableNames = getAvailableModels().keySet();
            var defaultModelName = availableNames.stream().findFirst().orElse(UNAVAILABLE);
             var warnings = project.overrideMissingModels(availableNames, defaultModelName);
             warnings.forEach(logger::debug);
        }

        // these should always be available
        quickModel = get("gemini-2.0-flash", Project.ReasoningLevel.DEFAULT);
        if (quickModel == null) {
            quickModel = new UnavailableStreamingModel();
        }
        // hardcode quickest temperature to 0 so that Quick Context inference is reproducible
        quickestModel = get("gemini-2.0-flash-lite", Project.ReasoningLevel.DEFAULT, 0.0);
        if (quickestModel == null) {
            quickestModel = new UnavailableStreamingModel();
        }

        // STT model initialization
        var sttLocation = modelInfoMap.entrySet().stream()
                .filter(entry -> "audio_transcription".equals(entry.getValue().get("mode")))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (sttLocation == null) {
            logger.warn("No suitable transcription model found via LiteLLM proxy. STT will be unavailable.");
            sttModel = new UnavailableSTT();
        } else {
            logger.info("Found transcription model at {}", sttLocation);
            sttModel = new OpenAIStt(sttLocation);
        }
    }

    public float getUserBalance() throws IOException {
        var kp = parseKey(Project.getBrokkKey());
        String url = "https://app.brokk.ai/api/payments/balance-lookup/" + kp.userId();
        logger.trace(url);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + kp.token())
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Failed to fetch user balance: "
                                              + response.code() + " - " + errorBody);
            }
            String responseBody = response.body() != null ? response.body().string() : "";
            JsonNode rootNode = objectMapper.readTree(responseBody);
            if (rootNode.has("available_balance") && rootNode.get("available_balance").isNumber()) {
                return rootNode.get("available_balance").floatValue();
            } else if (rootNode.isNumber()) {
                return rootNode.floatValue();
            } else {
                throw new IOException("Unexpected balance response format: " + responseBody);
            }
        }
    }

    /**
     * Fetches available models from the LLM proxy, applies filters, and sets the low balance status.
     * @param policy The data retention policy.
     * @throws IOException If network or parsing errors occur.
     */
    private void fetchAvailableModels(Project.DataRetentionPolicy policy) throws IOException {
        String baseUrl = Project.getLlmProxy(); // Get full URL (including scheme) from project settings
        boolean isBrokk = Project.getLlmProxySetting() == Project.LlmProxySetting.BROKK;
        this.isFreeTierOnly = false; // Reset/default to false before checking

        // Pick correct Authorization header for model/info
        var authHeader = "Bearer dummy-key";
        if (isBrokk) {
            var kp = parseKey(Project.getBrokkKey());
            // Use proToken to check available models and balance
            authHeader = "Bearer " + kp.token();
        }
        Request request = new Request.Builder()
                .url(baseUrl + "/model/info")
                .header("Authorization", authHeader)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Failed to fetch model info: " + response.code() + " " +
                                              response.message() + " - " + errorBody);
            }

            ResponseBody responseBodyObj = response.body();
            if (responseBodyObj == null) {
                throw new IOException("Received empty response body");
            }

            String responseBody = responseBodyObj.string();
            logger.debug("Models info: {}", responseBody);
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode dataNode = rootNode.path("data");

            if (!dataNode.isArray()) {
                logger.error("/model/info did not return a data array. No models discovered.");
                return;
            }

            modelLocations.clear();
            modelInfoMap.clear();
            float balance = 0f;
            // Only check balance if using Brokk proxy
            if (isBrokk) {
                try {
                    balance = getUserBalance();
                    logger.info("User balance: {}", balance);
                } catch (IOException e) {
                    logger.error("Failed to retrieve user balance: {}", e.getMessage());
                    // Decide how to handle failure - perhaps treat as low balance?
                    // For now, log and continue, assuming not low balance.
                    // For now, log and continue, assuming not low balance.
                }
                this.isFreeTierOnly = balance < MINIMUM_PAID_BALANCE; // Set instance field
            }

            for (JsonNode modelInfoNode : dataNode) {
                String modelName = modelInfoNode.path("model_name").asText();
                String modelLocation = modelInfoNode
                        .path("litellm_params")
                        .path("model")
                        .asText();

                // Process max_output_tokens from model_info
                JsonNode modelInfoData = modelInfoNode.path("model_info");

                // Store model location and max tokens
                if (!modelName.isBlank() && !modelLocation.isBlank()) {
                    // Process and store all model_info fields
                    Map<String, Object> modelInfo = new HashMap<>();
                    if (modelInfoData.isObject()) {
                        Iterator<Map.Entry<String, JsonNode>> fields = modelInfoData.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            String key = field.getKey();
                            JsonNode value = field.getValue();

                            // Convert JsonNode to appropriate Java type
                            if (value.isNull()) {
                                modelInfo.put(key, null);
                            } else if (value.isBoolean()) {
                                modelInfo.put(key, value.asBoolean());
                            } else if (value.isInt()) {
                                modelInfo.put(key, value.asInt());
                            } else if (value.isLong()) {
                                modelInfo.put(key, value.asLong());
                            } else if (value.isDouble()) {
                                modelInfo.put(key, value.asDouble());
                            } else if (value.isTextual()) {
                                modelInfo.put(key, value.asText());
                            } else if (value.isArray() || value.isObject()) {
                                // Convert complex objects to String representation
                                modelInfo.put(key, value.toString());
                            }
                        }
                    }
                    // Add model location to the info map
                    modelInfo.put("model_location", modelLocation);

                    // Apply data retention policy filter
                    if (policy == Project.DataRetentionPolicy.MINIMAL) {
                        boolean isPrivate = (Boolean) modelInfo.getOrDefault("is_private", false);
                        if (!isPrivate) {
                            logger.debug("Skipping non-private model {} due to MINIMAL data retention policy", modelName);
                            continue; // Skip adding this model
                        }
                    }

                    // Store the complete model info, filtering if low balance
                    if (isFreeTierOnly) {
                        var freeEligible = (Boolean) modelInfo.getOrDefault("free_tier_eligible", false);
                        if (!freeEligible) {
                            logger.debug("Skipping model {} - not eligible for free tier (low balance)", modelName);
                            continue;
                        }
                    }

                    // Always store the full model info
                    modelInfoMap.put(modelLocation, modelInfo);
                    logger.debug("Discovered model: {} -> {} with info {})",
                                 modelName, modelLocation, modelInfo);

                    // Only add chat models to the available locations for selection
                    if ("chat".equals(modelInfo.get("mode"))) {
                         modelLocations.put(modelName, modelLocation);
                         logger.debug("Added chat model {} to available locations.", modelName);
                    } else {
                        logger.debug("Skipping model {} (mode: {}) from available locations.", modelName, modelInfo.get("mode"));
                    }
                }
            }

            logger.info("Discovered {} models", modelLocations.size());
        }
    }

    /**
     * Gets a map of available model *names* to their full location strings, suitable for display in settings.
     * Filters out internal/utility models like flash-lite.
     * e.g. "deepseek-v3" -> "deepseek/deepseek-chat"
     */
    public Map<String, String> getAvailableModels() {
        return modelLocations.entrySet().stream()
                .filter(e -> !SYSTEM_ONLY_MODELS.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Retrieves the maximum output tokens for the given model name.
     * Returns a default value if the information is not available.
     */
    private int getMaxOutputTokens(String location) {
        var info = modelInfoMap.get(location);
        if (info == null || !info.containsKey("max_output_tokens")) {
            logger.warn("max_output_tokens not found for model location: {}", location);
            return 8192;
        }
        var value = info.get("max_output_tokens");
        assert value instanceof Integer;
        return (Integer) value;
    }

    /**
     * Retrieves the maximum input tokens for the given model name.
     * Returns a default value if the information is not available.
     */
    public int getMaxInputTokens(StreamingChatLanguageModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = modelInfoMap.get(location);
        if (info == null || !info.containsKey("max_input_tokens")) {
            logger.warn("max_input_tokens not found for model location: {}", location);
            return 65536;
        }
        var value = info.get("max_input_tokens");
        assert value instanceof Integer;
        return (Integer) value;
    }

    /**
     * Checks if the model supports reasoning effort based on its metadata.
     *
     * @param modelName The display name of the model (e.g., "gemini-2.5-pro-exp-03-25").
     * @return True if the model info contains `"supports_reasoning": true`, false otherwise.
     */
    public boolean supportsReasoning(String modelName) {
        var location = modelLocations.get(modelName);
        if (location == null) {
            logger.warn("Location not found for model name {}, assuming no reasoning support.", modelName);
            return false;
        }
        var info = modelInfoMap.get(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no reasoning support.", location);
            return false; // Assume not supported if info is missing
        }
        var supports = info.get("supports_reasoning");
        // supports_reasoning might not be present, treat null as false
        return supports instanceof Boolean && (Boolean) supports;
    }


    /**
     * Retrieves or creates a StreamingChatLanguageModel for the given modelName and reasoning level.
     *
     * @param modelName      The display name of the model (e.g., "gemini-2.5-pro-exp-03-25").
     */
    public StreamingChatLanguageModel get(String modelName, Project.ReasoningLevel reasoningLevel, Double temperature) {
        // Use a composite key for the cache to include reasoning level if not default
        String cacheKey = modelName + (reasoningLevel == Project.ReasoningLevel.DEFAULT ? "" : ":" + reasoningLevel.name());

        return loadedModels.computeIfAbsent(cacheKey, key -> {
            String location = modelLocations.get(modelName);
            logger.debug("Creating new model instance for '{}' at location '{}' with reasoning '{}' via LiteLLM",
                         modelName, location, reasoningLevel);
            if (location == null) {
                logger.error("Location not found for model name: {}", modelName);
                return null;
            }

            // OpenAI says, "Your rate limit is calculated as the maximum of max_tokens
            // and the estimated number of tokens based on the character count of your request.
            // https://platform.openai.com/docs/guides/rate-limits
            // We don't have a good way to predict output size, but almost all of them are lower than 32k,
            // and CodeAgent can pick up an request that stopped early from the last edit block
            var maxTokens = min(32768, getMaxOutputTokens(location));

            // We connect to LiteLLM using an OpenAiStreamingChatModel, specifying baseUrl
            // placeholder, LiteLLM manages actual keys
            String baseUrl = Project.getLlmProxy();
            var builder = OpenAiStreamingChatModel.builder()
                    .logRequests(true)
                    .logResponses(true)
                    .strictJsonSchema(true)
                    .maxTokens(maxTokens)
                    .baseUrl(baseUrl)
                    .timeout(Duration.ofMinutes(3)); // default 60s is not enough

            if (Project.getLlmProxySetting() == Project.LlmProxySetting.BROKK) {
                var kp = parseKey(Project.getBrokkKey());
                // Select token based on balance status
                builder = builder
                        .apiKey(kp.token)
                        .customHeaders(Map.of("Authorization", "Bearer " + kp.token))
                        .user(kp.userId().toString());
            } else {
                // Non-Brokk proxy
                builder = builder.apiKey("dummy-key");
            }

            builder = builder.modelName(location);

            // default request parameters
            var params = OpenAiChatRequestParameters.builder()
                    .temperature(temperature);
            // Apply reasoning effort if not default and supported
            logger.trace("Applying reasoning effort {} to model {}", reasoningLevel, modelName);
            if (supportsReasoning(modelName) && reasoningLevel != Project.ReasoningLevel.DEFAULT) {
                params = params.reasoningEffort(reasoningLevel.name().toLowerCase());
            }
            builder.defaultRequestParameters(params.build());

            if (modelName.contains("sonnet")) {
                // "Claude 3.7 Sonnet may be less likely to make make parallel tool calls in a response,
                // even when you have not set disable_parallel_tool_use. To work around this, we recommend
                // enabling token-efficient tool use, which helps encourage Claude to use parallel tools."
                builder = builder.customHeaders(Map.of("anthropic-beta", "token-efficient-tools-2025-02-19,output-128k-2025-02-19"));
            }

            return builder.build();
        });
    }

    public StreamingChatLanguageModel get(String modelName, Project.ReasoningLevel reasoningLevel) {
        return get(modelName, reasoningLevel, null);
    }

    public boolean supportsJsonSchema(StreamingChatLanguageModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = modelInfoMap.get(location);

        if (location.contains("gemini")) {
            // buildToolCallsSchema can't build a valid properties map for `arguments` without `oneOf` schema support.
            // o3mini is fine with this but gemini models are not.
            return false;
        }
        // hack for o3-mini not being able to combine json schema with argument descriptions in the text body
        if (location.contains("o3-mini")) {
            return false;
        }

        // default: believe the litellm metadata
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no JSON schema support.", location);
            return false;
        }
        var b = info.get("supports_response_schema");
        return b instanceof Boolean && (Boolean) b;
    }

    public boolean isLazy(StreamingChatLanguageModel model) {
        String modelName = nameOf(model);
        return !(modelName.contains("3-7-sonnet") || modelName.contains("gemini-2.5-pro"));
    }

    public boolean requiresEmulatedTools(StreamingChatLanguageModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = modelInfoMap.get(location);

        // first check litellm metadata
        if (info == null) {
             logger.warn("Model info not found for location {}, assuming tool emulation required.", location);
             return true;
        }
        var b = info.get("supports_function_calling");
        if (!(b instanceof Boolean) || !(Boolean) b) {
            // if it doesn't support function calling then we need to emulate
            return true;
        }

        // gemini and grok-3 support function calling but not parallel calls
        return location.contains("gemini")
                || location.contains("grok-3");
    }

    public boolean supportsParallelCalls(StreamingChatLanguageModel model) {
        // mostly we force models that don't support parallel calls to use our emulation, but o3 does so poorly with that
        // that serial calls is the lesser evil
        var location = model.defaultRequestParameters().modelName();
        return !location.contains("o3") && !location.contains("o4-mini");
    }

    /**
     * Checks if the model is designated as a "reasoning" model based on its metadata.
     * Reasoning models are expected to perform "think" steps implicitly.
     * This refers to the old `is_reasoning` flag, distinct from the new `supports_reasoning` for effort levels.
     *
     * @param model The model instance to check.
     * @return True if the model info contains `"is_reasoning": true`, false otherwise.
     */
    // TODO clean this up
    public boolean isReasoning(StreamingChatLanguageModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = modelInfoMap.get(location);
        if (info == null) {
            logger.warn("Model info not found for {}, assuming not a reasoning model (old flag).", location);
            return false;
        }
        var isReasoning = info.get("is_reasoning");
        // is_reasoning might not be present, treat null as false
        if (isReasoning == null) {
            return false;
        }
        return (Boolean) isReasoning;
    }

    /**
     * Checks if the model supports vision (image) inputs based on its metadata.
     *
     * @param model The model instance to check.
     * @return True if the model info contains `"supports_vision": true`, false otherwise.
     */
    public boolean supportsVision(StreamingChatLanguageModel model) {
        var location = model.defaultRequestParameters().modelName();
        var info = modelInfoMap.get(location);
        if (info == null) {
            logger.warn("Model info not found for location {}, assuming no vision support.", location);
            return false;
        }

        var supports = info.get("supports_vision");
        return supports instanceof Boolean && (Boolean) supports;
    }

    public StreamingChatLanguageModel quickestModel() {
        assert quickestModel != null;
        return quickestModel;
    }

    public StreamingChatLanguageModel quickModel() {
        assert quickModel != null;
        return quickModel;
    }

    /**
     * Returns the default speech-to-text model instance.
     */
    public SpeechToTextModel sttModel() {
        if (sttModel == null) {
            logger.warn("sttModel accessed before initialization, returning UnavailableSTT.");
            return new UnavailableSTT();
        }
        return sttModel;
    }

    /**
     * Interface for speech-to-text operations.
     * Can remain static as it's just an interface definition.
     */
    public interface SpeechToTextModel {
        /**
         * Transcribes audio, with optional context symbols.
         */
        String transcribe(Path audioFile, Set<String> symbols) throws IOException;
    }

    /**
     * Stubbed STT model for when speech-to-text is unavailable.
     * Can remain static as it has no dependency on Models instance state.
     */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(Path audioFile, Set<String> symbols) {
            return "Speech-to-text is unavailable (no suitable model found via proxy or connection failed).";
        }
    }

    /**
     * Stubbed Streaming model for when LLM is unavailable.
     * Can remain static as it has no dependency on Models instance state.
     */
    public static class UnavailableStreamingModel implements StreamingChatLanguageModel {
        public UnavailableStreamingModel() {
        }

        // Removed @Override annotations that seemed to cause compile errors
        public void generate(String userMessage, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, ToolSpecification toolSpecification, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new dev.langchain4j.model.output.Response<>(new AiMessage(UNAVAILABLE)));
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof UnavailableStreamingModel;
        }
    }

    /**
     * STT implementation using Whisper-compatible API via LiteLLM proxy.
     * Uses OkHttp for multipart/form-data upload.
     */
    public class OpenAIStt implements SpeechToTextModel {
        private final Logger logger = LogManager.getLogger(OpenAIStt.class);
        private final String modelLocation; // e.g., "openai/whisper-1"

        public OpenAIStt(String modelLocation) {
            this.modelLocation = modelLocation;
        }

        /**
         * Determines the MediaType based on file extension.
         * @param fileName Name of the file
         * @return MediaType for the HTTP request
         */
        private MediaType getMediaTypeFromFileName(String fileName) {
            var extension = fileName.toLowerCase();
            int dotIndex = extension.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = extension.substring(dotIndex + 1);
            }

            // Supported formats may depend on the specific model/proxy endpoint
            return switch (extension) {
                case "flac" -> MediaType.parse("audio/flac");
                case "mp3" -> MediaType.parse("audio/mpeg");
                case "mp4", "m4a" -> MediaType.parse("audio/mp4");
                case "mpeg", "mpga" -> MediaType.parse("audio/mpeg");
                case "oga", "ogg" -> MediaType.parse("audio/ogg");
                case "wav" -> MediaType.parse("audio/wav");
                case "webm" -> MediaType.parse("audio/webm");
                default -> {
                    logger.warn("Unsupported audio extension '{}', attempting audio/mpeg", extension);
                    yield MediaType.parse("audio/mpeg"); // Default fallback
                }
            };
        }

        @Override
        public String transcribe(Path audioFile, Set<String> symbols) throws IOException {
            logger.info("Beginning transcription via proxy for file: {}", audioFile);
            var file = audioFile.toFile();

            MediaType mediaType = getMediaTypeFromFileName(file.getName());
            RequestBody fileBody = RequestBody.create(file, mediaType);

            var builder = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(), fileBody)
                    .addFormDataPart("model", modelLocation)
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json");

            RequestBody requestBody = builder.build();

            // Determine endpoint and authentication
            String proxyUrl = Project.getLlmProxy();
            String endpoint = proxyUrl + "/audio/transcriptions";

            var authHeader = "Bearer dummy-key"; // Default for non-Brokk
            if (Project.getLlmProxySetting() == Project.LlmProxySetting.BROKK) {
                var kp = parseKey(Project.getBrokkKey());
                authHeader = "Bearer " + kp.token;
            }

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("Authorization", authHeader)
                    // Content-Type is set automatically by OkHttp for MultipartBody
                    .post(requestBody)
                    .build();

            logger.debug("Sending STT request to {}", endpoint);

            // Use the shared httpClient from the outer Models class
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String bodyStr = response.body() != null ? response.body().string() : "";
                logger.debug("Received STT response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Proxied STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("Proxied STT call failed with status "
                                                  + response.code() + ": " + bodyStr);
                }

                // Parse JSON response
                try {
                    JsonNode node = objectMapper.readTree(bodyStr);
                    if (node.has("text")) {
                        String transcript = node.get("text").asText().trim();
                        logger.info("Transcription successful, text length={} chars", transcript.length());
                        return transcript;
                    } else {
                        logger.warn("No 'text' field found in proxied STT response: {}", bodyStr);
                        return "No transcription found in response.";
                    }
                } catch (Exception e) {
                    logger.error("Error parsing proxied STT JSON response: {}", e.getMessage());
                    throw new IOException("Error parsing proxied STT JSON response: " + e.getMessage(), e);
                }
            }
        }
    }
}
