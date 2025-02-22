package io.github.jbellis.brokk;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public interface ContextFragment {
    /** display name for interaction with commands */
    String source();
    /** longer description displayed to user */
    String description();
    /** raw content */
    String text() throws IOException;
    /** content formatted for LLM */
    String format() throws IOException;
    /** fq classes found in this fragment */
    Set<CodeUnit> sources(Analyzer analyzer);
    /** should classes found in this fragment be included in AutoContext? */
    boolean isEligibleForAutoContext();

    sealed interface PathFragment extends ContextFragment 
        permits RepoPathFragment, ExternalPathFragment
    {
        BrokkFile file();

        default String text() throws IOException {
            return file().read();
        }

        default String format() throws IOException {
            return """
            <file path="%s">
            %s
            </file>
            """.formatted(file().toString(), text()).stripIndent();
        }
    }

    record RepoPathFragment(RepoFile file) implements PathFragment {
        @Override
        public String source() {
            return file.getFileName();
        }

        @Override
        public String description() {
            return file.getParent();
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return analyzer.getClassesInFile(file);
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
        @Override
        public String source() {
            return file.toString();
        }

        @Override
        public String description() {
            return "(External)";
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return Set.of();
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }
    }

    static PathFragment toPathFragment(BrokkFile bf) {
        if (bf instanceof RepoFile repo) {
            return new RepoPathFragment(repo);
        } else if (bf instanceof ExternalFile ext) {
            return new ExternalPathFragment(ext);
        }
        throw new IllegalArgumentException("Unknown BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        private int position;

        protected VirtualFragment(int position) {
            this.position = position;
        }

        public int position() {
            return position;
        }

        @Override
        public String source() {
            // 1-based label in brackets
            return "%d".formatted(position + 1);
        }

        public final void renumber(int newPosition) {
            this.position = newPosition;
        }

        @Override
        public String format() throws IOException {
            return """
            <fragment id="%d" description="%s">
            %s
            </fragment>
            """.formatted(position, description(), text()).stripIndent();
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return GitRepo.instance.getTrackedFiles().stream().parallel()
                    .filter(f -> text().contains(f.toString()))
                    .flatMap(f -> analyzer.getClassesInFile(f).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public abstract String text(); // no exceptions
    }

    class StringFragment extends VirtualFragment {
        private final String text;
        private final String description;

        public StringFragment(int position, String text, String description) {
            super(position);
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

    class PasteFragment extends VirtualFragment {
        private final String text;
        private final Future<String> descriptionFuture;

        public PasteFragment(int position, String text, Future<String> descriptionFuture) {
            super(position);
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
    }

    class StacktraceFragment extends VirtualFragment {
        private final Set<CodeUnit> sources;
        private final String original;
        private final String exception;
        private final String code;

        public StacktraceFragment(int position, Set<CodeUnit> sources, String original, String exception, String code) {
            super(position);
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
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return sources;
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
        private final String targetIdentifier;
        private final Set<CodeUnit> classnames;
        private final String code;

        public UsageFragment(int position, String targetIdentifier, Set<CodeUnit> classnames, String code) {
            super(position);
            this.targetIdentifier = targetIdentifier;
            this.classnames = classnames;
            this.code = code;
        }

        @Override
        public String text() {
            return code;
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return classnames;
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return true;
        }
    }

    class SkeletonFragment extends VirtualFragment {
        private final List<String> shortClassnames;
        private final Set<CodeUnit> sources;
        private final String skeletonText;

        public SkeletonFragment(int position, List<String> shortClassnames, Set<CodeUnit> sources, String skeletonText) {
            super(position);
            this.shortClassnames = shortClassnames;
            this.sources = sources;
            this.skeletonText = skeletonText;
        }

        @Override
        public String text() {
            return skeletonText;
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return sources;
        }

        @Override
        public String description() {
            return "Summary of " + String.join(", ", shortClassnames.stream().sorted().toList());
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() throws IOException {
            return """
            <summary classes="%s">
            %s
            </summary>
            """.formatted(String.join(", ", sources.stream().map(CodeUnit::reference).sorted().toList()), text()).stripIndent();
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    /**
     * A context fragment that holds a list of short class names and a text
     * representation (e.g. skeletons) of those classes.
     */
    class AutoContext implements ContextFragment {
        public static final AutoContext EMPTY = new AutoContext(List.of(new SkeletonFragment(-1, List.of("Enabled, but no references found"), Set.of(), "")));
        public static final AutoContext DISABLED  = new AutoContext(List.of(new SkeletonFragment(-1, List.of("Disabled"), Set.of(), "")));

        private final List<SkeletonFragment> skeletons;

        public AutoContext(List<SkeletonFragment> skeletons) {
            this.skeletons = skeletons;
        }

        public List<SkeletonFragment> getSkeletons() {
            return skeletons;
        }

        @Override
        public String source() {
            return "0 [Auto]";
        }

        @Override
        public String text() {
            return String.join("\n\n", skeletons.stream().map(SkeletonFragment::text).toList());
        }

        @Override
        public Set<CodeUnit> sources(Analyzer analyzer) {
            return skeletons.stream().flatMap(s -> s.sources.stream()).collect(java.util.stream.Collectors.toSet());
        }

        /**
         * Returns a comma-separated list of short class names (no package).
         */
        @Override
        public String description() {
            return String.join(", ", skeletons.stream().flatMap(s -> s.shortClassnames.stream()).toList());
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() throws IOException {
            String st = "";
            for (SkeletonFragment s : skeletons) {
                if (!st.isEmpty()) {
                    st += "\n\n";
                }
                st += s.format();
            }
            return st;
        }

        @Override
        public String toString() {
            return "AutoContext('%s')".formatted(description());
        }
    }
}
