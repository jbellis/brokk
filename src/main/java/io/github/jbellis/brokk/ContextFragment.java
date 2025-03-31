package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public interface ContextFragment extends Serializable {
    /** short description in history */
    String shortDescription();
    /** longer description displayed in context table */
    String description();
    /** raw content */
    String text() throws IOException;
    /** content formatted for LLM */
    String format() throws IOException;

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

    /**
     * should classes found in this fragment be included in AutoContext?
     */
    boolean isEligibleForAutoContext();

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

    sealed interface PathFragment extends ContextFragment
            permits RepoPathFragment, ExternalPathFragment
    {
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
        default String format() throws IOException {
            return """
            <file path="%s">
            %s
            </file>
            """.formatted(file().toString(), text()).stripIndent();
        }
    }

    record RepoPathFragment(ProjectFile file) implements PathFragment {
        private static final long serialVersionUID = 1L;

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
            return project.getAnalyzer().getClassesInFile(file);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "RepoPathFragment('%s')".formatted(file);
        }
    }

    record ExternalPathFragment(ExternalFile file) implements PathFragment {
        private static final long serialVersionUID = 1L;

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

    static PathFragment toPathFragment(BrokkFile bf) {
        if (bf instanceof ProjectFile repo) {
            return new RepoPathFragment(repo);
        } else if (bf instanceof ExternalFile ext) {
            return new ExternalPathFragment(ext);
        }
        throw new IllegalArgumentException("Unknown BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        @Override
        public String format() throws IOException {
            return """
            <fragment description="%s">
            %s
            </fragment>
            """.formatted(description(), text()).stripIndent();
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
                    .flatMap(f -> project.getAnalyzer().getClassesInFile(f).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public abstract String text();
    }

    class StringFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String text;
        private final String description;

        public StringFragment(String text, String description) {
            super();
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
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }
    }

    class SearchFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
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
        public boolean isEligibleForAutoContext() {
            return true;
        }

        @Override
        public String toString() {
            return "SearchFragment('%s')".formatted(query);
        }
    }

    class PasteFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String text;
        private transient Future<String> descriptionFuture;

        public PasteFragment(String text, Future<String> descriptionFuture) {
            super();
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public String text() {
            return text;
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
        public boolean isEligibleForAutoContext() {
            return true;
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

    class StacktraceFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
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
        public boolean isEligibleForAutoContext() {
            return true;
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
        private static final long serialVersionUID = 1L;
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
        public boolean isEligibleForAutoContext() {
            return true;
        }
    }

    class SkeletonFragment extends VirtualFragment {
        private static final long serialVersionUID = 2L;
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
                    .map(CodeUnit::name)
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
            <summary classes="%s">
            %s
            </summary>
            """.formatted(
                    skeletons.keySet().stream()
                            .map(CodeUnit::fqName)
                            .sorted()
                            .collect(Collectors.joining(", ")),
                    text()
            ).stripIndent();
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    class ConversationFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final List<ChatMessage> messages;

        public ConversationFragment(List<ChatMessage> messages) {
            super();
            assert messages != null;
            this.messages = List.copyOf(messages);
        }

        @Override
        public String text() {
            return messages.stream()
                    .map(m -> m.type() + ": " + Models.getText(m))
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
        public String description() {
            return "Conversation history (" + messages.size() + " messages)";
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            return """
            <conversation>
            %s
            </conversation>
            """.formatted(text()).stripIndent();
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + messages.size() + " messages)";
        }

        public List<ChatMessage> getMessages() {
            return messages;
        }
    }

    record AutoContext(SkeletonFragment fragment) implements ContextFragment {
        private static final long serialVersionUID = 2L;
        private static final ProjectFile DUMMY = new ProjectFile(Path.of("//dummy/share"), Path.of("dummy"));
        public static final AutoContext EMPTY =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Enabled, but no references found"), "")));
        public static final AutoContext DISABLED =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Disabled"), "")));
        public static final AutoContext REBUILDING =
                new AutoContext(new SkeletonFragment(Map.of(CodeUnit.cls(DUMMY, "Updating"), "")));

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
                    .map(CodeUnit::name)
                    .collect(Collectors.joining(", "));
        }

        @Override
        public String shortDescription() {
            if (isEmpty()) {
                return "Autosummary " + fragment.skeletons.keySet().stream().findFirst().orElseThrow();
            }
            return "Autosummary of " + fragment.skeletons.keySet().stream()
                    .map(CodeUnit::name)
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
        public String toString() {
            return "AutoContext('%s')".formatted(description());
        }
    }
}
