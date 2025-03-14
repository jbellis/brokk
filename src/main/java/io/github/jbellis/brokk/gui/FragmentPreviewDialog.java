package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextFragment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;

/**
 * Dialog for displaying a context fragment with search functionality
 */
public class FragmentPreviewDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(FragmentPreviewDialog.class);
    
    private final RSyntaxTextArea textArea;
    private final JTextField searchField;
    private final JButton prevButton;
    private final JButton nextButton;
    private final JLabel matchCountLabel;
    private SearchContext searchContext;
    
    public FragmentPreviewDialog(Frame owner, ContextFragment fragment, String syntaxStyle) {
        super(owner, fragment.description(), true);
        
        // Main content area
        textArea = new RSyntaxTextArea(20, 80);
        textArea.setEditable(false);
        textArea.setSyntaxEditingStyle(syntaxStyle);
        textArea.setCodeFoldingEnabled(true);
        
        try {
            textArea.setText(fragment.format());
        } catch (IOException e) {
            textArea.setText("Error loading content: " + e.getMessage());
            logger.error("Error loading fragment content", e);
        }
        
        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        
        // Build search panel
        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
        
        // Left components (label + search field)
        JPanel leftPanel = new JPanel(new BorderLayout(5, 0));
        JLabel searchLabel = new JLabel("Search:");
        searchField = new JTextField(20);
        leftPanel.add(searchLabel, BorderLayout.WEST);
        leftPanel.add(searchField, BorderLayout.CENTER);
        
        // Right components (navigation buttons)
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        prevButton = new JButton("↑");
        nextButton = new JButton("↓");
        matchCountLabel = new JLabel("0/0");
        
        prevButton.setMargin(new Insets(2, 5, 2, 5));
        nextButton.setMargin(new Insets(2, 5, 2, 5));
        
        prevButton.setToolTipText("Previous match (Shift+F3)");
        nextButton.setToolTipText("Next match (F3)");
        
        rightPanel.add(prevButton);
        rightPanel.add(nextButton);
        rightPanel.add(matchCountLabel);
        
        // Add components to search panel
        searchPanel.add(leftPanel, BorderLayout.CENTER);
        searchPanel.add(rightPanel, BorderLayout.EAST);
        
        // Set up the dialog content
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        contentPanel.add(searchPanel, BorderLayout.SOUTH);
        
        setContentPane(contentPanel);
        
        // Initialize search
        searchContext = new SearchContext();
        searchContext.setMatchCase(false);
        searchContext.setRegularExpression(false);
        searchContext.setWholeWord(false);
        searchContext.setSearchForward(true);
        
        // Set up event handlers
        setupEventHandlers();
        
        // Final setup
        setSize(800, 600);
        setLocationRelativeTo(owner);
        
        // Register Ctrl+F shortcut to focus search field
        registerKeyboardShortcuts();
    }
    
    private void setupEventHandlers() {
        // Search field document listener
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                performSearch();
            }
            
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                performSearch();
            }
            
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                performSearch();
            }
        });
        
        // Button actions
        nextButton.addActionListener(e -> findNext());
        prevButton.addActionListener(e -> findPrevious());
        
        // Enter in search field should find next
        searchField.addActionListener(e -> findNext());
    }
    
    private void registerKeyboardShortcuts() {
        // Register Ctrl+F to focus search field
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        getRootPane().getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
        
        // F3 for next
        KeyStroke f3 = KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(f3, "findNext");
        getRootPane().getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext();
            }
        });
        
        // Shift+F3 for previous
        KeyStroke shiftF3 = KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shiftF3, "findPrevious");
        getRootPane().getActionMap().put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findPrevious();
            }
        });
        
        // Escape to close
        KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }
    
    private void performSearch() {
        if (searchField.getText().isEmpty()) {
            matchCountLabel.setText("0/0");
            return;
        }
        
        searchContext.setSearchFor(searchField.getText());
        
        // Count total matches
        int totalMatches = countMatches();
        int currentMatch = getCurrentMatchIndex();
        
        // Update match counter
        if (totalMatches > 0) {
            matchCountLabel.setText((currentMatch + 1) + "/" + totalMatches);
        } else {
            matchCountLabel.setText("0/0");
        }
        
        // Enable/disable navigation buttons
        boolean hasMatches = totalMatches > 0;
        prevButton.setEnabled(hasMatches);
        nextButton.setEnabled(hasMatches);
    }
    
    private int countMatches() {
        // Save current position
        int caretPosition = textArea.getCaretPosition();
        
        // Go to start and count all matches
        textArea.setCaretPosition(0);
        int count = 0;
        while (SearchEngine.find(textArea, searchContext).wasFound()) {
            count++;
        }
        
        // Restore position
        textArea.setCaretPosition(caretPosition);
        return count;
    }
    
    private int getCurrentMatchIndex() {
        if (searchField.getText().isEmpty()) {
            return -1;
        }
        
        // Save current position
        int caretPosition = textArea.getCaretPosition();
        
        // Go to start and find which match we're at
        textArea.setCaretPosition(0);
        int currentMatch = 0;
        
        while (SearchEngine.find(textArea, searchContext).wasFound()) {
            if (textArea.getSelectionEnd() > caretPosition) {
                break;
            }
            currentMatch++;
        }
        
        // Restore position
        textArea.setCaretPosition(caretPosition);
        return currentMatch;
    }
    
    private void findNext() {
        if (searchField.getText().isEmpty()) {
            return;
        }
        
        searchContext.setSearchForward(true);
        boolean found = SearchEngine.find(textArea, searchContext).wasFound();
        
        // If not found and we're not at the start, wrap around
        if (!found && textArea.getCaretPosition() > 0) {
            textArea.setCaretPosition(0);
            SearchEngine.find(textArea, searchContext);
        }
        
        // Update match counter
        performSearch();
    }
    
    private void findPrevious() {
        if (searchField.getText().isEmpty()) {
            return;
        }
        
        searchContext.setSearchForward(false);
        boolean found = SearchEngine.find(textArea, searchContext).wasFound();
        
        // If not found and we're not at the end, wrap around
        if (!found && textArea.getCaretPosition() < textArea.getText().length()) {
            textArea.setCaretPosition(textArea.getText().length());
            SearchEngine.find(textArea, searchContext);
        }
        
        // Update match counter
        performSearch();
    }
}
