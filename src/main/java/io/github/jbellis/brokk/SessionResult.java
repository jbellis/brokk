package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Represents the outcome of a CodeAgent session, containing all necessary information
 * to update the context history.
 *
 * @param actionDescription A description of the user's goal for the session.
 * @param messages          The list of chat messages exchanged during the session.
 * @param originalContents  A map of project files to their original content before edits.
 * @param finalLlmOutput    The final raw text output from the LLM.
 * @param stopDetails       The reason the session concluded.
 */
public record SessionResult(String actionDescription,
                            List<ChatMessage> messages, // for Task History context
                            Map<ProjectFile, String> originalContents, // for undo
                            Context.ParsedOutput output,
                            StopDetails stopDetails)
{
    public SessionResult {
        assert actionDescription != null;
        assert messages != null;
        assert originalContents != null;
        assert output != null;
        assert stopDetails != null;
    }

    public SessionResult(String actionDescription,
                         List<ChatMessage> messages,
                         List<ChatMessage> uiMessages,
                         Map<ProjectFile, String> originalContents,
                         String outputString,
                         StopDetails stopDetails)
    {
        this(actionDescription,
             messages,
             originalContents,
             new Context.ParsedOutput(outputString,
                                      new ContextFragment.SessionFragment(
                                              List.of(new TaskEntry(0, actionDescription, uiMessages == null ? messages : uiMessages, null)),
                                              actionDescription)),
             stopDetails);
    }

    public static String getShortDescription(String description) {
        return getShortDescription(description, 5);
    }

    public static String getShortDescription(String description, int words) {
        var cleaned = description.trim().replaceAll("[^a-zA-Z0-9\\s]", "");
        return cleaned.split("\\s+").length <= words
               ? cleaned
               : String.join(" ", Arrays.asList(cleaned.split("\\s+")).subList(0, words));
    }

    /**
     * Enum representing the reason a CodeAgent session concluded.
     */
    public enum StopReason {
        /**
         * The agent successfully completed the goal.
         */
        SUCCESS,
        /**
         * The user interrupted the session.
         */
        INTERRUPTED,
        /**
         * The LLM returned an error after retries.
         */
        LLM_ERROR,
        /**
         * The LLM returned an empty or blank response after retries.
         */
        EMPTY_RESPONSE,
        /**
         * The LLM response could not be parsed after retries.
         */
        PARSE_ERROR,
        /**
         * Applying edits failed after retries.
         */
        APPLY_ERROR,
        /**
         * Build errors occurred and were not improving after retries.
         */
        BUILD_ERROR,
        /**
         * The LLM attempted to edit a read-only file.
         */
        READ_ONLY_EDIT,
        /**
         * the LLM called answer() but did not provide a result
         */
        SEARCH_INVALID_ANSWER,
        /**
         * the LLM determined that it was not possible to answer the query
         */
        SEARCH_IMPOSSIBLE;
    }

    public record StopDetails(StopReason reason, String explanation) {
        public StopDetails {
            assert reason != null;
            assert explanation != null;
        }

        public StopDetails(StopReason reason) {
            this(reason, "");
        }

        @Override
        public String toString() {
            if (explanation.isEmpty()) {
                return reason.toString();
            }
            return "%s:\n%s".formatted(reason.toString(), explanation);
        }
    }
}
