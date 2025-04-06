package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import io.github.jbellis.brokk.analyzer.FunctionLocation;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SymbolAmbiguousException;
import io.github.jbellis.brokk.analyzer.SymbolNotFoundException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Revamped LLM tool handling. We now separate:
 *  1) Parsing & validation (to find the intended file/method).
 *  2) Actual application of the edit.
 *
 * Instead of previewing in bulk, each tool request is parsed with parseToolRequest(...),
 * returning a ValidatedToolRequest (with a recognized ProjectFile, function location, or an error).
 * Then the second pass calls executeTool(...) to actually do the edit, returning
 * a ToolExecutionResultMessage indicating success or failure.
 */
public class LLMTools {
    private static final Logger logger = LogManager.getLogger(LLMTools.class);

    public enum Action {
        EDIT, NEW, REMOVE, NONE
    }

    public static class ExtToolExecutionResultMessage extends ToolExecutionResultMessage {
        private final ProjectFile file;
        private final Action action;
        private final Integer lines;

        public ExtToolExecutionResultMessage(ToolExecutionRequest request, String result, ProjectFile file, Action action, Integer lines) {
            super(request.id(), request.name(), result);
            this.file = file;
            this.action = action;
            this.lines = lines;
        }

        // Getters for the new fields
        public ProjectFile getFile() {
            return file;
        }

        public Action getAction() {
            return action;
        }

        public Integer getLines() {
            return lines;
        }

        // Static factory methods for convenience
        public static ExtToolExecutionResultMessage success(ToolExecutionRequest request, ProjectFile file, Action action, Integer lines) {
            return new ExtToolExecutionResultMessage(request, "SUCCESS", file, action, lines);
        }

        public static ExtToolExecutionResultMessage error(ToolExecutionRequest request, String error) {
            return new ExtToolExecutionResultMessage(request, error, null, Action.NONE, null);
        }

        public static ExtToolExecutionResultMessage none(ToolExecutionRequest request, String result) {
            return new ExtToolExecutionResultMessage(request, result, null, Action.NONE, null);
        }
    }

    private final IContextManager contextManager;

    public LLMTools(IContextManager contextManager) {
        this.contextManager = contextManager;
    }

