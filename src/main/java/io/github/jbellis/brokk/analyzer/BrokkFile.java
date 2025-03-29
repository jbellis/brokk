package io.github.jbellis.brokk.analyzer;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface BrokkFile extends Serializable {
    Path absPath();

    default String read() throws IOException {
        return Files.readString(absPath());
    }

    default boolean exists() {
        return Files.exists(absPath());
    }

    default boolean isText() {
        try {
            return Files.isRegularFile(absPath())
                    && (FileExtensions.EXTENSIONS.stream().anyMatch(ext -> absPath().endsWith(ext))
                        || Files.size(absPath()) < 128 * 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Just the filename, no path at all
     */
    default String getFileName() {
        return absPath().getFileName().toString();
    }

    @Override
    String toString();

}
