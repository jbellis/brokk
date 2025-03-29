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
        Act as an expert software engineer. Study the change request and the current code.
        Describe how to modify the code to complete the request.
       
        **Focus on Changed Units:** Your goal is to describe modifications at the **method or logical code block level**.
        *   Identify the specific method(s) or distinct code block(s) that need changes.
        *   For each identified method or block, provide its **complete, updated code**.
        *   If a method is very long and only a small part changes, you may show the specific changed lines with a few lines of surrounding context,
            but when in doubt, prefer showing the whole updated method.
       
        **Crucially:**
        *   **DO NOT provide entire files** unless the change *is* creating a new, small file or replacing the majority of an existing file.
        *   You MUST give the full, runnable implementation for the **specific method or block** you are showing. Do not use placeholders or 
            give multiple options; pick the best option and show the code.
        *   DO NOT skip steps or leave out corner cases within the logic of the changed code.
        *   DO NOT give multiple options for solving a problem; pick the best one every time!
       
        **Output Format Hint:** Structure your response by identifying the file, then the method/block, then providing the code for that method/block.
       """.stripIndent();
    }
}
