package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
        DEFINITIONS(SearchAgent::executeDefinitionsSearch, "Searching for symbols"),
        USAGES(SearchAgent::executeUsageSearch, "Finding usages"),
        PAGERANK(SearchAgent::executePageRankSearch, "Finding related code"),
        SKELETON(SearchAgent::executeSkeletonSearch, "Getting class overview"),
        CLASS(SearchAgent::executeClassSearch, "Fetching class source"),
        METHOD(SearchAgent::executeMethodSearch, "Fetching method source"),
        REFLECT(SearchAgent::executeReflect, "Breaking down the query"),
        ANSWER(SearchAgent::executeAnswer, "Answering the question"),
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
            return REFLECT; // Default to reflect if unknown
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
            BoundAction step = determineNextAction();
            String paramInfo = getHumanReadableParameter(step);
            String spinMessage = step.action.explanation + (paramInfo.isBlank() ? "" : " (" + paramInfo + ")");
            io.spin(spinMessage);
            logger.debug("{}; budget: {}/{}", spinMessage, currentTokenUsage, TOKEN_BUDGET);
            logger.debug("Action: {}", step);

            // Execute the action
            var actionWithResult = executeAction(step);
            logger.debug("Result: {}", actionWithResult.result());

            // Track success/failure for action control
            // TODO
