package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import scala.Tuple2;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SearchAgent implements an iterative, agentic approach to code search.
 * It uses multiple steps of searching and reasoning to find code elements relevant to a query.
 */
public class SearchAgent {
    private final Logger logger = LogManager.getLogger(SearchAgent.class);
    private static final int TOKEN_BUDGET = 64000; // 64K context window for models like R1
    private static final int MAX_STEPS = 20;
    private static final int MAX_SUB_QUERIES = 3;

    private final Analyzer analyzer;
    private final ContextManager contextManager;
    private final Coder coder;
    private final ConsoleIO io;

    // Budget and action control state
    private int badAttempts = 0;
    private static final int MAX_BAD_ATTEMPTS = 3;
    private boolean allowReflect = true;
    private boolean allowSearch = true;
    private boolean allowSkeleton = true;
    private boolean allowClass = true;
    private boolean allowMethod = true;
    private boolean allowPagerank = true;
    private boolean allowAnswer = true;

    /**
     * Enum representing possible search actions.
     */
    public enum Action {
        DEFINITIONS((agent, boundAction) -> {
            String pattern = boundAction.getParameterString("pattern");
            String reasoning = boundAction.getParameterString("reasoning");
            return agent.executeDefinitionsSearch(pattern, reasoning);
        }, "Searching for symbols"),
        USAGES((agent, boundAction) -> {
            String symbol = boundAction.getParameterString("symbol");
            String reasoning = boundAction.getParameterString("reasoning");
            return agent.executeUsageSearch(symbol, reasoning);
        }, "Finding usages"),
        PAGERANK((agent, boundAction) -> {
            @SuppressWarnings("unchecked")
            List<String> classList = (List<String>) boundAction.getParameter("classList");
            String reasoning = boundAction.getParameterString("reasoning");
            return agent.executePageRankSearch(classList, reasoning);
        }, "Finding related code"),
        SKELETON((agent, boundAction) -> {
            String className = boundAction.getParameterString("className");
            return agent.executeSkeletonSearch(className);
        }, "Getting class overview"),
        CLASS((agent, boundAction) -> {
            String className = boundAction.getParameterString("className");
            String reasoning = boundAction.getParameterString("reasoning");
            return agent.executeClassSearch(className, reasoning);
        }, "Fetching class source"),
        METHOD((agent, boundAction) -> {
            String methodName = boundAction.getParameterString("methodName");
            return agent.executeMethodSearch(methodName);
        }, "Fetching method source"),
        REFLECT((agent, boundAction) -> {
            List<String> subQueries = (List<String>) boundAction.getParameter("subQueries");
            return agent.executeReflect(subQueries);
        }, "Breaking down the query"),
        ANSWER((agent, boundAction) -> {
            String explanation = boundAction.getParameterString("explanation");
            return agent.executeAnswer(explanation);
        }, "Answering the question"),
        MALFORMED(null, "Incorrectly formatted action");

        private final BiFunction<SearchAgent, BoundAction, String> executor;
        private final String explanation;

        Action(BiFunction<SearchAgent, BoundAction, String> executor, String explanation) {
            this.executor = executor;
            this.explanation = explanation;
        }

        public String getValue() {
            return name().toLowerCase();
        }

        public BiFunction<SearchAgent, BoundAction, String> getExecutor() {
            return executor;
        }
        
        public String getExplanation() {
            return explanation;
        }

