package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.IProject;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCSharp; // Import the specific language class

import java.util.Set;

public final class CSharpAnalyzer extends TreeSitterAnalyzer {

    public CSharpAnalyzer(IProject project) {
        super(project);
    }

    @Override
    protected TSLanguage getTSLanguage() {
        return new TreeSitterCSharp(); // Instantiate the bonede language object
    }

    @Override
    protected String getQueryResource() {
        return "treesitter/c_sharp.scm";
    }

    @Override
    protected CodeUnit createCodeUnit(ProjectFile file, String captureName, String simpleName) {
        // C# doesn't have standard package structure like Java/Python based on folders.
        // Namespaces are declared in code. Deriving the 'package' equivalent (namespace)
        // from TreeSitter requires querying the namespace node, which is complex here.
        // Defaulting to empty package name for now. FQ name will rely solely on shortName.
        String packageName = ""; // TODO: Derive from namespace node if possible/needed

        // Simple name is the identifier (class name, method name, field name).
        // For methods/fields, the shortName should ideally include the class.
        // For constructors, simpleName is the class name.
        // We need to construct the appropriate shortName based on the context (which isn't fully available here).
        // Let's use simpleName as shortName for now, similar to Python, understanding this might be incomplete for members.
        String shortName = simpleName;

        return switch (captureName) {
            // Pass packageName and simpleName as shortName
            case "class.declaration", "interface.declaration" -> CodeUnit.cls(file, packageName, shortName);
            // Use simpleName (method identifier) as shortName. Class prefix missing.
            case "method.declaration" -> CodeUnit.fn(file, packageName, shortName);
            // simpleName is class name. Use "ClassName.<init>" as shortName for constructor function.
            case "constructor.declaration" -> CodeUnit.fn(file, packageName, shortName + ".<init>");
            // Use simpleName (field/property identifier) as shortName. Class prefix missing.
            case "field.declaration", "property.declaration" -> CodeUnit.field(file, packageName, shortName);
            // Ignore other captures
            default -> null;
        };
    }

    @Override
    protected Set<String> getIgnoredCaptures() {
        // C# query explicitly captures attributes/annotations to ignore them
        return Set.of("annotation");
    }

    @Override
    protected String bodyPlaceholder() {
        return "{ … }";
    }
}
