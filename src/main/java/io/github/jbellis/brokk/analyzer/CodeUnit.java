package io.github.jbellis.brokk.analyzer;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Represents a named code element (class, function, or field).
 */
public record CodeUnit(ProjectFile source, CodeUnitType kind, String fqName)
        implements Comparable<CodeUnit>, Serializable
{
    private static final long serialVersionUID = 2L;

    public CodeUnit {
        assert source != null;
    }

    /**
     * @return just the last symbol name (a.b.C -> C, a.b.C.foo -> foo)
     *         NB this is not *exactly* the identifier, since it includes class nesting (a.b.C$D -> C$D)
     */
    public String identifier()
    {
        var lastDotIndex = fqName.lastIndexOf('.');
        return lastDotIndex == -1
                ? fqName
                : fqName.substring(lastDotIndex + 1);
    }

    /**
     * @return for classes: just the class name
     *         for functions and fields: className.memberName (last two components)
     */
    public String shortName()
    {
        var parts = fqName.split("\\.");
        return switch (kind)
        {
            case CLASS -> parts[parts.length - 1];
            default ->
            {
                if (parts.length >= 2)
                    yield parts[parts.length - 2] + "." + parts[parts.length - 1];
                yield parts[parts.length - 1];
            }
        };
    }

    public boolean isClass()
    {
        return kind == CodeUnitType.CLASS;
    }

    public boolean isFunction()
    {
        return kind == CodeUnitType.FUNCTION;
    }

    /**
     * @return the package portion of the fully qualified name up to the first capitalized component
     *         in the dot-separated hierarchy.
     */
    public String packageName()
    {
        var parts = fqName.split("\\.");
        return Arrays.stream(parts)
                .takeWhile(part -> part.isEmpty() || !Character.isUpperCase(part.charAt(0)))
                .collect(Collectors.joining("."));
    }

    public Optional<CodeUnit> classUnit() {
        return switch (kind) {
            case CLASS -> Optional.of(this);
            default -> {
                var lastDotIndex = fqName.lastIndexOf('.');
                assert lastDotIndex > 0;
                var fqcn = fqName.substring(0, lastDotIndex);
                yield Optional.of(cls(source, fqcn));
            }
        };
    }

    @Override
    public int compareTo(CodeUnit other)
    {
        return this.fqName.compareTo(other.fqName);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) return true;
        if (!(obj instanceof CodeUnit other)) return false;
        return Objects.equals(this.fqName, other.fqName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(fqName);
    }

    @Override
    public String toString()
    {
        return switch (kind)
        {
            case CLASS -> "CLASS[" + fqName + "]";
            case FUNCTION -> "FUNCTION[" + fqName + "]";
            case FIELD -> "FIELD[" + fqName + "]";
        };
    }

    /**
     * Factory method to create a CodeUnit of type CLASS.
     */
    public static CodeUnit cls(ProjectFile source, String reference)
    {
        return new CodeUnit(source, CodeUnitType.CLASS, reference);
    }

    /**
     * Factory method to create a CodeUnit of type FUNCTION.
     */
    public static CodeUnit fn(ProjectFile source, String reference)
    {
        return new CodeUnit(source, CodeUnitType.FUNCTION, reference);
    }

    /**
     * Factory method to create a CodeUnit of type FIELD.
     */
    public static CodeUnit field(ProjectFile source, String reference)
    {
        return new CodeUnit(source, CodeUnitType.FIELD, reference);
    }

    public static Optional<CodeUnit> cls(IAnalyzer analyzer, String fqcn) {
        return analyzer.getFileFor(fqcn).map(pf -> cls(pf, fqcn));
    }
}
