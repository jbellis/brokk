package io.github.jbellis.brokk.difftool.ui;

import io.github.jbellis.brokk.difftool.node.FileNode;
import io.github.jbellis.brokk.difftool.node.JMDiffNode;
import io.github.jbellis.brokk.difftool.node.StringNode;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class FileComparison extends SwingWorker<String, Object> {
    private final BrokkDiffPanel mainPanel;
    private JMDiffNode diffNode;
    private final File leftFile;
    private final File rightFile;
    private BufferDiffPanel panel;
    private final String contentLeft;
    private final String contentRight;
    private final String contentLeftTitle;
    private final String contentRightTitle;
    private final boolean isTwoFilesComparison;
    private final String leftFileTitle;
    private final String rightFileTitle;
    private final boolean isDarkTheme;

    // Constructor
    private FileComparison(FileComparisonBuilder builder) {
        this.mainPanel = builder.mainPanel;
        this.leftFile = builder.leftFile;
        this.rightFile = builder.rightFile;
        this.contentLeft = builder.contentLeft;
        this.contentRight = builder.contentRight;
        this.contentLeftTitle = builder.contentLeftTitle;
        this.contentRightTitle = builder.contentRightTitle;
        this.isTwoFilesComparison = builder.isTwoFilesComparison;
        this.leftFileTitle = builder.leftFileTitle;
        this.rightFileTitle = builder.rightFileTitle;
        this.isDarkTheme = builder.isDarkTheme;
    }

    // Static Builder class
    public static class FileComparisonBuilder {
        private final BrokkDiffPanel mainPanel;
        private File leftFile;
        private File rightFile;
        private String contentLeft;
        private String contentRight;
        private String contentLeftTitle;
        private String contentRightTitle;
        private boolean isTwoFilesComparison;
        private boolean isStringAndFileComparison;
        private String leftFileTitle = "";
        private String rightFileTitle = "";
        private boolean isDarkTheme = false; // Default to light

        public FileComparisonBuilder(BrokkDiffPanel mainPanel) {
            this.mainPanel = mainPanel;
        }

        public FileComparisonBuilder withComparisonType(boolean isTwoFilesComparison, boolean isStringAndFileComparison) {
            this.isTwoFilesComparison = isTwoFilesComparison;
            this.isStringAndFileComparison = isStringAndFileComparison;
            return this;
        }

        public FileComparisonBuilder withFiles(File leftFile, String leftFileTitle, File rightFile, String rightFileTitle) {
            if (isTwoFilesComparison) {
                this.leftFile = leftFile;
                this.leftFileTitle = leftFileTitle;
                this.rightFile = rightFile;
                this.rightFileTitle = rightFileTitle;
            }
            return this;
        }

        public FileComparisonBuilder withStringAndFile(String contentLeft, String contentLeftTitle, File rightFile, String rightFileTitle) {
            if (isStringAndFileComparison) {
                this.contentLeft = contentLeft;
                this.contentLeftTitle = contentLeftTitle;
                this.rightFile = rightFile;
                this.rightFileTitle = rightFileTitle;
            }
            return this;
        }

        public FileComparisonBuilder withStringAndFile(File leftFile, String leftFileTitle, String contentRight, String contentRightTitle) {
            if (isStringAndFileComparison) {
                this.contentRight = contentRight;
                this.contentRightTitle = contentRightTitle;
                this.leftFile = leftFile;
                this.leftFileTitle = leftFileTitle;
            }
            return this;
        }

        public FileComparisonBuilder withStrings(String contentLeft, String contentLeftTitle, String contentRight, String contentRightTitle) {
            if (!isTwoFilesComparison && !isStringAndFileComparison) {
                this.contentLeft = contentLeft;
                this.contentLeftTitle = contentLeftTitle;
                this.contentRight = contentRight;
                this.contentRightTitle = contentRightTitle;
            }
            return this;
        }

        public FileComparisonBuilder withTheme(boolean isDark) {
            this.isDarkTheme = isDark;
            return this;
        }

        public FileComparison build() {
            return new FileComparison(this);
        }
    }

    public BufferDiffPanel getPanel() {
        return panel;
    }

    @Override
    public String doInBackground() {
        if (diffNode == null) {
            if (isTwoFilesComparison) {
                // Ensure both leftFile and rightFile are not null
                if (leftFile != null && rightFile != null) {
                    diffNode = create(leftFileTitle, leftFile, rightFileTitle, rightFile);
                } else {
                    return "Error: One or both files are null.";
                }
            } else if (mainPanel.isStringAndFileComparison()) {
                // Handle string and file comparison, ensuring that contentLeft is not null and rightFile is not null
                if (contentLeft != null && !contentLeft.isEmpty() && rightFile != null) {
                    diffNode = createStringAndFile(contentLeftTitle, contentLeft, rightFileTitle, rightFile);
                } else if (contentRight != null && !contentRight.isEmpty() && leftFile != null) {
                    diffNode = createStringAndFile(leftFileTitle, leftFile, contentRightTitle, contentRight);
                } else {
                    return "Error: Either the left content or right file is null or empty.";
                }
            } else if (contentLeft != null && contentRight != null) {
                // Ensure both contentLeft and contentRight are not null
                if (!contentLeft.isEmpty() && !contentRight.isEmpty()) {
                    diffNode = createString(contentLeftTitle, contentLeft, contentRightTitle, contentRight);
                } else {
                    return "Error: One or both content values are empty.";
                }
            } else {
                return "Error: One or both content values are null.";
            }
        }

        // If no errors, proceed to diffing
        SwingUtilities.invokeLater(() -> diffNode.diff());
        return null;
    }


    public JMDiffNode create(String fileLeftName, File fileLeft,
                             String fileRightName, File fileRight) {
        JMDiffNode node = new JMDiffNode(fileLeftName, true);
        node.setBufferNodeLeft(new FileNode(fileLeftName, fileLeft));
        node.setBufferNodeRight(new FileNode(fileRightName, fileRight));
        return node;
    }

    public JMDiffNode createString(String fileLeftName, String leftContent,
                                   String fileRightName, String rightContent) {
        JMDiffNode node = new JMDiffNode(fileLeftName, true);
        node.setBufferNodeLeft(new StringNode(fileLeftName, leftContent));
        node.setBufferNodeRight(new StringNode(fileRightName, rightContent));
        return node;
    }

    public JMDiffNode createStringAndFile(String contentLeftTitle, String leftContent,
                                          String fileRightName, File fileRight) {
        JMDiffNode node = new JMDiffNode(contentLeftTitle, true);
        node.setBufferNodeLeft(new StringNode(contentLeftTitle, leftContent));
        node.setBufferNodeRight(new FileNode(fileRightName, fileRight));
        return node;
    }

    public JMDiffNode createStringAndFile(String fileLeftName, File fileLeft, String contentRightTitle,
                                          String rightContent) {
        JMDiffNode node = new JMDiffNode(contentLeftTitle, true);
        node.setBufferNodeLeft(new FileNode(fileLeftName, fileLeft));
        node.setBufferNodeRight(new StringNode(contentRightTitle, rightContent));

        return node;
    }

    private static ImageIcon getScaledIcon() {
        try {
            BufferedImage originalImage = ImageIO.read(Objects.requireNonNull(FileComparison.class.getResource("/images/compare.png")));
            Image scaledImage = originalImage.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException | NullPointerException e) {
            System.err.println("Image not found: " + "/images/compare.png" + ": " + e.getMessage());
            // Optionally rethrow if icon is critical
            // throw new RuntimeException("Failed to load required image icon", e);
            return null;
        }
    }

    @Override
    protected void done() {
        try {
            String result = get();
            if (result != null) {
                JOptionPane.showMessageDialog(mainPanel, result, "Error opening file", JOptionPane.ERROR_MESSAGE);
            } else {
                panel = new BufferDiffPanel(mainPanel, isDarkTheme); // Pass theme state
                panel.setDiffNode(diffNode);
                ImageIcon resizedIcon = getScaledIcon();
                mainPanel.getTabbedPane().addTab(panel.getTitle(), resizedIcon, panel);
                mainPanel.getTabbedPane().setSelectedComponent(panel);
            }
        } catch (Exception ex) {
            // Handle exceptions during the 'done' phase, e.g., from get()
            System.err.println("Error completing file comparison task: " + ex.getMessage());
            JOptionPane.showMessageDialog(mainPanel, "Error finalizing comparison: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            // Rethrow if necessary
            // throw new RuntimeException("Failed to complete comparison UI update", ex);
        }
    }
}
