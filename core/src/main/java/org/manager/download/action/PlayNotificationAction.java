package org.manager.download.action;

import org.manager.download.Download;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * After completion action that plays a sound notification when a download
 * completes. Supports various audio formats including WAV, AIFF, and AU. Can
 * play system sounds, custom sound files, or built-in notification sounds.
 */
public class PlayNotificationAction implements AfterCompletionAction {

    private static final Logger LOGGER = Logger.getLogger(PlayNotificationAction.class.getName());

    public enum NotificationSound {
        SYSTEM_BEEP, // System beep sound
        SUCCESS, // Built-in success sound
        NOTIFICATION, // Built-in notification sound
        COMPLETE, // Built-in completion sound
        CUSTOM_FILE, // Custom sound file
        SYSTEM_COMMAND // Custom system command (e.g., paplay, aplay)
    }

    private final NotificationSound soundType;
    private Path customSoundFile;
    private String systemCommand;
    private float volume = 1.0f; // Volume level (0.0 to 1.0)
    private boolean repeat = false;
    private int repeatCount = 1;
    private int timeoutSeconds = 10;
    private volatile Clip audioClip;
    private volatile Process systemProcess;
    private volatile CompletableFuture<Void> playbackFuture;
    private volatile boolean isPlaying = false;

    /**
     * Creates a new PlayNotificationAction with a built-in sound.
     *
     * @param soundType The type of notification sound to play
     */
    public PlayNotificationAction(NotificationSound soundType) {
        this.soundType = soundType;
    }

    /**
     * Creates a new PlayNotificationAction with a custom sound file.
     *
     * @param soundFile Path to the custom sound file
     */
    public PlayNotificationAction(Path soundFile) {
        this.soundType = NotificationSound.CUSTOM_FILE;
        this.customSoundFile = soundFile;
    }

    /**
     * Creates a new PlayNotificationAction with a custom system command.
     *
     * @param systemCommand System command to play sound (e.g., "paplay
     *                      /path/to/sound.wav")
     */
    public PlayNotificationAction(String systemCommand) {
        this.soundType = NotificationSound.SYSTEM_COMMAND;
        this.systemCommand = systemCommand;
    }

