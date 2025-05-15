package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import io.github.jbellis.brokk.util.Decompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ImportDependencyDialog {
    private static final Logger logger = LogManager.getLogger(ImportDependencyDialog.class);

    private enum SourceType { JAR, DIRECTORY }

    public static void show(Chrome chrome) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        assert chrome.getContextManager() != null : "ContextManager must not be null to import a dependency";
        assert chrome.getProject() != null : "Project must not be null to import a dependency";

        new DialogHelper(chrome).buildAndShow();
    }

    private static class DialogHelper {
        private final Chrome chrome;
        private JDialog dialog;
        private JRadioButton jarRadioButton;
        private JRadioButton dirRadioButton;
        private JPanel fspContainerPanel;
        private FileSelectionPanel currentFileSelectionPanel;
        private JTextArea previewArea;
        private JButton importButton;

        private SourceType currentSourceType = SourceType.JAR;
        private BrokkFile selectedBrokkFileForImport;
        private final Path dependenciesRoot;

        DialogHelper(Chrome chrome) {
            this.chrome = chrome;
            this.dependenciesRoot = chrome.getProject().getRoot().resolve(".brokk").resolve("dependencies");
        }

        void buildAndShow() {
            dialog = new JDialog(chrome.getFrame(), "Import Dependency", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            JPanel mainPanel = new JPanel(new GridBagLayout());
            mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            boolean allowJarImport = chrome.getProject().getAnalyzerLanguage() == Language.JAVA;
            if (!allowJarImport) {
                currentSourceType = SourceType.DIRECTORY; // Default to directory if JAR not allowed
            } else {
                currentSourceType = SourceType.JAR; // Default to JAR if allowed (also field default)
            }

            int currentRowIndex = 0;

            if (allowJarImport) {
                // Source Type Radio Buttons
            gbc.gridx = 0;
            gbc.gridy = currentRowIndex;
            gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.NORTHWEST; // Apply NORTHWEST anchor for this row.
            mainPanel.add(new JLabel("Source Type:"), gbc);
            // This anchor will also apply to the radioPanel in the next cell of this row.

            jarRadioButton = new JRadioButton("JAR (decompile & add sources)");
                jarRadioButton.setSelected(true); // JAR is default if shown
                jarRadioButton.addActionListener(e -> updateSourceType(SourceType.JAR));

                dirRadioButton = new JRadioButton("Directory");
                dirRadioButton.addActionListener(e -> updateSourceType(SourceType.DIRECTORY));

                ButtonGroup sourceTypeGroup = new ButtonGroup();
                sourceTypeGroup.add(jarRadioButton);
                sourceTypeGroup.add(dirRadioButton);

                JPanel radioPanel = new JPanel();
                radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.PAGE_AXIS));
                radioPanel.add(jarRadioButton);
                radioPanel.add(dirRadioButton);
                gbc.gridx = 1;
                // gbc.gridy remains currentRowIndex
                mainPanel.add(radioPanel, gbc);
                currentRowIndex++;
            }

            // FileSelectionPanel container
            gbc.gridx = 0;
            gbc.gridy = currentRowIndex;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.BOTH; // Make FSP expand
            gbc.weightx = 1.0;
            gbc.weighty = 1.0; // Allow FSP to take vertical space
            fspContainerPanel = new JPanel(new BorderLayout());
            fspContainerPanel.setPreferredSize(new Dimension(500, 250));
            mainPanel.add(fspContainerPanel, gbc);
            currentRowIndex++;

            // Preview Area Label
            gbc.gridx = 0;
            gbc.gridy = currentRowIndex;
            gbc.gridwidth = 2;
            gbc.weighty = 0; // Label doesn't take extra space
            gbc.fill = GridBagConstraints.HORIZONTAL; // Label can take horizontal space
            mainPanel.add(new JLabel("Preview:"), gbc);
            currentRowIndex++;

            // Preview Area
            gbc.gridx = 0;
            gbc.gridy = currentRowIndex;
            gbc.gridwidth = 2;
            gbc.weighty = 0.5; // Preview area can also take some space
            gbc.fill = GridBagConstraints.BOTH;
            previewArea = new JTextArea(5, 40);
            previewArea.setEditable(false);
            previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane previewScrollPane = new JScrollPane(previewArea);
            previewScrollPane.setMinimumSize(new Dimension(100, 80)); // Min size for preview
            mainPanel.add(previewScrollPane, gbc);
            // currentRowIndex++; // No more items using gridbag below this

            updateFileSelectionPanel(); // Initialize FSP based on currentSourceType

            dialog.add(mainPanel, BorderLayout.CENTER);

            // Buttons
            importButton = new JButton("Import");
            importButton.setEnabled(false);
            importButton.addActionListener(e -> performImport());
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dialog.dispose());

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(importButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            dialog.setMinimumSize(new Dimension(600, 500)); // Adjusted minimum size
            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        }

        private void updateSourceType(SourceType newType) {
            if (currentSourceType == newType) return;
            currentSourceType = newType;
            selectedBrokkFileForImport = null;
            previewArea.setText("");
            importButton.setEnabled(false);
            updateFileSelectionPanel();
        }

        private void updateFileSelectionPanel() {
            if (currentFileSelectionPanel != null) {
                fspContainerPanel.remove(currentFileSelectionPanel);
            }

            Predicate<File> filter;
            Future<List<Path>> candidates;

            if (currentSourceType == SourceType.JAR) {
                filter = file -> file.isDirectory() || file.getName().toLowerCase().endsWith(".jar");
                candidates = chrome.getContextManager().submitBackgroundTask("Scanning for JAR files", Decompiler::findCommonDependencyJars);
            } else { // DIRECTORY
                filter = File::isDirectory;
                candidates = CompletableFuture.completedFuture(List.of());
            }

            FileSelectionPanel.Config fspConfig;
            if (currentSourceType == SourceType.JAR) {
                fspConfig = new FileSelectionPanel.Config(
                        chrome.getProject(),
                        true, // allowExternalFiles
                        filter,
                        candidates,
                        false, // multiSelect = false
                        this::handleFspSingleFileConfirmed,
                        true, // includeProjectFilesInAutocomplete for JARs
                        "Ctrl+Space to autocomplete common dependency JARs.\nSelected JAR will be decompiled and its sources added to the project."
                );
            } else { // DIRECTORY
                fspConfig = new FileSelectionPanel.Config(
                        chrome.getProject(),
                        true, // allowExternalFiles
                        filter,
                        candidates, // CompletableFuture.completedFuture(List.of())
                        false, // multiSelect = false
                        this::handleFspSingleFileConfirmed,
                        false, // includeProjectFilesInAutocomplete for Directories
                        "Select an external directory. Its contents will be copied into the project.\nProject's own directories cannot be selected."
                );
            }

            currentFileSelectionPanel = new FileSelectionPanel(fspConfig);


            // Listener for text changes in FSP input to update preview
            currentFileSelectionPanel.getFileInputComponent().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { onFspInputTextChange(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { onFspInputTextChange(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { onFspInputTextChange(); }
            });

            fspContainerPanel.add(currentFileSelectionPanel, BorderLayout.CENTER);
            fspContainerPanel.revalidate();
            fspContainerPanel.repaint();
        }

        private void onFspInputTextChange() {
            SwingUtilities.invokeLater(() -> { // Ensure UI updates are on EDT
                String text = currentFileSelectionPanel.getInputText();

                if (text.isEmpty()) {
                    selectedBrokkFileForImport = null;
                    previewArea.setText("");
                    importButton.setEnabled(false);
                    return;
                }

                Path path;
                try {
                    path = Paths.get(text);
                } catch (InvalidPathException e) {
                    selectedBrokkFileForImport = null;
                    previewArea.setText(""); // Clear preview for invalid path
                    importButton.setEnabled(false);
                    return;
                }

                // For live preview, we need an absolute path to check existence and type.
                // If the user types a relative path, it's relative to CWD.
                Path resolvedPath = path.toAbsolutePath();

                if (Files.exists(resolvedPath)) {
                    BrokkFile bf;
                    // Create a BrokkFile. ProjectFile if inside project, else ExternalFile.
                    // For dependencies, they are typically external until copied.
                    if (resolvedPath.startsWith(chrome.getProject().getRoot())) {
                         bf = new io.github.jbellis.brokk.analyzer.ProjectFile(chrome.getProject().getRoot(),
                                                                               chrome.getProject().getRoot().relativize(resolvedPath));
                    } else {
                         bf = new io.github.jbellis.brokk.analyzer.ExternalFile(resolvedPath);
                    }
                    updatePreviewAndButtonState(bf);
                } else {
                    selectedBrokkFileForImport = null;
                    previewArea.setText("");
                    importButton.setEnabled(false);
                }
            });
        }

        private void handleFspSingleFileConfirmed(BrokkFile file) {
            // This is called on double-click or enter in FSP if configured
            // It implies an explicit selection. Update text field which triggers document listener,
            // or directly update preview. Let's ensure text field is set.
            currentFileSelectionPanel.setInputText(file.absPath().toString());
            // The document listener on setInputText will call updatePreviewAndButtonState.
            // For robustness, also call it directly in case the text was already identical.
            updatePreviewAndButtonState(file);
            if (importButton.isEnabled()) {
                // Optional: directly trigger import on double click if valid
                // performImport();
            }
        }

        private void updatePreviewAndButtonState(BrokkFile file) {
            selectedBrokkFileForImport = null; // Reset
            importButton.setEnabled(false);
            previewArea.setText(""); // Clear previous preview

            if (file == null || file.absPath() == null) {
                 previewArea.setText(""); // Clear preview for invalid file selection
                 return;
            }

            Path path = file.absPath();
            if (!Files.exists(path)) {
                previewArea.setText("");
                return;
            }

            if (currentSourceType == SourceType.JAR) {
                if (Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar")) {
                    selectedBrokkFileForImport = file;
                    previewArea.setText(generateJarPreviewText(path));
                    importButton.setEnabled(true);
                } else {
                    previewArea.setText("Selected item is not a valid JAR file: " + path.getFileName());
                }
            } else { // DIRECTORY
                if (Files.isDirectory(path)) {
                    Path projectRoot = chrome.getProject().getRoot().normalize();
                    Path selectedPathNormalized = path.normalize();

                    if (selectedPathNormalized.startsWith(projectRoot)) {
                        selectedBrokkFileForImport = null; // Or keep `file` but ensure button is disabled
                        importButton.setEnabled(false);
                        previewArea.setText("Cannot import the project's own directory or its subdirectories.");
                    } else {
                        selectedBrokkFileForImport = file;
                        previewArea.setText(generateDirectoryPreviewText(path));
                        importButton.setEnabled(true);
                    }
                } else {
                    previewArea.setText("Selected item is not a valid directory: " + path.getFileName());
                }
            }
            previewArea.setCaretPosition(0); // Scroll to top
        }

        private String generateJarPreviewText(Path jarPath) {
            Map<String, Integer> classCountsByPackage = new HashMap<>();
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        String className = entry.getName().replace('/', '.');
                        className = className.substring(0, className.length() - ".class".length());
                        int lastDot = className.lastIndexOf('.');
                        String packageName = (lastDot == -1) ? "(default package)" : className.substring(0, lastDot);
                        classCountsByPackage.merge(packageName, 1, Integer::sum);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error reading JAR for preview: {}", jarPath, e);
                return "Error reading JAR: " + e.getMessage();
            }
            if (classCountsByPackage.isEmpty()) return "No classes found in JAR.";
            return classCountsByPackage.entrySet().stream()
                                       .sorted(Map.Entry.comparingByKey())
                                       .map(e -> e.getKey() + ": " + e.getValue() + " class(es)")
                                       .collect(Collectors.joining("\n"));
        }

        private String generateDirectoryPreviewText(Path dirPath) {
            List<String> extensions = chrome.getProject().getAnalyzerLanguage().getExtensions();
            Map<String, Long> counts = new TreeMap<>();

            long rootFileCount = 0;
            try (Stream<Path> filesInRoot = Files.list(dirPath).filter(Files::isRegularFile)) {
                rootFileCount = filesInRoot.filter(p -> {
                    String fileName = p.getFileName().toString();
                    int lastDot = fileName.lastIndexOf('.');
                    if (lastDot > 0 && lastDot < fileName.length() - 1) {
                        String ext = fileName.substring(lastDot + 1).toLowerCase();
                        return extensions.contains(ext);
                    }
                    return false;
                }).count();
            } catch (IOException e) {
                logger.warn("Error listing files in directory root for preview: {}", dirPath, e);
                // continue, some info might still be gathered from subdirs
            }
            if (rootFileCount > 0) {
                counts.put("(Files in " + dirPath.getFileName() + ")", rootFileCount);
            }

            try (Stream<Path> subdirs = Files.list(dirPath).filter(Files::isDirectory)) {
                for (Path subdir : subdirs.toList()) {
                    try (Stream<Path> allFilesRecursive = Files.walk(subdir)) {
                        long count = allFilesRecursive
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                String fileName = p.getFileName().toString();
                                int lastDot = fileName.lastIndexOf('.');
                                if (lastDot > 0 && lastDot < fileName.length() - 1) {
                                    String ext = fileName.substring(lastDot + 1).toLowerCase();
                                    return extensions.contains(ext);
                                }
                                return false;
                            })
                            .count();
                        if (count > 0) {
                            counts.put(subdir.getFileName().toString(), count);
                        }
                    } catch (IOException e) {
                         logger.warn("Error walking subdirectory for preview: {}", subdir, e);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error listing subdirectories for preview: {}", dirPath, e);
                if (counts.isEmpty() && rootFileCount == 0) return "Error reading directory: " + e.getMessage();
            }

            if (counts.isEmpty()) return "No relevant files found for project language (" + String.join(", ", extensions) + ").";

            String languageName = chrome.getProject().getAnalyzerLanguage().name().toLowerCase();
            return counts.entrySet().stream()
                         .map(e -> e.getKey() + ": " + e.getValue() + " " + languageName + " file(s)")
                         .collect(Collectors.joining("\n"));
        }


        private void performImport() {
            if (selectedBrokkFileForImport == null) {
                JOptionPane.showMessageDialog(dialog, "No valid source selected.", "Import Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Path sourcePath = selectedBrokkFileForImport.absPath();
            importButton.setEnabled(false); // Disable during operation

            if (currentSourceType == SourceType.JAR) {
                Decompiler.decompileJar(chrome, sourcePath, chrome.getContextManager()::submitBackgroundTask);
                // Decompiler.decompileJar is asynchronous and handles its own user feedback.
                // We might want to close the dialog or give some feedback here too.
                // For now, assume decompileJar gives enough feedback. Dialog stays open.
                // Re-enable button if needed, or simply close dialog on success from decompileJar.
                // Let's clear selection to prevent re-import of same.
                selectedBrokkFileForImport = null;
                previewArea.setText("Decompilation process started for " + sourcePath.getFileName());
                if (currentFileSelectionPanel != null) currentFileSelectionPanel.setInputText("");
                dialog.dispose();

            } else { // DIRECTORY
                Path targetPath = dependenciesRoot.resolve(sourcePath.getFileName());
                if (Files.exists(targetPath)) {
                    int response = JOptionPane.showConfirmDialog(dialog,
                            "The destination directory '" + targetPath.getFileName() + "' already exists. Overwrite?",
                            "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (response == JOptionPane.NO_OPTION) {
                        importButton.setEnabled(true); // Re-enable if cancelled
                        return;
                    }
                }

                chrome.getContextManager().submitBackgroundTask(
                    "Copying directory: " + sourcePath.getFileName(),
                    () -> {
                        try {
                            Files.createDirectories(dependenciesRoot);
                            if (Files.exists(targetPath)) {
                                ImportDependencyDialog.deleteRecursively(targetPath); // Use static method
                            }
                            var projectLanguage = chrome.getProject().getAnalyzerLanguage();
                            ImportDependencyDialog.copyDirectoryRecursively(sourcePath, targetPath, projectLanguage.getExtensions()); // Use static method
                            SwingUtilities.invokeLater(() -> {
                                chrome.systemOutput("Directory copied successfully to " + targetPath +
                                                    " (filtered by project language: " + projectLanguage.name() +
                                                    "). Reopen project to incorporate the new files.");
                                if (currentFileSelectionPanel != null) currentFileSelectionPanel.setInputText("");
                                previewArea.setText("Directory copied successfully.");
                                selectedBrokkFileForImport = null;
                                // importButton.setEnabled(false); // Keep disabled until new selection
                            });
                        } catch (IOException ex) {
                            logger.error("Error copying directory {} to {}", sourcePath, targetPath, ex);
                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(dialog, "Error copying directory: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                importButton.setEnabled(true); // Re-enable on error
                            });
                        }
                        return null;
                    }
                );
                dialog.dispose();
            }
        }
    }

    // Static helper methods, kept from original, ensure they are used via class name if called from DialogHelper
    private static void copyDirectoryRecursively(Path source, Path destination, List<String> allowedExtensions) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetDir = destination.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                int lastDot = fileName.lastIndexOf('.');
                // Ensure dot is not the first or last character and an extension exists
                if (lastDot > 0 && lastDot < fileName.length() - 1) {
                    String extension = fileName.substring(lastDot + 1).toLowerCase();
                    if (allowedExtensions.contains(extension)) {
                        Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            // Sort in reverse order to delete files before directories
            List<Path> pathsToDelete = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : pathsToDelete) {
                Files.delete(p);
            }
        }
    }
}
