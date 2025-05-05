package io.github.jbellis.brokk.analyzer;

// Treesitter imports

import io.github.jbellis.brokk.IProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic, language-agnostic skeleton extractor backed by Tree-sitter.
 * Stores summarized skeletons for top-level definitions only.
 * <p>
 * Subclasses provide the language–specific bits: which Tree-sitter grammar,
 * which file extensions, which query, and how to map a capture to a {@link CodeUnit}.
 */
public abstract class TreeSitterAnalyzer implements IAnalyzer {
    protected static final Logger log = LoggerFactory.getLogger(TreeSitterAnalyzer.class);

    // Native library loading is assumed automatic by the io.github.bonede.tree_sitter library.

    /* ---------- instance state ---------- */
    protected final TSLanguage tsLanguage;
    private final TSQuery query;
    private final Map<ProjectFile, Map<CodeUnit, String>> skeletons = new ConcurrentHashMap<>();
    private final Map<ProjectFile, Set<CodeUnit>> fileClasses = new ConcurrentHashMap<>();
    private final IProject project;

    /* ---------- constructor ---------- */
    protected TreeSitterAnalyzer(IProject project) {
        this.project = project;
        this.tsLanguage = getTSLanguage(); // Provided by subclass
        Objects.requireNonNull(tsLanguage, "Tree-sitter TSLanguage must not be null");

        String rawQueryString = loadResource(getQueryResource());
        this.query = new TSQuery(tsLanguage, rawQueryString);

        log.debug("Initializing TSA for {} (query: {})",
                  project.getAnalyzerLanguage(), getQueryResource());

        var validExtensions = project.getAnalyzerLanguage().getExtensions();
        log.debug("Filtering project files for extensions: {}", validExtensions);

        project.getAllFiles().stream()
                .filter(pf -> {
                    var pathStr = pf.absPath().toString();
                    return validExtensions.stream().anyMatch(pathStr::endsWith);
                })
                .parallel()
                .forEach(pf -> {
                    // TSParser is not threadsafe, so we create a parser per thread
                    var localParser = new TSParser();
                    try {
                        if (!localParser.setLanguage(tsLanguage)) {
                            log.error("Failed to set language on thread-local TSParser for language {} in file {}", tsLanguage, pf);
                            return; // Skip this file if parser setup fails
                        }
                        var map = parseAndExtractSkeletons(pf, localParser);
                        if (!map.isEmpty()) {
                             skeletons.put(pf, map);
                             // Extract and store classes for this file
                             var classesInFile = map.keySet().stream()
                                 .filter(CodeUnit::isClass)
                                 .collect(Collectors.toSet());
                             if (!classesInFile.isEmpty()) {
                                 fileClasses.put(pf, classesInFile);
                             }
                        } else {
                             // Explicitly log when the returned map is empty
                             log.warn("parseAndExtractSkeletons returned an empty map for file: {}", pf);
                        }
                    } catch (Exception e) {
                        log.warn("Error parsing {}: {}", pf, e, e);
                    }
                });
    }

