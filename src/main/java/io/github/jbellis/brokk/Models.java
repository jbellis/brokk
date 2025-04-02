package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages dynamically loaded models via LiteLLM.
 */
public final class Models {
    private static final Logger logger = LogManager.getLogger(Models.class);
    public static String[] defaultKeyNames = {"ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "GEMINI_API_KEY"}; // TODO remove
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    public static final String LITELLM_BASE_URL = "http://localhost:4000"; // Default LiteLLM endpoint

    // name -> model instance
    private static final ConcurrentHashMap<String, StreamingChatLanguageModel> loadedModels = new ConcurrentHashMap<>();

    // name -> location
    private static final ConcurrentHashMap<String, String> modelLocations = new ConcurrentHashMap<>();

    // "quick" default model instance, set in init()
    private static volatile StreamingChatLanguageModel quickModel = null;

    // STT model
    private static volatile SpeechToTextModel sttModel = null;

    // Simple OpenAI tokenizer for approximate counting
    private static final OpenAiTokenizer tokenizer = new OpenAiTokenizer("gpt-4o");

    static final String UNAVAILABLE = "AI is unavailable";

    // Initialize at class load time
    static {
        init();
    }

    /**
     * Returns the display name for a given model instance
     */
    public static String nameOf(StreamingChatLanguageModel model) {
        return loadedModels.entrySet().stream().filter(e -> e.getValue().equals(model)).findFirst().orElseThrow().getKey();
    }

    /**
     * Initializes models by fetching available models from LiteLLM and setting up quickModel and sttModel.
     */
    public static void init() {
        logger.info("Initializing models");
        try {
            Request request = new Request.Builder()
                    .url(LITELLM_BASE_URL + "/model/info")
                    .get()
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    throw new IOException("Failed to fetch model info: " + response.code() + " " + response.message() + " - " + errorBody);
                }
                ResponseBody responseBodyObj = response.body();
                if (responseBodyObj == null) {
                    throw new IOException("Received empty response body");
                }
                String responseBody = responseBodyObj.string();
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode dataNode = rootNode.path("data");

                if (dataNode.isArray()) {
                    modelLocations.clear(); // Clear previous entries

                    // For each element, parse:
                    //   top-level "model_name" => modelName
                    //   "litellm_params"."model" => modelLocation
                    for (JsonNode modelInfo : dataNode) {
                        String modelName = modelInfo.path("model_name").asText();
                        String modelLocation = modelInfo
                                .path("litellm_params")
                                .path("model")
                                .asText();

                        logger.debug("Parsed from info: modelName={}, modelLocation={}", modelName, modelLocation);

                        if (modelName != null && !modelName.isBlank() &&
                                modelLocation != null && !modelLocation.isBlank())
                        {
                            modelLocations.put(modelName, modelLocation);
                            logger.debug("Discovered model: {} -> {}", modelName, modelLocation);
                        }
                    }

                    logger.info("Discovered {} models", modelLocations.size());
                } else {
                    logger.error("/model/info did not return a data array. No models discovered.");
                }
            }
        } catch (IOException e) {
            logger.error("Failed to connect to Brokk at {} or parse response: {}. AI models will be unavailable.",
                         LITELLM_BASE_URL, e.getMessage());
            modelLocations.clear();
        }

        // Attempt to find a "preferred" quick model
        String preferredQuickModelLocation = "gemini/gemini-2.0-flash-lite";

        // If we see "gemini/gemini-2.0-flash-lite" in the discovered values, use that
        if (modelLocations.containsValue(preferredQuickModelLocation)) {
            var matchingEntry = modelLocations.entrySet().stream()
                    .filter(e -> e.getValue().equals(preferredQuickModelLocation))
                    .findFirst()
                    .orElse(null);

            if (matchingEntry == null) {
                logger.warn("No model name found for location {}", preferredQuickModelLocation);
            } else {
                quickModel = get(matchingEntry.getKey());
                logger.info("Initialized quickModel with {} => {}", matchingEntry.getKey(), preferredQuickModelLocation);
            }
        } else {
            logger.warn("Quick model unavailable!? Using first available");
            var firstEntry = modelLocations.entrySet().iterator().next();
            quickModel = get(firstEntry.getKey());
            logger.info("Using first available model: {} => {}", firstEntry.getKey(), firstEntry.getValue());
        }