        public static Action fromString(String text) {
            for (Action action : Action.values()) {
                if (action.name().equalsIgnoreCase(text)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("Unrecognized action: " + text);
        }
    }

    // Search state
    private final String originalQuery;
    private final Deque<String> gapQueries = new ArrayDeque<>();
    private final List<String> processedQueries = new ArrayList<>();
    private final List<BoundAction> actionHistory = new ArrayList<>();
    private final List<Tuple2<String, String>> knowledge = new ArrayList<>();

    private int currentTokenUsage = 0;
    private int totalSteps = 0;

    public SearchAgent(String query, ContextManager contextManager, Coder coder, ConsoleIO io) {
        this.originalQuery = query;
        this.contextManager = contextManager;
        this.analyzer = contextManager.getAnalyzer();
        this.coder = coder;
        this.io = io;
    }

    private String currentQuery() {
        return gapQueries.peek();
    }

    /**
     * Execute the search process, iterating through queries until completion.
     * @return The final set of discovered code units
     */
    public String execute() {
        // Initialize
        gapQueries.add(originalQuery);
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
            messages.add(new UserMessage("<query>%s</query>\n\n".formatted(originalQuery) + contextWithClasses));
            String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
            knowledge.add(new Tuple2<>("Initial context", response));
        }

        io.spin("Exploring: " + originalQuery);
        while (totalSteps < MAX_STEPS && currentTokenUsage < TOKEN_BUDGET && !gapQueries.isEmpty()) {
            totalSteps++;
            processedQueries.add(currentQuery());

            // Reset action controls for this step
            resetActionControls();

            // Special handling based on previous steps
            updateActionControlsBasedOnContext();

            // Decide what action to take for this query
            var steps = determineNextActions();
            String paramInfo = getHumanReadableParameter(steps.getFirst());
            String spinMessage = steps.getFirst().action.explanation + (paramInfo.isBlank() ? "" : " (" + paramInfo + ")");
            io.spin(spinMessage);
            logger.debug("{}; budget: {}/{}", spinMessage, currentTokenUsage, TOKEN_BUDGET);
            logger.debug("Actions: {}", steps);

            // Execute the actions
            var results = steps.stream().parallel().map(step -> {
                var actionWithResult = executeAction(step);
                logger.debug("Result: {}", actionWithResult.result());
                return actionWithResult;
            }).toList();
            logger.debug("Query queue: {}", gapQueries);

            // Check if we should terminate
            if (gapQueries.isEmpty()) {
                logger.debug("Search complete after answering original query");
                assert steps.size() == 1 : steps;
                assert steps.getFirst().action == Action.ANSWER;
                return results.getFirst().result();
            }

            // Add the step to the history
            actionHistory.addAll(results);
        }

        logger.debug("Search complete after reaching max steps or budget");
        return "No answer found within budget";
    }

    /**
     * Reset action controls for each search step.
     */
    private void resetActionControls() {
        allowReflect = true;
        allowSearch = true;
        allowSkeleton = true;
        allowClass = true;
        allowMethod = true;
        allowPagerank = true;
        allowAnswer = true;
    }

    /**
     * Update action controls based on current search context.
     */
    private void updateActionControlsBasedOnContext() {
        // If we just started, encourage reflection
        if (actionHistory.isEmpty() && currentQuery().equals(originalQuery)) {
            allowAnswer = false; // Don't allow finishing immediately
        }

        allowReflect = actionHistory.isEmpty() || actionHistory.getLast().action() != Action.REFLECT;

        // TODO more context-based control
    }

    /**
     * Determine the next action to take for the current query.
     */
    private List<BoundAction> determineNextActions() {
        List<ChatMessage> messages = buildPrompt();

        // Ask LLM for next action with tools
        var tools = createToolSpecifications();
        var response = coder.sendStreamingWithTools(coder.models.editModel(), messages, false, tools);
        currentTokenUsage += response.tokenUsage().inputTokenCount();

        // Parse response into potentially multiple actions
        return parseResponse(response.content());
    }
    
    /**
     * Create tool specifications for the LLM based on allowed actions.
     */
    private List<dev.langchain4j.agent.tool.ToolSpecification> createToolSpecifications() {
        List<dev.langchain4j.agent.tool.ToolSpecification> tools = new ArrayList<>();
        
        if (allowSearch) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeDefinitionsSearch")));
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeUsageSearch")));
        }
        
        if (allowPagerank) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executePageRankSearch")));
        }
        
