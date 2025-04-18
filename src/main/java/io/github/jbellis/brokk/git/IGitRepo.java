package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;

import java.util.List;
import java.util.Set;

public interface IGitRepo {
    Set<ProjectFile> getTrackedFiles();

    default String diff() throws GitAPIException {
        return "";
    }

    default void refresh() {
    }

    default ObjectId resolve(String s) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String diffFiles(List<ProjectFile> selectedFiles) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String showFileDiff(String head, String commitId, ProjectFile file) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String getFileContent(String commitId, ProjectFile file) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default String showDiff(String firstCommitId, String s) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default List<ProjectFile> listChangedFilesInCommitRange(String firstCommitId, String lastCommitId) throws GitAPIException {
        throw new UnsupportedOperationException();
    }

    default void add(List<ProjectFile> files) throws GitAPIException {
        throw new UnsupportedOperationException();
    }
}
