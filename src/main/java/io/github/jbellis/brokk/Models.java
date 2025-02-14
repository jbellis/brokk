package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Holds references to the models we use in Brokk.
 */
public record Models(
        ChatLanguageModel editModel,
        StreamingChatLanguageModel editModelStreaming,
        ChatLanguageModel quickModel,
        StreamingChatLanguageModel quickModelStreaming,
        String editModelName,
        String quickModelName
) {
    // correct for these models
    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final ObjectMapper mapper = new ObjectMapper();
    public static final Map<String, Object> MODEL_METADATA;
    
    /**
     * If the user does not have a config file, or if there's a parse error, fallback to some default.
     */
    private static final String DEFAULT_CONFIG = """
    edit_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-5-sonnet-20241022
      maxTokens: 8192

    quick_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-5-haiku-20241022
      maxTokens: 8192
    """;

    static {
        try {
            MODEL_METADATA = mapper.readValue(
                Models.class.getResourceAsStream("/model-metadata.json"),
                Map.class
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load model metadata", e);
        }
    }

    

    /**
     * Attempts to load model configurations from <code>~/.config/brokk/brokk.yaml</code>.
     * <p>
     * The YAML filename can define two sets of models: <strong>edit_model</strong> and <strong>quick_model</strong>.
     * Each set is used to build a non-streaming and a streaming model. If any model definition is missing or
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
     *
     * quick_model:
     *   provider: anthropic
     *   key: ANTHROPIC_API_KEY
     *   name: claude-v1.3
     *   temperature: 0.5
     *   maxTokens: 6000
     * </pre>
     * <ul>
     *   <li><strong>provider:</strong> Required if you want to use a known provider
     *       (<code>openai</code>, <code>deepseek</code>, <code>anthropic</code>). If omitted, the code falls back
     *       to interpreting <em>url</em> + <em>key</em> as a custom OpenAI-like endpoint or uses
     *       a built-in OpenAI default.</li>
     *   <li><strong>key:</strong> Indicates the environment variable holding the API key (e.g., <code>DEEPSEEK_API_KEY</code>).
     *       This is looked up via <code>System.getenv</code>. If <code>key</code> is <code>env:SOME_VAR</code>,
     *       the portion after <code>env:</code> is taken as the environment variable name. Otherwise, the entire
     *       <code>key</code> string is treated as the environment variable name directly.</li>
     *   <li><strong>url:</strong> Optional. If using <code>deepseek</code>, for instance, defaults to
     *       <code>https://api.deepseek.com</code> unless otherwise specified. OpenAI can omit <em>url</em>
     *       to use the default OpenAI endpoint.</li>
     *   <li><strong>name:</strong> The model identifier (e.g., <code>gpt-3.5-turbo</code>, <code>deepseek-chat</code>,
     *       <code>claude-v1.3</code>, etc.).</li>
     *   <li><strong>temperature:</strong> A double value controlling the "creativity" of responses (default 0.4).</li>
     *   <li><strong>maxTokens:</strong> The maximum number of tokens to allow in the response (default 8000).</li>
     * </ul>
     * If the filename is missing or cannot be parsed, the method returns a {@link Models} instance with
     * hardcoded default model settings (currently using Anthropic-based defaults).
     *
     * @return A {@link Models} record containing references to the edit and quick Chat/StreamingChat models,
     *         plus each model's name.
     */
    public static Models load() {
        Path configPath = Path.of(System.getProperty("user.home"), ".config", "brokk", "brokk.yml");
        String yamlStr;
        if (!Files.exists(configPath)) {
            yamlStr = DEFAULT_CONFIG;
        } else {
            try {
                yamlStr = Files.readString(configPath);
            } catch (IOException e) {
                System.out.println("Error reading " + configPath + ": " + e.getMessage());
                yamlStr = DEFAULT_CONFIG;
            }
        }

        return buildModelsFromYaml(yamlStr);
    }

    private static Models buildModelsFromYaml(String yamlStr) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> top = yaml.load(yamlStr);

            // Build each model
            ChatLanguageModel editModel = buildChatModel(top, "edit_model", false);
            StreamingChatLanguageModel editModelStreaming = buildStreamingModel(top, "edit_model", true);
            ChatLanguageModel quickModel = buildChatModel(top, "quick_model", false);
            StreamingChatLanguageModel quickModelStreaming = buildStreamingModel(top, "quick_model", true);

            // Extract each model name (or use defaults if none specified)
            String editModelName = readModelName(top, "edit_model", "claude-3-5-sonnet-20241022");
            String quickModelName = readModelName(top, "quick_model", "claude-3-5-haiku-20241022");

            return new Models(editModel, editModelStreaming, quickModel, quickModelStreaming, editModelName, quickModelName);
        } catch (Exception e) {
            System.out.println("Error parsing yaml: " + e.getMessage());
            System.exit(1);
        }
    }

    public static Models disabled() {
        return new Models(new UnavailableModel(),
                          new UnavailableStreamingModel(),
                          new UnavailableModel(),
                          new UnavailableStreamingModel(),
                          "disabled",
                          "disabled");
    }

    /**
     * Build a non-streaming ChatLanguageModel.
     */
    @SuppressWarnings("unchecked")
    private static ChatLanguageModel buildChatModel(Map<String, Object> top, String key, boolean streaming) {
        if (!top.containsKey(key)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) top.get(key);
        return (ChatLanguageModel) buildModelFromMap(map, false);
    }

    /**
     * Build a streaming ChatLanguageModel.
     */
    @SuppressWarnings("unchecked")
    private static StreamingChatLanguageModel buildStreamingModel(Map<String, Object> top, String key, boolean streaming) {
        if (!top.containsKey(key)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) top.get(key);
        return (StreamingChatLanguageModel) buildModelFromMap(map, true);
    }

    /**
     * Internal helper: build either ChatLanguageModel or StreamingChatLanguageModel from the map.
     */
    private static Object buildModelFromMap(Map<String, Object> modelMap, boolean streaming) {
        // Read common fields
        String provider = (String) modelMap.get("provider"); // optional
        String url = (String) modelMap.get("url");           // optional
        String apiKeyOrEnv = (String) modelMap.get("key");   // optional, can be an env var name
        String modelName = (String) modelMap.get("name");    // optional
        Double temperature = getDouble(modelMap, "temperature", null); // don't hardcode a default
        int maxTokens = getInt(modelMap, "maxTokens", DEFAULT_MAX_TOKENS);

        // Resolve the actual API key if "key" is an env var name
        String resolvedKey = (apiKeyOrEnv != null && apiKeyOrEnv.startsWith("env:"))
                ? System.getenv(apiKeyOrEnv.substring("env:".length()))
                : (apiKeyOrEnv != null ? System.getenv(apiKeyOrEnv) : null);

        if (provider != null && provider.equalsIgnoreCase("deepseek")) {
            provider = null;
            url = "https://api.deepseek.com";
        }

        if ((provider == null || provider.isBlank()) && url == null) {
            throw new IllegalArgumentException("Either provider or url must be specified");
        }
        if (provider != null && url != null) {
            throw new IllegalArgumentException("Exactly one of provider and url must be specified");
        }

        // No provider => use openai client with url
        if (provider == null || provider.isBlank()) {
            if (streaming) {
                var builder = OpenAiStreamingChatModel.builder()
                        .apiKey(resolvedKey)
                        .baseUrl(url)
                        .modelName(modelName)
                        .maxTokens(maxTokens);
                maybeSetTemperature(temperature, builder::temperature);
                return builder.build();
            } else {
                var builder = OpenAiChatModel.builder()
                        .apiKey(resolvedKey)
                        .baseUrl(url)
                        .modelName(modelName)
                        .maxTokens(maxTokens);
                maybeSetTemperature(temperature, builder::temperature);
                return builder.build();
            }
        }

        // Otherwise, provider is set: handle openai or anthropic
        return switch (provider.toLowerCase()) {
            case "openai" -> {
                if (streaming) {
                    var builder = OpenAiStreamingChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetTemperature(temperature, builder::temperature);
                    yield builder.build();
                } else {
                    var builder = OpenAiChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetTemperature(temperature, builder::temperature);
                    yield builder.build();
                }
            }
            case "anthropic" -> {
                if (streaming) {
                    var builder = AnthropicStreamingChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetTemperature(temperature, builder::temperature);
                    yield builder.build();
                } else {
                    var builder = AnthropicChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetTemperature(temperature, builder::temperature);
                    yield builder.build();
                }
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    /**
     * Conditionally set temperature on an OpenAI/Anthropic builder if the
     * user actually specified a "temperature" in the map.
     */
    private static void maybeSetTemperature(Double temperature, Consumer<Double> setter) {
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
    private static String readModelName(Map<String, Object> top, String modelKey, String defaultName) {
        if (!top.containsKey(modelKey)) {
            return defaultName;
        }
        Map<String, Object> map = (Map<String, Object>) top.get(modelKey);
        String configName = (String) map.get("name");
        return (configName != null && !configName.isBlank()) ? configName : defaultName;
    }

    static final String UNAVAILABLE = "AI is unavailable";

    public static class UnavailableModel implements ChatLanguageModel {
        public UnavailableModel() {
        }

        public String generate(String userMessage) {
            return UNAVAILABLE;
        }

        public Response<AiMessage> generate(ChatMessage... messages) {
            return new Response<>(new AiMessage(UNAVAILABLE));
        }

        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return new Response<>(new AiMessage(UNAVAILABLE));
        }

        public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
            return new Response<>(new AiMessage(UNAVAILABLE));
        }

        public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
            return new Response<>(new AiMessage(UNAVAILABLE));
        }
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
    }}
