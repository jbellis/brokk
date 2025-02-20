package io.github.jbellis.brokk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Project {
    private final Path propertiesFile;
    private final Path root;
    private final IConsoleIO io;
    private final Properties props;
    
    private static final int DEFAULT_AUTO_CONTEXT_FILE_COUNT = 10;

    public Project(Path root, IConsoleIO io) {
        this.root = root;
        this.propertiesFile = root.resolve(".brokk").resolve("project.properties");
        this.io = io;
        this.props = new Properties();

        if (Files.exists(propertiesFile)) {
            try (var reader = Files.newBufferedReader(propertiesFile)) {
                props.load(reader);
                // Validate autoContextFileCount on load
                getAutoContextFileCount();
            } catch (Exception e) {
                io.toolErrorRaw("Error loading project properties: " + e.getMessage());
                props.clear();
            }
        }

        // Set defaults on missing or error
        if (props.isEmpty()) {
            props.setProperty("autoContextFileCount", String.valueOf(DEFAULT_AUTO_CONTEXT_FILE_COUNT));
        }
    }

    public String getBuildCommand() {
        return props.getProperty("buildCommand");
    }

    public void setBuildCommand(String command) {
        props.setProperty("buildCommand", command);
        saveProperties();
    }
    
    public int getAutoContextFileCount() {
        return Integer.parseInt(props.getProperty("autoContextFileCount"));
    }
    
    public void setAutoContextFileCount(int count) {
        props.setProperty("autoContextFileCount", String.valueOf(count));
        saveProperties();
    }
    
    private void saveProperties() {
        try {
            Files.createDirectories(propertiesFile.getParent());
            try (var writer = Files.newBufferedWriter(propertiesFile)) {
                props.store(writer, "Brokk project configuration");
            }
        } catch (IOException e) {
            io.toolErrorRaw("Error saving project properties: " + e.getMessage());
        }
    }

    public IConsoleIO getIo() {
        return io;
    }

    public Path getRoot() {
        return root;
    }

    public enum CpgRefresh {
        AUTO,
        MANUAL,
        UNSET;
    }

    public CpgRefresh getCpgRefresh() {
        String value = props.getProperty("cpg_refresh");
        if (value == null) {
            return CpgRefresh.UNSET;
        }
        try {
            return CpgRefresh.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CpgRefresh.UNSET;
        }
    }

    public void setCpgRefresh(CpgRefresh value) {
        assert value != null;
        props.setProperty("cpg_refresh", value.name());
        saveProperties();
    }
}