        if (allowSkeleton) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeSkeletonSearch")));
        }
        
        if (allowClass) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeClassSearch")));
        }
        
        if (allowMethod) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeMethodSearch")));
        }
        
        if (allowReflect) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeReflect")));
        }
        
        if (allowAnswer) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("executeAnswer")));
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
        You will be given access to tools that can help you analyze code.
        """.stripIndent());

        // Add beast mode if we're out of time or we've had too many bad attempts
        if (currentTokenUsage > 0.9 * TOKEN_BUDGET || badAttempts >= MAX_BAD_ATTEMPTS) {
            gapQueries.clear();
            gapQueries.add(originalQuery);

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
            allowReflect = false;
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
        if (!originalQuery.equals(currentQuery())) {
            systemPrompt.append("\n<original-query>\n");
            systemPrompt.append(originalQuery);
            systemPrompt.append("\n</original-query>\n");
        }

        // Add information about current query
        systemPrompt.append("\n<current-query>\n");
        systemPrompt.append(currentQuery());
        systemPrompt.append("\n</current-query>\n");

        messages.add(new SystemMessage(systemPrompt.toString()));
        messages.add(new UserMessage("Determine the next action to take to search for code related to: " + currentQuery()));

        return messages;
    }

    /**
     * Parse the LLM response into a list of SearchStep objects.
     * This method handles both direct text responses and tool execution responses.
     * ANSWER tools will be sorted at the end.
     */
    private List<BoundAction> parseResponse(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            return List.of(new BoundAction(Action.MALFORMED, Map.of("unparsed", response.text()), "No tool execution requests found"));
        }

        // Process each tool execution request
        var L = response.toolExecutionRequests().stream().map(toolRequest -> {
            String toolName = toolRequest.name();
            try {
                // Get the tool name and map it to an action
                Action action = mapToolNameToAction(toolName);

                // Convert the JSON arguments to a map
                ObjectMapper mapper = new ObjectMapper();
                String argumentsJson = toolRequest.arguments();
                Map<String, Object> arguments = mapper.readValue(argumentsJson, new TypeReference<>() {});

                // Create a new BoundAction with the parsed parameters
                return new BoundAction(action, arguments, null);
            } catch (Exception e) {
                logger.error("Failed to parse tool response: {}", e.getMessage());
                return new BoundAction(Action.MALFORMED, Map.of("error", e.getMessage()), "Error parsing tool execution request for " + toolName);
            }
        }).toList();
        // if we have an ANSWER action, just return that
        if (L.stream().anyMatch(t -> t.action == Action.ANSWER)) {
            return List.of(L.stream().filter(t -> t.action == Action.ANSWER).findFirst().orElseThrow());
        }
        return L;
    }
    
    /**
     * Maps a tool name to a SearchAgent Action.
     */
    private Action mapToolNameToAction(String toolName) {
        return switch (toolName) {
            case "executeDefinitionsSearch" -> Action.DEFINITIONS;
            case "executeUsageSearch" -> Action.USAGES;
            case "executePageRankSearch" -> Action.PAGERANK;
            case "executeSkeletonSearch" -> Action.SKELETON;
            case "executeClassSearch" -> Action.CLASS;
            case "executeMethodSearch" -> Action.METHOD;
            case "executeReflect" -> Action.REFLECT;
            case "executeAnswer" -> Action.ANSWER;
            default -> throw new IllegalArgumentException("Unknown tool name: " + toolName);
        };
    }

    @NotNull
    private List<String> getListOrEmpty(Map<String, Object> parsed, String key) {
        List<String> subQueries = new ArrayList<>();
        Object subQueriesObj = parsed.get(key);
        if (!(subQueriesObj instanceof List)) {
            throw new IllegalArgumentException(String.format("%s is not a list", key));
        }

        @SuppressWarnings("unchecked")
        List<Object> subQueriesList = (List<Object>) subQueriesObj;
        if (!subQueriesList.isEmpty()) {
            subQueries = subQueriesList.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.trim().isEmpty())
                    .collect(Collectors.toList());
        }
        return subQueries;
    }

    /**
     * Execute the selected action for the current step.
     */
    private BoundAction executeAction(BoundAction step) {
        // Skip execution if the result is already present
        if (step.result() != null) {
            logger.debug("Skipping execution of {}", step);
            return step;
        }

        try {
            String result = step.execute();
            return step.withResult(result);
        } catch (Exception e) {
            logger.error("Action execution error", e);
            return step.withResult("Error: " + e.getMessage());
        }
    }

    @Tool("Search for class/method/field definitions using a regular expression pattern. Use this when you need to find symbols matching a pattern.")
    public String executeDefinitionsSearch(
            @P(value = "Regex pattern to search for code symbols. Should not contain whitespace. Don't include ^ or $ as they're implicit. Use explicit wildcarding for substrings (e.g., .*Value, Abstract.*, [a-z]*DAO).", required = true) String pattern,
            @P(value = "Reasoning about why this pattern is relevant to the query", required = true) String reasoning
    ) {
        if (pattern.isBlank()) {
            return "Cannot search definitions: pattern is empty";
        }
        if (reasoning.isBlank()) {
            return "Cannot search definitions: reasoning is empty";
        }

        var definitions = analyzer.getDefinitions(pattern);
        if (definitions.isEmpty()) {
            return "No definitions found for pattern: " + pattern;
        }

        logger.debug("Raw definitions: {}", definitions);

        // Ask coder to determine which definitions are potentially relevant
        StringBuilder definitionsStr = new StringBuilder();
        for (CodeUnit definition : definitions) {
            definitionsStr.append(definition.reference()).append("\n");
        }

        // Get reasoning if available
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are helping evaluate which code definitions are relevant to a user query. " +
                                       "Review the list of definitions and select the ones most relevant to the query and " +
                                       "to your previous reasoning."));
        messages.add(new UserMessage("Query: %s\nReasoning:%s\nDefinitions found:\n%s".formatted(currentQuery(), reasoning, definitionsStr)));
        String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        // Extract mentions of the definitions from the response
        Set<String> relevantDefinitions = new HashSet<>();
        for (CodeUnit definition : definitions) {
            String ref = definition.reference();
            // Look for the reference with word boundaries to avoid partial matches
            var p = Pattern.compile("\\b" + Pattern.quote(ref) + "\\b");
            var matcher = p.matcher(response);
            if (matcher.find()) {
                relevantDefinitions.add(ref);
            }
        }

        // Include the matches in the result
        var relevant = new ArrayList<String>();

        // Add the relevant definitions first
        for (CodeUnit definition : definitions) {
            if (relevantDefinitions.contains(definition.reference())) {
                relevant.add(definition.reference());
            }
        }

        return "Relevant symbols: " + String.join(", ", relevant);
    }

    /**
     * Search for usages of a symbol.
     */
    @Tool("Find where a symbol is used in code. Use this to discover how a class, method, or field is actually used throughout the codebase.")
    public String executeUsageSearch(
        @P(value = "Fully qualified symbol name (package name, class name, optional member name) to find usages for", required = true) String symbol,
        @P(value = "Reasoning about what information you're hoping to find in these usages", required = true) String reasoning
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

        // Process the usages to get formatted result
        String processedUsages = AnalyzerWrapper.processUsages(analyzer, uses).toString();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are helping evaluate which code usages are relevant to a user query. " +
                                       "Review the following code usages and select ONLY the relevant chunks that directly " +
                                       "address the query. Output the FULL TEXT of the relevant code chunks."));
        messages.add(new UserMessage("Query: %s\nReasoning: %s\nUsages found for %s:\n%s".formatted(
                currentQuery(), reasoning, symbol, processedUsages)));
        String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        return "Relevant usages of " + symbol + ":\n\n" + response;
    }

    /**
     * Find related code using PageRank.
     */
    @Tool("Find related code units using PageRank algorithm. Use this when you've made some progress but got stuck, or when you're almost done and want to double-check that you haven't missed anything.")
    public String executePageRankSearch(
        @P(value = "List of fully qualified class names to use as seeds for PageRank. Use classes you've already found that seem relevant.", required = true) List<String> classList,
        @P(value = "Reasoning about what related code you're hoping to discover", required = true) String reasoning
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

        // Build a string with the top 100 results to pass to the LLM for relevance filtering
        var pageRankUnits = pageRankResults.stream()
                .limit(100)
                .collect(Collectors.joining("\n"));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are helping evaluate which code units from PageRank results are relevant to a user query. " +
                                       "Review the list of code units and select the ones that are relevant to the query and " +
                                       "to your previous reasoning. Output just the fully qualified names of relevant units."));
        messages.add(new UserMessage("Query: %s\nReasoning: %s\nPageRank results:\n%s".formatted(
                currentQuery(), reasoning, pageRankUnits)));
        String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        // Extract mentions of the PageRank results from the response
        var relevantUnits = new ArrayList<String>();
        for (var ref : pageRankResults) {
            // Look for the reference with word boundaries to avoid partial matches
            var p = Pattern.compile("\\b" + Pattern.quote(ref) + "\\b");
            var matcher = p.matcher(response);
            if (matcher.find()) {
                relevantUnits.add(ref);
            }
        }

        if (relevantUnits.isEmpty()) {
            return pageRankResults.stream().limit(10).collect(Collectors.joining(", "));
        }
        return String.join(", ", relevantUnits);
    }

    /**
     * Get the skeleton (structure) of a class.
     */
    @Tool("Get an overview of a class's contents, including fields and method signatures. Use this to understand a class's structure without fetching its full source code.")
    public String executeSkeletonSearch(
        @P(value = "Fully qualified class name to get the skeleton structure for", required = true) String className
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
    public String executeClassSearch(
        @P(value = "Fully qualified class name to retrieve the full source code for", required = true) String className,
        @P(value = "Reasoning about what specific implementation details you're looking for in this class", required = true) String reasoning
    ) {
        if (className.isBlank()) {
            return "Cannot get class source: class name is empty";
        }

        String classSource = analyzer.getClassSource(className);
        if (classSource == null || classSource.isEmpty()) {
            return "No source found for class: " + className;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are helping evaluate which parts of a class source are relevant to a user query. " +
                                       "Review the following class source and select ONLY the relevant portions that directly " +
                                       "address the user's query and/or your own reasoning. Output the FULL TEXT of the relevant code chunks. When in doubt, include the chunk."));
        messages.add(new UserMessage("Query: %s\nReasoning: %s\nClass source for %s:\n%s".formatted(
                currentQuery(), reasoning, className, classSource)));
        String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        return "Relevant portions of " + className + ":\n\n" + response;
    }

    /**
     * Get the source code of a method.
     */
    @Tool("Get the source code of a specific method. Use this to examine the implementation of a particular method without retrieving the entire class.")
    public String executeMethodSearch(
        @P(value = "Fully qualified method name (package name, class name, method name) to retrieve source for", required = true) String methodName
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

    /**
     * Generate sub-queries to further explore the search space.
     */
    @Tool("Break down the complex query into smaller, more targeted sub-queries. Use this when the current query is too broad or contains multiple distinct questions that should be explored separately.")
    public String executeReflect(
        @P(value = "List of focused sub-queries that together address the current query. Each sub-query should be more specific than the original.", required = true) List<String> subQueries
    ) {
        if (subQueries.isEmpty()) {
            return "No sub-queries generated";
        }

        // Add the sub-queries to the queue
        int i = 0;
        for (String query : subQueries) {
            // TODO semantic deduplications
            if (!processedQueries.contains(query) && !gapQueries.contains(query)) {
                gapQueries.offerFirst(query);
                logger.debug("Adding new query: {}", query);
                i++;
            } else {
                logger.debug("Skipping duplicate query: {}", query);
            }
            if (i >= MAX_SUB_QUERIES) {
                break;
            }
        }

        return String.join(", ", subQueries);
    }

    /**
     * Answer the current query and remove it from the queue.
     */
    @Tool("Provide a final answer to the current query and remove it from the queue. Use this when you have enough information to fully address the query.")
    public String executeAnswer(
        @P(value = "Comprehensive explanation that answers the current query. Include relevant source code snippets and explain how they relate to the query.", required = true) String explanation
    ) {
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        String currentQuery = gapQueries.poll();
        logger.debug("Answering query: {}", currentQuery);
        logger.debug("Answer: {}", explanation);

        // Store the answer in our collection
        knowledge.add(new Tuple2<>(currentQuery, explanation));

        return explanation;
    }

    /**
     * Estimate token count of messages or text.
     * This is a very rough approximation - 1 token ~= 4 characters in English.
     */
    private int estimateTokenCount(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                total += estimateTokenCount(((SystemMessage) message).text());
            } else if (message instanceof UserMessage) {
                total += estimateTokenCount(((UserMessage) message).text());
            } else if (message instanceof AiMessage) {
                total += estimateTokenCount(((AiMessage) message).text());
            }
        }
        return total;
    }

    private int estimateTokenCount(String text) {
        return text.length() / 4;
    }

    private String getStringOrEmpty(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private String getHumanReadableParameter(BoundAction step) {
        // Reflect is omitted on purpose. Others show key info.
        return switch (step.action()) {
            case DEFINITIONS -> {
                String pattern = step.getParameterString("pattern");
                yield (pattern == null || pattern.isBlank() ? "?" : pattern);
            }
            case USAGES -> {
                String symbol = step.getParameterString("symbol");
                yield (symbol == null || symbol.isBlank() ? "?" : symbol);
            }
            case SKELETON -> {
                String className = step.getParameterString("className");
                yield (className == null || className.isBlank() ? "?" : className);
            }
            case CLASS -> {
                String className = step.getParameterString("className");
                yield (className == null || className.isBlank() ? "?" : className);
            }
            case METHOD -> {
                String methodName = step.getParameterString("methodName");
                yield (methodName == null || methodName.isBlank() ? "?" : methodName);
            }
            case ANSWER -> "finalizing";  // Keep it concise
            default -> "";                // Reflect or malformed, omit
        };
    }

    /**
     * Represents a single step in the search process with bound parameters.
     */
    public class BoundAction {
        private final Action action;
        private final Map<String, Object> parameters;
        private final String result;

        public BoundAction(Action action, Map<String, Object> parameters, String result) {
            this.action = action;
            this.parameters = parameters;
            this.result = result;
        }

        public Action action() {
            return action;
        }

        public String result() {
            return result;
        }

        /**
         * Execute this action and return the result.
         */
        public String execute() {
            return action.getExecutor().apply(SearchAgent.this, this);
        }

        @Override
        public String toString() {
            return "SearchStep{" +
                    "action=" + action +
                    ", parametersJson='" + parameters + '\'' +
                    (result != null ? ", result='" + result + '\'' : "") +
                    '}';
        }

        public BoundAction withResult(String result) {
            return new BoundAction(action, parameters, result);
        }

        private Object getParameter(String paramName) {
            return parameters.get(paramName);
        }

        private String getParameterString(String paramName) {
            return (String) parameters.get(paramName);
        }
    }
}