    /* ---------- IAnalyzer ---------- */
    @Override public boolean isEmpty() { return skeletons.isEmpty(); }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var m = skeletons.get(file);
        return m == null ? Map.of() : Collections.unmodifiableMap(m);
    }
    
    @Override
    public Set<CodeUnit> getClassesInFile(ProjectFile file) {
        var classes = fileClasses.get(file);
        return classes == null ? Set.of() : Collections.unmodifiableSet(classes);
    }
    
    @Override
    public scala.Option<String> getSkeleton(String fqName) {
        for (var fileSkeletons : skeletons.values()) {
            for (var entry : fileSkeletons.entrySet()) {
                // Compute the fqName from the CodeUnit's components before comparing
                if (entry.getKey().fqName().equals(fqName)) {
                    return scala.Option.apply(entry.getValue());
                }
            }
        }
        return scala.Option.empty();
    }

    /* ---------- abstract hooks ---------- */
    /** Tree-sitter TSLanguage grammar to use (e.g. {@code new TreeSitterPython()}). */
    protected abstract TSLanguage getTSLanguage();

    /** Class-path resource for the query (e.g. {@code "treesitter/python.scm"}). */
    protected abstract String getQueryResource();

    /**
     * Translate a capture produced by the query into a {@link CodeUnit}.
     * Return {@code null} to ignore this capture.
     */
    protected abstract CodeUnit createCodeUnit(ProjectFile file,
                                               String captureName,
                                               String simpleName);

    /** Captures that should be ignored entirely. */
    protected Set<String> getIgnoredCaptures() { return Set.of(); }

    /**
     * Get the project this analyzer is associated with.
     */
    protected IProject getProject() {
        return project;
    }

    /* ---------- core parsing ---------- */
    /** Parses a single file and extracts skeletons using the provided thread-local parser. */
    private Map<CodeUnit, String> parseAndExtractSkeletons(ProjectFile file, TSParser localParser) throws IOException {
        String src = Files.readString(file.absPath(), StandardCharsets.UTF_8);
        Map<CodeUnit, String> result = new HashMap<>();

        // TSTree does not implement AutoCloseable, rely on GC + Cleaner for resource management
        TSTree tree = localParser.parseString(null, src);
        TSNode rootNode = tree.getRootNode();
        if (rootNode.isNull()) {
            log.warn("Parsing failed or produced null root node for {}", file);
            return Map.of();
        }
        log.debug("Root node type for {}: {}", file, rootNode.getType()); // Log root node type

        // Map to store potential top-level declaration nodes found during the query
        Map<TSNode, String> declarationNodes = new HashMap<>();

        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(this.query, rootNode); // Use the query field, execute on root node

        TSQueryMatch match = new TSQueryMatch(); // Reusable match object
        while (cursor.nextMatch(match)) {
            // Group nodes by capture name for this specific match
            Map<String, TSNode> capturedNodes = new HashMap<>();
            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = this.query.getCaptureNameForId(capture.getIndex());
                if (getIgnoredCaptures().contains(captureName)) continue;

                TSNode node = capture.getNode();
                if (node != null && !node.isNull()) {
                    // Store the first non-null node found for this capture name in this match
                    // Note: Overwrites if multiple nodes have the same capture name in one match.
                    // The old code implicitly took the first from a list; this takes the last encountered.
                    // If specific handling of multiple nodes per capture/match is needed, adjust here.
                    capturedNodes.put(captureName, node);
                }
            }

            // Process each potential declaration found in the match
            for (var entry : capturedNodes.entrySet()) {
                String captureName = entry.getKey();
                TSNode node = entry.getValue();

                // We only care about captures designating a definition node (e.g., class.definition, function.definition)
                // The field.declaration capture is handled separately if needed, but won't be top-level.
                if (captureName.endsWith(".definition")) { // Changed from .declaration to .definition

                    // Try to find the corresponding name node within the same match
                    String expectedNameCapture = captureName.replace(".definition", ".name"); // Adjusted suffix
                    TSNode nameNode = capturedNodes.get(expectedNameCapture);

                    // Determine simple name
                    String simpleName;
                    if (nameNode != null && !nameNode.isNull()) {
                        // Use content from the explicit *.name node if found
                        simpleName = src.substring(nameNode.getStartByte(), nameNode.getEndByte());
                    } else {
                        // Fallback: Try extracting name directly from the definition node ('node')
                        log.warn("Explicit *.name capture ('{}') not found or invalid in match for definition node '{}'. Falling back to traversal.",
                                 expectedNameCapture, textSlice(node, src).lines().findFirst().orElse(""));
                        Optional<String> fallbackNameOpt = extractSimpleName(node, src); // Calls helper that logs its own failure
                        if (fallbackNameOpt.isPresent()) {
                             log.debug("Fallback extractSimpleName succeeded for definition node.");
                             simpleName = fallbackNameOpt.get();
                        } else {
                             log.warn("Fallback extractSimpleName also failed for definition node type {} at line {}.",
                                      node.getType(), node.getStartPoint().getRow() + 1);
                             simpleName = null;
                        }
                    }

                    // Proceed if we have the essential parts
                    if (simpleName != null && !simpleName.isBlank()) {
                        // Store the node and its primary capture type for later processing
                        declarationNodes.putIfAbsent(node, captureName);
                        log.debug("MATCH [{}]: Found potential definition: Capture [{}], Node Type [{}], Simple Name [{}] -> Storing for later processing.",
                                  match.getId(), captureName, node.getType(), simpleName);
                    } else {
                         // This log now indicates *both* primary and fallback name extraction failed in the first loop.
                         log.warn("Could not determine simple name for definition capture {} (Node Type [{}], Line {}) in file {} after checking explicit capture and fallback traversal.",
                                  captureName, node.getType(), node.getStartPoint().getRow() + 1, file);
                    }
                }
                // We are primarily interested in the *.definition captures now.
                // Other captures like *.name were used above to help find the simpleName.
                // Field declarations might also be captured but will likely not be top-level.
            }
        } // End main query loop

        // Now, build skeletons only for top-level definitions
        Map<CodeUnit, String> finalSkeletons = new HashMap<>();
        TSNode root = tree.getRootNode(); // Get root node again for isTopLevel check

        for (var entry : declarationNodes.entrySet()) {
            TSNode node = entry.getKey();
            String primaryCaptureName = entry.getValue(); // e.g., "class.definition"
            log.debug("Checking node type {} for top-level status.", node.getType()); // Log node being checked

            if (isTopLevel(node, root)) { // Call isTopLevel
                 log.debug("Node is top-level: {}", textSlice(node, src).lines().findFirst().orElse(""));
                 // Extract simple name using the standard fallback mechanism (which now only uses getChildByFieldName).
                 Optional<String> simpleNameOpt = extractSimpleName(node, src);

                 if (simpleNameOpt.isPresent()) {
                     String simpleName = simpleNameOpt.get();
                     log.debug("Processing top-level definition: Name='{}', Capture='{}', Node Type='{}'",
                               simpleName, primaryCaptureName, node.getType());
                      CodeUnit cu = createCodeUnit(file, primaryCaptureName, simpleName); // Use the definition capture name
                      if (cu != null) {
                          String skeleton = buildSkeletonString(node, src, primaryCaptureName);
                          // Log the skeleton result *before* checking if it's null/blank
                          log.debug("buildSkeletonString result for '{}': [{}]", simpleName, skeleton == null ? "NULL" : skeleton.isBlank() ? "BLANK" : skeleton.lines().findFirst().orElse("EMPTY"));
                          if (skeleton != null && !skeleton.isBlank()) {
                              log.debug("Storing TOP-LEVEL skeleton for {} in {} | Skeleton starts with: '{}'",
                                        cu, file, skeleton.lines().findFirst().orElse(""));
                              if (finalSkeletons.containsKey(cu)) {
                                 log.warn("Overwriting skeleton for {} in {}. Old: '{}', New: '{}'",
                                          cu, file, finalSkeletons.get(cu).lines().findFirst().orElse(""), skeleton.lines().findFirst().orElse(""));
                             }
                             finalSkeletons.put(cu, skeleton);
                         } else {
                             log.warn("buildSkeletonString returned empty/null for top-level node {} ({})", simpleName, primaryCaptureName);
                         }
                     } else {
                         log.warn("createCodeUnit returned null for top-level node {} ({})", simpleName, primaryCaptureName);
                     }
                 } else {
                     log.warn("Could not determine simple name for top-level node type {} in file {}", node.getType(), file);
                  }
             } else {
                  TSNode parent = node.getParent();
                  String parentType = (parent == null || parent.isNull()) ? "null" : parent.getType();
                  log.debug("Node is NOT top-level: Type='{}', ParentType='{}'. First line: '{}'",
                            node.getType(), parentType, textSlice(node, src).lines().findFirst().orElse(""));
             }
         }


        return finalSkeletons;
    }

    /** Helper to find a specific capture within a match related to a primary node */
    private Optional<String> findCaptureInMatch(TSQueryMatch match, String targetCaptureName, TSNode primaryNode, String src) {
       // This approach is flawed because 'match' is from the loop and not specific to the node processing after loop.
       // We should rely on node traversal (getChildByFieldName) or run targeted queries if needed.
       // Let's stick to extractSimpleName which uses traversal.
       return Optional.empty(); // Placeholder - use extractSimpleName
    }


    /* ---------- Skeleton Building Logic ---------- */

    /** Checks if a node is a direct child of the root node. */
    private boolean isTopLevel(TSNode node, TSNode rootNode) {
        if (node == null || node.isNull()) {
            log.debug("isTopLevel check: node is null"); // Changed to debug
            return false;
        }
         if (rootNode == null || rootNode.isNull()) {
            log.warn("isTopLevel check: rootNode is null!"); // Should not happen if initial check passed
            return false;
        }
        TSNode parent = node.getParent();
        boolean result;
        if (parent == null || parent.isNull()) {
            log.debug("isTopLevel check: parent is null for node type {}", node.getType()); // Changed to debug
            result = false;
        } else {
            // Check if parent's type is 'module' (common root for Python) OR if parent equals rootNode
            // This provides a potential fallback if direct equality check fails but type check works.
            boolean parentIsModule = "module".equals(parent.getType());
            boolean parentEqualsRoot = parent.equals(rootNode);
            result = parentEqualsRoot; // Primarily rely on equals
            if (!result && parentIsModule) {
                 log.warn("isTopLevel check: parent.equals(rootNode) was false, but parent type is 'module'. Treating as top-level. Node Type='{}', Parent Type='{}', Root Type='{}'",
                          node.getType(), parent.getType(), rootNode.getType());
                 result = true; // Use type check as fallback
            } else {
                 log.debug("isTopLevel check: Node Type='{}', Parent Type='{}', Root Type='{}', Parent Type == 'module'? {}, Parent == Root? {}",
                           node.getType(), parent.getType(), rootNode.getType(), parentIsModule, parentEqualsRoot);
            }
        }
        return result;
    }

    /** Calculates the leading whitespace indentation for the line the node starts on. */
    private String computeIndentation(TSNode node, String src) {
        int startByte = node.getStartByte();
        int lineStartByte = src.lastIndexOf('\n', startByte - 1);
        if (lineStartByte == -1) {
            lineStartByte = 0; // Start of file
        } else {
            lineStartByte++; // Move past the newline character
        }

        // Find the first non-whitespace character on the line
        int firstCharByte = lineStartByte;
        while (firstCharByte < startByte && Character.isWhitespace(src.charAt(firstCharByte))) {
            firstCharByte++;
        }
        // Ensure we don't include indentation from within the node itself if it starts mid-line after whitespace
        int effectiveStart = Math.min(startByte, firstCharByte);

        // The indentation is the substring from the line start up to the first non-whitespace character.
        // Safety check: ensure lineStartByte <= firstCharByte and firstCharByte is within src bounds
        if (lineStartByte > firstCharByte || firstCharByte > src.length()) {
             log.warn("Indentation calculation resulted in invalid range [{}, {}] for node starting at byte {}",
                      lineStartByte, firstCharByte, startByte);
             return ""; // Return empty string on error
        }
        return src.substring(lineStartByte, firstCharByte);
    }


    /**
     * Builds a summarized skeleton string for a given top-level definition node.
     * Uses tree traversal to find relevant parts like signature and body.
     */
    private String buildSkeletonString(TSNode definitionNode, String src, String primaryCaptureName) {
        List<String> lines = new ArrayList<>();
        String baseIndent = computeIndentation(definitionNode, src);

        // Common elements: decorators
        List<TSNode> decorators = getPrecedingDecorators(definitionNode, src);
        for (TSNode decoratorNode : decorators) {
            lines.add(baseIndent + textSlice(decoratorNode, src));
        }

        if (primaryCaptureName.startsWith("class")) {
            buildClassSkeleton(definitionNode, src, baseIndent, lines);
        } else if (primaryCaptureName.startsWith("function")) {
            buildFunctionSkeleton(definitionNode, src, baseIndent, lines);
        } else {
             log.warn("Unsupported top-level definition type for skeleton: {}", primaryCaptureName);
             return textSlice(definitionNode, src); // Fallback: return raw text
        }

        return String.join("\n", lines);
    }

    private void buildClassSkeleton(TSNode classNode, String src, String baseIndent, List<String> lines) {
        TSNode nameNode = classNode.getChildByFieldName("name");
        TSNode bodyNode = classNode.getChildByFieldName("body"); // Usually a block node

        if (nameNode == null || nameNode.isNull() || bodyNode == null || bodyNode.isNull()) {
             log.warn("Could not find name or body for class node: {}", textSlice(classNode, src).lines().findFirst().orElse(""));
             lines.add(baseIndent + textSlice(classNode, src)); // Fallback
             log.warn("-> Falling back to raw text slice for class skeleton."); // Add log
             return;
         }

        // Class signature line
        String signature = textSlice(classNode.getStartByte(), bodyNode.getStartByte(), src).stripTrailing();
        lines.add(baseIndent + signature + " {");

        String memberIndent = baseIndent + "  "; // Indent members further

        // Iterate through direct children of the body node
        for (int i = 0; i < bodyNode.getNamedChildCount(); i++) {
            TSNode memberNode = bodyNode.getNamedChild(i);
            String memberType = memberNode.getType();

            // Handle potentially decorated methods/functions within the class
            if ("function_definition".equals(memberType)) {
                 // Handle undecorated functions (should have no preceding decorators in this context)
                 // Pass empty placeholder `""` so method bodies use { ... }
                 buildFunctionSkeleton(memberNode, src, memberIndent, lines);
            } else if ("decorated_definition".equals(memberType)) {
                 // Handle decorated functions
                 TSNode functionDefNode = null;
                 // Add decorators first
                 for (int j = 0; j < memberNode.getChildCount(); j++) {
                     TSNode child = memberNode.getChild(j);
                     if (child == null || child.isNull()) continue;
                      if ("decorator".equals(child.getType())) {
                          // Decorator should use the standard member indentation for skeleton output
                          lines.add(memberIndent + textSlice(child, src));
                      } else if ("function_definition".equals(child.getType())) {
                          functionDefNode = child;
                          // Don't break, might be other non-decorator children theoretically
                     }
                 }
                 // Then add the function skeleton
                 if (functionDefNode != null) {
                     // Pass empty placeholder `""` so method bodies use { ... }
                     buildFunctionSkeleton(functionDefNode, src, memberIndent, lines);
                 } else {
                     log.warn("decorated_definition node found without an inner function_definition: {}", textSlice(memberNode, src).lines().findFirst().orElse(""));
                 }
            }
            // Handle field assignments (like self.x = ...) - check if it's an assignment targeting 'self'
            else if ("expression_statement".equals(memberType)) {
                 TSNode expr = memberNode.getChild(0);
                 if (expr != null && "assignment".equals(expr.getType())) {
                    TSNode left = expr.getChildByFieldName("left");
                    if (left != null && "attribute".equals(left.getType())) {
                         TSNode object = left.getChildByFieldName("object");
                         if (object != null && "identifier".equals(object.getType()) && "self".equals(textSlice(object, src))) {
                             // It's an assignment like self.x = ...
                             lines.add(memberIndent + textSlice(memberNode, src).strip());
                         }
                    }
                 }
            }
            // Potentially handle nested classes, etc. here if needed
        }


        lines.add(baseIndent + "}");
    }

     private void buildFunctionSkeleton(TSNode funcNode, String src, String indent, List<String> lines) {
        TSNode nameNode = funcNode.getChildByFieldName("name");
        TSNode paramsNode = funcNode.getChildByFieldName("parameters");
        TSNode returnTypeNode = funcNode.getChildByFieldName("return_type"); // Might be null
        TSNode bodyNode = funcNode.getChildByFieldName("body"); // The block containing statements

        if (nameNode == null || nameNode.isNull() || paramsNode == null || paramsNode.isNull() || bodyNode == null || bodyNode.isNull()) {
              log.warn("Could not find essential parts (name, params, body) for function node: {}", textSlice(funcNode, src).lines().findFirst().orElse(""));
              lines.add(indent + textSlice(funcNode, src)); // Fallback
              log.warn("-> Falling back to raw text slice for function skeleton."); // Add log
              return;
         }

        // Handle async keyword
        TSNode firstChild = funcNode.getChild(0);
        String prefix = "";
        if (firstChild != null && "async".equals(firstChild.getType())) {
            prefix = "async ";
        }

        // Reconstruct signature parts from their specific nodes
        String name = textSlice(nameNode, src);
        String params = textSlice(paramsNode, src);
        String returnType = (returnTypeNode != null && !returnTypeNode.isNull()) ? " -> " + textSlice(returnTypeNode, src) : "";

        String reconstructedSignature = String.format("%sdef %s%s%s:", prefix, name, params, returnType);


        // Check if the body is more than just 'pass' or empty
        boolean hasMeaningfulBody = bodyNode.getNamedChildCount() > 1 ||
                                    (bodyNode.getNamedChildCount() == 1 && !"pass_statement".equals(bodyNode.getNamedChild(0).getType()));



        // Add the reconstructed line
        if (hasMeaningfulBody) {
             lines.add(indent + reconstructedSignature + " " + bodyPlaceholder());
         } else {
             lines.add(indent + reconstructedSignature); // Simple signature line without suffix
         }
    }

    protected abstract String bodyPlaceholder();

    /** Finds decorator nodes immediately preceding a function or class node. */
    private List<TSNode> getPrecedingDecorators(TSNode decoratedNode, String src) {
        List<TSNode> decorators = new ArrayList<>();
        TSNode current = decoratedNode.getPrevSibling();
        while (current != null && !current.isNull() && "decorator".equals(current.getType())) {
            decorators.add(current);
            current = current.getPrevSibling();
        }
        Collections.reverse(decorators); // Decorators should be in source order
        return decorators;
    }


    /** Extracts a substring from the source code based on node boundaries. */
    private String textSlice(TSNode node, String src) {
        if (node == null || node.isNull()) return "";
        return src.substring(node.getStartByte(), node.getEndByte());
    }

     /** Extracts a substring from the source code based on byte offsets. */
    private String textSlice(int startByte, int endByte, String src) {
        return src.substring(startByte, Math.min(endByte, src.length()));
    }


    /* ---------- helpers ---------- */

    /**
     * Fallback to extract a simple name from a declaration node when an explicit `.name` capture isn't found.
     * Tries finding a child node with field name "name", then falls back to the first child of type "identifier".
     * Needs the source string `src` for substring extraction.
     */
    private static Optional<String> extractSimpleName(TSNode decl, String src) {
        try {
            // Try finding a child node with field name "name" first.
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode != null && !nameNode.isNull()) {
                return Optional.of(src.substring(nameNode.getStartByte(), nameNode.getEndByte()));
            } else {
                 // Log failure specific to getChildByFieldName before falling back or failing
                 log.warn("getChildByFieldName('name') returned null or isNull for node type {} at line {}",
                          decl.getType(), decl.getStartPoint().getRow() + 1);
            }
            // Fallback removed. If getChildByFieldName fails, we assume name extraction isn't straightforward.
        } catch (Exception e) {
             log.warn("Error extracting simple name from node type {} for node starting with '{}...': {}",
                      decl.getType(), src.substring(decl.getStartByte(), Math.min(decl.getEndByte(), decl.getStartByte() + 20)), e.getMessage());
        }
        // If we reach here, it means the try block finished without returning (i.e., getChildByFieldName failed).
        // Log the failure and return empty.
        log.warn("extractSimpleName: Failed using getChildByFieldName('name') for node type {} at line {}",
                 decl.getType(), decl.getStartPoint().getRow() + 1);
        return Optional.empty();
    }

    private static String loadResource(String path) {
        try (InputStream in = TreeSitterAnalyzer.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Removed parseQueryPatterns as TSQuery constructor takes the raw query string.
}