//            if (!success) {
//                badAttempts++;
//                io.toolOutput("Bad attempt #" + badAttempts + " - adjusting strategy");
//
//                // Disable the failed action for next step
//                disableAction(step.action());
//            } else {
//                badAttempts = 0; // Reset on success
//            }

            // Debug output
            logger.debug("Query queue: {}", gapQueries);

            // Check if we should terminate
            if (gapQueries.isEmpty()) {
                logger.debug("Search complete after answering original query");
                assert step.action == Action.ANSWER;
                return actionWithResult.result();
            }

            // Add the step to the history
            actionHistory.add(actionWithResult);
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
    private BoundAction determineNextAction() {
        List<ChatMessage> messages = buildPrompt();

        // Track token usage for the prompt
        int promptTokens = estimateTokenCount(messages);
        currentTokenUsage += promptTokens;

        // Ask LLM for next action
        String response = coder.sendStreaming(coder.models.editModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        // Parse response
        return parseResponse(response);
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
        You have several ways to analyze code:
        """.stripIndent());

        systemPrompt.append("<actions>\n");

        if (allowSearch) {
            systemPrompt.append("""
            <action name=definitions parameters=pattern>
            Search for class/method/field definitions using a regular expression pattern.
             - You can only search for one pattern at a time.
             - You are searching for code symbols so you know that e.g. they never contain whitespace
             - The pattern is implicitly surrounded by ^ and $ as bookends; do NOT include these in your pattern
             - OTOH you *will* need to use explicit wildcarding to match substrings
             - The pattern is also implicitly case-insensitive.
            Examples:
             - get.*Value // Matches methods like getValue, getStringValue, etc.
             - [a-z]*DAO // Matches DAO classes like UserDAO, ProductDAO
             - Abstract.* // Matches classes with Abstract prefix
             - .*Exception // Matches all exception classes
             - .*vec.* // Substring search for `vec`
            </action>
            """.stripIndent());
            systemPrompt.append("""
            <action name=usage parameters=symbol>
            Find where a symbol is used in code
             - Takes a fully-qualified symbol name (package name, class name, optional member name)
            </action>
            """.stripIndent());
        }
        if (allowPagerank) {
            // TODO
            // systemPrompt.append("<action-pagerank>Find related code elements using PageRank</action-pagerank>\n");
        }
        if (allowSkeleton) {
            systemPrompt.append("""
            <action name=skeleton parameters=className>
            Get an overview of a class's contents, including fields and method signatures.
             - Takes a fully-qualified class name
            </action>
            """.stripIndent());
        }
        if (allowClass) {
            systemPrompt.append("""
            <action name=class parameters=className>
            Get the full source code of a class.  This is expensive, so prefer inferring what you need
            from the skeleton or method sources. But if you need the full source, this is available.
             - Takes a fully-qualified class name
            </action>
            """.stripIndent());
        }
        if (allowMethod) {
            systemPrompt.append("""
            <action name=method parameters=methodName>
            Get the source code of a method
             - Takes a fully-qualified method name (package name, class name, method name)
            </action>
            """.stripIndent());
        }
        if (allowReflect) {
            systemPrompt.append("<action-reflect>Break down the query into sub-questions</action-reflect>\n");
        }
        if (allowAnswer) {
            systemPrompt.append("""
            <action name=answer parameters=explanation>
            Provide an answer to the current query and remove it from the queue.
             - Takes the answer to the current query as a string.  Explanations should include relevant source
               code snippets as well as an description of how they relate to the query.
            </action>
            """.stripIndent());
        }

        systemPrompt.append("""
        The code you are analyzing is *stable*.  You will NOT get different results by re-running the same action.
        If an action is in your history already, you do not need to repeat it unless you have new information.
        """.stripIndent());
        
        systemPrompt.append("</actions>\n\n");

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

        systemPrompt.append("""
        Respond with your reasoning and only ONE action in JSON format like this.
        Remember that symbols, class names, and method names must be fully-qualified.
        {
          "reasoning": "[your thought process]"
          "action": "[one of: definitions, usages, pagerank, skeleton, method, reflect, answer]",
          // per-action parameters
          "pattern": "[pattern to search for, if applicable]",
          "symbol": "[specific symbol to find, if applicable]",
          "className": "[class name, if applicable]",
          "methodName": "[method name, if applicable]",
          "subQueries": ["[sub-query1]", "[sub-query2]", ...],
          "explanation": "[the answer to the query]"
        }
        """.stripIndent());

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
     * Parse the LLM response into a SearchStep object.
     */
    private BoundAction parseResponse(String response) {
        // Default values in case parsing fails
        Action action = Action.REFLECT;
        Map<String, Object> parameters = new HashMap<>();

        try {
            // Attempt to find JSON content
            int startIndex = response.indexOf("{");
            int endIndex = response.lastIndexOf("}");

            if (startIndex >= 0 && endIndex > startIndex) {
                String jsonContent = response.substring(startIndex, endIndex + 1);

                // Parse JSON using Jackson
                ObjectMapper mapper = new ObjectMapper();
                var parsed = mapper.readValue(jsonContent, new TypeReference<Map<String, Object>>() {});

                String actionStr = getStringOrEmpty(parsed, "action");
                action = Action.fromString(actionStr);

                // Extract reasoning if available
                String reasoning = getStringOrEmpty(parsed, "reasoning");
                if (!reasoning.isBlank()) {
                    parameters.put("reasoning", reasoning);
                }

                // Extract relevant parameters based on action type
                switch (action) {
                    case DEFINITIONS -> parameters.put("pattern", getStringOrEmpty(parsed, "pattern"));
                    case USAGES -> parameters.put("symbol", getStringOrEmpty(parsed, "symbol"));
                    case SKELETON -> parameters.put("className", getStringOrEmpty(parsed, "className"));
                    case CLASS -> parameters.put("className", getStringOrEmpty(parsed, "className"));
                    case METHOD -> parameters.put("methodName", getStringOrEmpty(parsed, "methodName"));
                    case ANSWER -> parameters.put("explanation", getStringOrEmpty(parsed, "explanation"));
                    case REFLECT -> {
                        // Handle subQueries - convert to JSON string
                        var subQueries = getListOrEmpty(parsed, "subQueries");
                        parameters.put("subQueries", mapper.writeValueAsString(subQueries));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse response {}: {}", response, e.getMessage());
            return new BoundAction(Action.MALFORMED, response, "Failed to parse response: " + e.getMessage());
        }

        // Convert parameters map to a JSON string
        String parameterJson = "{}";
        try {
            ObjectMapper mapper = new ObjectMapper();
            parameterJson = mapper.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize parameters to JSON", e);
        }

        return new BoundAction(action, parameterJson, null);
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

    /**
     * Search for definitions matching a pattern.
     */
    private String executeDefinitionsSearch(BoundAction step) {
        String pattern = step.getParameterValue("pattern");
        if (pattern == null || pattern.isBlank()) {
            return "Cannot search definitions: pattern is empty";
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
        String reasoning = step.getParameterValue("reasoning");
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
    private String executeUsageSearch(BoundAction step) {
        String symbol = step.getParameterValue("symbol");
        if (symbol == null || symbol.isBlank()) {
            return "Cannot search usages: symbol is empty";
        }

        List<CodeUnit> uses = analyzer.getUses(symbol);
        if (uses.isEmpty()) {
            return "No usages found for: " + symbol;
        }

        // Process the usages to get formatted result
        String processedUsages = AnalyzerWrapper.processUsages(analyzer, uses).toString();

        // Get reasoning if available
        String reasoning = step.getParameterValue("reasoning");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are helping evaluate which code usages are relevant to a user query. " +
                                       "Review the following code usages and select ONLY the relevant chunks that directly " +
                                       "address the user's query and/or your own reasoning. Output the FULL TEXT of the relevant code chunks."));
        messages.add(new UserMessage("Query: %s\nReasoning: %s\nUsages found for %s:\n%s".formatted(
                currentQuery(), reasoning, symbol, processedUsages)));
        String response = coder.sendStreaming(coder.models.applyModel(), messages, false);
        currentTokenUsage += estimateTokenCount(response);

        return "Relevant usages of " + symbol + ":\n\n" + response;
    }

    /**
     * Find related code using PageRank.
     */
    private String executePageRankSearch(BoundAction step) {
        // Create map of seeds from discovered units
        HashMap<String, Double> weightedSeeds = new HashMap<>();
        // TODO: Once we track discovered units, add them to the weightedSeeds map with weights

        var pageRankResults = analyzer.getPagerank(weightedSeeds, 10, false);

        if (pageRankResults.isEmpty()) {
            return "No related code found via PageRank";
        }

        StringBuilder result = new StringBuilder();
        result.append("Found ").append(pageRankResults.size()).append(" related code units via PageRank:\n\n");

        AtomicInteger counter = new AtomicInteger(0);
        pageRankResults.stream()
                .limit(10)
                .forEach(pair -> result.append(counter.incrementAndGet())
                                      .append(". ")
                                      .append(pair._1)
                                      .append("\n"));

        return result.toString();
    }

    /**
     * Get the skeleton (structure) of a class.
     */
    private String executeSkeletonSearch(BoundAction step) {
        String className = step.getParameterValue("className");
        if (className == null || className.isBlank()) {
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
    private String executeClassSearch(BoundAction step) {
        String className = step.getParameterValue("className");
        if (className == null || className.isBlank()) {
            return "Cannot get class source: class name is empty";
        }

        String classSource = analyzer.getClassSource(className);
        if (classSource == null || classSource.isEmpty()) {
            return "No source found for class: " + className;
        }

        // Get reasoning if available
        String reasoning = step.getParameterValue("reasoning");
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
    private String executeMethodSearch(BoundAction step) {
        String methodName = step.getParameterValue("methodName");
        if (methodName == null || methodName.isBlank()) {
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
    private String executeReflect(BoundAction step) {
        List<String> subQueries = getSubQueriesFromJson(step.getParameterValue("subQueries"));
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
    private String executeAnswer(BoundAction step) {
        String answer = step.getParameterValue("explanation");
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        String currentQuery = gapQueries.poll();
        logger.debug("Answering query: {}", currentQuery);
        logger.debug("Answer: {}", answer);

        // Store the answer in our collection
        knowledge.add(new Tuple2<>(currentQuery, answer));

        return answer;
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
                String pattern = step.getParameterValue("pattern");
                yield (pattern == null || pattern.isBlank() ? "?" : pattern);
            }
            case USAGES -> {
                String symbol = step.getParameterValue("symbol");
                yield (symbol == null || symbol.isBlank() ? "?" : symbol);
            }
            case SKELETON -> {
                String className = step.getParameterValue("className");
                yield (className == null || className.isBlank() ? "?" : className);
            }
            case CLASS -> {
                String className = step.getParameterValue("className");
                yield (className == null || className.isBlank() ? "?" : className);
            }
            case METHOD -> {
                String methodName = step.getParameterValue("methodName");
                yield (methodName == null || methodName.isBlank() ? "?" : methodName);
            }
            case ANSWER -> "finalizing";  // Keep it concise
            default -> "";                // Reflect or malformed, omit
        };
    }

    /**
     * Helper method to parse subQueries from JSON string.
     */
    private List<String> getSubQueriesFromJson(String subQueriesJson) {
        if (subQueriesJson == null || subQueriesJson.isBlank()) {
            return new ArrayList<>();
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(subQueriesJson, new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("Failed to parse subQueries JSON", e);
            return new ArrayList<>();
        }
    }

    /**
     * Represents a single step in the search process with bound parameters.
     */
    public class BoundAction {
        private final Action action;
        private final String parametersJson;
        private final String result;

        public BoundAction(Action action, String parametersJson, String result) {
            this.action = action;
            this.parametersJson = parametersJson;
            this.result = result;
        }

        public Action action() {
            return action;
        }

        public String parametersJson() {
            return parametersJson;
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
                    ", parametersJson='" + parametersJson + '\'' +
                    (result != null ? ", result='" + result + '\'' : "") +
                    '}';
        }

        public BoundAction withResult(String result) {
            return new BoundAction(action, parametersJson, result);
        }

        /**
         * Helper method to get a parametersJson value from the SearchStep's parametersJson JSON.
         */
        private String getParameterValue(String paramName) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                var params = mapper.readValue(parametersJson(), new TypeReference<Map<String, Object>>() {});
                Object value = params.get(paramName);
                return value != null ? value.toString() : null;
            } catch (Exception e) {
                SearchAgent.this.logger.error("Failed to get parametersJson " + paramName, e);
                return null;
            }
        }
    }
}
