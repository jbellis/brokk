package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;

import java.util.List;

public interface IConsoleIO {
    void actionOutput(String msg);

    default void actionComplete() {
    }

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    enum MessageSubType {
        Run,
        Ask,
        Code,
        Architect,
        Search,
        BuildError,
        CommandOutput
    }

    void llmOutput(String token, ChatMessageType type);
    
    default void systemOutput(String message) {
        llmOutput("\n" + message, ChatMessageType.USER);
    }
    
    default void showOutputSpinner(String message) {}

    default void hideOutputSpinner() {}

    default String getLlmOutputText() {
        throw new UnsupportedOperationException();
    }
    
    default List<ChatMessage> getLlmRawMessages() {
        throw new UnsupportedOperationException();
    }

    void blockLlmOutput(boolean blocked);
}
