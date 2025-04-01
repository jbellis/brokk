package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LLMTest {

    private IContextManager contextManager;
    private LLMTools tools;

    @BeforeEach
    void setUp() {
        // Minimal context manager stub
        contextManager = new IContextManager() {
            @Override
            public ProjectFile toFile(String path) {
                return new ProjectFile(Paths.get("/tmp"), path);
            }
            // We'll mark everything as editable
            @Override
            public java.util.Set<ProjectFile> getEditableFiles() {
                return Collections.singleton(toFile("test.txt"));
            }
        };
        tools = new LLMTools(contextManager);
    }

    @Test
    void testReplaceFile() {
        var specList = ToolSpecifications.toolSpecificationsFrom(tools);
        var request = ToolExecutionRequest.builder()
                .name("replace_file")
                .arguments("{\"filename\":\"test.txt\",\"text\":\"hello world\"}")
                .build();
        var result = specList.get(0).toolExecutor().execute(request, null);
        assertTrue(result.startsWith("Successfully replaced file"));
    }

    @Test
    void testReplaceTextFail() {
        // Attempt partial match that can't be found
        var specList = ToolSpecifications.toolSpecificationsFrom(tools);
        var request = ToolExecutionRequest.builder()
                .name("replace_text")
                .arguments("{\"filename\":\"test.txt\",\"oldText\":\"nonexistent\",\"newText\":\"???\"}")
                .build();
        var result = specList.get(0).toolExecutor().execute(request, null);
        assertTrue(result.contains("ERROR:"), "Should fail because 'nonexistent' not found");
    }

    @Test
    void testReplaceFunction() {
        var specList = ToolSpecifications.toolSpecificationsFrom(tools);

        // We'll pretend there's a function: com.example.Foo.myMethod
        var request = ToolExecutionRequest.builder()
                .name("replace_function")
                .arguments("{\"fullyQualifiedFunctionName\":\"com.example.Foo.myMethod\"," +
                                   "\"functionParameterNames\":[],\"text\":\"return 123;\"}")
                .build();
        var result = specList.get(0).toolExecutor().execute(request, null);

        // We haven't set up a real analyzer in the context manager, so expect an error
        assertTrue(result.contains("ERROR:"), "No analyzer => error");
    }
}
