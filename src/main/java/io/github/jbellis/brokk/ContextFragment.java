package io.github.jbellis.brokk;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public interface ContextFragment {
    String source();
    String text() throws IOException;
    String description();
    Set<String> classnames(Analyzer analyzer);


    record PathFragment(RepoFile file) implements ContextFragment {
        @Override
        public String source() {
            return file.getFileName();
        }

        @Override
        public String text() throws IOException {
            return file.read();
        }

        @Override
        public String description() {
            return file.getParent();
        }

        @Override
        public Set<String> classnames(Analyzer analyzer) {
            return analyzer.classesInFile(file);
        }

        @Override
        public String toString() {
            return "PathFragment('%s')".formatted(file);
        }
    }

    interface VirtualFragment extends ContextFragment {
        int position();

        // implementations should override this if they have a better option!
        @Override
        default Set<String> classnames(Analyzer analyzer) {
            return ContextManager.getTrackedFiles().stream().parallel()
                    .filter(f -> text().contains(f.toString()))
                    .flatMap(f -> analyzer.classesInFile(f).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        String text(); // no exceptions
    }

    record StringFragment(int position, String text, String description) implements VirtualFragment {
        @Override
        public String source() {
            // 1-based label in brackets
            return "[%d]".formatted(position + 1);
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }
    }

    record PasteFragment(int position, String text, Future<String> descriptionFuture) implements VirtualFragment {
        @Override
        public String source() {
            // 1-based label in brackets
            return "[%d]".formatted(position + 1);
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
    }

    record StacktraceFragment(int position, Set<String> classnames, String original, String exception, String code) implements VirtualFragment {
        @Override
        public String source() {
            // 1-based label in brackets
            return "[%d]".formatted(position + 1);
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public Set<String> classnames(Analyzer analyzer) {
            return classnames;
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }
    }

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    record UsageFragment(int position, String targetIdentifier, Set<String> classnames, String code) implements VirtualFragment {
        public String source() {
            // 1-based label in brackets
            return "[%d]".formatted(position + 1);
        }

        public String text() {
            return "Uses of %s:\n\n%s".formatted(targetIdentifier, code);
        }

        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public Set<String> classnames(Analyzer analyzer) {
            return classnames;
        }
    }

    class SkeletonFragment implements VirtualFragment {
        private final int position;
        private final List<String> shortClassnames;
        private final Set<String> classnames;
        private final String skeletonText;

        public SkeletonFragment(int position, List<String> shortClassnames, Set<String> classnames, String skeletonText) {
            this.position = position;
            this.shortClassnames = shortClassnames;
            this.classnames = classnames;
            this.skeletonText = skeletonText;
        }

        @Override
        public String source() {
            return "[%d]".formatted(position + 1);
        }

        @Override
        public String text() {
            return skeletonText;
        }

        @Override
        public Set<String> classnames(Analyzer analyzer) {
            return classnames;
        }

        @Override
        public int position() {
            return position;
        }

        @Override
        public String description() {
            return "Summary of " + String.join(", ", shortClassnames.stream().sorted().toList());
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
        public static final AutoContext EMPTY     = new AutoContext(List.of("Enabled, but no references found"), Set.of(), "");
        public static final AutoContext DISABLED  = new AutoContext(List.of(), Set.of(), "");
        public static final AutoContext COMPUTING = new AutoContext(List.of("Computing asynchronously"), Set.of(), "");

        private final List<String> shortClassnames;
        private final Set<String> classnames;
        private final String skeletonText;

        public AutoContext(List<String> shortClassnames, Set<String> classnames, String skeletonText) {
            this.shortClassnames = shortClassnames;
            this.classnames = classnames;
            this.skeletonText = skeletonText;
        }

        @Override
        public String source() {
            return "[0] [Auto]";
        }

        @Override
        public String text() {
            return skeletonText;
        }

        @Override
        public Set<String> classnames(Analyzer analyzer) {
            return classnames;
        }

        /**
         * Returns a comma-separated list of short class names (no package).
         */
        @Override
        public String description() {
            return String.join(", ", shortClassnames);
        }

        @Override
        public String toString() {
            return "AutoContext('%s')".formatted(description());
        }
    }
}
