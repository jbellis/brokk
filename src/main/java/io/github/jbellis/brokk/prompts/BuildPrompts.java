package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public abstract class BuildPrompts extends DefaultPrompts {
    public static final BuildPrompts instance = new BuildPrompts() {};

    @Override
    public List<ChatMessage> collectMessages(ContextManager cm) {
        throw new UnsupportedOperationException();
    }

    public List<ChatMessage> collectMessages(List<String> buildResults) {
        var formattedResults = new ArrayList<String>();
        for (int i = 0; i < buildResults.size(); i++) {
            var result = buildResults.get(i);
            var st = """
            # Build %d
            ```
            %s
            ```
            """.stripIndent().formatted(i + 1, result);
            formattedResults.add(st);
        }
        var buildStr = """
        Here are the build results, oldest first.
        
        %s
        
        Is he making progress? Remember to conclude
        with either `BROKK_PROGRESSING` or `BROKK_FLOUNDERING`.""".stripIndent()
                .formatted(String.join("\n\n", formattedResults));
        return List.of(new SystemMessage(systemIntro()), new UserMessage(buildStr));
    }

    @Override
    public String systemIntro() {
        return """
               You are an expert software engineer that can tell if a junior engineer is
               getting closer to solving his build errors, or if he is floundering.
               
               Review the history of build outputs here.  Think carefully about what
               you can determine about his progress or lack thereof, and conclude your
               reasoning with either `BROKK_PROGRESSING` or `BROKK_FLOUNDERING`.
               """.stripIndent();
    }
}
