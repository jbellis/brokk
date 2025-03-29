package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scala.Tuple2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextSerializationTest {
    @TempDir
    Path tempDir;
    private IContextManager mockContextManager;

    @BeforeEach
    void setup() {
        // Setup mock context manager
        mockContextManager = new IContextManager() {};
    }
    
    @Test
    void testBasicContextSerialization() throws Exception {
        // Create a context with minimal state
        Context context = new Context(mockContextManager, 5);
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify non-transient fields were preserved
        assertEquals(context.getAutoContextFileCount(), deserialized.getAutoContextFileCount());
        assertEquals(context.editableFiles.size(), deserialized.editableFiles.size());
        assertEquals(context.readonlyFiles.size(), deserialized.readonlyFiles.size());
        assertEquals(context.virtualFragments.size(), deserialized.virtualFragments.size());
        
        // Most transient fields should be initialized to empty
        assertNull(deserialized.contextManager);
        assertNotNull(deserialized.parsedOutput); // Empty ParsedOutput
        assertNotNull(deserialized.originalContents); // Empty Map
        assertNotNull(deserialized.historyMessages); // Empty List
        
        // AutoContext should have been preserved
        assertNotNull(deserialized.getAutoContext());
        
        // We injected output
        assertEquals("stub", deserialized.parsedOutput.output());
    }
    
    @Test
    void testContextWithFragmentsSerialization() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("repo");
        Files.createDirectories(repoRoot);
        
        var projectFile = new ProjectFile(repoRoot, "src/main/java/Test.java");
        Files.createDirectories(projectFile.absPath().getParent());
        Files.writeString(projectFile.absPath(), "public class Test {}");
        
        var externalFile = new ExternalFile(tempDir.resolve("external.txt").toAbsolutePath());
        Files.writeString(externalFile.absPath(), "This is external content");
        
        // Create context with fragments
        Context context = new Context(mockContextManager, 5)
            .addEditableFiles(List.of(new ContextFragment.RepoPathFragment(projectFile)))
            .addReadonlyFiles(List.of(new ContextFragment.ExternalPathFragment(externalFile)))
            .addVirtualFragment(new ContextFragment.StringFragment("virtual content", "Virtual Fragment"));
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify fragment counts
        assertEquals(1, deserialized.editableFiles.size());
        assertEquals(1, deserialized.readonlyFiles.size());
        assertEquals(1, deserialized.virtualFragments.size());
        
        // Check paths were properly serialized
        ContextFragment.RepoPathFragment repoFragment = deserialized.editableFiles.get(0);
        assertEquals(projectFile.toString(), repoFragment.file().toString());
        assertEquals(repoRoot.toString(), repoFragment.file().absPath().getParent().getParent().getParent().getParent().toString());

        ContextFragment.PathFragment externalFragment = deserialized.readonlyFiles.get(0);
        assertEquals(externalFile.toString(), externalFragment.file().toString());
        
        // Verify the ExternalPathFragment can be read correctly after deserialization
        assertDoesNotThrow(() -> {
            String content = externalFragment.text();
            assertEquals("This is external content", content);
            String formatted = externalFragment.format();
            assertTrue(formatted.contains("external.txt"));
            assertTrue(formatted.contains("This is external content"));
        });
    }
    
    @Test
    void testAllVirtualFragmentTypes() throws Exception {
        Context context = new Context(mockContextManager, 5);
        
        // Create mock RepoFile for CodeUnit construction
        ProjectFile mockFile = new ProjectFile(tempDir, "Mock.java");

        // Add examples of each VirtualFragment type
        context = context
            .addVirtualFragment(new ContextFragment.StringFragment("string content", "String Fragment"))
            .addVirtualFragment(new ContextFragment.SearchFragment("query", "explanation", Set.of(CodeUnit.cls(mockFile, "Test"))))
            .addVirtualFragment(new ContextFragment.SkeletonFragment(
                Map.of(CodeUnit.cls(mockFile, "com.test.Test"), "class Test {}")
            ));
        
        // Add fragments that use Future
        CompletableFuture<String> descFuture = CompletableFuture.completedFuture("description");
        context = context.addPasteFragment(
            new ContextFragment.PasteFragment("paste content", descFuture), 
            descFuture
        );
        
        // Add fragment with usage
        context = context.addVirtualFragment(
            new ContextFragment.UsageFragment("Uses", "Test.method", Set.of(CodeUnit.cls(mockFile, "com.test.Test")), "Test.method()")
        );
        
        // Add stacktrace fragment
        context = context.addVirtualFragment(
            new ContextFragment.StacktraceFragment(
                Set.of(CodeUnit.cls(mockFile, "com.test.Test")),
                "original",
                "NPE",
                "code"
            )
        );
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify all fragments were preserved
        assertEquals(6, deserialized.virtualFragments.size());
        
        // Verify fragment types
        var fragmentTypes = deserialized.virtualFragments.stream()
            .map(Object::getClass)
            .toList();
        
        assertTrue(fragmentTypes.contains(ContextFragment.StringFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SearchFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.SkeletonFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.PasteFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.UsageFragment.class));
        assertTrue(fragmentTypes.contains(ContextFragment.StacktraceFragment.class));
        
        // Verify text content for a fragment
        var stringFragment = deserialized.virtualFragments.stream()
            .filter(f -> f instanceof ContextFragment.StringFragment)
            .findFirst()
            .orElseThrow();
        assertEquals("string content", stringFragment.text());
    }
    
    @Test
    void testAutoContextSerialization() throws Exception {
        // Create a context with auto-context
        Context context = new Context(mockContextManager, 5)
            .setAutoContextFiles(10);
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify autoContextFileCount was preserved
        assertEquals(10, deserialized.getAutoContextFileCount());
        assertTrue(deserialized.isAutoContextEnabled());
        assertNotNull(deserialized.getAutoContext());
    }

    @Test
    void testExternalPathFragmentSerialization() throws Exception {
        // Create an external file
        Path externalPath = tempDir.resolve("serialization-test.txt").toAbsolutePath();
        String testContent = "External file content for serialization test";
        Files.writeString(externalPath, testContent);
        
        ExternalFile externalFile = new ExternalFile(externalPath);
        ContextFragment.ExternalPathFragment fragment = new ContextFragment.ExternalPathFragment(externalFile);
        
        // Add to context
        Context context = new Context(mockContextManager, 5)
            .addReadonlyFiles(List.of(fragment));
            
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify the fragment was properly deserialized
        assertEquals(1, deserialized.readonlyFiles.size());
        ContextFragment.PathFragment deserializedFragment = deserialized.readonlyFiles.get(0);
        
        // Check if it's the correct type
        assertInstanceOf(ContextFragment.ExternalPathFragment.class, deserializedFragment);
        
        // Verify path and content
        assertEquals(externalPath.toString(), deserializedFragment.file().toString());
        
        // Should be able to read the file
        assertEquals(testContent, deserializedFragment.text());
        
        // Check format method works
        String formatted = deserializedFragment.format();
        assertTrue(formatted.contains("serialization-test.txt"));
        assertTrue(formatted.contains(testContent));
    }

    @Test
    void testRoundTripOfLargeContext() throws Exception {
        // Create test files
        Path repoRoot = tempDir.resolve("largeRepo");
        Files.createDirectories(repoRoot);
        
        // Create many files
        List<ContextFragment.RepoPathFragment> editableFiles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ProjectFile file = new ProjectFile(repoRoot, "src/main/java/Test" + i + ".java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "public class Test" + i + " {}");
            editableFiles.add(new ContextFragment.RepoPathFragment(file));
        }
        
        // Create many virtual fragments
        List<ContextFragment.VirtualFragment> virtualFragments = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            virtualFragments.add(new ContextFragment.StringFragment(
                "content " + i, 
                "Fragment " + i
            ));
        }
        
        Context context = new Context(mockContextManager, 50);
        
        // Add all fragments
        context = context.addEditableFiles(editableFiles);
        for (var fragment : virtualFragments) {
            context = context.addVirtualFragment(fragment);
        }
        
        // Serialize and deserialize
        byte[] serialized = Context.serialize(context);
        Context deserialized = Context.deserialize(serialized, "stub");
        
        // Verify counts
        assertEquals(20, deserialized.editableFiles.size());
        assertEquals(15, deserialized.virtualFragments.size());
        
        // Check content of fragments
        for (int i = 0; i < 15; i++) {
            assertTrue(deserialized.virtualFragments.get(i).text().contains("content"));
            assertTrue(deserialized.virtualFragments.get(i).description().contains("Fragment"));
        }
    }
}
