package io.github.jbellis.brokk.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent that iteratively explores a codebase using specific tools
 * to extract information relevant to the *development* build process.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Llm llm;
    private final ToolRegistry toolRegistry;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    // Field to store the result from the reportBuildDetails tool
    private BuildDetails reportedDetails = null;
    // Field to store the reason from the abortBuildDetails tool
    private String abortReason = null;

    public BuildAgent(Llm llm, ToolRegistry toolRegistry) {
        assert llm != null : "coder cannot be null";
        assert toolRegistry != null : "toolRegistry cannot be null";
        this.llm = llm;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or null if the process fails or is interrupted.
     */
    public BuildDetails execute() throws InterruptedException {
        // 1. Initial step: List files in the root directory to give the agent a starting point
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                                                                  .name("listFiles")
                                                                  .arguments("{\"directoryPath\": \".\"}") // Request root dir
                                                                  .build();
        ToolExecutionResult initialResult = toolRegistry.executeTool(this, initialRequest);
        ToolExecutionResultMessage initialResultMessage = initialResult.toExecutionResultMessage();

        // Add the initial result to history (no AI request for this one)
        chatHistory.add(initialResultMessage);
        logger.debug("Initial tool result added to history: {}", initialResultMessage.text());

        // 2. Iteration Loop
        while (true) {
            // 3. Build Prompt
            List<ChatMessage> messages = buildPrompt();

            // 4. Call LLM
            // Get specifications for ALL tools the agent might use in this turn.
            var tools = new ArrayList<>(toolRegistry.getRegisteredTools(List.of("listFiles", "searchFilenames", "searchSubstrings", "getFileContents")));
            if (chatHistory.size() > 1) {
                // allow terminal tools
                tools.addAll(toolRegistry.getTools(this, List.of("reportBuildDetails", "abortBuildDetails")));
            }

            // Call the Coder to get the LLM's response, including potential tool calls
            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages, tools, ToolChoice.REQUIRED, false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Unexpected request cancellation in build agent");
                return null;
            }

            if (result.error() != null) {
                logger.error("LLM error in BuildInfoAgent: " + result.error().getMessage());
                return null;
            }
            var response = result.chatResponse();
            if (response == null || response.aiMessage() == null || !response.aiMessage().hasToolExecutionRequests()) {
                // This shouldn't happen with ToolChoice.REQUIRED and Coder retries, but handle defensively.
                logger.error("LLM response did not contain expected tool call in BuildInfoAgent.");
                return null;
            }

            var aiMessage = ToolRegistry.removeDuplicateToolRequests(response.aiMessage());
            chatHistory.add(aiMessage); // Add AI request message to history

            // 5. Process Tool Execution Requests
            var requests = aiMessage.toolExecutionRequests();
            logger.debug("LLM requested {} tools", requests.size());

            // Prioritize terminal actions (report or abort)
            ToolExecutionRequest reportRequest = null;
            ToolExecutionRequest abortRequest = null;
            List<ToolExecutionRequest> otherRequests = new ArrayList<>();

            for (var request : requests) {
                String toolName = request.name();
                logger.debug("Processing requested tool: {} with args: {}", toolName, request.arguments());
                if (toolName.equals("reportBuildDetails")) {
                    reportRequest = request;
                } else if (toolName.equals("abortBuildDetails")) {
                    abortRequest = request;
                } else {
                    otherRequests.add(request);
                }
            }

            // 6. Execute Terminal Actions via ToolRegistry (if any)
            if (reportRequest != null) {
                var terminalResult = toolRegistry.executeTool(this, reportRequest);
                if (terminalResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    assert reportedDetails != null;
                    return reportedDetails;
                } else {
                    // Tool execution failed
                    logger.warn("reportBuildDetails tool execution failed. Error: {}", terminalResult.resultText());
                    chatHistory.add(terminalResult.toExecutionResultMessage());
                    continue;
                }
            } else if (abortRequest != null) {
                var terminalResult = toolRegistry.executeTool(this, abortRequest);
                if (terminalResult.status() == ToolExecutionResult.Status.SUCCESS) {
                    assert abortReason != null;
                    return null;
                } else {
                    // Tool execution failed
                    logger.warn("abortBuildDetails tool execution failed. Error: {}", terminalResult.resultText());
                    chatHistory.add(terminalResult.toExecutionResultMessage());
                    continue;
                }
            }

            // 7. Execute Non-Terminal Tools
            // Only proceed if no terminal action was requested this turn
            for (var request : otherRequests) {
                String toolName = request.name();
                logger.debug(String.format("Agent action: %s (%s)", toolName, request.arguments()));
                ToolExecutionResult execResult = toolRegistry.executeTool(this, request);
                ToolExecutionResultMessage resultMessage = execResult.toExecutionResultMessage();

                // Record individual tool result history
                chatHistory.add(resultMessage);
                logger.debug("Tool result added to history: {}", resultMessage.text());
            }
        }
    }

    /**
     * Build the prompt for the LLM, including system message and history.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        // System Prompt
        messages.add(new SystemMessage("""
        You are an agent tasked with finding build information for the *development* environment of a software project.
        Your goal is to identify dependencies, plugins, repositories, development profile details, key build commands (clean, compile/build, test all, test specific), how to invoke those commands correctly, and the main application entry point.
        Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific configurations unless they are the only ones available.

        Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files.
        The information you gather will be handed off to agents that do not have access to these files, so
        make sure your instructions are comprehensive.
        
        Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
        """.stripIndent()));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(new UserMessage("Gather the development build details based on the project files and previous findings. Use the available tools to explore and collect the information, then report it using 'reportBuildDetails'."));

        return messages;
    }

    @Tool(value = "Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
    public String reportBuildDetails(
            @P("List of build files involved in the build, including module build files") List<String> buildfiles,
            @P("List of identified third-party dependencies") List<String> dependencies,
            @P("Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes .") String buildLintCommand,
            @P("Command to run all tests") String testAllCommand,
            @P("""
                Instructions and details about the build process, including environment configurations
                and any idiosyncracies observed. Include information on how to run other test configurations, especially 
                individual tests but also at other levels e.g. package or namespace;
                also include information on other pre-commit tools like code formatters or static analysis tools.
                     """) String instructions
        ) {
            // Construct the BuildDetails object from the parameters and store it in the agent's field
            this.reportedDetails = new BuildDetails(buildfiles, dependencies, buildLintCommand, testAllCommand, instructions);
            logger.debug("reportBuildDetails tool executed, details captured.");
            return "Build details report received and processed.";
        }

    @Tool(value = "Abort the process if you cannot determine the build details or the project structure is unsupported.")
    public String abortBuildDetails(
            @P("Explanation of why the build details cannot be determined") String explanation
         ) {
             // Store the explanation in the agent's field
             this.abortReason = explanation;
             logger.debug("abortBuildDetails tool executed with explanation: {}", explanation);
             return "Abort signal received and processed.";
        }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(
            List<String> buildfiles,
            List<String> dependencies,
            String buildLintCommand,
            String testAllCommand,
            String instructions
    ) {
        public static final BuildDetails EMPTY = new BuildDetails(List.of(), List.of(), "", "", "");
    }
}
