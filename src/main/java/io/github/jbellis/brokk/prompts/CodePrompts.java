package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Models;

import java.util.ArrayList;
import java.util.List;

public abstract class CodePrompts {
    public static final CodePrompts instance = new CodePrompts() {
    };

    public static final String LAZY_REMINDER = """
            You are diligent and tireless!
            You NEVER leave comments describing code without implementing it!
            You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
            """.stripIndent();

    public static final String OVEREAGER_REMINDER = """
            Avoid changing code or comments that are not directly related to the request.
            Do not comment on your modifications, only on the resulting code in isolation.
            This means that comments like "added X" or "changed Y" or "moved Z" are NOT WELCOME.
            """.stripIndent();

    public static final String ARCHITECT_REMINDER = """
            Pay careful attention to the scope of the user's request. Attempt to do everything required
            to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
            For example, if the user asks you a question, you should do your best to answer his question first,
            before immediately jumping into taking further action.
            """.stripIndent();

    // Now takes a Models instance
    public static String reminderForModel(Models models, StreamingChatLanguageModel model) {
        return models.isLazy(model)
                ? LAZY_REMINDER
                : OVEREAGER_REMINDER;
    }

    public final List<ChatMessage> collectCodeMessages(ContextManager cm,
                                                       StreamingChatLanguageModel model,
                                                       EditBlockParser parser,
                                                       ArrayList<ChatMessage> sessionMessages,
                                                       UserMessage request)
    {
        // TODO revisit cache-friendly message ordering with the next generation of models,
        // as of early 2025 o4-mini in particular gets very confused when it sees (its own) edits in the history
        // after the initial workspace contents
        var messages = new ArrayList<ChatMessage>();
        var reminder = reminderForModel(cm.getModels(), model);

        messages.add(new SystemMessage(formatIntro(cm, reminder)));
        messages.addAll(parser.exampleMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);
        messages.addAll(cm.getWorkspaceContentsMessages());
        messages.add(request);

        return messages;
    }

    public final List<ChatMessage> collectAskMessages(ContextManager cm, String input) {
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm, "")));
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(cm.getWorkspaceContentsMessages());
        messages.add(askRequest(input));

        return messages;
    }

    /**
     * Generates a concise summary of the workspace contents.
     * @param cm The ContextManager.
     * @return A string summarizing editable files, read-only snippets, etc.
     */
    public static String formatWorkspaceSummary(ContextManager cm, boolean includeAutocontext) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var workspaceBuilder = new StringBuilder();
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }
        return workspaceBuilder.toString();
    }

    protected String formatIntro(ContextManager cm, String reminder) {
        var workspaceSummary = formatWorkspaceSummary(cm, true);
        var styleGuide = cm.getProject().getStyleGuide();

        return """
          <instructions>
          %s
          </instructions>
          <workspace-summary>
          %s
          </workspace-summary>
          <style_guide>
          %s
          </style_guide>
          """.stripIndent().formatted(systemIntro(reminder), workspaceSummary, styleGuide).trim();
    }

    public String systemIntro(String reminder) {
        return """
        Act as an expert software developer.
        Always use best practices when coding.
        Respect and use existing conventions, libraries, etc. that are already present in the code base.
        
        %s
        """.stripIndent().formatted(reminder);
    }

    public UserMessage codeRequest(String input, String reminder, EditBlockParser parser) {
        var instructions = """
        <instructions>
        Think about this request for changes to the supplied code.
        If the request is ambiguous, ask questions.
        
        Once you understand the request you MUST:
        
        1. Decide if you need to propose *SEARCH/REPLACE* edits for any code whose source is not available.
           You can create new files without asking!
           But if you need to propose changes to code you can't see,
           you *MUST* tell the user their full filename names and ask them to *add the files to the chat*;
           end your reply and wait for their approval.
           But if you only need to change individual functions whose code you can see,
           you may do so without having the entire file in the Workspace.
        
        2. Explain the needed changes in a few short sentences.
        
        3. Describe each change with a *SEARCH/REPLACE* block.

        All changes to files must use this *SEARCH/REPLACE* block format.

        If a file is read-only or unavailable, ask the user to add it or make it editable.
        
        If you are struggling to use a dependency or API correctly, stop and ask the user for help.
        """;
        return new UserMessage(instructions + parser.instructions(input, reminder));
    }

    public UserMessage askRequest(String input) {
        var text = """
               <instructions>
               Answer this question about the supplied code thoroughly and accurately.
               
               Provide insights, explanations, and analysis; do not implement changes.
               While you can suggest high-level approaches and architectural improvements, remember that:
               - You should focus on understanding and clarifying the code
               - The user will make other requests when he wants to actually implement changes
               - You are being asked here for conceptual understanding and problem diagnosis
               
               Be concise but complete in your explanations. If you need more information to answer a question,
               don't hesitate to ask for clarification.
               
               Format your answer with Markdown for readability.
               </instructions>
               
               <question>
               %s
               </question>
               """.formatted(input);
        return new UserMessage(text);
    }
}
