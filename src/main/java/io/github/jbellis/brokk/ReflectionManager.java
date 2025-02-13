package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.prompts.BuildPrompts;

import java.util.ArrayList;
import java.util.List;

/**
 * The ReflectionManager orchestrates whether a second "reflected" pass is required,
 * and accumulates a reflection count. We keep it separate from Coder to avoid bloating.
 */
class ReflectionManager {
    private static final int MAX_PARSE_ATTEMPTS = 3;

    private final IConsoleIO io;
    private int parseErrorAttempts;
    private List<String> buildErrors;
    private final Coder coder;

    public ReflectionManager(IConsoleIO io, Coder coder) {
        this.io = io;
        this.coder = coder;
        this.parseErrorAttempts = 0;
        this.buildErrors = new ArrayList<>();
    }

    /**
     * Inspect the user's last prompt and the LLM's response,
     * see if there's a reason to re-ask with a "reflection" query.
     * The Python logic might check for newly referenced files,
     * lint/test errors, etc.
     *
     * For example, if we see "Test errors" in the LLM output,
     * we can ask user if they want to fix them => generate reflection query.
     */
    public String runBuild(IContextManager contextManager) {
        var result = contextManager.runBuild();
        if (result.status() == ContextManager.OperationStatus.SUCCESS) {
            buildErrors.clear(); // Reset on successful build
            return "";
        }

        assert result.status() == ContextManager.OperationStatus.ERROR;
        io.toolError(result.message());
        buildErrors.add(result.message());

        // Return a reflection query with all build error history
        StringBuilder query = new StringBuilder("The build failed. Here is the history of build attempts:\n\n");
        for (int i = 0; i < buildErrors.size(); i++) {
            query.append("=== Attempt ").append(i + 1).append(" ===\n")
                    .append(buildErrors.get(i))
                    .append("\n\n");
        }
        query.append("Please fix these build errors.");
        return query.toString();
    }

    /**
     * getReflectionMessage is responsible for displaying something to the user
     */
    String getReflectionMessage(EditBlock.ParseResult parseResult, List<FailedBlock> failedBlocks, List<EditBlock.SearchReplaceBlock> blocks, IContextManager cm) {
        // Handle parse errors and failed blocks
        if (parseResult.parseError() != null || !failedBlocks.isEmpty()) {
            incrementParseErrors();

            var reflectionMsg = new StringBuilder();
            if (parseResult.parseError() != null) {
                io.toolErrorRaw(parseResult.parseError());
                reflectionMsg.append(parseResult.parseError()).append("\n");
            }
            if (!failedBlocks.isEmpty()) {
                var failedApplyMessage = handleFailedBlocks(failedBlocks, blocks.size() - failedBlocks.size());
                io.toolErrorRaw(failedApplyMessage);
                reflectionMsg.append(failedApplyMessage);
            }

            return reflectionMsg.toString();
        }

        // Reset parse error counter when there are no parse errors
        resetParseErrors();
        if (blocks.isEmpty()) {
            return "";
        }

        // If no parse/apply errors, try running build
        return runBuild(cm);
    }

    // responsible for outputting the reason we stopped
    public boolean shouldContinue() {
        // If we have parse errors, limit to 3 attempts
        if (parseErrorAttempts > 0) {
            if (parseErrorAttempts < MAX_PARSE_ATTEMPTS) {
                return true;
            }
            io.toolOutput("Parse retry limit reached, stopping.");
            return false;
        }
        
        // For build errors, check if we're making progress
        if (buildErrors.size() > 1) {
            if (isBuildProgressing(buildErrors)) {
                return true;
            }
            io.toolOutput("Build errors are not improving, stopping.");
            return false;
        }
        
        return true;
    }

    /**
     * Helper to get a quick response from the LLM without streaming
     */
    public boolean isBuildProgressing(List<String> buildResults) {
        var messages = BuildPrompts.instance.collectMessages(buildResults);
        var response = coder.sendMessage(messages);

        // Keep trying until we get one of our expected tokens
        while (!response.contains("BROKK_PROGRESSING") && !response.contains("BROKK_FLOUNDERING")) {
            messages = new ArrayList<>(messages);
            messages.add(new AiMessage(response));
            messages.add(new UserMessage("Please indicate either BROKK_PROGRESSING or BROKK_FLOUNDERING."));
            response = coder.sendMessage(messages);
        }

        return response.contains("BROKK_PROGRESSING");
    }


    private void incrementParseErrors() {
        parseErrorAttempts++;
    }

    private void resetParseErrors() {
        parseErrorAttempts = 0;
    }

    public enum EditBlockFailureReason {
        FILE_NOT_FOUND,
        NO_MATCH,
        NO_FILENAME,
        IO_ERROR
    }

    public record FailedBlock(EditBlock.SearchReplaceBlock block, EditBlockFailureReason reason, String suggestions) {
        public FailedBlock(EditBlock.SearchReplaceBlock block, EditBlockFailureReason reason) {
            this(block, reason, null);
        }
    }

    /**
     * Generates a reflection message for failed edit blocks using the same format as before
     */
    public String handleFailedBlocks(List<FailedBlock> failed, int succeededCount) {
        if (failed.isEmpty()) {
            return "";
        }

        // build an error message
        int count = failed.size();
        boolean singular = (count == 1);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(count).append(" SEARCH/REPLACE block")
          .append(singular ? " " : "s ")
          .append("failed to match!\n");

        for (var f : failed) {
            String fname = (f.block().filename() == null ? "(none)" : f.block().filename());
            sb.append("## Failed to match in file: ").append(fname).append("\n");
            sb.append("<<<<<<< SEARCH\n").append(f.block().beforeText())
              .append("=======\n").append(f.block().afterText())
              .append(">>>>>>> REPLACE\n\n");
            
            if (f.suggestions() != null) {
                sb.append(f.suggestions()).append("\n");
            }
        }

        sb.append("The SEARCH text must match exactly (with allowance for fuzzy matching) the lines in the file.\n");
        if (succeededCount > 0) {
            sb.append("\n# The other ").append(succeededCount).append(" SEARCH/REPLACE block")
              .append(succeededCount == 1 ? " was" : "s were").append(" applied successfully.\n");
            sb.append("Don't re-send them. Just fix the failing blocks above.\n");
        }

        return sb.toString();
    }
}
