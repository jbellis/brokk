package io.github.jbellis.brokk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Project {
    private final Path propertiesFile;
    private final IConsoleIO io;

    public Project(Path root, IConsoleIO io) {
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.io = io;
    }

    public String loadBuildCommand() {
        if (!Files.exists(propertiesFile)) {
            return null;
        }
        try {
            var props = new Properties();
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                props.load(reader);
            }
            return props.getProperty("buildCommand");
        } catch (IOException e) {
            io.toolErrorRaw("Error loading build command: " + e.getMessage());
            return null;
        }
    }

    public void saveBuildCommand(String command) {
        try {
            Files.createDirectories(propertiesFile.getParent());
            var props = new Properties();
            if (Files.exists(propertiesFile)) {
                try (var reader = Files.newBufferedReader(propertiesFile)) {
                    props.load(reader);
                }
            }
            props.setProperty("buildCommand", command);
            try (var writer = Files.newBufferedWriter(propertiesFile)) {
                props.store(writer, "Brokk project configuration");
            }
        } catch (IOException e) {
            io.toolErrorRaw("Error saving build command: " + e.getMessage());
        }
    }
}
