package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.Tool;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class LLMTools {

    private static final Logger logger = LogManager.getLogger(LLMTools.class);

    private final IContextManager contextManager;
    private final IConsoleIO io;
    private final IAnalyzer analyzer;

    public LLMTools(IContextManager contextManager, IConsoleIO io, IAnalyzer analyzer) {
        this.contextManager = contextManager;
        this.io = io;
        this.analyzer = analyzer;
    }

    @Tool(name = "replace_file",
            description = "Replaces the entire content of the given filename with 'text'. Use only if we want to overwrite the file completely.")
    public String replaceFile(
            @ToolParameter(name = "filename") String filename,
            @ToolParameter(name = "text") String text
    ) {
        var file = contextManager.toFile(filename);
        if (!contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("File is not in editable set: " + filename);
        }

        try {
            file.write(text);
            contextManager.addToGit(filename);
            return "Successfully replaced file content of " + filename;
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write file " + filename + ": " + e.getMessage());
        }
    }

    @Tool(name = "replace_text",
            description = "Replaces 'oldText' with 'newText' in 'filename' using partial/fuzzy match. Returns success or error message.")
    public String replaceText(
            @ToolParameter(name = "filename") String filename,
            @ToolParameter(name = "oldText") String oldText,
            @ToolParameter(name = "newText") String newText
    ) {
        var file = contextManager.toFile(filename);
        if (!contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("File is not editable: " + filename);
        }

        try {
            var original = file.exists() ? file.read() : "";
            var replaced = EditBlock.doReplace(original, oldText, newText);
            if (replaced == null) {
                return "No matching chunk found in " + filename + " for oldText:\n" + oldText;
            }
            file.write(replaced);
            contextManager.addToGit(filename);
            return "Successfully replaced text in " + filename;
        } catch (IOException e) {
            throw new ToolExecutionError("IO Error on " + filename + ": " + e.getMessage());
        }
    }

    @Tool(name = "replace_function",
            description = "Finds a function by fully qualified name and param variable names, replaces its entire body with 'text'.")
    public String replaceFunction(
            @ToolParameter(name = "fullyQualifiedFunctionName") String fqFuncName,
            @ToolParameter(name = "functionParameterNames") List<String> paramNames,
            @ToolParameter(name = "text") String text
    ) {
        var locOpt = analyzer.findFunctionLocation(fqFuncName, paramNames);
        if (locOpt.isEmpty()) {
            throw new ToolExecutionError("No matching function found for " + fqFuncName + " with param names=" + paramNames);
        }

        FunctionLocation loc = locOpt.get();
        var file = loc.file();
        if (!contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("Function is in a file not currently editable: " + file);
        }

        // We have a startLine .. endLine range
        String original;
        try {
            original = file.read();
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to read file: " + file + " => " + e.getMessage());
        }

        // We'll replace that entire line range with 'text'
        var lines = original.split("\n", -1);
        if (loc.startLine() < 1 || loc.endLine() > lines.length) {
            throw new ToolExecutionError("Line range out of bounds for " + file);
        }

        StringBuilder sb = new StringBuilder();
        // keep the lines before
        for (int i = 0; i < loc.startLine() - 1; i++) {
            sb.append(lines[i]).append("\n");
        }
        // insert the new text
        sb.append(text);
        if (!text.endsWith("\n")) {
            sb.append("\n");
        }
        // skip the old function lines
        for (int i = loc.endLine(); i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }

        try {
            file.write(sb.toString());
            contextManager.addToGit(file.toString());
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write updated function: " + e.getMessage());
        }

        return "Replaced function body for " + fqFuncName + ".";
    }
}
