package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Analyzer;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.Project;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import javax.swing.text.JTextComponent;

import java.util.List;

/**
 * A completion provider for Java classes and members using Completions.completeClassesAndMembers
 */
public class SymbolCompletionProvider extends DefaultCompletionProvider {

    private final Project project;

    public SymbolCompletionProvider(Project project) {
        this.project = project;
        setAutoActivationRules(false, ".");
    }

    @Override
    public List<Completion> getCompletions(JTextComponent comp) {
        String text = comp.getText();
        int caretPosition = comp.getCaretPosition();
        
        // If the caret is not at the end, adjust the text
        if (caretPosition < text.length()) {
            text = text.substring(0, caretPosition);
        }
        
        // Get completions using the brokk Completions utility
        var analyzer = project.getAnalyzerWrapper().getNonBlocking();
        // TODO need a better solution than this
        var completions = analyzer == null
                        ? List.<String>of()
                        : Completions.completeClassesAndMembers(text, analyzer, true);
        
        // Convert to RSTA completions
        return completions.stream()
                .map(c -> (Completion) new BasicCompletion(this, c))
                .toList();
    }
}
