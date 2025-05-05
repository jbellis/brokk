package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitStatusUtilTest {
    
    @TempDir
    Path tempDir;
    
    private Repository repository;
    private Git git;
    
    @BeforeEach
    void setUp() throws IOException, GitAPIException {
        // Initialize a test repository
        File gitDir = new File(tempDir.toFile(), ".git");
        repository = FileRepositoryBuilder.create(gitDir);
        repository.create();
        git = new Git(repository);
        
        // Initial commit to establish the repository
        File readme = new File(tempDir.toFile(), "README.md");
        Files.writeString(readme.toPath(), "# Test Repository");
        git.add().addFilepattern("README.md").call();
        git.commit().setMessage("Initial commit").call();
    }
    
    @AfterEach
    void tearDown() {
        git.close();
        repository.close();
    }
    
    @Test
    void addedFileHasCorrectStatus() throws Exception {
        // Create a new file
        File newFile = new File(tempDir.toFile(), "added.txt");
        Files.writeString(newFile.toPath(), "new content");
        
        // Add it to git
        git.add().addFilepattern("added.txt").call();
        
        // Check status
        Map<String, GitStatus> statusMap = GitStatusUtil.statusFor(repository, Set.of("added.txt"));
        
        // Verify status
        assertEquals(GitStatus.ADDED, statusMap.get("added.txt"));
    }
    
    @Test
    void modifiedFileHasCorrectStatus() throws Exception {
        // Modify the README file
        File readme = new File(tempDir.toFile(), "README.md");
        Files.writeString(readme.toPath(), "# Modified Repository");
        
        // Check status (not adding it to git staging, just modifying it)
        Map<String, GitStatus> statusMap = GitStatusUtil.statusFor(repository, Set.of("README.md"));
        
        // Verify status
        assertEquals(GitStatus.MODIFIED, statusMap.get("README.md"));
    }
    
    @Test
    void deletedFileHasCorrectStatus() throws Exception {
        // Create and commit a file first
        File fileToDelete = new File(tempDir.toFile(), "to-delete.txt");
        Files.writeString(fileToDelete.toPath(), "will be deleted");
        git.add().addFilepattern("to-delete.txt").call();
        git.commit().setMessage("Add file to delete").call();
        
        // Now delete it and tell git about it
        Files.delete(fileToDelete.toPath());
        git.rm().addFilepattern("to-delete.txt").call();
        
        // Check status
        Map<String, GitStatus> statusMap = GitStatusUtil.statusFor(repository, Set.of("to-delete.txt"));
        
        // Verify status
        assertEquals(GitStatus.DELETED, statusMap.get("to-delete.txt"));
    }
    
    @Test
    void multipleFilesStatusesReturned() throws Exception {
        // Create a new file
        File addedFile = new File(tempDir.toFile(), "added.txt");
        Files.writeString(addedFile.toPath(), "new content");
        git.add().addFilepattern("added.txt").call();
        
        // Modify the README file
        File readme = new File(tempDir.toFile(), "README.md");
        Files.writeString(readme.toPath(), "# Modified Repository");
        
        // Check status for both files
        Map<String, GitStatus> statusMap = GitStatusUtil.statusFor(repository, 
                Set.of("added.txt", "README.md"));
        
        // Verify statuses
        assertEquals(GitStatus.ADDED, statusMap.get("added.txt"));
        assertEquals(GitStatus.MODIFIED, statusMap.get("README.md"));
    }
}