    /**
     * Parse a single ToolExecutionRequest into a ValidatedToolRequest.
     * This is where we:
     *  - parse JSON arguments
     *  - locate the file (by exact path or unique match ignoring directories)
     *  - or locate the function (by exact FQN or unique class+method ignoring package)
     *  - store any relevant new text
     *
     * On success, returns a ValidatedToolRequest with no error; if something
     * can't be resolved, returns one with an error message.
     *
     * The caller can then apply these edits in a second pass by calling executeTool(...).
     */
    public ValidatedToolRequest parseToolRequest(ToolExecutionRequest request) {
        String toolName = request.name();
        Map<String, Object> argMap;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            argMap = mapper.readValue(request.arguments(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ex) {
            return ValidatedToolRequest.error(request, "JSON parse error: " + ex.getMessage(), toolName);
        }

        return switch (toolName) {
            case "replaceFile" -> parseReplaceFile(request, argMap);
            case "replaceLines" -> parseReplaceLines(request, argMap);
            case "replaceFunction" -> parseReplaceFunction(request, argMap);
            case "removeFile" -> parseRemoveFile(request, argMap);
            case "explain" -> parseExplain(request, argMap);
            default -> ValidatedToolRequest.error(request, "Unrecognized tool name: " + toolName, toolName);
        };
    }

    /**
     * Actually apply a ValidatedToolRequest to the filesystem, returning
     * an ExtToolExecutionResultMessage with details about the action performed.
     */
    public ExtToolExecutionResultMessage executeTool(ValidatedToolRequest validated) {
        if (validated.error() != null) {
            // Error during parsing, no file action taken yet
            return ExtToolExecutionResultMessage.error(validated.originalRequest(), validated.error());
        }
        try {
            return switch (validated.originalRequest().name()) {
                case "replaceFile" -> replaceFile(validated.originalRequest(), validated.file(), validated.newFileContent());
                case "replaceLines" -> replaceLines(validated.originalRequest(), validated.file(), validated.oldLines(), validated.newLines());
                case "replaceFunction" -> replaceFunction(validated.originalRequest(), validated.functionLocation(), validated.newFunctionBody());
                case "removeFile" -> removeFile(validated.originalRequest(), validated.file());
                case "explain" -> ExtToolExecutionResultMessage.none(validated.originalRequest(), validated.explanation());
                default -> throw new ToolExecutionException("Unsupported tool: " + validated.originalRequest().name());
            };
        } catch (Exception ex) {
            logger.warn("Tool application error", ex);
            return ExtToolExecutionResultMessage.error(validated.originalRequest(), "Failed: " + ex.getMessage());
        }
    }

    @Tool(value = """
    Replaces the entire file content. This is expensive for large files, so prefer replaceLines if you are only making targeted changes.
    Use replaceFile when you need to make sweeping changes across the entire code or when replaceLines might result in overlapping edits.
    Also use this tool to create new files: just provide the full path and content.
    """)
    public void replaceFile(@P("The path of the file to overwrite") String filename, @P("The new file content") String text) {
        throw new ToolExecutionException("Direct invocation of replaceFile(String,String) is not supported. Use parseToolRequest + executeTool.");
    }

    /**
     * Overload that actually does the disk write after we've validated the file.
     */
    public ExtToolExecutionResultMessage replaceFile(ToolExecutionRequest request, ProjectFile file, String newContent) {
        try {
            var existing = file.exists();
            file.write(newContent);
            var action = existing ? Action.EDIT : Action.NEW;
            if (!existing) {
                contextManager.addToGit(List.of(file));
            }
            logger.debug("replaceFile: overwrote content in {}", file);
            int lines = newContent.split("\n", -1).length;
            return ExtToolExecutionResultMessage.success(request, file, action, lines);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed writing file " + file + ": " + e.getMessage(), e);
        }
    }

    @Tool(value = """
    Replace the first occurrence of oldLines in the specified file with newLines (both are full lines).
    - replaceLines cannot create new files; use replaceFile for that use case.
    - If oldLines is empty, newLines is appended at the end of the file.
    - If replacing sequential lines, make one call for all of them. Never make multiple calls to replaceLines when they can be combined to one!
    - Include enough oldLines that the match is unique.
    - You can use this tool to add new lines by giving existing lines as an "anchor," then repeating the anchor unchanged with new lines appended.
    - If you want to move code within a file, use 2 calls: one to remove the old code, and another to add it in the new location.
    - Never call replaceLines with newLines == oldLines, that is just a waste of time!
    """)
    public void replaceLines(@P("Full path + name of the file to modify") String filename,
                             @P("Lines to replace") String oldLines,
                             @P("Replacement lines (will be used as-is, so make sure indentation is appropriate)") String newLines) {
        throw new ToolExecutionException("Direct invocation of replaceLines(String,String,String) is not supported. Use parseToolRequest + executeTool.");
    }

    public ExtToolExecutionResultMessage replaceLines(ToolExecutionRequest request, ProjectFile file, String oldLines, String newLines)
            throws EditBlock.NoMatchException, EditBlock.AmbiguousMatchException {
        try {
            EditBlock.replaceInFile(file, oldLines, newLines);
            logger.info("replaceLines: updated text in {}", file);
            int lines = newLines.split("\n", -1).length;
            return ExtToolExecutionResultMessage.success(request, file, Action.EDIT, lines);
        } catch (IOException e) {
            throw new ToolExecutionException("Could not read or write file: " + file + " -> " + e.getMessage(), e);
        }
    }

    /**
     * "removeFile" - deletes a file from the filesystem.
     */
    @Tool(value = "Remove (delete) a file from the filesystem.")
    public void removeFile(@P("Full path + name of the file to remove") String filename) {
        throw new ToolExecutionException("Direct invocation of removeFile(String) is not supported. Use parseToolRequest + executeTool.");
    }

    /**
     * Overload that actually does the file removal after we've validated the file.
     */
    public ExtToolExecutionResultMessage removeFile(ToolExecutionRequest request, ProjectFile file) {
        try {
            if (!file.exists()) {
                throw new ToolExecutionException("File does not exist: " + file);
            }
            java.nio.file.Files.delete(file.absPath());
            logger.debug("removeFile: deleted {}", file);
            // TODO: Add to git remove?
            return ExtToolExecutionResultMessage.success(request, file, Action.REMOVE, null);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed deleting file " + file + ": " + e.getMessage(), e);
        }
    }

    public ExtToolExecutionResultMessage replaceFunction(ToolExecutionRequest request, FunctionLocation loc, String newFunctionBody) {
        // read original file
        ProjectFile file = loc.file();
        String original;
        try {
            original = file.read();
        } catch (IOException e) {
            throw new ToolExecutionException("Failed reading file: " + file + " -> " + e.getMessage());
        }

        var lines = original.split("\n", -1);
        if (loc.startLine() - 1 < 0 || loc.endLine() > lines.length) {
            throw new ToolExecutionException("Invalid line range for function body in " + file);
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
            logger.info("replaceFunction: replaced lines {}..{} in {}", loc.startLine(), loc.endLine(), file);
            int changedLines = loc.endLine() - loc.startLine();
            return ExtToolExecutionResultMessage.success(request, file, Action.EDIT, changedLines);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to write updated function to file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Utility to parse "replaceFile" arguments from the tool request,
     * resolving the target ProjectFile from the context manager.
     */
    private ValidatedToolRequest parseReplaceFile(ToolExecutionRequest req, Map<String, Object> argMap) {
        var filename = (String) argMap.get("filename");
        var newText = (String) argMap.get("text");
        if (filename == null || newText == null) {
            return ValidatedToolRequest.error(req, "replaceFile requires 'filename' and 'text' fields", "replaceFile: " + (filename != null ? filename : "unknown"));
        }

        ProjectFile pf;
        try {
            pf = resolveProjectFile(filename, true);
        } catch (Exception e) {
            return ValidatedToolRequest.error(req, e.getMessage(), "replaceFile: " + filename);
        }

        return new ValidatedToolRequest(req, pf, null, null,
                                        new ValidatedToolRequest.RequestContents(null, null, newText, null, null),
                                        "replaceFile: " + filename);
    }

    /**
     * Utility to parse "removeFile" arguments from the tool request,
     * resolving the target ProjectFile from the context manager.
     */
    private ValidatedToolRequest parseRemoveFile(ToolExecutionRequest req, Map<String, Object> argMap) {
        var filename = (String) argMap.get("filename");
        if (filename == null) {
            return ValidatedToolRequest.error(req, "removeFile requires 'filename' field", "removeFile: unknown");
        }

        ProjectFile pf;
        try {
            pf = resolveProjectFile(filename, false);
        } catch (Exception e) {
            return ValidatedToolRequest.error(req, e.getMessage(), "removeFile: " + filename);
        }

        return new ValidatedToolRequest(req, pf, null, null,
                                        new ValidatedToolRequest.RequestContents(null, null, null, null, null),
                                        "removeFile: " + filename);
    }

    private ValidatedToolRequest parseReplaceLines(ToolExecutionRequest req, Map<String, Object> argMap) {
        var filename = (String) argMap.get("filename");
        var oldLines = (String) argMap.get("oldLines");
        var newLines = (String) argMap.get("newLines");

        if (filename == null || oldLines == null || newLines == null) {
            return ValidatedToolRequest.error(req, "replaceLines requires 'filename','oldLines','newLines'", "replaceLines: " + (filename != null ? filename : "unknown"));
        }

        ProjectFile pf;
        try {
            pf = resolveProjectFile(filename, false);
        } catch (Exception e) {
            return ValidatedToolRequest.error(req, e.getMessage(), "replaceLines: " + filename);
        }
        var firstLine = oldLines.split("\n", -1)[0];
        return new ValidatedToolRequest(req, pf, null, null,
                                        new ValidatedToolRequest.RequestContents(oldLines, newLines, null, null, null),
                                        "replaceLines: " + filename + ", " + firstLine + "...");
    }

    private ValidatedToolRequest parseReplaceFunction(ToolExecutionRequest req, Map<String, Object> argMap) {
        var fullyQualifiedName = (String) argMap.get("fullyQualifiedFunctionName");
        @SuppressWarnings("unchecked")
        var paramNames = (List<String>) argMap.get("functionParameterNames");
        var body = (String) argMap.get("newFunctionBody");

        if (fullyQualifiedName == null || paramNames == null || body == null) {
            return ValidatedToolRequest.error(req, "replaceFunction requires 'fullyQualifiedFunctionName','functionParameterNames','newFunctionBody'",
                                              "replaceFunction: " + (fullyQualifiedName != null ? fullyQualifiedName : "unknown"));
        }

        var analyzer = contextManager.getAnalyzer();
        assert analyzer != null;

        // 1) try exact FQ name
        try {
            var location = analyzer.getFunctionLocation(fullyQualifiedName, paramNames);
            if (!contextManager.getEditableFiles().contains(location.file())) {
                return ValidatedToolRequest.error(req, "File for " + fullyQualifiedName + " is not editable: " + location.file(),
                                                  "replaceFunction: " + fullyQualifiedName + ", " + paramNames);
            }
            return new ValidatedToolRequest(req, location.file(), location, null,
                                            new ValidatedToolRequest.RequestContents(null, null, null, body, null),
                                            "replaceFunction: " + fullyQualifiedName + ", " + paramNames);
        } catch (SymbolNotFoundException e) {
            int lastDot = fullyQualifiedName.lastIndexOf('.');
            if (lastDot > 0) {
                var shortMethod = fullyQualifiedName.substring(lastDot + 1);
                var shortName = fullyQualifiedName.substring(fullyQualifiedName.lastIndexOf('.', lastDot - 1) + 1);
                var maybeLoc = getFunctionLocationIgnoringPackage(shortName, paramNames);
                if (maybeLoc.size() == 1) {
                    var loc = maybeLoc.get(0);
                    if (!contextManager.getEditableFiles().contains(loc.file())) {
                        return ValidatedToolRequest.error(req, "File for " + shortName + " is not editable: " + loc.file(),
                                                          "replaceFunction: " + shortName + ", " + paramNames);
                    }
                    return new ValidatedToolRequest(req, loc.file(), loc, null,
                                                    new ValidatedToolRequest.RequestContents(null, null, null, body, null),
                                                    "replaceFunction: " + shortMethod + ", " + paramNames);
                }
                if (maybeLoc.isEmpty()) {
                    return ValidatedToolRequest.error(req, "No match found for function " + fullyQualifiedName,
                                                      "replaceFunction: " + fullyQualifiedName + ", " + paramNames);
                }
                var allFQNs = maybeLoc.stream()
                        .map(fl -> fl.file().toString() + " -> " + fl.code())
                        .collect(Collectors.joining("\n"));
                return ValidatedToolRequest.error(req, "Multiple matches found for " + fullyQualifiedName + ", ignoring package. Candidates:\n" + allFQNs,
                                                  "replaceFunction: " + fullyQualifiedName + ", " + paramNames);
            }
            return ValidatedToolRequest.error(req, "No match found for function: " + fullyQualifiedName,
                                              "replaceFunction: " + fullyQualifiedName + ", " + paramNames);
        }
    }

    /**
     * Helper method: see if "class.method" can be found ignoring the top-level package.
     * Returns all potential matches.
     */
    private List<FunctionLocation> getFunctionLocationIgnoringPackage(String classAndMethod, List<String> paramNames) {
        var analyzer = contextManager.getAnalyzer();
        if (analyzer == null) return List.of();

        int dotIdx = classAndMethod.indexOf('.');
        if (dotIdx <= 0) {
            return List.of();
        }
        var cls = classAndMethod.substring(0, dotIdx);
        var mName = classAndMethod.substring(dotIdx + 1);

        var allClasses = analyzer.getAllClasses();
        var matchedClasses = new ArrayList<String>();
        for (var codeUnit : allClasses) {
            if (codeUnit.isClass() && codeUnit.shortName().equals(cls)) {
                matchedClasses.add(codeUnit.fqName());
            }
        }
        var results = new ArrayList<FunctionLocation>();
        for (String fqcn : matchedClasses) {
            String guess = fqcn + "." + mName;
            try {
                results.add(analyzer.getFunctionLocation(guess, paramNames));
            } catch (SymbolNotFoundException | SymbolAmbiguousException e) {
                // skip
            }
        }
        return results;
    }

    /**
     * Attempt to find a unique ProjectFile for the given path, or a partial match ignoring directory.
     * Returns null if we cannot find exactly one match. The caller can then store an error on the request.
     *
     * Steps:
     *   1) Check if an exact match is recognized by contextManager.toFile(...).
     *   2) If that fails, find all files in contextManager.getEditableFiles() whose filename matches ignoring path.
     *   3) If that fails, find all tracked files in the git repo whose filename matches ignoring path.
     *   4) If exactly 1 match is found, return it. Otherwise return null.
     */
    private ProjectFile resolveProjectFile(String filename, boolean createNew) {
        // Step 1: see if contextManager can directly map this path
        var direct = contextManager.toFile(filename);
        if (createNew || direct != null && contextManager.getEditableFiles().contains(direct)) {
            return direct;
        }

        // Step 2: search editable files by last segment
        String fileNameOnly = Path.of(filename).getFileName().toString();
        var editableMatches = new ArrayList<ProjectFile>();
        for (var ef : contextManager.getEditableFiles()) {
            var last = ef.absPath().getFileName().toString();
            if (fileNameOnly.equals(last)) {
                editableMatches.add(ef);
            }
        }
        if (editableMatches.size() == 1) {
            return editableMatches.get(0);
        }
        if (editableMatches.size() > 1) {
            throw new SymbolAmbiguousException("No exact match for %s and %s is ambiguous".formatted(filename, fileNameOnly));
        }

        // Step 3: look in the repo's tracked files
        var tracked = contextManager.getRepo().getTrackedFiles();
        var repoMatches = new ArrayList<ProjectFile>();
        for (var t : tracked) {
            String last = Path.of(t.toString()).getFileName().toString();
            if (fileNameOnly.equals(last)) {
                repoMatches.add(t);
            }
        }
        if (repoMatches.size() == 1) {
            return repoMatches.getFirst();
        }
        if (repoMatches.size() > 1) {
            throw new SymbolAmbiguousException("No exact match for %s and %s is ambiguous".formatted(filename, fileNameOnly));
        }

        throw new SymbolNotFoundException("File not found: " + filename);
    }

    /**
     * An internal exception thrown when a tool fails. The parse or execute logic
     * catches it and packages the message for the user.
     */
    public static class ToolExecutionException extends RuntimeException {
        public ToolExecutionException(String s) {
            super(s);
        }

        public ToolExecutionException(String s, Throwable cause) {
            super(s, cause);
        }
    }

    /**
     * Represents a single parsed tool request.
     * If error() is non-null, the request is invalid.
     * Otherwise, we have at least a non-null file or functionLocation.
     */
    public record ValidatedToolRequest(ToolExecutionRequest originalRequest,
                                       ProjectFile file,
                                       FunctionLocation functionLocation,
                                       String error,
                                       RequestContents contents,
                                       String description) {
        /**
         * Convenience factory for error responses.
         */
        public static ValidatedToolRequest error(ToolExecutionRequest req, String msg, String description) {
            return new ValidatedToolRequest(req, null, null, msg, null, description);
        }

        /**
         * Accessors for individual content fields, delegating to the nested RequestContents.
         */
        public String oldLines() {
            return contents.oldLines();
        }

        public String newLines() {
            return contents.newLines();
        }

        public String newFileContent() {
            return contents.newFileContent();
        }

        public String newFunctionBody() {
            return contents.newFunctionBody();
        }

        public String explanation() {
            return contents.explanation();
        }

        /**
         * Single record grouping the various forms of replacement text.
         */
        public record RequestContents(String oldLines, String newLines, String newFileContent, String newFunctionBody, String explanation) { }
    }

    @Tool(value = "Explain your strategy to the user. ALWAYS CALL THIS BEFORE OTHER TOOLS..")
    public void explain(@P("Explanation") String text) {
        throw new UnsupportedOperationException("runSession should special case `explain`");
    }

    private ValidatedToolRequest parseExplain(ToolExecutionRequest req, Map<String, Object> argMap) {
        var text = (String) argMap.get("text");
        if (text == null) {
            return ValidatedToolRequest.error(req, "explain requires parameter `text`", "explain: ???");
        }
        var contents = new ValidatedToolRequest.RequestContents(null, null, null, null, text);
        var shortDesc = text.substring(0, Math.min(20, text.length())).replace('\n', ' ');
        return new ValidatedToolRequest(req, null, null, null, contents, "explain: " + shortDesc);
    }

    /**
     * Returns the list of ToolSpecifications for this tool set.
     * If the model does not require emulated tools, the "explain" tool is filtered out.
     */
    public List<ToolSpecification> getToolSpecifications(StreamingChatLanguageModel model) {
        var all = ToolSpecifications.toolSpecificationsFrom(this);
        if (!requiresEmulatedTools(model)) {
            all = all.stream().filter(spec -> !spec.name().equals("explain")).toList();
        }
        return all;
    }

    public static boolean requiresEmulatedTools(StreamingChatLanguageModel model) {
        var modelName = Models.nameOf(model);
        return modelName.toLowerCase().contains("deepseek") || modelName.toLowerCase().contains("gemini") || modelName.toLowerCase().contains("o3-mini");
    }
}
