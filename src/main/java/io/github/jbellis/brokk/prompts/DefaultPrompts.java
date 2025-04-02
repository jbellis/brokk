package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Models;

import java.util.ArrayList;
import java.util.List;

public abstract class DefaultPrompts {
    public static final DefaultPrompts instance = new DefaultPrompts() {};

    public static final String LAZY_REMINDER = """
    You are diligent and tireless!
    You NEVER leave comments describing code without implementing it!
    You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
    """;

    public static final String OVEREAGER_REMINDER = """
    Pay careful attention to the scope of the user's request. Do what he asks, but no more.
    Do comment new code, but if existing comments are adequate, do not rewrite them.
    """;

    public static String reminderForModel(StreamingChatLanguageModel model) {
        return Models.isLazy(model)
                ? LAZY_REMINDER
                : OVEREAGER_REMINDER;
    }

    public final List<ChatMessage> collectMessages(ContextManager cm, List<ChatMessage> sessionMessages, String reminder) {
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm, reminder)));
        messages.addAll(cm.getReadOnlyMessages());

        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);

        messages.add(new UserMessage(toolUsageReminder(reminder)));
        messages.add(new AiMessage("I will use these tools accordingly."));

        messages.addAll(cm.getEditableMessages());

        return messages;
    }

    protected String formatIntro(ContextManager cm, String reminder) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var styleGuide = cm.getProject().getStyleGuide();

        var workspaceBuilder = new StringBuilder();
        workspaceBuilder.append("- Root: ").append(cm.getRoot().getFileName());
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }

        return """
        <instructions>
        %s
        </instructions>
        <workspace>
        %s
        </workspace>
        <style_guide>
        %s
        </style_guide>
        """.stripIndent().formatted(systemIntro(reminder), workspaceBuilder.toString(), styleGuide).trim();
    }

    public String systemIntro(String reminder) {
        return """
               Act as an expert software developer.
               Always use best practices when coding.
               Respect and use existing conventions, libraries, etc. that are already present in the code base.
    
               %s

               Take requests for changes to the supplied code.
               If the request is ambiguous, ask questions.

               Once you understand the request you MUST do the following:

               1. Plan the changes you will make.
               2. Explain them in plain English, in a few short sentences.
               3. Use the correct tools to apply the changes.

               Include as many tool calls as necessary to fulfill the requested changes.
               If you need to add or modify multiple files, simply provide multiple tool calls.

               If a file is read-only or unavailable, ask the user to add it or make it editable.
               """.formatted(reminder).stripIndent();
    }

    /**
     * Provides a reminder or instructions about how to call the tools properly.
     */
    private String toolUsageReminder(String reminder) {
        return """
               <rules>
               # Tips for editing code

               - If you are replacing large amounts of code, or if this file is tiny, use replaceFile.
               - replaceFunction is the most frequently useful but it can be fragile if the file is not
                 syntactically correct enough to parse.
               - replaceLines can handle any edit, but it is the most manual and you have to get the
                 oldLines exactly right.

               ALWAYS MAKE ALL TOOL CALL EDITS IN A SINGLE RESPONSE!

               %s
               </rules>
               """.formatted(reminder).stripIndent();
    }
}
