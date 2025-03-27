package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.git.IGitRepo;

public interface IProject {
    IAnalyzer getAnalyzer();

    IGitRepo getRepo();
}
