
package io.github.jbellis.brokk.diffTool;

import io.github.jbellis.brokk.diffTool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.diffTool.ui.JMHighlightPainter;

import javax.swing.*;
import java.io.File;
import java.util.Objects;

public class BrokkDiffTool
        implements Runnable {

    public BrokkDiffTool() {
    }

    public void run() {
        JMHighlightPainter.initializePainters();
        JFrame frame = new JFrame("BrokkDiffTool");
        // Creating a new BrokkDiffPanel instance for file comparison mode.
        // The panel will compare two files: "java.txt" and "world.txt" from the desktop.
        BrokkDiffPanel brokkPanel = new BrokkDiffPanel(
                true,  // Enable file comparison mode
                "", "", // Titles for the left and right content (empty in this case)
                "", "", // Content for direct text comparison (not used here)
                new File("C:\\Users\\Administrator\\Desktop\\java.txt"),  // Left sample file to compare
                new File("C:\\Users\\Administrator\\Desktop\\world.txt")   // Right sample file to compare
        );
        frame.add(brokkPanel);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setIconImage(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/compare.png"))).getImage());
        frame.setSize(800, 700);
        frame.setVisible(true);
        frame.toFront();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new BrokkDiffTool());
    }
}
