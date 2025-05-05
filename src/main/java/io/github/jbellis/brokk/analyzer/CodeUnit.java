package io.github.jbellis.brokk.analyzer;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents a named code element (class, function, or field).
 */
public record CodeUnit(ProjectFile source, CodeUnitType kind, String packageName, String shortName)
        implements Comparable<CodeUnit>, Serializable {
    private static final long serialVersionUID = 3L; // Increment serialVersionUID due to field changes

    public CodeUnit {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(packageName, "packageName must not be null"); // Allow empty, but not null
        Objects.requireNonNull(shortName, "shortName must not be null");
        if (shortName.isEmpty()) {
            throw new IllegalArgumentException("shortName must not be empty");
        }
    }

    /**
     * @return The fully qualified name constructed from package and short name.
     */
    public String fqName() {
        return packageName.isEmpty() ? shortName : packageName + "." + shortName;
    }

    /**
     * @return just the last symbol name component (e.g., C for a.b.C, foo for a.b.C.foo, C$D for a.b.C$D, method for Outer$Inner.method).
     */
    public String identifier() {
        if (kind == CodeUnitType.CLASS) {
            // For classes, the shortName is the simple class name, potentially including nesting (C, C$D)
            return shortName; // Simple class name, potentially including nesting (C, C$D)
        } else {
            // For FUNCTION/FIELD, shortName format is "Class.member", extract just the member
            int lastDot = shortName.lastIndexOf('.');
            return lastDot > 0 ? shortName.substring(lastDot + 1) : shortName;
        }
    }

    /**
     * Returns the short name component.
     * <ul>
     *     <li>For {@link CodeUnitType#CLASS}, this is the simple class name (e.g., "MyClass", "Outer$Inner").</li>
     *     <li>For {@link CodeUnitType#FUNCTION} or {@link CodeUnitType#FIELD}, this is "className.memberName" (e.g., "MyClass.myMethod").</li>
     * </ul>
     * @return The short name.
     */
    public String shortName() {
        return shortName;
    }

    public boolean isClass() {
        return kind == CodeUnitType.CLASS;
    }

    public boolean isFunction() {
        return kind == CodeUnitType.FUNCTION;
    }

    /**
     * @return Accessor for the package name component.
     */
    public String packageName() {
        return packageName;
    }

    /**
     * @return The CodeUnit representing the containing class, if this is a member (function/field).
     */
    public Optional<CodeUnit> classUnit() {
        return switch (kind) {
            case CLASS -> Optional.of(this);
            default -> { // FUNCTION or FIELD
                // shortName is "ClassName.memberName" for FUNCTION/FIELD
                int lastDot = shortName.lastIndexOf('.');
                // Constructor validation ensures shortName contains '.' for FUNCTION/FIELD
                assert lastDot > 0 : "shortName for FUNCTION/FIELD should contain a dot: " + shortName;

                // Extract the class name part from the shortName
                String className = shortName.substring(0, lastDot); // e.g., "MyClass" or "Outer$Inner"

                // Use the existing packageName field and the extracted className
                yield Optional.of(cls(source, packageName, className));
            }
        };
    }

    @Override
    public int compareTo(CodeUnit other) {
        // Compare based on the derived fully qualified name
        return this.fqName().compareTo(other.fqName());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CodeUnit other)) return false;
        // Equality based on the derived fully qualified name
        return Objects.equals(this.fqName(), other.fqName());
    }

    @Override
    public int hashCode() {
        // Hash code based on the derived fully qualified name
        return Objects.hashCode(fqName());
    }

    @Override
    public String toString() {
        // Use derived fqName in toString representation
        return switch (kind) {
            case CLASS -> "CLASS[" + fqName() + "]";
            case FUNCTION -> "FUNCTION[" + fqName() + "]";
            case FIELD -> "FIELD[" + fqName() + "]";
        };
    }

    /**
     * Factory method to create a CodeUnit of type CLASS. Assumes correct arguments.
     *
     * @param source      The source file.
     * @param packageName The package name (can be empty).
     * @param shortName   The simple class name (e.g., "MyClass", "Outer$Inner").
     */
    public static CodeUnit cls(ProjectFile source, String packageName, String shortName) {
        return new CodeUnit(source, CodeUnitType.CLASS, packageName, shortName);
    }

    /**
     * Factory method to create a CodeUnit of type FUNCTION. Assumes correct arguments.
     *
     * @param source      The source file.
     * @param packageName The package name (e.g., "com.example", or "" for default package). Does NOT include the class name.
     * @param shortName   The short name including the simple class name and member name (e.g., "MyClass.myMethod", "Outer$Inner.myMethod").
     */
    public static CodeUnit fn(ProjectFile source, String packageName, String shortName) {
        // Validate that shortName contains a dot if it's expected for FUNCTION/FIELD
        if (shortName == null || !shortName.contains(".")) {
             throw new IllegalArgumentException("shortName for FUNCTION must be in 'ClassName.methodName' format, got: " + shortName);
        }
        return new CodeUnit(source, CodeUnitType.FUNCTION, packageName, shortName);
    }

    /**
     * Factory method to create a CodeUnit of type FIELD. Assumes correct arguments.
     *
     * @param source      The source file.
     * @param packageName The package name (e.g., "com.example", or "" for default package). Does NOT include the class name.
     * @param shortName   The short name including the simple class name and member name (e.g., "MyClass.myField", "Outer$Inner.myField").
     */
    public static CodeUnit field(ProjectFile source, String packageName, String shortName) {
        // Validate that shortName contains a dot if it's expected for FUNCTION/FIELD
        if (shortName == null || !shortName.contains(".")) {
             throw new IllegalArgumentException("shortName for FIELD must be in 'ClassName.fieldName' format, got: " + shortName);
        }
        return new CodeUnit(source, CodeUnitType.FIELD, packageName, shortName);
    }

    // Helper records for parsing, made public for external access
    public record Tuple2<T1, T2>(T1 _1, T2 _2) {}
    
    // Package, className, identifier - used for language-specific parsing
    public record Tuple3<T1, T2, T3>(T1 _1, T2 _2, T3 _3) {}
}
