package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.model.output.Response;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Holds references to the models we use in Brokk.
 */
public record Models(StreamingChatLanguageModel editModel,
                     StreamingChatLanguageModel applyModel,
                     StreamingChatLanguageModel quickModel,
                     StreamingChatLanguageModel searchModel,
                     String editModelName,
                     String applyModelName,
                     String quickModelName,
                     String searchModelName,
                     SpeechToTextModel sttModel,
                     String sttModelName)
{
    public static String[] defaultKeyNames = { "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "GEMINI_API_KEY" };

    /**
     * Returns the name of the given model.
     * 
     * @param model The model to get the name for
     * @return The name of the model, or "unknown" if the model is not recognized
     */
    public String nameOf(Object model) {
        if (model == editModel) return editModelName;
        if (model == applyModel) return applyModelName;
        if (model == quickModel) return quickModelName;
        if (model == searchModel) return searchModelName;
        return "unknown";
    }
    // langchain4j only supports openai tokenization, this is not very accurate for other providers
    // but doing loc-based estimation based on information in the responses was worse
    private static final OpenAiTokenizer tokenizer = new OpenAiTokenizer("o3-mini");

    // correct for these models
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private static final String OPENAI_DEFAULTS = """
    edit_model:
      provider: openai
      key: OPENAI_API_KEY
      name: o3-mini
      maxTokens: 100000

    search_model:
      provider: openai
      key: OPENAI_API_KEY
      name: o3-mini
      reasoning_effort: low
      maxTokens: 100000

    apply_model:
      provider: openai
      key: OPENAI_API_KEY
      name: o3-mini
      reasoning_effort: low
      maxTokens: 100000

    quick_model:
      provider: openai
      key: OPENAI_API_KEY
      name: gpt-4o-mini
      maxTokens: 16384
    """;
    
    private static final String GOOGLE_DEFAULTS = """
    edit_model:
      provider: google
      key: GEMINI_API_KEY
      name: gemini-2.5-pro-exp-03-25
      maxTokens: 1000000

    search_model:
      provider: google
      key: GEMINI_API_KEY
      name: gemini-1.5-pro
      maxTokens: 2000000

    apply_model:
      provider: google
      key: GEMINI_API_KEY
      name: gemini-2.0-flash
      maxTokens: 1000000

    quick_model:
      provider: google
      key: GEMINI_API_KEY
      name: gemini-2.0-flash-lite
      maxTokens: 1000000
    """;

    private static final String ANTHROPIC_DEFAULTS = """
    edit_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-7-sonnet-latest
      maxTokens: 8192

    search_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-7-sonnet-latest
      enableCaching: true
      maxTokens: 8192

    apply_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-5-haiku-latest
      maxTokens: 8192

    quick_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-haiku-20240307
      maxTokens: 4096
      
    """;

    private static final String DEEPSEEK_DEFAULTS = """
    edit_model:
      provider: deepseek
      key: DEEPSEEK_API_KEY
      name: deepseek-reasoner
      maxTokens: 8192

    search_model:
      provider: deepseek
      key: DEEPSEEK_API_KEY
      name: deepseek-chat
      maxTokens: 8192

    apply_model:
      provider: deepseek
      key: DEEPSEEK_API_KEY
      name: deepseek-chat
      maxTokens: 8192

    quick_model:
      provider: deepseek
      key: DEEPSEEK_API_KEY
      name: deepseek-chat
      maxTokens: 8192
    """;

    /**
     * Attempts to load model configurations from <code>~/.config/brokk/brokk.yaml</code>.
     * <p>
     * The YAML filename define three models: edit_model, apply_model, and quick_model.
     * If any model definition is missing or
     * the filename cannot be read or parsed, default settings are used.
     * <p>
     * <strong>Example YAML structure:</strong>
     * <pre>
     * edit_model:
     *   provider: deepseek
     *   key: DEEPSEEK_API_KEY
     *   url: https://api.deepseek.com
     *   name: deepseek-chat
     *   temperature: 0.4
     *   maxTokens: 8000
     * </pre>
     * <ul>
     *   <li><strong>provider:</strong> Required if you want to use a known provider
     *       (<code>openai</code>, <code>deepseek</code>, <code>anthropic</code>). If omitted, the code falls back
     *       to interpreting <em>url</em> + <em>key</em> as a custom OpenAI-like endpoint or uses
     *       a built-in OpenAI default.</li>
     *   <li><strong>key:</strong> Indicates the environment variable holding the API key (e.g., <code>DEEPSEEK_API_KEY</code>).
     *       This is looked up via <code>~/.config/brokk/keys.properties</code>, or by <code>System.getenv</code>.
     *       </li>
     *   <li><strong>url:</strong> Optional. If using <code>deepseek</code>, for instance, defaults to
     *       <code>https://api.deepseek.com</code> unless otherwise specified. OpenAI can omit <em>url</em>
     *       to use the default OpenAI endpoint.</li>
     *   <li><strong>name:</strong> The model identifier (e.g., <code>gpt-3.5-turbo</code>, <code>deepseek-chat</code>,
     *       <code>claude-v1.3</code>, etc.).</li>
     *   <li><strong>temperature:</strong> A double value controlling the "creativity" of responses (default: omitted).</li>
     *   <li><strong>maxTokens:</strong> The maximum number of tokens to allow in the response (default 8k).</li>
     * </ul>
     * If the filename is missing or cannot be parsed, the method returns a {@link Models} instance with
     * hardcoded default model settings (currently using Anthropic-based defaults).
     *
     * @return A {@link Models} record containing references to the edit and quick Chat/StreamingChat models,
     *         plus each model's name.
     */
    public static Models load() {
        // First try loading from yaml file
        Path configPath = Path.of(System.getProperty("user.home"), ".config", "brokk", "brokk.yml");
        if (Files.exists(configPath)) {
            try {
                String yamlStr = Files.readString(configPath);
                return buildModelsFromYaml(yamlStr);
            } catch (IOException e) {
                System.out.println("Error reading " + configPath + ": " + e.getMessage());
                // Fall through to try defaults
            }
        }

        // Next try Anthropic if API key exists
        String anthropicKey = getKey("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            return buildModelsFromYaml(ANTHROPIC_DEFAULTS);
        }

        // Next try OpenAI if API key exists
        String openaiKey = getKey("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            return buildModelsFromYaml(OPENAI_DEFAULTS);
        }

        String deepseekKey = getKey("DEEPSEEK_API_KEY");
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            return buildModelsFromYaml(DEEPSEEK_DEFAULTS);
        }
        
        String googleKey = getKey("GEMINI_API_KEY");
        if (googleKey != null && !googleKey.isBlank()) {
            return buildModelsFromYaml(GOOGLE_DEFAULTS);
        }

        // If no API keys are available, return disabled models
        return disabled();
    }

    private static String getKey(String keyName) {
        // First try properties file
        try {
            Path keysPath = Project.getLlmKeysPath();
            if (Files.exists(keysPath)) {
                var properties = new java.util.Properties();
                try (var reader = Files.newBufferedReader(keysPath)) {
                    properties.load(reader);
                }
                String propValue = properties.getProperty(keyName);
                if (propValue != null && !propValue.isBlank()) {
                    return propValue.trim();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading LLM keys: " + e.getMessage());
        }

        // Next try system environment variables
        String envValue = System.getenv(keyName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        // Nothing found
        return null;
    }

    private static Models buildModelsFromYaml(String yamlStr) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> top = yaml.load(yamlStr);

            // Build each model
            var editModel = buildStreamingModel(top, "edit_model");
            var applyModel = buildStreamingModel(top, "apply_model");
            var quickModel = buildStreamingModel(top, "quick_model");
            var searchModel = buildStreamingModel(top, "search_model");

            String editModelName = readModelName(top, "edit_model");
            String applyModelName = readModelName(top, "apply_model");
            String quickModelName = readModelName(top, "quick_model");
            String searchModelName = readModelName(top, "search_model");

            // Check if OpenAI key is available for STT
            String openaiKey = getKey("OPENAI_API_KEY");
            SpeechToTextModel stt;
            String sttModelName;
            if (openaiKey != null && !openaiKey.isBlank()) {
                stt = new OpenAiWhisperSTT(openaiKey);
                sttModelName = "gpt-4o-transcribe";
            } else {
                stt = new UnavailableSTT();
                sttModelName = "disabled";
            }

            return new Models(editModel, applyModel, quickModel, searchModel,
                              editModelName, applyModelName, quickModelName, searchModelName,
                              stt, sttModelName);
        } catch (Exception e) {
            System.out.println("Error parsing yaml: " + e.getMessage());
            System.exit(1);
            throw new RuntimeException(e); // make compiler happy
        }
    }

    public static Models disabled() {
        return new Models(new UnavailableStreamingModel(),
                          new UnavailableStreamingModel(),
                          new UnavailableStreamingModel(),
                          new UnavailableStreamingModel(),
                          "disabled",
                          "disabled",
                          "disabled",
                          "disabled",
                          new UnavailableSTT(),
                          "disabled");
    }

    /**
     * Build a streaming StreamingChatLanguageModel.
     */
    @SuppressWarnings("unchecked")
    private static StreamingChatLanguageModel buildStreamingModel(Map<String, Object> top, String key) {
        if (!top.containsKey(key)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) top.get(key);
        return (StreamingChatLanguageModel) buildModelFromMap(map);
    }

    /**
     * Internal helper: build either StreamingChatLanguageModel or StreamingChatLanguageModel from the map.
     */
    private static Object buildModelFromMap(Map<String, Object> modelMap) {
        // Read common fields
        String provider = (String) modelMap.get("provider"); // optional
        String url = (String) modelMap.get("url");           // optional
        String apiKeyOrEnv = (String) modelMap.get("key");   // optional, can be an env var name
        String modelName = (String) modelMap.get("name");    // optional
        Double temperature = getDouble(modelMap, "temperature", null); // don't hardcode a default
        int maxTokens = getInt(modelMap, "maxTokens", DEFAULT_MAX_TOKENS);

        // Resolve the actual API key if "key" is a property name
        String resolvedKey = getKey(apiKeyOrEnv) == null ? apiKeyOrEnv : getKey(apiKeyOrEnv);

        if (provider != null && provider.equalsIgnoreCase("deepseek")) {
            provider = null;
            url = "https://api.deepseek.com";
        }
        if (provider != null && provider.equalsIgnoreCase("google")) {
            provider = null;
            url = "https://generativelanguage.googleapis.com/v1beta/openai/";
        }

        if ((provider == null || provider.isBlank()) && url == null) {
            throw new IllegalArgumentException("Either provider or url must be specified");
        }
        if (provider != null && url != null) {
            throw new IllegalArgumentException("Exactly one of provider and url must be specified");
        }

        // No provider => use openai client with url
        if (provider == null || provider.isBlank()) {
            var builder = OpenAiStreamingChatModel.builder()
                    .apiKey(resolvedKey)
                    .baseUrl(url)
                    .modelName(modelName)
                    .maxCompletionTokens(maxTokens);
            maybeSetDouble(temperature, builder::temperature);
            return builder.build();
        }

        // Otherwise, provider is set: handle openai or anthropic
        return switch (provider.toLowerCase()) {
            case "openai" -> {
                var builder = OpenAiStreamingChatModel.builder()
                        .apiKey(resolvedKey)
                        .modelName(modelName)
                        .maxCompletionTokens(maxTokens);
                maybeSetDouble(temperature, builder::temperature);
                if (modelMap.get("reasoning_effort") != null) {
                    var reasoningEffort = OpenAiChatRequestParameters.builder()
                            .reasoningEffort(modelMap.get("reasoning_effort").toString())
                            .build();
                    if (modelMap.get("reasoning_effort").toString().equals("high")) {
                        // tolerate longer delays
                        builder = builder.defaultRequestParameters(reasoningEffort)
                                .timeout(Duration.of(3, java.time.temporal.ChronoUnit.MINUTES));
                    }
                }
                yield builder.build();
            }
            case "anthropic" -> {
                var builder = AnthropicStreamingChatModel.builder()
                        .apiKey(resolvedKey)
                        .modelName(modelName)
                        .maxTokens(maxTokens);
                maybeSetDouble(temperature, builder::temperature);
                yield builder.build();
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    private static void maybeSetDouble(Double temperature, Consumer<Double> setter) {
        if (temperature != null) {
            setter.accept(temperature);
        }
    }

    private static Double getDouble(Map<String, Object> map, String key, Double defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return defaultValue; 
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return defaultValue;
    }

    /**
     * Helper method to pull a "name" field from a sub-map, falling back to a default if missing.
     */
    @SuppressWarnings("unchecked")
    private static String readModelName(Map<String, Object> top, String modelKey) {
        assert top.containsKey(modelKey); // already built model itself

        Map<String, Object> map = (Map<String, Object>) top.get(modelKey);
        String configName = (String) map.get("name");
        if (map.containsKey("reasoning_effort")) {
            String reasoningEffort = (String) map.get("reasoning_effort");
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                configName = configName + "-" + reasoningEffort;
            }
        }
        return configName;
    }

    static final String UNAVAILABLE = "AI is unavailable";

    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> am.text();
            case UserMessage um -> um.singleText();
            default -> throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static int getApproximateTokens(String text) {
        return tokenizer.encode(text).size();
    }

    public static class UnavailableStreamingModel implements StreamingChatLanguageModel {
        public UnavailableStreamingModel() {
        }

        public void generate(String userMessage, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }

        public void generate(List<ChatMessage> messages, ToolSpecification toolSpecification, StreamingResponseHandler<AiMessage> handler) {
            handler.onComplete(new Response<>(new AiMessage(UNAVAILABLE)));
        }
    }

    /**
     * Simple interface for speech-to-text operations.
     */
    public interface SpeechToTextModel
    {
        /**
         * Transcribes the given audio file to text, returning the transcript.
         */
        String transcribe(Path audioFile) throws IOException;
    }

    /**
     * Stubbed / unavailable STT model that always returns a message
     * indicating that STT is disabled.
     */
    public static class UnavailableSTT implements SpeechToTextModel
    {
        @Override
        public String transcribe(Path audioFile) {
            return "Speech-to-text is unavailable (no OpenAI key).";
        }
    }

    /**
     * Real STT using OpenAI Whisper v1. Uses OkHttp for multipart/form-data upload.
     * We block until the transcription is done. This requires an OpenAI API key.
     */
    public static class OpenAiWhisperSTT implements SpeechToTextModel
    {
        private static final Logger logger = LogManager.getLogger(OpenAiWhisperSTT.class);

        private final String apiKey;
        private static final String WHISPER_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
        private final OkHttpClient httpClient;

        public OpenAiWhisperSTT(String apiKey)
        {
            this.apiKey = apiKey;
            this.httpClient = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .build();
        }

        /**
         * Determines the MediaType based on file extension.
         * @param fileName Name of the file
         * @return MediaType for the HTTP request
         */
        private MediaType getMediaTypeFromFileName(String fileName)
        {
            var extension = fileName.toLowerCase();
            int dotIndex = extension.lastIndexOf('.');
            if (dotIndex > 0)
            {
                extension = extension.substring(dotIndex + 1);
            }

            return switch (extension)
            {
                case "mp3", "mpga", "mpeg" -> MediaType.parse("audio/mpeg");
                case "mp4", "m4a"          -> MediaType.parse("audio/mp4");
                case "wav"                 -> MediaType.parse("audio/wav");
                case "webm"                -> MediaType.parse("audio/webm");
                default                    -> MediaType.parse("audio/mpeg"); // fallback
            };
        }

        /**
         * Transcribes the given audio file to text, returning the transcript.
         * Uses OkHttp for multipart/form-data with file, model, and response_format fields.
         */
        @Override
        public String transcribe(Path audioFile) throws IOException
        {
            logger.info("Beginning transcription for file: {}", audioFile);
            File file = audioFile.toFile();

            // Use getMediaTypeFromFileName if you want to differentiate .wav vs .mp3, etc.
            // For a simpler approach, you could always use MediaType.get("audio/mpeg") or "audio/wav"
            MediaType mediaType = getMediaTypeFromFileName(file.getName());
            RequestBody fileBody = RequestBody.create(file, mediaType);

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(), fileBody)
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", "en")
                    .addFormDataPart("response_format", "json")
                    .build();

            Request request = new Request.Builder()
                    .url(WHISPER_ENDPOINT)
                    .header("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "multipart/form-data")
                    .post(requestBody)
                    .build();

            logger.debug("Sending Whisper STT request to {}", WHISPER_ENDPOINT);

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                String bodyStr = response.body() != null ? response.body().string() : "";

                logger.debug("Received response, status = {}", response.code());

                if (!response.isSuccessful()) {
                    logger.error("Whisper STT call failed with status {}: {}", response.code(), bodyStr);
                    throw new IOException("Whisper STT call failed with status "
                                                  + response.code() + ": " + bodyStr);
                }

                // Parse JSON response
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode node = mapper.readTree(bodyStr);
                    if (node.has("text")) {
                        String transcript = node.get("text").asText().trim();
                        logger.info("Transcription successful, text length={} chars", transcript.length());
                        return transcript;
                    } else {
                        logger.warn("No 'text' field found in Whisper response.");
                        return "No transcription found in response.";
                    }
                } catch (Exception e) {
                    logger.error("Error parsing Whisper JSON response: {}", e.getMessage());
                    throw new IOException("Error parsing Whisper JSON response: " + e.getMessage(), e);
                }
            }
        }
    }
}
