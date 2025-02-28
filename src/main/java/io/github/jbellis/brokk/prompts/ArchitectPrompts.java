package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.ArrayList;
import java.util.List;

public abstract class ArchitectPrompts extends DefaultPrompts {
    public static final ArchitectPrompts instance = new ArchitectPrompts() {};

    @Override
    public List<ChatMessage> collectMessages(ContextManager cm) {
        // like the default, but omits the edit instructions and examples
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm)));
        messages.addAll(cm.getReadOnlyMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(cm.getEditableMessages());

        return messages;
    }

    @Override
    public String systemIntro() {
        return """
               Act as an expert software architect and provide direction to your implementing junior engineer.
               Study the change request and the current code.
               Describe how to modify the code to complete the request.
               The junior engineer will rely solely on your instructions, so make them unambiguous and complete.
               Do not skip steps or leave out corner cases.
               Do not leave code changes to the junior engineer's discretion.
               """.stripIndent();
    }
}
