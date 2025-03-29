package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A button that captures voice input from the microphone and transcribes it to a text area.
 */
public class VoiceInputButton extends JButton {
    private static final Logger logger = LogManager.getLogger(VoiceInputButton.class);
    
    private final JTextArea targetTextArea;
    private final ContextManager contextManager;
    private final Consumer<String> onError;
    private final Runnable onRecordingStart;
    
    // For STT (mic) usage
    private volatile TargetDataLine micLine = null;
    private volatile ByteArrayOutputStream micBuffer = null;
    private volatile Thread micCaptureThread = null;
    private ImageIcon micOnIcon;
    private ImageIcon micOffIcon;

    /**
     * Creates a new voice input button.
     *
     * @param targetTextArea the text area where transcribed text will be placed
     * @param contextManager the context manager for speech-to-text processing
     * @param onRecordingStart callback when recording starts
     * @param onError callback for error handling
     */
    public VoiceInputButton(JTextArea targetTextArea,
                            ContextManager contextManager,
                            Runnable onRecordingStart,
                            Consumer<String> onError)
    {
        assert targetTextArea != null;

        this.targetTextArea = targetTextArea;
        this.contextManager = contextManager;
        this.onRecordingStart = onRecordingStart;
        this.onError = onError;
        
        // Load mic icons
        try {
            micOnIcon = new ImageIcon(getClass().getResource("/mic-on.png"));
            micOffIcon = new ImageIcon(getClass().getResource("/mic-off.png"));
            // Scale icons if needed
            micOnIcon = new ImageIcon(micOnIcon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH));
            micOffIcon = new ImageIcon(micOffIcon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH));
            logger.debug("Successfully loaded mic icons");
        } catch (Exception e) {
            logger.warn("Failed to load mic icons", e);
            // We'll fall back to text if icons can't be loaded
            micOnIcon = null;
            micOffIcon = null;
        }
        
        // Set default appearance
        if (micOffIcon != null) {
            setIcon(micOffIcon);
        } else {
            setText("Mic");
        }
        
        setPreferredSize(new Dimension(32, 32));
        setMinimumSize(new Dimension(32, 32));
        setMaximumSize(new Dimension(32, 32));
        
        // Track recording state
        putClientProperty("isRecording", false);
        
        // Configure the toggle behavior
        addActionListener(e -> {
            boolean isRecording = (boolean)getClientProperty("isRecording");
            if (isRecording) {
                // If recording, stop and transcribe
                stopMicCaptureAndTranscribe();
                putClientProperty("isRecording", false);
            } else {
                // Otherwise start recording
                startMicCapture();
                putClientProperty("isRecording", true);
            }
        });

        model.setEnabled(contextManager != null);
    }
    
    /**
     * Configures the button based on STT availability.
     * 
     * @param sttEnabled whether STT is available
     * @return this button instance for method chaining
     */
    public VoiceInputButton configure(boolean sttEnabled) {
        if (!sttEnabled) {
            setEnabled(false);
            setToolTipText("OpenAI key is required for STT");
        } else {
            setEnabled(true);
            setToolTipText("Click to start/stop recording");
        }
        return this;
    }

    /**
     * Starts capturing audio from the default microphone to micBuffer on a background thread.
     */
    private void startMicCapture() {
        try {
            // disable input field while capturing
            targetTextArea.setEnabled(false);

            // Change icon to mic-on
            if (micOnIcon != null) {
                setIcon(micOnIcon);
            }

            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, true);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(format);
            micLine.start();

            micBuffer = new ByteArrayOutputStream();
            micCaptureThread = new Thread(() -> {
                var data = new byte[4096];
                while (micLine != null && micLine.isOpen()) {
                    int bytesRead = micLine.read(data, 0, data.length);
                    if (bytesRead > 0) {
                        synchronized (micBuffer) {
                            micBuffer.write(data, 0, bytesRead);
                        }
                    }
                }
            }, "mic-capture-thread");
            micCaptureThread.start();
            // Notify that recording has started
            onRecordingStart.run();
        } catch (Exception ex) {
            logger.error("Failed to start mic capture", ex);
            onError.accept("Error starting mic capture: " + ex.getMessage());
            targetTextArea.setEnabled(true);
        }
    }

    /**
     * Stops capturing and sends to STT on a background thread.
     */
    private void stopMicCaptureAndTranscribe() {
        assert contextManager != null;

        // stop capturing
        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }
        micLine = null;

        // Change icon back to mic-off and restore background
        if (micOffIcon != null) {
            setIcon(micOffIcon);
        }
        setBackground(null); // Return to default button background

        // Convert the in-memory raw PCM data to a valid .wav file
        var audioBytes = micBuffer.toByteArray();

        // We do the STT in the background so as not to block the UI
        contextManager.submitUserTask("Transcribing Audio", () -> {
            try {
                // Our original AudioFormat from startMicCapture
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, true);

                // Create an AudioInputStream wrapping the raw data + format
                try (var bais = new java.io.ByteArrayInputStream(audioBytes);
                     var ais = new AudioInputStream(bais, format, audioBytes.length / format.getFrameSize()))
                {
                    // Write to a temp .wav
                    var tempFile = Files.createTempFile("brokk-stt-", ".wav");
                    AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tempFile.toFile());

                    // call coder
                    var transcript = contextManager.getCoder().transcribeAudio(tempFile);

                    // put it in the target text area
                    SwingUtilities.invokeLater(() -> {
                        targetTextArea.setEnabled(true);
                        if (!transcript.isBlank()) {
                            // If user typed something already, put a space
                            if (!targetTextArea.getText().isBlank()) {
                                targetTextArea.append(" ");
                            }
                            targetTextArea.append(transcript);
                        }
                    });

                    // cleanup
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignore) {}
                }
            } catch (IOException e) {
                logger.error("Error writing audio data: {}", e.getMessage(), e);
                onError.accept("Error writing audio data: " + e.getMessage());
                SwingUtilities.invokeLater(() -> targetTextArea.setEnabled(true));
            }
        });
    }
}
