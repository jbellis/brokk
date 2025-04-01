package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tools for rewriting file contents or specific function definitions.
 * Also a static utility for "handleToolCall(...)" that finds the right tool
 * and executes it.
 */
public class LLMTools {

    private static final Logger logger = LogManager.getLogger(LLMTools.class);

    private final IContextManager contextManager;

    public LLMTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    @Tool(value = "Replace or create the entire file content. Usage: replaceFile(\"path/to/MyClass.java\", \"the entire text...\").")
    public void replaceFile(
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
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write file " + filename + ": " + e.getMessage());
        }
    }

    @Tool(value = "Replace the entire function body, identified by fully qualified function name (e.g. com.foo.Bar.doStuff) and param names. The param names must match exactly. Do not include param types, just names.")
    public void replaceFunction(
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

        var sb = new StringBuilder();
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
            logger.info("Function replaced: {} in file {}", fullyQualifiedFunctionName, file);
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write updated function to file: " + e.getMessage());
        }
    }

    @Tool(value = "Replace the first occurrence of `oldLines` in the specified file with `newLines`. If oldLines is empty, newLines is appended. oldLines and newLines must both be full lines, not substrings!")
    public void replaceLines(
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
        String updated = EditBlock.doReplace(original, oldLines, newLines);
        if (updated == null) {
            throw new ToolExecutionError("No matching location found for oldLines in " + filename);
        }
        try {
            file.write(updated);
            logger.info("Replaced text in file: {}", filename);
        } catch (IOException e) {
            throw new ToolExecutionError("Failed to write updated content to file " + filename + ": " + e.getMessage());
        }
    }

    /**
     * A record capturing the outcome of a preview for a single tool request.
     * If success == false, errorMessage should contain the reason for failure.
     */
    public record ToolOperationPreview(
            String toolName,
            ProjectFile targetFile,
            Object parsedArguments,
            boolean success,
            String errorMessage
    ) {}

    /**
     * Parse and validate a list of tool requests *without* actually writing to disk.
     * Return a list of previews (one per request). Each entry indicates success/failure
     * and references the target file (if applicable).
     */
    public List<ToolOperationPreview> validateToolRequests(List<ToolExecutionRequest> requests) {
        var previews = new ArrayList<ToolOperationPreview>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (var request : requests) {
            // Parse request arguments
            String toolName = request.name();
            Map<String, Object> argMap;
            try {
                argMap = mapper.readValue(request.arguments(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (Exception ex) {
                previews.add(new ToolOperationPreview(
                        toolName, null, null, false,
                        "Could not parse JSON arguments: " + ex.getMessage()
                ));
                continue;
            }

            // Attempt to locate file if the tool references one
            // Also attempt auto-add if not read-only
            ProjectFile file;
            try {
                switch (toolName) {
                    case "replaceFile" -> {
                        var filename = (String) argMap.get("filename");
                        file = checkOrAutoAddFile(filename);
                    }
                    case "replaceLines" -> {
                        var filename = (String) argMap.get("filename");
                        file = checkOrAutoAddFile(filename);
                    }
                    case "replaceFunction" -> {
                        var fqn = (String) argMap.get("fullyQualifiedFunctionName");
                        var analyzer = contextManager.getAnalyzer();
                        if (analyzer == null) {
                            throw new ToolExecutionError("No analyzer available to locate " + fqn);
                        }
                        @SuppressWarnings("unchecked")
                        var params = (List<String>) argMap.get("functionParameterNames");
                        var locationOpt = analyzer.findFunctionLocation(fqn, params);
                        if (locationOpt.isEmpty()) {
                            throw new ToolExecutionError("Could not find function location for " + fqn);
                        }
                        var loc = locationOpt.get();
                        file = checkOrAutoAddFile(loc.file().toString());
                    }
                    default -> {
                        throw new ToolExecutionError("Unsupported tool: " + toolName);
                    }
                }

                previews.add(new ToolOperationPreview(
                        toolName, file, argMap, true, ""
                ));
            } catch (Exception ex) {
                previews.add(new ToolOperationPreview(
                        toolName, null, argMap, false, ex.getMessage()
                ));
            }
        }
        return previews;
    }

    /**
     * Apply the list of tool operations that have been validated.
     * Throws a ToolExecutionError if any validated tool execution fails.
     */
    public void applyToolOperations(List<ToolOperationPreview> previews) {
        for (var preview : previews) {
            if (!preview.success()) {
                continue;
            }
            try {
                applyTool(preview);
            } catch (Exception ex) {
                throw new ToolExecutionError("Failed applying tool " + preview.toolName() + ": " + ex.getMessage(), ex);
            }
        }
    }

    /**
     * Actually runs the existing logic for the named tool.
     * This function *does* modify the file on disk.
     */
    private void applyTool(ToolOperationPreview preview) {
        var toolName = preview.toolName();
        @SuppressWarnings("unchecked")
        var argMap = (Map<String, Object>) preview.parsedArguments();

        switch (toolName) {
            case "replaceFile" -> {
                var filename = (String) argMap.get("filename");
                var text = (String) argMap.get("text");
                replaceFile(filename, text);
            }
            case "replaceLines" -> {
                var filename = (String) argMap.get("filename");
                var oldText = (String) argMap.get("oldText");
                var newText = (String) argMap.get("newText");
                replaceLines(filename, oldText, newText);
            }
            case "replaceFunction" -> {
                var fullyQualifiedFunctionName = (String) argMap.get("fullyQualifiedFunctionName");
                @SuppressWarnings("unchecked")
                var functionParameterNames = (List<String>) argMap.get("functionParameterNames");
                var newFunctionBody = (String) argMap.get("newFunctionBody");
                replaceFunction(fullyQualifiedFunctionName, functionParameterNames, newFunctionBody);
            }
            default -> throw new ToolExecutionError("Unsupported tool: " + toolName);
        }
    }

    /**
     * Check if file is recognized/editable, or attempt to auto-add if it's new and not read-only.
     * Throws ToolExecutionError if read-only or unrecognized.
     */
    private ProjectFile checkOrAutoAddFile(String filename) {
        var file = contextManager.toFile(filename);
        if (file == null) {
            throw new ToolExecutionError("File path not recognized at all: " + filename);
        }
        if (contextManager.getEditableFiles().contains(file)) {
            return file;
        }
        if (contextManager.getReadonlyFiles().contains(file)) {
            throw new ToolExecutionError("File is read-only: " + filename);
        }
        contextManager.editFiles(List.of(file));
        return file;
    }

    private static class ToolExecutionError extends RuntimeException {
        public ToolExecutionError(String s) {
            super(s);
        }
        public ToolExecutionError(String s, Throwable cause) {
            super(s, cause);
        }
    }
}
