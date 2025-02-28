package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Tuple2;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SearchAgent implements an iterative, agentic approach to code search.
 * It uses multiple steps of searching and reasoning to find code elements relevant to a query.
 */
public class SearchAgent {
    private final Logger logger = LogManager.getLogger(SearchAgent.class);
    private static final int TOKEN_BUDGET = 64000; // 64K context window for models like R1
    private static final int MAX_STEPS = 20;

    private final Analyzer analyzer;
    private final ContextManager contextManager;
    private final Coder coder;
    private final ConsoleIO io;

    // Budget and action control state
    private int badAttempts = 0;
    private static final int MAX_BAD_ATTEMPTS = 3;
    private boolean allowSearch = true;
    private boolean allowSkeleton = true;
    private boolean allowClass = true;
    private boolean allowMethod = true;
    private boolean allowPagerank = true;
    private boolean allowAnswer = true;
    private boolean allowSubstringSearch = false; // Starts disabled until searchSymbols is called
    private boolean symbolsFound = false;

    // Search state
    private final String query;
    private final List<ToolCall> actionHistory = new ArrayList<>();
    private final List<Tuple2<String, String>> knowledge = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);
    private int totalSteps = 0;

    public SearchAgent(String query, ContextManager contextManager, Coder coder, ConsoleIO io) {
        this.query = query;
        this.contextManager = contextManager;
        this.analyzer = contextManager.getAnalyzer();
        this.coder = coder;
        this.io = io;
    }

    /**
     * Execute the search process, iterating through queries until completion.
     * @return The final set of discovered code units
     */
    public ContextFragment.VirtualFragment execute() {
        // Initialize
        var contextWithClasses = contextManager.currentContext().allFragments().map(f -> {
            String text;
            try {
                text = f.text();
            } catch (IOException e) {
                contextManager.removeBadFragment(f, e);
                return null;
            }
            return """
            <fragment description="%s" sources="%s">
            %s
            </fragment>
            """.stripIndent().formatted(f.description(),
                                        (f.sources(analyzer).stream().map(CodeUnit::reference).collect(Collectors.joining(", "))),
                                        text);
        }).filter(Objects::nonNull).collect(Collectors.joining("\n"));
        if (!contextWithClasses.isBlank()) {
            io.spin("Evaluating context");
            var messages = new ArrayList<ChatMessage>();
            messages.add(new SystemMessage("""
            You are an expert software architect.
            evaluating which code fragments are relevant to a user query.
            Review the following list of code fragments and select the ones most relevant to the query.
            Make sure to include the fully qualified source (class, method, etc) as well as the code.
            """.stripIndent()));
            messages.add(new UserMessage("<query>%s</query>\n\n".formatted(query) + contextWithClasses));
            String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
            knowledge.add(new Tuple2<>("Initial context", response));
        }

        io.spin("Exploring: " + query);
        while (totalSteps < MAX_STEPS && totalUsage.inputTokenCount() < TOKEN_BUDGET) {
            totalSteps++;

            // Special handling based on previous steps
            updateActionControlsBasedOnContext();

            // Decide what action to take for this query
            var steps = determineNextActions();

            if (steps.isEmpty()) {
                logger.debug("No valid actions determined");
                break;
            }

            // Get the tool name from the first step for spinner message
            ToolCall firstStep = steps.getFirst();
            String toolName = firstStep.getRequest().name();
            String explanation = getExplanationForTool(toolName, firstStep);
            io.spin(explanation);
            logger.debug("{}; token usage: {}", explanation, totalUsage);
            logger.debug("Actions: {}", steps);

            // Execute the actions
            var results = steps.stream().parallel().peek(step -> {
                step.execute();
                logger.debug("Result: {}", step);
            }).toList();

            // Check if we should terminate
            String firstToolName = steps.getFirst().getRequest().name();
            if (firstToolName.equals("answer")) {
                logger.debug("Search complete");
                assert steps.size() == 1 : steps;

                // Parse the classNames from the answer tool call
                try {
                    ToolCall answerCall = steps.getFirst();
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> arguments = mapper.readValue(answerCall.getRequest().arguments(), new TypeReference<>() {});
                    @SuppressWarnings("unchecked")
                    var classNames = (List<String>) arguments.get("classNames");
                    String result = results.getFirst().execute();
                    logger.debug("Relevant classes are {}", classNames);
                    // coalese inner classes whose parents are also present
                    var coalesced = classNames.stream().filter(c -> classNames.stream().noneMatch(c2 -> c.startsWith(c2 + "$"))).toList();

                    // Return a SearchFragment with the answer and class names
                    var sources = coalesced.stream().map(CodeUnit::cls).collect(Collectors.toSet());
                    return new ContextFragment.SearchFragment(query, result, sources);
                } catch (Exception e) {
                    logger.error("Error creating SearchFragment", e);
                    // Fallback to just returning the answer as string in a SearchFragment with empty classes
                    return new ContextFragment.StringFragment(query, results.getFirst().execute());
                }
            } else if (firstToolName.equals("abort")) {
                logger.debug("Search aborted");
                assert steps.size() == 1 : steps;
                
                // Return a String fragment with the abort message
                String result = results.getFirst().execute();
                return new ContextFragment.StringFragment(query, result);
            }

            // Add the step to the history
            actionHistory.addAll(results);
        }

        logger.debug("Search complete after reaching max steps or budget");
        return new ContextFragment.SearchFragment(query, "No answer found within budget", Set.of());
    }

    /**
     * Update action controls based on current search context.
     */
    private void updateActionControlsBasedOnContext() {
        // If we just started, don't allow answer yet
        allowAnswer = !actionHistory.isEmpty();

        allowSearch = true;
        allowSkeleton = true;
        allowClass = true;
        allowMethod = true;
        allowPagerank = true;
        // don't reset allowSubstringSearch
    }

    /**
     * Gets an explanation for a tool name with parameter information
     */
    private String getExplanationForTool(String toolName, ToolCall toolCall) {
        String paramInfo = getToolParameterInfo(toolCall);
        String baseExplanation = switch (toolName) {
            case "searchSymbols" -> "Searching for symbols";
            case "searchSubstring" -> "Searching for substrings";
            case "getUsages" -> "Finding usages";
            case "getRelatedClasses" -> "Finding related code";
            case "getClassSkeleton" -> "Getting class overview";
            case "getClassSource" -> "Fetching class source";
            case "getMethodSource" -> "Fetching method source";
            case "answer" -> "Answering the question";
            case "abort" -> "Abort the search";
            default -> {
                logger.debug("Unknown tool name: {}", toolName);
                yield  "Processing request";
            }
        };
        
        return paramInfo.isBlank() ? baseExplanation : baseExplanation + " (" + paramInfo + ")";
    }
    
    /**
     * Gets human-readable parameter information from a tool call
     */
    private String getToolParameterInfo(ToolCall toolCall) {
        if (toolCall == null) return "";
        
        try {
            // Parse the JSON arguments
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> arguments = mapper.readValue(toolCall.getRequest().arguments(), new TypeReference<>() {});
            
            return switch (toolCall.getRequest().name()) {
                case "searchSymbols" -> getStringParam(arguments, "pattern");
                case "searchSubstring" -> getStringParam(arguments, "pattern");
                case "getUsages" -> getStringParam(arguments, "symbol");
                case "getRelatedClasses" -> {
                    Object classList = arguments.get("classList");
                    if (classList instanceof List<?> list && !list.isEmpty()) {
                        yield list.size() == 1 ? list.getFirst().toString() : list.size() + " classes";
                    }
                    yield "";
                }
                case "getClassSkeleton" -> getStringParam(arguments, "className");
                case "getClassSource" -> getStringParam(arguments, "className");
                case "getMethodSource" -> getStringParam(arguments, "methodName");
                case "answer" -> "finalizing";  // Keep it concise
                default -> throw new IllegalArgumentException("Unknown tool name " + toolCall.getRequest().name());
            };
        } catch (Exception e) {
            logger.error("Error getting parameter info", e);
            return "";
        }
    }
    
    private String getStringParam(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? "" : value.toString();
    }

    /**
     * Determine the next action to take for the current query.
     */
    private List<ToolCall> determineNextActions() {
        List<ChatMessage> messages = buildPrompt();

        // Ask LLM for next action with tools
        var tools = createToolSpecifications();
        var response = coder.sendStreamingWithTools(actionModel(), messages, false, tools);
        totalUsage = TokenUsage.sum(totalUsage, response.tokenUsage());

        // Parse response into potentially multiple actions
        return parseResponse(response.content());
    }

    private StreamingChatLanguageModel actionModel() {
        // openai: use applyModel (default o3-mini-low)
        // everyone else: use editModel
        // hardcoding this sucks but it's openai's fault for not giving us a smart+fast option
        return coder.models.editModel() instanceof OpenAiStreamingChatModel ? coder.models.applyModel() : coder.models.editModel();
    }

    /**
     * Create tool specifications for the LLM based on allowed actions.
     */
    private List<dev.langchain4j.agent.tool.ToolSpecification> createToolSpecifications() {
        List<dev.langchain4j.agent.tool.ToolSpecification> tools = new ArrayList<>();

        if (allowSearch) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("searchSymbols")));
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getUsages")));
        }

        if (allowSubstringSearch) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("searchSubstring")));
        }

        if (allowPagerank) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getRelatedClasses")));
        }

        if (allowSkeleton) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getClassSkeleton")));
        }

        if (allowClass) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getClassSource")));
        }

        if (allowMethod) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getMethodSource")));
        }

        if (allowAnswer) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("answer")));
            
            // Always allow abort when answer is allowed
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("abort")));
        }
        
        return tools;
    }
    
    /**
     * Helper method to get a Method by name.
     * 
     * @param methodName The name of the method to find
     * @return The Method object or null if not found
     */
    private java.lang.reflect.Method getMethodByName(String methodName) {
        for (java.lang.reflect.Method method : getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Method not found: " + methodName);
    }

    /**
     * Build the system prompt for determining the next action.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        // System prompt outlining capabilities
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("""
        You are a code search agent that helps find relevant code based on queries.
        Even if not explicitly stated, the query should be understood to refer to the current codebase,
        and not a general-knowledge question.
        Your goal is to find code definitions, implementations, and usages that answer the user's query.
        """.stripIndent());

        // Add beast mode if we're out of time or we've had too many bad attempts
        if (totalUsage.inputTokenCount() > 0.9 * TOKEN_BUDGET || badAttempts >= MAX_BAD_ATTEMPTS) {
            systemPrompt.append("""
            <beast-mode>
            🔥 MAXIMUM PRIORITY OVERRIDE! 🔥
            - YOU MUST FINALIZE RESULTS NOW WITH AVAILABLE INFORMATION
            - USE DISCOVERED CODE UNITS TO PROVIDE BEST POSSIBLE ANSWER
            - FAILURE IS NOT AN OPTION
            </beast-mode>
            """.stripIndent());
            // Force finalize only
            allowAnswer = true;
            allowSearch = false;
            allowSkeleton = false;
            allowClass = false;
            allowMethod = false;
        }

        // Add knowledge gathered during search
        if (!knowledge.isEmpty()) {
            var collected = knowledge.stream().map(t -> systemPrompt.append("""
            <entry description="%s">
            %s
            </entry>
            """.stripIndent().formatted(t._1, t._2)))
                    .collect(Collectors.joining("\n"));
            systemPrompt.append("\n<knowledge>\n%s\n</knowledge>\n".formatted(collected));
        }

        // Add information about current search state
        if (!actionHistory.isEmpty()) {
            systemPrompt.append("\n<action-history>\n");
            for (int i = 0; i < actionHistory.size(); i++) {
                var step = actionHistory.get(i);
                systemPrompt.append(String.format("Step %d: %s\n", i + 1, step));
            }
            systemPrompt.append("</action-history>\n");
        }

        // Remind about the original query
        systemPrompt.append("\n<original-query>\n");
        systemPrompt.append(query);
        systemPrompt.append("\n</original-query>\n");

        messages.add(new SystemMessage(systemPrompt.toString()));
        var instructions = """
        Determine the next action(s) to take to search for code related to: %s.
        It is more efficient to call multiple tools in a single response when you know they will be needed.
        But if you don't have enough information to speculate, you can call just one tool.
        """.formatted(query);
        if (!symbolsFound) {
            instructions += """
            Start with broad searches, and then explore more specific code units once you find a foothold.
            For example, if the user is asking
            [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
            then we should start with searchSymbols(".*SSTable.*) or searchSymbols(".*Compaction.*") or searchSymbols(".*reference.*"),
            instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*"
            """;
        }
        messages.add(new UserMessage(instructions.stripIndent()));

        return messages;
    }

    /**
     * Parse the LLM response into a list of ToolCall objects.
     * This method handles both direct text responses and tool execution responses.
     * ANSWER or ABORT tools will be sorted at the end.
     */
    private List<ToolCall> parseResponse(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            var dummyTer = ToolExecutionRequest.builder().name("MISSING_TOOL_CALL").build();
            var errorCall = new ToolCall(dummyTer, "Error: No tool execution requests found in response");
            return List.of(errorCall);
        }

        // Process each tool execution request
        var toolCalls = response.toolExecutionRequests().stream()
            .map(ToolCall::new)
            .toList();

        // If we have an Answer or Abort action, just return that
        var answerTools = toolCalls.stream()
            .filter(t -> t.getRequest().name().equals("answer") || t.getRequest().name().equals("abort"))
            .toList();

        if (!answerTools.isEmpty()) {
            return List.of(answerTools.getFirst());
        }

        return toolCalls;
    }

    @Tool("Search for symbols (class/method/field definitions) using Joern. This should usually be the first step in a search.")
    public String searchSymbols(
            @P(value = "Case-insensitive Joern regex pattern to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
            String pattern,
            @P(value = "Reasoning about why this pattern is relevant to the query")
            String reasoning
    ) {
        if (pattern.isBlank()) {
            return "Cannot search definitions: pattern is empty";
        }
        if (reasoning.isBlank()) {
            return "Cannot search definitions: reasoning is empty";
        }

        // Enable substring search after the first successful searchSymbols call
        allowSubstringSearch = true;

        var definitions = analyzer.getDefinitions(pattern);
        if (definitions.isEmpty()) {
            return "No definitions found for pattern: " + pattern;
        }

        symbolsFound = true;
        logger.debug("Raw definitions: {}", definitions);

        // Include all matches or filter if there are too many
        var relevant = new ArrayList<String>();

        // Check if we need to filter by relevance (if results are > 10% of token budget)
        int definitionTokens = Models.getApproximateTokens(definitions.stream().map(CodeUnit::reference).collect(Collectors.joining("\n")));
        boolean shouldFilter = definitionTokens > TOKEN_BUDGET * 0.1;
        if (shouldFilter) {
            logger.debug("Filtering definitions due to size: {} tokens (> 10% of budget)", definitionTokens);

            // Get reasoning if available
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("You are helping evaluate which code definitions are relevant to a user query. " +
                                          "Review the list of definitions and select the ones most relevant to the query and " +
                                          "to your previous reasoning."));
            messages.add(new UserMessage("Query: %s\nReasoning:%s\nDefinitions found:\n%s".formatted(query, reasoning, definitions)));
            String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
            io.spin("Filtering very large search result");

            // Extract mentions of the definitions from the response
            var relevantDefinitions = extractMatches(response, definitions.stream().map(CodeUnit::reference).collect(Collectors.toSet()));

            logger.debug("Filtered definitions: {} (from {})", relevantDefinitions.size(), definitions.size());

            // Add the relevant definitions
            for (CodeUnit definition : definitions) {
                if (relevantDefinitions.contains(definition.reference())) {
                    relevant.add(definition.reference());
                }
            }
        } else {
            // Just use all definitions without filtering
            for (CodeUnit definition : definitions) {
                relevant.add(definition.reference());
            }
        }

        return "Relevant symbols: " + String.join(", ", relevant);
    }

    /**
     * Extract text occurrences in response that match references from the originals
     */
    private static Set<String> extractMatches(String response, Set<String> originals) {
        Set<String> relevantDefinitions = new HashSet<>();
        for (var ref : originals) {
            // Look for the reference with word boundaries to avoid partial matches
            var p = Pattern.compile("\\b" + Pattern.quote(ref) + "\\b");
            var matcher = p.matcher(response);
            if (matcher.find()) {
                relevantDefinitions.add(ref);
            }
        }
        return relevantDefinitions;
    }

    /**
     * Search for usages of a symbol.
     */
    @Tool("Find where a symbol is used in code. Use this to discover how a class, method, or field is actually used throughout the codebase.")
    public String getUsages(
        @P(value = "Fully qualified symbol name (package name, class name, optional member name) to find usages for")
        String symbol,
        @P(value = "Reasoning about what information you're hoping to find in these usages")
        String reasoning
    ) {
        if (symbol.isBlank()) {
            return "Cannot search usages: symbol is empty";
        }
        if (reasoning.isBlank()) {
            return "Cannot search usages: reasoning is empty";
        }

        List<CodeUnit> uses = analyzer.getUses(symbol);
        if (uses.isEmpty()) {
            return "No usages found for: " + symbol;
        }

        // Check if we need to filter by relevance (if results are > 10% of token budget)
        String processedUsages = AnalyzerWrapper.processUsages(analyzer, uses).toString();
        int usageTokens = Models.getApproximateTokens(processedUsages);
        boolean shouldFilter = usageTokens > TOKEN_BUDGET * 0.1;
        if (shouldFilter) {
            logger.debug("Filtering usages due to size: {} tokens (> 10% of budget)", usageTokens);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("You are helping evaluate which code usages are relevant to a user query. " +
                                          "Review the following code usages and select ONLY the relevant chunks that directly " +
                                          "address the query. Output the FULL TEXT of the relevant code chunks."));
            messages.add(new UserMessage("Query: %s\nReasoning: %s\nUsages found for %s:\n%s".formatted(
                    query, reasoning, symbol, processedUsages)));
            String response = coder.sendStreaming(coder.models.applyModel(), messages, false);

            return "Relevant usages of " + symbol + ":\n\n" + response;
        } else {
            // Return all usages without filtering
            return "Usages of " + symbol + ":\n\n" + processedUsages;
        }
    }

    /**
     * Find related code using PageRank.
     */
    @Tool("Find related code units using PageRank algorithm. Use this when you've made some progress but got stuck, or when you're almost done and want to double-check that you haven't missed anything.")
    public String getRelatedClasses(
        @P(value = "List of fully qualified class names to use as seeds for PageRank. Use classes you've already found that seem relevant.")
        List<String> classList,
        @P(value = "Reasoning about what related code you're hoping to discover")
        String reasoning
    ) {
        if (classList.isEmpty()) {
            return "Cannot search pagerank: classList is empty";
        }
        if (reasoning.isBlank()) {
            return "Cannot search pagerank: reasoning is empty";
        }

        // Create map of seeds from discovered units
        HashMap<String, Double> weightedSeeds = new HashMap<>();
        for (String className : classList) {
            weightedSeeds.put(className, 1.0);
        }

        var pageRankResults = AnalyzerWrapper.combinedPageRankFor(analyzer, weightedSeeds);

        if (pageRankResults.isEmpty()) {
            return "No related code found via PageRank";
        }

        // Check if we need to filter by relevance (if results are > 10% of token budget)
        return pageRankResults.stream().limit(100).collect(Collectors.joining(", "));
    }

    /**
     * Get the skeleton (structure) of a class.
     */
    @Tool("Get an overview of a class's contents, including fields and method signatures. Use this to understand a class's structure without fetching its full source code.")
    public String getClassSkeleton(
        @P(value = "Fully qualified class name to get the skeleton structure for")
        String className
    ) {
        if (className.isBlank()) {
            return "Cannot get skeleton: class name is empty";
        }

        var skeletonOpt = analyzer.getSkeleton(className);
        if (skeletonOpt.isEmpty()) {
            return "No skeleton found for class: " + className;
        }

        return skeletonOpt.get();
    }

    /**
     * Get the full source code of a class.
     */
    @Tool("Get the full source code of a class. This is expensive, so prefer using skeleton or method sources when possible. Use this when you need the complete implementation details, or if you think multiple methods in the class may be relevant.")
    public String getClassSource(
        @P(value = "Fully qualified class name to retrieve the full source code for")
        String className,
        @P(value = "Reasoning about what specific implementation details you're looking for in this class")
        String reasoning
    ) {
        if (className.isBlank()) {
            return "Cannot get class source: class name is empty";
        }

        String classSource = analyzer.getClassSource(className);
        if (classSource == null || classSource.isEmpty()) {
            return "No source found for class: " + className;
        }

        // Check if we need to filter by relevance (if results are > 10% of token budget)
        int sourceTokens = Models.getApproximateTokens(classSource);
        boolean shouldFilter = sourceTokens > TOKEN_BUDGET * 0.1;
        if (shouldFilter) {
            logger.debug("Filtering class source due to size: {} tokens (> 10% of budget)", sourceTokens);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("You are helping evaluate which parts of a class source are relevant to a user query. " +
                                          "Review the following class source and select ONLY the relevant portions that directly " +
                                          "address the user's query and/or your own reasoning. Output the FULL TEXT of the relevant code chunks. When in doubt, include the chunk."));
            messages.add(new UserMessage("Query: %s\nReasoning: %s\nClass source for %s:\n%s".formatted(
                    query, reasoning, className, classSource)));
            String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
            io.spin("Filtering very large class source");

            return "Relevant portions of " + className + ":\n\n" + response;
        } else {
            // Return full class source without filtering
            return "Source code of " + className + ":\n\n" + classSource;
        }
    }

    /**
     * Get the source code of a method.
     */
    @Tool("Get the source code of a specific method. Use this to examine the implementation of a particular method without retrieving the entire class.")
    public String getMethodSource(
        @P(value = "Fully qualified method name (package name, class name, method name) to retrieve source for")
        String methodName
    ) {
        if (methodName.isBlank()) {
            return "Cannot get method source: method name is empty";
        }

        var methodSourceOpt = analyzer.getMethodSource(methodName);
        if (methodSourceOpt.isEmpty()) {
            return "No source found for method: " + methodName;
        }

        return methodSourceOpt.get();
    }

    @Tool("Provide a final answer to the current query and remove it from the queue. Use this when you have enough information to fully address the query.")
    public String answer(
        @P(value = "Comprehensive explanation that answers the current query. Include relevant source code snippets and explain how they relate to the query.")
        String explanation,
        @P(value = "List of fully qualified class names (FQCNs) of all classes relevant to the explanation.")
        List<String> classNames
    ) {
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        logger.debug("Answer: {}", explanation);
        logger.debug("Referenced classes: {}", classNames);

        return explanation;
    }

    @Tool("Search for classes whose text contents match a Java regular expression pattern. This is slower than searchSymbols but can find usages of external dependencies and comment strings.")
    public String searchSubstring(
            @P(value = "Java-style regex pattern to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
            String pattern,
            @P(value = "Reasoning about why this pattern is relevant to the query")
            String reasoning
    ) {
        if (pattern.isBlank()) {
            return "Cannot search substrings: pattern is empty";
        }
        if (reasoning.isBlank()) {
            return "Cannot search substrings: reasoning is empty";
        }

        logger.debug("Searching file contents for pattern: {}", pattern);

        try {
            // Compile the regex pattern
            Pattern compiledPattern = Pattern.compile(pattern);

            // Get all tracked files from GitRepo and process them functionally
            var matchingClasses = GitRepo.instance.getTrackedFiles().parallelStream().map(file -> {
                try {
                    RepoFile repoFile = new RepoFile(GitRepo.instance.getRoot(), file.toString());
                    String fileContents;
                    fileContents = new String(Files.readAllBytes(repoFile.absPath()));
                    // Return the repoFile if its contents match the pattern, otherwise null
                    return compiledPattern.matcher(fileContents).find() ? repoFile : null;
                } catch (Exception e) {
                    logger.debug("Error processing file {}: {}", file, e.getMessage());
                    return null;
                }
            })
            .filter(Objects::nonNull) // Filter out nulls (files with errors or no matches)
            .flatMap(repoFile -> {
                try {
                    // For each matching file, get all non-inner classes and flatten them into the stream
                    return analyzer.getClassesInFile(repoFile).stream()
                        .map(CodeUnit::reference)
                        .filter(reference -> !reference.contains("$"));
                } catch (Exception e) {
                    logger.debug("Error getting classes for file {}: {}", repoFile, e.getMessage());
                    return Stream.empty();
                }
            })
            .collect(Collectors.toSet()); // Collect to a set to eliminate duplicates

            if (matchingClasses.isEmpty()) {
                return "No classes found with content matching pattern: " + pattern;
            }

            // Check if we need to filter by relevance (if results are > 10% of token budget)
            int resultsTokens = Models.getApproximateTokens(String.join("\n", matchingClasses));
            boolean shouldFilter = resultsTokens > TOKEN_BUDGET * 0.1;
            if (shouldFilter) {
                logger.debug("Filtering substring search results due to size: {} tokens (> 10% of budget)", resultsTokens);

                List<ChatMessage> messages = new ArrayList<>();
                messages.add(new SystemMessage("You are helping evaluate which classes are relevant to a user query. " +
                                             "Review the list of class names and select the ones most relevant to the query and " +
                                             "to your previous reasoning."));
                messages.add(new UserMessage("Query: %s\nReasoning: %s\nClasses found with content matching pattern '%s':\n%s".formatted(
                        query, reasoning, pattern, String.join("\n", matchingClasses))));
                String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
                io.spin("Filtering very large substring search result");

                // Extract mentions of the class names from the response
                var relevantClasses = extractMatches(response, matchingClasses);

                logger.debug("Filtered substring search results: {} (from {})", relevantClasses.size(), matchingClasses.size());
                return "Relevant classes with content matching pattern: " + String.join(", ", relevantClasses);
            } else {
                // Return all classes without filtering
                return "Classes with content matching pattern: " + String.join(", ", matchingClasses);
            }
        } catch (Exception e) {
            logger.error("Error searching file contents", e);
            return "Error searching file contents: " + e.getMessage();
        }
    }

    @Tool("Abort the search process when you determine the question is not relevant to this codebase or when an answer cannot be found. Use this as a last resort when you're confident no useful answer can be provided.")
    public String abort(
        @P(value = "Explanation of why the question cannot be answered or is not relevant to this codebase")
        String explanation
    ) {
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        logger.debug("Search aborted: {}", explanation);

        return "SEARCH ABORTED: " + explanation;
    }

    /**
     * Represents a single tool execution request and its result.
     */
    public class ToolCall {
        private final ToolExecutionRequest request;
        private String result; // initially null

        public ToolCall(ToolExecutionRequest request) {
            this(request, null);
        }

        public ToolCall(ToolExecutionRequest request, String result) {
            this.request = request;
            this.result = result;
        }

        public ToolExecutionRequest getRequest() {
            return request;
        }

        /**
         * Executes the tool call by using reflection.
         */
        public String execute() {
            if (result != null) {
                return result;
            }
            try {
                // Use TER's tool name as the method name
                String methodName = request.name();
                java.lang.reflect.Method method = getMethodByName(methodName);

                // Parse the JSON arguments into a map
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> arguments = mapper.readValue(request.arguments(), new TypeReference<>() {});

                // Prepare arguments array from method parameters
                var params = method.getParameters();
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    String paramName = params[i].getName(); // requires compilation with -parameters
                    Object value = arguments.get(paramName);
                    if (value == null) {
                        throw new IllegalArgumentException("Missing required parameter: " + paramName);
                    }
                    args[i] = value;
                }

                // Invoke the method
                Object res = method.invoke(SearchAgent.this, args);
                result = res != null ? res.toString() : "";
                return result;
            } catch (Exception e) {
                logger.error("Tool method invocation error", e);
                result = "Error: " + e.getMessage();
                return result;
            }
        }

        @Override
        public String toString() {
            return "ToolCall{" +
                    "toolName=" + request.name() +
                    ", arguments=" + request.arguments() +
                    ", result=" + (result != null ? "'" + result + "'" : "null") +
                    '}';
        }
    }
}
