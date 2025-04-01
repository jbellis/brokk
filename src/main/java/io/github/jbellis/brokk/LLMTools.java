package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Tools for rewriting file contents or specific function definitions.
 * Also a static utility for "handleToolCall(...)" that finds the right tool
 * and executes it, returning a simple result object.
 */
public class LLMTools {

    private static final Logger logger = LogManager.getLogger(LLMTools.class);

    private final IContextManager contextManager;
    private final List<ToolSpecification> specs;

    public LLMTools(IContextManager contextManager) {
        this.contextManager = contextManager;
        this.specs = ToolSpecifications.toolSpecificationsFrom(this);
    }

    @Tool(value = "Replace or create the entire file content. Usage: replaceFile(\"path/to/MyClass.java\", \"the entire text...\").")
    public String replaceFile(
            @P("The path of the file to overwrite") String filename,
            @P("The new file content") String text
    ) {
        var file = contextManager.toFile(filename);
        if (file == null || !contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("File is read-only or not recognized as editable: " + filename);
        }
        try {
            file.write(text);
            logger.info("Replaced content of file: {}", filename);
            return "Successfully replaced file " + filename;
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write file " + filename + ": " + e.getMessage());
        }
    }

    @Tool(value = "Replace the entire function body, identified by fully qualified function name (e.g. com.foo.Bar.doStuff) and param names. The param names must match exactly. Do not include param types, just names.")
    public String replaceFunction(
            @P("The fully qualified function name, e.g. com.example.Foo.barMethod") String fullyQualifiedFunctionName,
            @P("List of parameter variable names, e.g. [\"arg1\", \"userId\"]") List<String> functionParameterNames,
            @P("The new code for the entire function (no extra braces)") String newFunctionBody
    ) {
        var analyzer = contextManager.getAnalyzer();
        if (analyzer == null) {
            throw new ToolExecutionError("No analyzer is available to locate function " + fullyQualifiedFunctionName);
        }
        var optLocation = analyzer.findFunctionLocation(fullyQualifiedFunctionName, functionParameterNames);
        if (optLocation.isEmpty()) {
            throw new ToolExecutionError("Could not find function location for " + fullyQualifiedFunctionName);
        }
        FunctionLocation loc = optLocation.get();
        var file = loc.file();
        if (!contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("File is read-only or not recognized as editable: " + file);
        }

        String original;
        try {
            original = file.read();
        } catch (IOException e) {
            throw new ToolExecutionError("Failed reading file: " + file + " -> " + e.getMessage());
        }

        var lines = original.split("\n", -1);
        if (loc.startLine() - 1 < 0 || loc.endLine() > lines.length) {
            throw new ToolExecutionError("Invalid line range for function body in " + file);
        }
        StringBuilder sb = new StringBuilder();
        // lines before
        for (int i = 0; i < loc.startLine() - 1; i++) {
            sb.append(lines[i]).append("\n");
        }
        // new lines
        for (String line : newFunctionBody.split("\n", -1)) {
            sb.append(line).append("\n");
        }
        // lines after
        for (int i = loc.endLine(); i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        try {
            file.write(sb.toString());
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write updated function to file: " + e.getMessage());
        }
        logger.info("Function replaced: {} in file {}", fullyQualifiedFunctionName, file);
        return "Successfully replaced function " + fullyQualifiedFunctionName + " in " + file;
    }

    @Tool(value = "Replace the first occurrence of `oldLines` in the specified file with `newLines`. If oldLines is empty, newLines is appended. oldLines and newLines must both be full lines, not substrings!")
    public String replaceLines(
            @P("Name of the file to modify") String filename,
            @P("Text to search for") String oldLines,
            @P("Text to insert in its place") String newLines
    ) {
        var file = contextManager.toFile(filename);
        if (file == null || !contextManager.getEditableFiles().contains(file)) {
            throw new ToolExecutionError("File is read-only or not recognized as editable: " + filename);
        }
        String original;
        try {
            original = file.read();
        } catch (IOException e) {
            throw new ToolExecutionError("Could not read file " + filename + ": " + e.getMessage());
        }
        // Re-use partial matching from EditBlock
        String updated = EditBlock.doReplace(original, oldLines, newLines);
        if (updated == null) {
            throw new ToolExecutionError("No matching location found for oldLines in " + filename);
        }
        try {
            file.write(updated);
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write updated content to file " + filename + ": " + e.getMessage());
        }
        logger.info("Replaced text in file: {}", filename);
        return "Successfully replaced text in file " + filename;
    }

    /**
     * Encapsulates the results of a tool call:
     *  - output text
     *  - which file changed (if any)
     */
    public record ToolCallResult(String output, ProjectFile changedFile) {}

    /**
     * This method finds and executes the requested tool by name.
     * The 'toolSpecs' list is typically obtained from ToolSpecifications.toolSpecificationsFrom(new LLMTools(...)).
     *
     * Return a small record containing the output plus a reference to the file that changed (if any).
     */
    public ToolCallResult execute(ToolExecutionRequest request) {
        var toolSpecOpt = specs.stream()
                .filter(spec -> spec.name().equals(request.name()))
                .findFirst();
        if (toolSpecOpt.isEmpty()) {
            return new ToolCallResult("ERROR: No such tool: " + request.name(), null);
        }

        logger.info("Executing tool: {}", request.name());
        logger.info("Tool arguments (JSON): {}", request.arguments());

        String result;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var argMap = mapper.readValue(
                    request.arguments(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    }
            );

            result = switch (request.name()) {
                case "replaceFile" -> {
                    var filename = (String) argMap.get("filename");
                    var text = (String) argMap.get("text");
                    yield replaceFile(filename, text);
                }
                case "replaceFunction" -> {
                    var fullyQualifiedFunctionName = (String) argMap.get("fullyQualifiedFunctionName");
                    @SuppressWarnings("unchecked")
                    var functionParameterNames = (List<String>) argMap.get("functionParameterNames");
                    var newFunctionBody = (String) argMap.get("newFunctionBody");
                    yield replaceFunction(fullyQualifiedFunctionName, functionParameterNames, newFunctionBody);
                }
                case "replaceLines" -> {
                    var filename = (String) argMap.get("filename");
                    var oldText = (String) argMap.get("oldText");
                    var newText = (String) argMap.get("newText");
                    yield replaceLines(filename, oldText, newText);
                }
                default -> "ERROR: Tool not recognized or not implemented: " + request.name();
            };
        } catch (Exception e) {
            result = "ERROR: " + e.getMessage();
        }

        logger.info("Tool result: {}", result);
        var changedFile = extractFileFromMessage(contextManager, result);
        return new ToolCallResult(result, changedFile);
    }

    /**
     * A quick hack to detect which file changed from the success message.
     * You can refine this or store file references directly in each tool method's return object.
     */
    private static ProjectFile extractFileFromMessage(IContextManager contextManager, String result) {
        // For example, "Successfully replaced file path/to/Test.java"
        // We'll look for "file " or "in " as a naive approach
        String marker = " file ";
        int idx = result.indexOf(marker);
        if (idx < 0) {
            marker = " in ";
            idx = result.indexOf(marker);
            if (idx < 0) return null;
        }
        var partial = result.substring(idx + marker.length()).trim();
        // if there's a space or punctuation, parse up to that
        int spaceIdx = partial.indexOf(' ');
        if (spaceIdx > 0) {
            partial = partial.substring(0, spaceIdx);
        }
        // see if we can create a ProjectFile from partial
        var file = contextManager.toFile(partial);
        if (file == null) return null;
        if (!contextManager.getEditableFiles().contains(file)) return null;
        return file;
    }

    private static class ToolExecutionError extends RuntimeException {
        public ToolExecutionError(String s) {
            super(s);
        }
    }
}
