package io.github.jbellis.brokk.analyzer;

import scala.Option;
import scala.Tuple2;

import java.util.*;

public interface IAnalyzer {

    default boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    default boolean isCpg() {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> getAllClasses() {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> getMembersInClass(String fqClass) {
        throw new UnsupportedOperationException();
    }

    default Set<CodeUnit> getClassesInFile(ProjectFile file) {
        throw new UnsupportedOperationException();
    }

    default boolean isClassInProject(String className) {
        throw new UnsupportedOperationException();
    }

    default List<Tuple2<CodeUnit, Double>> getPagerank(Map<String, Double> seedClassWeights,
                                                       int k,
                                                       boolean reversed) {
        throw new UnsupportedOperationException();
    }

    default Option<String> getSkeleton(String className) {
        throw new UnsupportedOperationException();
    }

    default Option<String> getSkeletonHeader(String className) {
        throw new UnsupportedOperationException();
    }

    default Optional<ProjectFile> getFileFor(String fqcn) {
        throw new UnsupportedOperationException();
    }

    default Optional<CodeUnit> getDefinition(String symbol) {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> searchDefinitions(String pattern) {
        throw new UnsupportedOperationException();
    }

    default List<CodeUnit> getUses(String symbol) {
        throw new UnsupportedOperationException();
    }

    default Option<String> getMethodSource(String methodName) {
        throw new UnsupportedOperationException();
    }

    default String getClassSource(String className) {
        throw new UnsupportedOperationException();
    }

    default Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }

    default Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        throw new UnsupportedOperationException();
    }

    /**
     * Locates the source file and line range for the given fully-qualified method name.
     * The {@code paramNames} list contains the *parameter variable names* (not types).
     * If there is only a single match, or exactly one match with matching param names, return it.
     * Otherwise throw {@code SymbolNotFoundException} or {@code SymbolAmbiguousException}.
     */
    default FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets a set of relevant symbol names (classes, methods, fields) defined within the given source CodeUnits.
     *
     * @param sources source files or classes to analyse
     * @return fully-qualified symbol names found within the sources
     */
    default Set<String> getSymbols(Set<CodeUnit> sources) {
        throw new UnsupportedOperationException();
    }

    default Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        Map<CodeUnit, String> skeletons = new HashMap<>();
        for (CodeUnit cls : getClassesInFile(file)) {
            Option<String> skelOpt = getSkeleton(cls.fqName());
            if (skelOpt.isDefined()) {
                skeletons.put(cls, skelOpt.get());
            }
        }
        return skeletons;
    }

    /**
     * Container for a function’s location and current source text.
     */
    record FunctionLocation(ProjectFile file, int startLine, int endLine, String code) {}
}
