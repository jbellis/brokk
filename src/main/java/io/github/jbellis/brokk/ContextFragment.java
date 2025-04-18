package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.awt.*;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public interface ContextFragment extends Serializable {

    // Static counter for all fragments
    AtomicInteger NEXT_ID = new AtomicInteger(1);
    
    /** Unique identifier for this fragment */
    int id();
    /** short description in history */
    String shortDescription();
    /** longer description displayed in context table */
    String description();
    /** raw content for preview */
    String text() throws IOException;
    /** content formatted for LLM */
    String format() throws IOException;
    default boolean isText() {
        return true;
    }
    default Image image() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * code sources found in this fragment
     */
    Set<CodeUnit> sources(IProject project);

    /**
     * Returns all repo files referenced by this fragment.
     * This is used when we *just* want to manipulate or show actual files,
     * rather than the code units themselves.
     */
    Set<ProjectFile> files(IProject project);

    String syntaxStyle();

    /**
     * should AutoContext exclude classes found in this fragment?
     */
    default boolean isEligibleForAutoContext() {
        return isText();
    }

    static Set<ProjectFile> parseRepoFiles(String text, IProject project) {
        var exactMatches = project.getFiles().stream().parallel()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        return project.getFiles().stream().parallel()
                .filter(f -> text.contains(f.getFileName()))
                .collect(Collectors.toSet());
    }

    sealed interface OutputFragment permits ConversationFragment, SessionFragment, StringFragment, PasteTextFragment, SearchFragment {
        List<TaskEntry> getMessages();
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment {
        BrokkFile file();

        @Override
        default Set<ProjectFile> files(IProject project) {
            return Set.of();
        }

        @Override
        default String text() throws IOException {
            return file().read();
        }

        @Override
        default public String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
        default String format() throws IOException {
            return """
            <file path="%s" fragmentid="%d">
            %s
            </file>
            """.stripIndent().formatted(file().toString(), id(), text());
        }
    }

    record ProjectPathFragment(ProjectFile file, int id) implements PathFragment {
        private static final long serialVersionUID = 2L;

        public ProjectPathFragment(ProjectFile file) {
            this(file, NEXT_ID.getAndIncrement());
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return Set.of(file);
        }

        @Override
        public String description() {
            if (file.getParent().isEmpty()) {
                return file.getFileName();
            }
            return "%s [%s]".formatted(file.getFileName(), file.getParent());
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return project.getAnalyzerUninterrupted().getClassesInFile(file);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(file);
        }
    }

    /**
     * Represents a specific revision of a ProjectFile from Git history.
     */
    record GitFileFragment(ProjectFile file, String revision, int id) implements PathFragment {
        private static final long serialVersionUID = 2L;

        public GitFileFragment(ProjectFile file, String revision) {
            this(file, revision, NEXT_ID.getAndIncrement());
        }

        private String shortRevision() {
            return (revision.length() > 7) ? revision.substring(0, 7) : revision;
        }

        @Override
        public String shortDescription() {
            return "%s @%s".formatted(file().getFileName(), shortRevision());
        }

        @Override
        public String description() {
             String parentDir = file.getParent();
             return parentDir.isEmpty()
                    ? shortDescription()
                    : "%s [%s]".formatted(shortDescription(), parentDir);
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            // Treat historical content as potentially different from current; don't claim sources
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            // Content is historical, not suitable for auto-context
            return false;
        }

        @Override
        public String format() throws IOException {
             return """
               <file path="%s" revision="%s">
               %s
               </file>
               """.stripIndent().formatted(file().toString(), revision(), text());
        }

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, shortRevision());
        }
    }


    record ExternalPathFragment(ExternalFile file, int id) implements PathFragment {
        private static final long serialVersionUID = 2L;

        public ExternalPathFragment(ExternalFile file) {
            this(file, NEXT_ID.getAndIncrement());
        }

        @Override
        public String shortDescription() {
            return description();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }
        }
    
        /**
         * Represents an image file, either from the project or external.
         */
        record ImageFileFragment(BrokkFile file, int id) implements PathFragment {
            private static final long serialVersionUID = 1L;
    
            public ImageFileFragment(BrokkFile file) {
                this(file, NEXT_ID.getAndIncrement());
                assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            }
    
            @Override
            public String shortDescription() {
                return file().getFileName();
            }
    
            @Override
            public String description() {
                 if (file instanceof ProjectFile pf && !pf.getParent().isEmpty()) {
                    return "%s [%s]".formatted(file.getFileName(), pf.getParent());
                 }
                 return file.toString(); // For ExternalFile or root ProjectFile
            }
    
            @Override
            public boolean isText() {
                return false;
            }
    
            @Override
            public String text() {
                throw new UnsupportedOperationException();
            }
    
            @Override
            public Image image() throws IOException {
                return javax.imageio.ImageIO.read(file.absPath().toFile());
            }
    
            @Override
            public Set<CodeUnit> sources(IProject project) {
                return Set.of();
            }
    
             @Override
            public Set<ProjectFile> files(IProject project) {
                return (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of();
            }
    
            @Override
            public String format() {
                // Format for LLM, indicating image content (similar to PasteImageFragment)
                return """
                  <file path="%s" fragmentid="%d">
                  [Image content provided out of band]
                  </file>
                  """.stripIndent().formatted(file().toString(), id());
            }
    
            @Override
            public String toString() {
                return "ImageFileFragment('%s')".formatted(file);
            }
        }
    
        static PathFragment toPathFragment(BrokkFile bf) {
            if (bf.isText()) {
                if (bf instanceof ProjectFile repo) {
                    return new ProjectPathFragment(repo);
                } else if (bf instanceof ExternalFile ext) {
                    return new ExternalPathFragment(ext);
                }
            } else {
                // If it's not text, treat it as an image
                return new ImageFileFragment(bf);
            }
            // Should not happen if bf is ProjectFile or ExternalFile
            throw new IllegalArgumentException("Unsupported BrokkFile subtype: " + bf.getClass().getName());
        }

    abstract class VirtualFragment implements ContextFragment {
        private static final long serialVersionUID = 2L;
        private final int id;

        public VirtualFragment() {
            this.id = NEXT_ID.getAndIncrement();
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String format() throws IOException {
            return """
            <fragment description="%s" fragmentid="%d">
            %s
            </fragment>
            """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            // lowercase the first letter in description()
            return description().substring(0, 1).toLowerCase() + description().substring(1);
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return parseRepoFiles(text(), project);
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return files(project).stream()
                    .flatMap(f -> project.getAnalyzerUninterrupted().getClassesInFile(f).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public abstract String text();

        // Override equals and hashCode for proper comparison, especially for EMPTY
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VirtualFragment that = (VirtualFragment) o;
            return id() == that.id();
        }

        @Override
        public int hashCode() {
            // Use id for hashCode
            return Integer.hashCode(id());
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return isText();
        }
    }

    final class StringFragment extends VirtualFragment implements OutputFragment {
            private static final long serialVersionUID = 3L;
            private final String text;
            private final String description;
        private final String syntaxStyle;

        public StringFragment(String text, String description, String syntaxStyle) {
            super();
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String syntaxStyle() {
            return syntaxStyle;
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }

        @Override
        public List<TaskEntry> getMessages() {
            return List.of(new TaskEntry(0, description, List.of(new CustomMessage(Map.of("text", text))), null));
        }
    }

    final class SearchFragment extends VirtualFragment implements OutputFragment {
            private static final long serialVersionUID = 3L;
            private final String query;
            private final String explanation;
        private final Set<CodeUnit> sources;

        public SearchFragment(String query, String explanation, Set<CodeUnit> sources) {
            super();
            assert query != null;
            assert explanation != null;
            assert sources != null;
            this.query = query;
            this.explanation = explanation;
            this.sources = sources;
        }

        @Override
        public String text() {
            return explanation;
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return sources;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return sources.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "Search: " + query;
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public String toString() {
            return "SearchFragment('%s')".formatted(query);
        }

        @Override
        public List<TaskEntry> getMessages() {
            var messages = List.of(
                    new UserMessage("# Query\n\n%s".formatted(query)),
                    new AiMessage("# Answer\n\n%s".formatted(explanation))
            );
            return List.of(new TaskEntry(0, "Search", messages, null));
        }
    }

    abstract class PasteFragment extends ContextFragment.VirtualFragment {
        private static final long serialVersionUID = 1L;

        protected transient Future<String> descriptionFuture;

        public PasteFragment(Future<String> descriptionFuture) {
            super();
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return "Paste of " + descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
        }

        private void writeObject(java.io.ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            String desc;
            if (descriptionFuture.isDone()) {
                try {
                    desc = descriptionFuture.get();
                } catch (Exception e) {
                    desc = "(Error summarizing paste)";
                }
            } else {
                desc = "(Paste summary incomplete)";
            }
            out.writeObject(desc);
        }

        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            String desc = (String) in.readObject();
            this.descriptionFuture = java.util.concurrent.CompletableFuture.completedFuture(desc);
        }
    }

    final class PasteTextFragment extends PasteFragment implements OutputFragment {
            private static final long serialVersionUID = 4L;
            private final String text;

        public PasteTextFragment(String text, Future<String> descriptionFuture) {
            super(descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        @Override
        public String syntaxStyle() {
            // TODO infer from contents
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public List<TaskEntry> getMessages() {
            var description = descriptionFuture.isDone() ? description() : "Paste";
            return List.of(new TaskEntry(0, description, List.of(new CustomMessage(Map.of("text", text))), null));
        }
    }

    class PasteImageFragment extends PasteFragment {
        private static final long serialVersionUID = 1L;
        private final Image image;

        public PasteImageFragment(Image image, Future<String> descriptionFuture) {
            super(descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Image image() {
            return image;
        }

        @Override
        public String syntaxStyle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String format() throws IOException {
            return """
              <fragment description="%s" fragmentid="%d">
              [Image content provided out of band]
              </fragment>
              """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return Set.of();
        }
    }

    class StacktraceFragment extends VirtualFragment {
        private static final long serialVersionUID = 2L;
        private final Set<CodeUnit> sources;
        private final String original;
        private final String exception;
        private final String code;

        public StacktraceFragment(Set<CodeUnit> sources, String original, String exception, String code) {
            super();
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return sources;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return sources.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
    }

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    class UsageFragment extends VirtualFragment {
        private static final long serialVersionUID = 2L;
        private final String type;
        private final String targetIdentifier;
        private final Set<CodeUnit> classes;
        private final String code;

        public UsageFragment(String type, String targetIdentifier, Set<CodeUnit> classes, String code) {
            super();
            assert type != null;
            assert targetIdentifier != null;
            assert classes != null;
            assert code != null;
            this.type = type;
            this.targetIdentifier = targetIdentifier;
            this.classes = classes;
            this.code = code;
        }

        @Override
        public String text() {
            return code;
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return classes;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return classes.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "%s of %s".formatted(type, targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
    }

    class SkeletonFragment extends VirtualFragment {
        private static final long serialVersionUID = 3L;
        final Map<CodeUnit, String> skeletons;

        public SkeletonFragment(Map<CodeUnit, String> skeletons) {
            super();
            assert skeletons != null;
            this.skeletons = skeletons;
        }

        @Override
        public String text() {
            if (isEmpty()) {
                return "";
            }
            var skeletonsByPackage = skeletons.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> {
                                var pkg = e.getKey().packageName();
                                return pkg.isEmpty() ? "(default package)" : pkg;
                            },
                            Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (v1, v2) -> v1,
                                    java.util.LinkedHashMap::new
                            )
                    ));
            if (skeletons.isEmpty()) return "";
            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> {
                        var packageHeader = "package " + pkgEntry.getKey() + ";";
                        var pkgCode = String.join("\n\n", pkgEntry.getValue().values());
                        return packageHeader + "\n\n" + pkgCode;
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        private Set<CodeUnit> nonDummy() {
            return skeletons.keySet().stream().filter(k -> k.source() != AutoContext.DUMMY).collect(Collectors.toSet());
        }

        private boolean isEmpty() {
            return nonDummy().isEmpty();
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return nonDummy();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return nonDummy().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            assert !isEmpty();
            return "Summary of " + skeletons.keySet().stream()
                    .map(CodeUnit::identifier)
                    .sorted()
                    .collect(Collectors.joining(", "));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            assert !isEmpty();
            return """
              <summary classes="%s" fragmentid="%d">
              %s
              </summary>
              """.stripIndent().formatted(
                      skeletons.keySet().stream()
                              .map(CodeUnit::fqName)
                              .sorted()
                              .collect(Collectors.joining(", ")),
                      id(),
                      text()
              );
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    /** Base class for fragments that represent task history */
        abstract class TaskHistoryFragment extends VirtualFragment {
            private static final long serialVersionUID = 4L;
            protected final List<TaskEntry> history;

        public TaskHistoryFragment(List<TaskEntry> history) {
            super();
            assert history != null;
            this.history = List.copyOf(history);
        }

        public List<TaskEntry> getMessages() {
            return history;
        }

        @Override
        public String text() {
            return history.stream()
                    .map(TaskEntry::toString)
                    .collect(Collectors.joining("\n\n"));
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            return """
              <taskhistory fragmentid="%d">
              %s
              </taskhistory>
              """.stripIndent().formatted(id(), text());
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }
    }

    /** represents the entire Task History */
        final class ConversationFragment extends TaskHistoryFragment implements OutputFragment {
            private static final long serialVersionUID = 4L;
    
            public ConversationFragment(List<TaskEntry> history) {
            super(history);
        }

        @Override
        public String description() {
            return "Task History (" + history.size() + " task%s)".formatted(history.size() > 1 ? "s" : "");
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }
    }

    /** represents a single session's Task History */
        final class SessionFragment extends TaskHistoryFragment implements OutputFragment {
            private static final long serialVersionUID = 4L;
            private final String sessionName;

        public SessionFragment(List<TaskEntry> history, String sessionName) {
            super(history);
            this.sessionName = sessionName;
        }

        @Override
        public String description() {
            return "AI response: " + sessionName;
        }

        @Override
        public String toString() {
            return "SessionFragment(" + sessionName + ")";
        }
    }

    record AutoContext(SkeletonFragment fragment, int id) implements ContextFragment {
        private static final long serialVersionUID = 3L;

        public AutoContext(SkeletonFragment fragment) {
            this(fragment, NEXT_ID.getAndIncrement());
        }
        private static final ProjectFile DUMMY = new ProjectFile(Path.of("//dummy/share"), Path.of("dummy"));
        // Use constants for these special values
        private static final int EMPTY_ID = -1;
        private static final int DISABLED_ID = -2;
        private static final int REBUILDING_ID = -3;
        
        public static final AutoContext EMPTY =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Enabled, but no references found"), "")), EMPTY_ID);
        public static final AutoContext DISABLED =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Disabled"), "")), DISABLED_ID);
        public static final AutoContext REBUILDING =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Updating"), "")), REBUILDING_ID);

        public AutoContext {
            assert fragment != null;
            assert !fragment.skeletons.isEmpty();
        }

        public boolean isEmpty() {
            return fragment.isEmpty();
        }

        @Override
        public String text() {
            return fragment.text();
        }

        @Override
        public Set<CodeUnit> sources(IProject project) {
            return fragment.sources(project);
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return fragment.files(project);
        }

        @Override
        public String description() {
            return "[Auto] " + fragment.skeletons.keySet().stream()
                    .map(CodeUnit::identifier)
                    .collect(Collectors.joining(", "));
        }

        @Override
        public String shortDescription() {
            if (isEmpty()) {
                return "Autosummary " + fragment.skeletons.keySet().stream().findFirst().orElseThrow();
            }
            return "Autosummary of " + fragment.skeletons.keySet().stream()
                    .map(CodeUnit::identifier)
                    .collect(Collectors.joining(", "));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            if (isEmpty()) {
                return "";
            }
            return fragment.format();
        }
        
        @Override
        public int id() {
            return id;
        }

        @Override
        public String toString() {
            return "AutoContext('%s')".formatted(description());
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
    }
}
