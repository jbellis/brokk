package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.mop.ThemeColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

public class TableUtils {

    /**
     * Adjusts the preferred width of the specified column to fit its content.
     * Also sets a maximum width to prevent columns from becoming too wide.
     *
     * @param table    The JTable whose column will be resized.
     * @param colIndex The index of the column to adjust.
     */
    public static void fitColumnWidth(JTable table, int colIndex) {
        TableColumn column = table.getColumnModel().getColumn(colIndex);
        int width = 10; // a minimum width

        // Get header width
        TableCellRenderer headerRenderer = table.getTableHeader().getDefaultRenderer();
        Component headerComp = headerRenderer.getTableCellRendererComponent(
                table, column.getHeaderValue(), false, false, 0, colIndex);
        width = Math.max(width, headerComp.getPreferredSize().width);

        // Get maximum width of cells in this column
        for (int row = 0; row < table.getRowCount(); row++) {
            TableCellRenderer cellRenderer = table.getCellRenderer(row, colIndex);
            Component comp = table.prepareRenderer(cellRenderer, row, colIndex);
            width = Math.max(width, comp.getPreferredSize().width);
        }

        column.setPreferredWidth(width);
        column.setMaxWidth(width);
    }

    /**
     * Table cell renderer for displaying file references.
     */
    static class FileReferencesTableCellRenderer implements TableCellRenderer {
        public FileReferencesTableCellRenderer() {
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            // Convert the value to a list of FileReferenceData
            List<FileReferenceList.FileReferenceData> fileRefs = convertToFileReferences(value);

            FileReferenceList component = new FileReferenceList(fileRefs);

            // Set colors based on selection
            if (isSelected) {
                component.setBackground(table.getSelectionBackground());
                component.setForeground(table.getSelectionForeground());
                component.setSelected(true);
            } else {
                component.setBackground(table.getBackground());
                component.setForeground(table.getForeground());
                component.setSelected(false);
            }

            // Ensure the component is properly painted in the table
            component.setOpaque(true);

            // Set border to match the editor's border for consistency when transitioning
            component.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

            return component;
        }

        /**
         * Converts various input types to a list of FileReferenceData objects.
         */
        @SuppressWarnings("unchecked")
        public static List<FileReferenceList.FileReferenceData> convertToFileReferences(Object value) {
            if (value == null) {
                return new ArrayList<>();
            }

            if (value instanceof List) {
                return (List<FileReferenceList.FileReferenceData>) value;
            } else {
                throw new IllegalArgumentException("Input is not supported for FileReferencesTableCellRenderer. Expected List<FileReferenceData>");
            }
        }
    }

    /**
     * Component to display and interact with a list of file references.
     */
    static class FileReferenceList extends JPanel {
        private final List<FileReferenceData> fileReferences = new ArrayList<>();
        private boolean selected = false;

        private static final int BADGE_ARC_WIDTH = 10;
        private static final float BORDER_THICKNESS = 1.5f;

        public FileReferenceList() {
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
            setOpaque(true);
        }

        public FileReferenceList(List<FileReferenceData> fileReferences) {
            this();
            setFileReferences(fileReferences);
        }


        /**
         * Updates the displayed file references
         */
        public void setFileReferences(List<FileReferenceData> fileReferences) {
            this.fileReferences.clear();
            if (fileReferences != null) {
                this.fileReferences.addAll(fileReferences);
            }

            // Rebuild the UI
            removeAll();

            // Add each file reference as a label
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);

                // Set tooltip to show the full path
                fileLabel.setToolTipText(file.getFullPath());

                add(fileLabel);
            }

            revalidate();
            repaint();
        }

        /**
         * Sets the selection state of this component
         *
         * @param selected true if this component is in a selected table row
         */
        public void setSelected(boolean selected) {
            if (this.selected == selected) {
                return; // No change needed
            }

            this.selected = selected;

            // Just update the badges directly - we're already on the EDT when this is called
            removeAll();
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);
                fileLabel.setToolTipText(file.getFullPath());
                add(fileLabel);
            }
            revalidate();
            repaint();
        }

        /**
         * Returns whether this component is currently selected
         */
        public boolean isSelected() {
            return selected;
        }

        private JLabel createBadgeLabel(String text) {
            JLabel label = new JLabel(text) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Determine if hovering
                    boolean isHovered = getMousePosition() != null;
                    // Get theme state
                    boolean isDarkTheme = UIManager.getBoolean("laf.dark");

                    // Get colors from ThemeColors
                    Color badgeBorder = ThemeColors.getColor(isDarkTheme, "badge_border");
                    Color badgeHoverBorder = ThemeColors.getColor(isDarkTheme, "badge_hover_border");
                    Color selectedBadgeBorder = ThemeColors.getColor(isDarkTheme, "selected_badge_border");

                    // Set border color based on selection state and hover state
                    Color borderColor;
                    if (selected) {
                        borderColor = isHovered ? selectedBadgeBorder.brighter() : selectedBadgeBorder;
                    } else {
                        borderColor = isHovered ? badgeHoverBorder : badgeBorder;
                    }
                    g2d.setColor(borderColor);

                    // Use a thicker stroke for the border
                    g2d.setStroke(new BasicStroke(BORDER_THICKNESS));

                    // Draw rounded rectangle border only
                    g2d.draw(new RoundRectangle2D.Float(BORDER_THICKNESS / 2, BORDER_THICKNESS / 2,
                                                        getWidth() - BORDER_THICKNESS, getHeight() - BORDER_THICKNESS,
                                                        BADGE_ARC_WIDTH, BADGE_ARC_WIDTH));

                    g2d.dispose();

                    // Then draw the text
                    super.paintComponent(g);
                }
            };

            // Style the badge - use a smaller font for table cell
            float fontSize = label.getFont().getSize() * 0.85f;
            label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize));

            boolean isDarkTheme = UIManager.getBoolean("laf.dark");
            Color badgeForeground = ThemeColors.getColor(isDarkTheme, "badge_foreground");
            Color selectedBadgeForeground = ThemeColors.getColor(isDarkTheme, "selected_badge_foreground");

            // Set foreground color based on selection state
            label.setForeground(selected ? selectedBadgeForeground : badgeForeground);
            label.setBorder(new EmptyBorder(1, 6, 1, 6));

            return label;
        }

        /**
         * Represents a file reference with metadata for context menu usage.
         */
        public static class FileReferenceData {
            private final String fileName;
            private final String fullPath;
            private final ProjectFile projectFile; // Optional, if available

            public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
                this.fileName = fileName;
                this.fullPath = fullPath;
                this.projectFile = projectFile;
            }

            // Getters
            public String getFileName() {
                return fileName;
            }

            public String getFullPath() {
                return fullPath;
            }

            public ProjectFile getRepoFile() {
                return projectFile;
            }

            @Override
            public String toString() {
                return fileName;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FileReferenceData that = (FileReferenceData) o;
                return fullPath.equals(that.fullPath);
            }

            @Override
            public int hashCode() {
                return fullPath.hashCode();
            }
        }
    }
}
