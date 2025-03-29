package io.github.jbellis.brokk.gui;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.ShorthandCompletion;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.util.List;

public class AutoCompleteUtil {

    private static final int MAX_POPUP_WIDTH = 800;
    private static final int MAX_POPUP_HEIGHT = 400;
    private static final int MAX_VISIBLE_ROWS = 15; // Limit rows shown without scrolling
    private static final int HORIZONTAL_PADDING = 40;
    private static final int VERTICAL_PADDING = 20;
    private static final int ROW_PADDING = 2;
    private static final double DESC_WIDTH_FACTOR = 1.2; // Hack for monospaced desc font

    /**
     * Dynamically adjusts the size of the AutoCompletion popup windows (choices and description)
     * based on the content of the completions.
     *
     * @param autoCompletion The AutoCompletion instance whose popups need resizing.
     * @param textComponent The text component the AutoCompletion is attached to (used for font metrics).
     * @param completions   The list of completions to display.
     */
    public static void sizePopupWindows(AutoCompletion autoCompletion, JTextComponent textComponent, List<ShorthandCompletion> completions) {
        // Nothing works, autocomplete is just broken.  stick with the defaults
        if (true) {
            autoCompletion.setShowDescWindow(false);
            return;
        }

        var defaultFontMetrics = textComponent.getFontMetrics(textComponent.getFont());
        int rowHeight = defaultFontMetrics.getHeight() + ROW_PADDING;
        int visibleRowCount = Math.min(completions.size(), MAX_VISIBLE_ROWS);
        int calculatedHeight = visibleRowCount * rowHeight + VERTICAL_PADDING;
        int popupHeight = Math.min(MAX_POPUP_HEIGHT, calculatedHeight);

        // Calculate Choices window width
        int maxInputWidth = completions.stream()
                .mapToInt(c -> defaultFontMetrics.stringWidth(c.getInputText() + " - " + c.getShortDescription()))
                .max().orElse(200); // Default width if stream is empty (shouldn't happen here)
        int choicesWidth = Math.min(MAX_POPUP_WIDTH, maxInputWidth + HORIZONTAL_PADDING);
        autoCompletion.setChoicesWindowSize(choicesWidth, popupHeight);

        // Calculate Description window width and show it
        var ttFontMetrics = textComponent.getFontMetrics(UIManager.getFont("ToolTip.font"));
        boolean hasDescriptions = completions.stream().anyMatch(c -> getCompletionDescription(c) != null && !getCompletionDescription(c).isEmpty());
        // disabled for https://github.com/bobbylight/AutoComplete/issues/97
        if (false) {
            int maxDescWidth = completions.stream()
                    .mapToInt(c -> ttFontMetrics.stringWidth(getCompletionDescription(c)))
                    .max().orElse(300); // Default width
            // Apply hack factor for potentially monospaced font in description
            int descWidth = Math.min(MAX_POPUP_WIDTH, (int) (DESC_WIDTH_FACTOR * maxDescWidth) + HORIZONTAL_PADDING);

            autoCompletion.setShowDescWindow(true);
            autoCompletion.setDescriptionWindowSize(descWidth, popupHeight);
        } else {
            autoCompletion.setShowDescWindow(false);
        }
    }

    /**
     * Helper to get the description text, handling ShorthandCompletion.
     */
    private static String getCompletionDescription(Completion c) {
        if (c instanceof ShorthandCompletion sc) {
            // ShorthandCompletion often uses replacement text as the primary description
            return sc.getReplacementText();
        }
        // Fallback to tooltip text if available, otherwise null
        return c.getToolTipText();
    }
}