    @Override
    public boolean execute(Download download) {
        try {
            LOGGER.info("Playing notification sound for completed download: " + download.getName());

            return switch (soundType) {
                case SYSTEM_BEEP ->
                    playSystemBeep();
                case SUCCESS, NOTIFICATION, COMPLETE ->
                    playBuiltInSound();
                case CUSTOM_FILE ->
                    playCustomFile();
                case SYSTEM_COMMAND ->
                    playWithSystemCommand();
                default -> {
                    LOGGER.warning("Unknown sound type: " + soundType);
                    yield false;
                }
            };
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to play notification sound: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean playSystemBeep() {
        try {
            // Play system beep using AWT Toolkit
            java.awt.Toolkit.getDefaultToolkit().beep();

            // For multiple beeps
            if (repeat && repeatCount > 1) {
                playbackFuture = CompletableFuture.runAsync(() -> {
                    try {
                        for (int i = 1; i < repeatCount && !Thread.currentThread().isInterrupted(); i++) {
                            Thread.sleep(500); // Wait between beeps
                            java.awt.Toolkit.getDefaultToolkit().beep();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            LOGGER.info("System beep played successfully");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to play system beep: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean playBuiltInSound() {
        try {
            // Generate a built-in sound based on the type
            byte[] soundData = generateBuiltInSound();
            return playAudioData(soundData);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to play built-in sound: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean playCustomFile() {
        if (customSoundFile == null || !Files.exists(customSoundFile)) {
            LOGGER.warning("Custom sound file not found: " + customSoundFile);
            return false;
        }

        try {
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(customSoundFile.toFile());
            return playAudioStream(audioInputStream);
        } catch (UnsupportedAudioFileException e) {
            LOGGER.warning("Unsupported audio file format: " + customSoundFile);
            return false;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to read audio file: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean playWithSystemCommand() {
        if (systemCommand == null || systemCommand.trim().isEmpty()) {
            LOGGER.warning("System command is empty");
            return false;
        }

        try {
            isPlaying = true;
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", systemCommand);
            pb.redirectErrorStream(true);
            systemProcess = pb.start();

            playbackFuture = CompletableFuture.runAsync(() -> {
                try {
                    boolean finished = systemProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                    if (!finished) {
                        LOGGER.warning("Sound command timed out, terminating process");
                        systemProcess.destroyForcibly();
                    } else {
                        int exitCode = systemProcess.exitValue();
                        if (exitCode != 0) {
                            LOGGER.warning("Sound command exited with code: " + exitCode);
                        } else {
                            LOGGER.info("Sound played successfully via system command");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("Sound playback interrupted");
                } finally {
                    isPlaying = false;
                }
            });

            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to execute sound command: " + e.getMessage(), e);
            isPlaying = false;
            return false;
        }
    }

    private boolean playAudioStream(AudioInputStream audioInputStream) {
        try {
            audioClip = AudioSystem.getClip();
            audioClip.open(audioInputStream);

            // Set volume if supported
            if (audioClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gainControl = (FloatControl) audioClip.getControl(FloatControl.Type.MASTER_GAIN);
                float dB = (float) (Math.log(volume) / Math.log(10.0) * 20.0);
                gainControl.setValue(Math.max(gainControl.getMinimum(), Math.min(dB, gainControl.getMaximum())));
            }

            isPlaying = true;

            // Set up playback completion handling
            playbackFuture = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < (repeat ? repeatCount : 1) && !Thread.currentThread().isInterrupted(); i++) {
                        audioClip.setFramePosition(0); // Reset to beginning
                        audioClip.start();

                        // Wait for playback to complete
                        while (audioClip.isRunning() && !Thread.currentThread().isInterrupted()) {
                            Thread.sleep(100);
                        }

                        audioClip.stop();

                        if (i < repeatCount - 1) {
                            Thread.sleep(200); // Brief pause between repeats
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("Audio playback interrupted");
                } finally {
                    if (audioClip != null) {
                        audioClip.close();
                    }
                    isPlaying = false;
                }
            });

            LOGGER.info("Audio playback started");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to play audio stream: " + e.getMessage(), e);
            isPlaying = false;
            return false;
        }
    }

    private boolean playAudioData(byte[] audioData) {
        try {
            AudioFormat format = new AudioFormat(22050, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warning("Audio format not supported");
                return false;
            }

            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();

            isPlaying = true;

            playbackFuture = CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 0; i < (repeat ? repeatCount : 1) && !Thread.currentThread().isInterrupted(); i++) {
                        line.write(audioData, 0, audioData.length);
                        line.drain();

                        if (i < repeatCount - 1) {
                            Thread.sleep(200); // Brief pause between repeats
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    line.stop();
                    line.close();
                    isPlaying = false;
                }
            });

            LOGGER.info("Generated audio playback started");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to play generated audio: " + e.getMessage(), e);
            isPlaying = false;
            return false;
        }
    }

    private byte[] generateBuiltInSound() {
        // Generate a simple tone based on the sound type
        int sampleRate = 22050;
        double duration = 0.5; // Duration in seconds
        int frequency;

        frequency = switch (soundType) {
            case SUCCESS ->
                800; // High pleasant tone
            case NOTIFICATION ->
                600; // Medium tone
            case COMPLETE ->
                400; // Lower completion tone
            default ->
                440; // Default tone (A4)
        };

        int numSamples = (int) (sampleRate * duration);
        byte[] audioData = new byte[numSamples * 2]; // 16-bit samples

        for (int i = 0; i < numSamples; i++) {
            double time = i / (double) sampleRate;
            double amplitude = Math.sin(2 * Math.PI * frequency * time);

            // Apply envelope to avoid clicks
            double envelope = 1.0;
            if (time < 0.05) {
                envelope = time / 0.05; // Fade in
            } else if (time > duration - 0.05) {
                envelope = (duration - time) / 0.05; // Fade out
            }

            amplitude *= envelope * volume;

            // Convert to 16-bit signed integer
            short sample = (short) (amplitude * Short.MAX_VALUE);
            audioData[i * 2] = (byte) (sample & 0xFF);
            audioData[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        return audioData;
    }

    @Override
    public ActionType getType() {
        return ActionType.PLAY_SOUND;
    }

    @Override
    public String getDescription() {
        return switch (soundType) {
            case SYSTEM_BEEP ->
                "Play system beep" + (repeat ? " (" + repeatCount + " times)" : "");
            case SUCCESS ->
                "Play success sound" + (repeat ? " (" + repeatCount + " times)" : "");
            case NOTIFICATION ->
                "Play notification sound" + (repeat ? " (" + repeatCount + " times)" : "");
            case COMPLETE ->
                "Play completion sound" + (repeat ? " (" + repeatCount + " times)" : "");
            case CUSTOM_FILE ->
                "Play custom sound: " + (customSoundFile != null ? customSoundFile.getFileName() : "unknown");
            case SYSTEM_COMMAND ->
                "Execute sound command: " + (systemCommand != null ? systemCommand : "unknown");
            default ->
                "Play notification sound";
        };
    }

    @Override
    public Severity getSeverity() {
        return Severity.LOW;
    }

    @Override
    public boolean cancel() {
        if (!isPlaying) {
            return true; // Nothing to cancel
        }

        try {
            if (audioClip != null && audioClip.isRunning()) {
                audioClip.stop();
                audioClip.close();
            }

            if (systemProcess != null && systemProcess.isAlive()) {
                systemProcess.destroyForcibly();
            }

            if (playbackFuture != null) {
                playbackFuture.cancel(true);
            }

            isPlaying = false;
            LOGGER.info("Sound playback canceled");
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to cancel sound playback: " + e.getMessage(), e);
            return false;
        }
    }

    // Configuration methods
    /**
     * Set the volume level for audio playback.
     *
     * @param volume Volume level from 0.0 (silent) to 1.0 (full volume)
     * @return This action for method chaining
     */
    public PlayNotificationAction setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        return this;
    }

    /**
     * Set whether to repeat the sound.
     *
     * @param repeat      Whether to repeat the sound
     * @param repeatCount Number of times to repeat (ignored if repeat is false)
     * @return This action for method chaining
     */
    public PlayNotificationAction setRepeat(boolean repeat, int repeatCount) {
        this.repeat = repeat;
        this.repeatCount = Math.max(1, repeatCount);
        return this;
    }

    /**
     * Set the timeout for sound playback.
     *
     * @param timeoutSeconds Timeout in seconds
     * @return This action for method chaining
     */
    public PlayNotificationAction setTimeout(int timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        return this;
    }

    // Getter methods
    /**
     * Get the notification sound type.
     *
     * @return The notification sound type
     */
    public NotificationSound getSoundType() {
        return soundType;
    }

    /**
     * Get the custom sound file path.
     *
     * @return The custom sound file path, or null if not using custom file
     */
    public Path getCustomSoundFile() {
        return customSoundFile;
    }

    /**
     * Get the system command.
     *
     * @return The system command, or null if not using system command
     */
    public String getSystemCommand() {
        return systemCommand;
    }

    /**
     * Get the current volume level.
     *
     * @return The volume level (0.0 to 1.0)
     */
    public float getVolume() {
        return volume;
    }

    /**
     * Check if repeat is enabled.
     *
     * @return true if repeat is enabled
     */
    public boolean isRepeat() {
        return repeat;
    }

    /**
     * Get the repeat count.
     *
     * @return The number of times to repeat
     */
    public int getRepeatCount() {
        return repeatCount;
    }

    /**
     * Get the timeout in seconds.
     *
     * @return The timeout in seconds
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Check if sound is currently playing.
     *
     * @return true if sound is playing
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * Create a PlayNotificationAction that tries multiple sound methods in
     * order. This is useful for ensuring sound plays on different systems.
     *
     * @return A new PlayNotificationAction with fallback options
     */
    public static PlayNotificationAction createWithFallbacks() {
        // Try system command first (usually most reliable on Linux)
        String[] commands = {
                "paplay /usr/share/sounds/alsa/Front_Left.wav", // PulseAudio
                "aplay /usr/share/sounds/alsa/Front_Left.wav", // ALSA
                "speaker-test -t sine -f 1000 -l 1", // Speaker test
                "echo -e '\\007'" // Terminal bell
        };

        for (String command : commands) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", "which " + command.split(" ")[0]);
                Process process = pb.start();
                if (process.waitFor() == 0) {
                    return new PlayNotificationAction(command);
                }
            } catch (Exception e) {
                // Continue to next option
            }
        }

        // Fallback to built-in sounds
        return new PlayNotificationAction(NotificationSound.SYSTEM_BEEP);
    }
}