        sttModel = new GeminiSTT("gemini/gemini-2.0-flash", httpClient);
    }

    /**
     * Gets a map of available model *names* to their full location strings.
     * e.g. "deepseek-v3" -> "deepseek/deepseek-chat"
     */
    public static Map<String, String> getAvailableModels() {
        return Map.copyOf(modelLocations);
    }

    /**
     * Retrieves or creates a StreamingChatLanguageModel for the given modelName.
     * The actual location string is looked up internally.
     */
    public static StreamingChatLanguageModel get(String modelName) {
        return loadedModels.computeIfAbsent(modelName, key -> {
            String location = modelLocations.get(modelName);
            logger.info("Creating new model instance for '{}' at location '{}' via LiteLLM", modelName, location);

            // We connect to LiteLLM using an OpenAiStreamingChatModel, specifying baseUrl
            // placeholder, LiteLLM manages actual keys
            var builder = OpenAiStreamingChatModel.builder()
                    .baseUrl(LITELLM_BASE_URL)
                    .apiKey("litellm") // placeholder, LiteLLM manages actual keys
                    .modelName(location);

            if (modelName.contains("sonnet")) {
                // "Claude 3.7 Sonnet may be less likely to make make parallel tool calls in a response,
                // even when you have not set disable_parallel_tool_use. To work around this, we recommend
                // enabling token-efficient tool use, which helps encourage Claude to use parallel tools."
                builder = builder.customHeaders(Map.of("anthropic-beta", "token-efficient-tools-2025-02-19"));
            }

            return builder.build();
        });
    }

    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> am.text();
            case UserMessage um -> um.singleText();
            // let ToolExecutionResultMessage throw, almost certainly shouldn't be calling getText on it
            default -> throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static int getApproximateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return tokenizer.encode(text).size();
    }

    /**
     * Returns the statically configured quick model instance.
     */
    public static StreamingChatLanguageModel quickModel() {
        assert quickModel != null;
        return quickModel;
    }

    /**
     * Returns the statically configured SpeechToTextModel instance.
     */
    public static SpeechToTextModel sttModel() {
        if (sttModel == null) {
            logger.warn("sttModel accessed before initialization, returning UnavailableSTT.");
            return new UnavailableSTT();
        }
        return sttModel;
    }

    public static boolean isLazy(StreamingChatLanguageModel model) {
        return nameOf(model).contains("3-7-sonnet") || nameOf(model).contains("gemini-2.5-pro");
    }

    /**
     * Simple interface for speech-to-text operations.
     */
    public interface SpeechToTextModel {
        String transcribe(Path audioFile) throws IOException;
    }

    /**
     * Stubbed / unavailable STT model that always returns a message
     * indicating that STT is disabled.
     */
    public static class UnavailableSTT implements SpeechToTextModel {
        @Override
        public String transcribe(Path audioFile) {
            return "Speech-to-text is unavailable (no OpenAI key).";
        }
    }

    /**
     * STT implementation using Gemini via LiteLLM's OpenAI compatible endpoint.
     */
    public static class GeminiSTT implements SpeechToTextModel {
        private static final Logger logger = LogManager.getLogger(GeminiSTT.class);
        private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

        private final String modelLocation; // e.g., "gemini/gemini-2.0-flash-lite"
        private final OkHttpClient httpClient;

        public GeminiSTT(String modelLocation, OkHttpClient httpClient) {
            this.modelLocation = modelLocation;
            // Reuse the shared client configured in Models
            this.httpClient = httpClient;
        }

        private String getAudioFormat(String fileName) {
            var lowerCaseFileName = fileName.toLowerCase();
            int dotIndex = lowerCaseFileName.lastIndexOf('.');
            if (dotIndex > 0 && dotIndex < lowerCaseFileName.length() - 1) {
                return lowerCaseFileName.substring(dotIndex + 1);
            }
            // Default or throw error? LiteLLM example used "wav"
            logger.warn("Could not determine audio format for {}, defaulting to 'wav'", fileName);
            return "wav";
        }

        @Override
        public String transcribe(Path audioFile) throws IOException {
            logger.info("Beginning transcription for file: {} using model {}", audioFile, modelLocation);

            byte[] audioBytes = Files.readAllBytes(audioFile);
            String encodedString = Base64.getEncoder().encodeToString(audioBytes);
            String audioFormat = getAudioFormat(audioFile.getFileName().toString());

            // Construct the JSON body based on LiteLLM's multi-modal input format
            String jsonBody = """
                {
                  "model": "%s",
                  "messages": [
                    {
                      "role": "user",
                      "content": [
                        {"type": "text", "text": "Transcribe this audio."},
                        {"type": "input_audio", "input_audio": {"data": "%s", "format": "%s"}}
                      ]
                    }
                  ],
                  "modalities": ["text", "audio"],
                  "stream": false
                }
                """.formatted(modelLocation, encodedString, audioFormat).stripIndent();

            RequestBody body = RequestBody.create(jsonBody, JSON);
            Request request = new Request.Builder()
                    .url(LITELLM_BASE_URL + "/chat/completions")
                    // LiteLLM requires a dummy API key here for the OpenAI compatible endpoint
                    .header("Authorization", "Bearer dummy-key")
                    .post(body)
                    .build();

            logger.debug("Sending Gemini STT request to LiteLLM endpoint /chat/completions for model {}", modelLocation);

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody responseBodyObj = response.body();
                String bodyStr = responseBodyObj != null ? responseBodyObj.string() : "";
                logger.debug("Received response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("LiteLLM STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("LiteLLM STT call failed with status "
                                                  + response.code() + ": " + bodyStr);
                }

                try {
                    JsonNode rootNode = objectMapper.readTree(bodyStr);
                    // Response structure: { choices: [ { message: { content: "...", role: "..." } } ] }
                    JsonNode contentNode = rootNode.path("choices").path(0).path("message").path("content");

                    if (contentNode.isTextual()) {
                        String transcript = contentNode.asText().trim();
                        logger.info("Transcription successful: {}", transcript);
                        return transcript;
                    } else {
                        logger.error("No text content found in LiteLLM response. Response body: {}", bodyStr);
                        return "No transcription found in response.";
                    }
                } catch (Exception e) {
                    logger.error("Error parsing LiteLLM JSON response: {} Body: {}", e.getMessage(), bodyStr, e);
                    throw new IOException("Error parsing LiteLLM JSON response: " + e.getMessage(), e);
                }
            } catch (IOException e) {
                 logger.error("IOException during LiteLLM STT request: {}", e.getMessage(), e);
                 throw e; // Re-throw IOExceptions
            }
        }
    }
}
