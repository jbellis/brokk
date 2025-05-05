package io.github.jbellis.brokk.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import java.util.*;

/**
 * Utility class for calculating Git status of files in the repository.
 */
public final class GitStatusUtil {
    private GitStatusUtil() {}

    /**
     * Determines the Git status for a set of file paths.
     * 
     * @param repo The Git repository to check
     * @param paths The file paths to check status for
     * @return A map of file paths to their Git status
     * @throws Exception If there's an error accessing the Git repository
     */
    public static Map<String, GitStatus> statusFor(Repository repo, Set<String> paths)
            throws Exception {
        var git = new Git(repo);
        var st = git.status().call();
        var out = new HashMap<String, GitStatus>();

        for (var p : paths) {
            if (st.getAdded().contains(p)) {
                out.put(p, GitStatus.ADDED);
            } else if (st.getRemoved().contains(p) ||
                     st.getMissing().contains(p)) {
                out.put(p, GitStatus.DELETED);
            } else if (st.getChanged().contains(p) ||
                     st.getModified().contains(p)) {
                out.put(p, GitStatus.MODIFIED);
            } else {
                out.put(p, GitStatus.MODIFIED); // Default to modified if status unknown
            }
        }
        return out;
    }
}
