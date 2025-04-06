
package io.github.jbellis.brokk.difftool.doc;


import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FileDocument
        extends AbstractBufferDocument {
    // instance variables:
    private final File file;
    private Charset charset;
    private final Map<String, Charset> charsetMap;
    private boolean readOnly;

    public FileDocument(File file, String name) {
        super(); // Call AbstractBufferDocument constructor
        this.file = file;
        this.readOnly = !file.canWrite();
        charsetMap = Charset.availableCharsets();
        setName(name); // Set names before reading
        setShortName(file.getName());
        initializeAndRead(); // Read content during construction
    }

    @Override
    public boolean isReadonly() {
        return readOnly;
    }

    @Override
    public int getBufferSize() {
        // Return a reasonable default buffer size if file doesn't exist or is empty
        return (file != null && file.exists()) ? (int) Math.max(file.length(), 1024) : 1024;
    }

    @Override
    public Reader getReader() {
        try {
            if (!file.exists() || !file.canRead()) {
                System.err.println("Warning: File does not exist or cannot be read: " + file.getAbsolutePath());
                // Return a reader for an empty string if file is inaccessible
                return new BufferedReader(new InputStreamReader(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            }

            // Use try-with-resources for the InputStream
            try (InputStream fis = new FileInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(fis)) {

                // Detect charset *without* consuming the stream if possible,
                // or reset it if necessary and supported.
                bis.mark(1024 * 1024); // Mark a large enough buffer for detection
                this.charset = detectCharset(bis);
                bis.reset(); // Reset stream to the beginning

                if (this.charset == null) {
                    System.err.println("Warning: Could not detect charset for " + file.getName() + ", using default UTF-8.");
                    this.charset = StandardCharsets.UTF_8;
                    // No need to reset again if detection failed, already at start
                }

                // MUST return a NEW InputStreamReader each time, as the underlying stream (bis)
                // might be closed by the caller (e.g., DefaultEditorKit.read).
                // We re-open the file here to ensure a fresh stream.
                return new BufferedReader(new InputStreamReader(new FileInputStream(file), this.charset));
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create reader for file: " + file.getName(), ex);
        }
    }

    private Charset detectCharset(BufferedInputStream bis) {
        try {
            CharsetDetector detector = new CharsetDetector();
            // Provide the stream directly if supported, or read bytes if needed.
            // The detector might consume part of the stream, hence the mark/reset in getReader.
            detector.setText(bis);
            CharsetMatch match = detector.detect();
            if (match != null) {
                String charsetName = match.getName();
                // Ensure the detected charset is supported by Java
                if (Charset.isSupported(charsetName)) {
                    return Charset.forName(charsetName);
                } else {
                    System.err.println("Detected charset '" + charsetName + "' is not supported by Java runtime.");
                }
            }
        } catch (IOException ex) {
            // Log error during detection
            System.err.println("IOException during charset detection for " + file.getName() + ": " + ex.getMessage());
        }
        // Return null if detection fails or charset is unsupported
        return null;
    }

    @Override
    public Writer getWriter() throws IOException {
         if (isReadonly()) {
             throw new IOException("Cannot get writer for read-only file: " + file.getName());
         }
        try {
             // Ensure the detected or default charset is used for writing
             Charset effectiveCharset = (this.charset != null) ? this.charset : StandardCharsets.UTF_8;
            // Use try-with-resources for the output streams
            FileOutputStream fos = new FileOutputStream(file); // Opens the file for writing (truncates by default)
            BufferedOutputStream bos = new BufferedOutputStream(fos);
            return new BufferedWriter(new OutputStreamWriter(bos, effectiveCharset));
        } catch (IOException ex) {
            throw new RuntimeException("Cannot create FileWriter for file: " + file.getName(), ex);
        }
    }

    @Override
    public void read() {
        // Re-initialize and read the file content again
        initializeAndRead();
    }
}
