package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
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
public record Models(StreamingChatLanguageModel editModel,
                     StreamingChatLanguageModel applyModel,
                     ChatLanguageModel quickModel,
                     String editModelName,
                     String applyModelName,
                     String quickModelName)
{
    // correct for these models
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private static final String OPENAI_DEFAULTS = """
    edit_model:
      provider: openai
      key: OPENAI_API_KEY
      name: o3-mini
      reasoning_effort: high
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

    private static final String ANTHROPIC_DEFAULTS = """
    edit_model:
      provider: anthropic
      key: ANTHROPIC_API_KEY
      name: claude-3-5-sonnet-latest
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
     *       This is looked up via <code>System.getenv</code>. If <code>key</code> is <code>env:SOME_VAR</code>,
     *       the portion after <code>env:</code> is taken as the environment variable name. Otherwise, the entire
     *       <code>key</code> string is treated as the environment variable name directly.</li>
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
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        if (anthropicKey != null && !anthropicKey.isBlank()) {
            return buildModelsFromYaml(ANTHROPIC_DEFAULTS);
        }

        // Next try OpenAI if API key exists
        String openaiKey = System.getenv("OPENAI_API_KEY");
        if (openaiKey != null && !openaiKey.isBlank()) {
            return buildModelsFromYaml(OPENAI_DEFAULTS);
        }

        // If no API keys are available, return disabled models
        return disabled();
    }

    private static Models buildModelsFromYaml(String yamlStr) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> top = yaml.load(yamlStr);

            // Build each model
            var editModel = buildStreamingModel(top, "edit_model", true);
            var applyModel = buildStreamingModel(top, "apply_model", false);
            var quickModel = buildChatModel(top, "quick_model", false);
    
        String editModelName = readModelName(top, "edit_model", "claude-3-5-sonnet-20241022");
            String applyModelName = readModelName(top, "apply_model", "o3-mini");
            String quickModelName = readModelName(top, "quick_model", "claude-3-5-haiku-20241022");
    
        return new Models(editModel, applyModel, quickModel, editModelName, applyModelName, quickModelName);
        } catch (Exception e) {
            System.out.println("Error parsing yaml: " + e.getMessage());
            System.exit(1);
            throw new RuntimeException(e); // make compiler happy
        }
    }

    public static Models disabled() {
        return new Models(new UnavailableStreamingModel(),
                          new UnavailableStreamingModel(),
                          new UnavailableModel(),
                          "disabled",
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
                        .maxCompletionTokens(maxTokens);
                maybeSetDouble(temperature, builder::temperature);
                return builder.build();
            } else {
                var builder = OpenAiChatModel.builder()
                        .apiKey(resolvedKey)
                        .baseUrl(url)
                        .modelName(modelName)
                        .maxCompletionTokens(maxTokens);
                maybeSetDouble(temperature, builder::temperature);
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
                            .maxCompletionTokens(maxTokens);
                    maybeSetDouble(temperature, builder::temperature);
                    if (modelMap.get("reasoning_effort") != null) {
                        var reasoningEffort = OpenAiChatRequestParameters.builder()
                                .reasoningEffort(modelMap.get("reasoning_effort").toString())
                                .build();
                        builder = builder.defaultRequestParameters(reasoningEffort);
                    }
                    yield builder.build();
                } else {
                    var builder = OpenAiChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxCompletionTokens(maxTokens);
                    maybeSetDouble(temperature, builder::temperature);
                    if (modelMap.get("reasoning_effort") != null) {
                        var reasoningEffort = OpenAiChatRequestParameters.builder()
                                .reasoningEffort(modelMap.get("reasoning_effort").toString())
                                .build();
                        builder = builder.defaultRequestParameters(reasoningEffort);
                    }
                    yield builder.build();
                }
            }
            case "anthropic" -> {
                if (streaming) {
                    var builder = AnthropicStreamingChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetDouble(temperature, builder::temperature);
                    yield builder.build();
                } else {
                    var builder = AnthropicChatModel.builder()
                            .apiKey(resolvedKey)
                            .modelName(modelName)
                            .maxTokens(maxTokens);
                    maybeSetDouble(temperature, builder::temperature);
                    yield builder.build();
                }
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
    private static String readModelName(Map<String, Object> top, String modelKey, String defaultName) {
        if (!top.containsKey(modelKey)) {
            return defaultName;
        }
        Map<String, Object> map = (Map<String, Object>) top.get(modelKey);
        String configName = (String) map.get("name");
        if (configName == null || configName.isBlank()) {
            configName = defaultName;
        }
        if (map.containsKey("reasoning_effort")) {
            String reasoningEffort = (String) map.get("reasoning_effort");
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                configName = configName + "-" + reasoningEffort;
            }
        }
        return configName;
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
