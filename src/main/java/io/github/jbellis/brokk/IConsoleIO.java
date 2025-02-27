package io.github.jbellis.brokk;

public interface IConsoleIO {
    void toolOutput(String msg);

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    boolean confirmAsk(String msg);

    void llmOutput(String token);

    default void spin(String s) {
        toolOutput(s);
    }

    default void spinComplete() {
    }
}
