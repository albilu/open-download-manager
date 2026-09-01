package org.manager.download.action;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;

/**
 * Demo program that detects and tests sound system capabilities. This utility
 * helps users configure optimal sound notifications for their system.
 */
public class SoundSystemDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundSystemDetector.class);

    public static void main(String[] args) {
        SoundSystemDetector detector = new SoundSystemDetector();

        try {
            detector.runDetection();
        } catch (Exception e) {
            LOGGER.error("Sound system detection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void runDetection() {
        LOGGER.info("=== Open Download Manager Sound System Detector ===");
        LOGGER.info("");

        // Detect sound system capabilities
        LOGGER.info("Detecting sound system capabilities...");
        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();

        // Display system information
        displaySystemInfo(info);

        // Test individual capabilities
        testIndividualCapabilities(info);

        // Run comprehensive tests
        runComprehensiveTests();

        // Provide recommendations
        provideRecommendations(info);
    }

    private void displaySystemInfo(SoundSystemUtils.SoundSystemInfo info) {
        LOGGER.info("=== System Information ===");
        LOGGER.info("Operating System: " + info.getOperatingSystem());
        LOGGER.info("Recommended Method: " + info.getRecommendedMethod());
        LOGGER.info("");

        LOGGER.info("=== Java Audio Support ===");
        LOGGER.info("Java Audio API: " + (info.isJavaAudioSupported() ? "✓ Available" : "✗ Not available"));
        LOGGER.info("System Beep: " + (info.isSystemBeepSupported() ? "✓ Available" : "✗ Not available"));
        LOGGER.info("Supported Formats: " + info.getSupportedFormats());
        LOGGER.info("Audio Devices: " + info.getAvailableDevices().size() + " found");

        if (!info.getAvailableDevices().isEmpty()) {
            LOGGER.info("Available Audio Devices:");
            for (String device : info.getAvailableDevices()) {
                LOGGER.info("  - " + device);
            }
        }
        LOGGER.info("");

        LOGGER.info("=== System Commands ===");
        if (info.getAvailableCommands().isEmpty()) {
            LOGGER.info("No sound commands found");
        } else {
            LOGGER.info("Available Commands:");
            for (String command : info.getAvailableCommands()) {
                LOGGER.info("  - " + command);
            }
        }
        LOGGER.info("");

        LOGGER.info("=== Sound Files ===");
        if (info.getAvailableSoundFiles().isEmpty()) {
            LOGGER.info("No system sound files found");
        } else {
            LOGGER.info("Available Sound Files: " + info.getAvailableSoundFiles().size() + " found");
            for (int i = 0; i < Math.min(5, info.getAvailableSoundFiles().size()); i++) {
                LOGGER.info("  - " + info.getAvailableSoundFiles().get(i));
            }
            if (info.getAvailableSoundFiles().size() > 5) {
                LOGGER.info("  ... and " + (info.getAvailableSoundFiles().size() - 5) + " more");
            }
        }
        LOGGER.info("");
    }

    private void testIndividualCapabilities(SoundSystemUtils.SoundSystemInfo info) {
        LOGGER.info("=== Individual Capability Tests ===");

        // Test system beep
        if (info.isSystemBeepSupported()) {
            LOGGER.info("Testing system beep...");
            PlayNotificationAction beepAction = new PlayNotificationAction(
                    PlayNotificationAction.NotificationSound.SYSTEM_BEEP);
            boolean success = testAction(beepAction, 2000);
            LOGGER.info("System beep test: " + (success ? "✓ Success" : "✗ Failed"));
        }

        // Test Java Audio with built-in sound
        if (info.isJavaAudioSupported()) {
            LOGGER.info("Testing Java Audio with built-in sound...");
            PlayNotificationAction audioAction = new PlayNotificationAction(
                    PlayNotificationAction.NotificationSound.SUCCESS);
            boolean success = testAction(audioAction, 3000);
            LOGGER.info("Java Audio test: " + (success ? "✓ Success" : "✗ Failed"));
        }

        // Test custom sound file
        if (!info.getAvailableSoundFiles().isEmpty()) {
            LOGGER.info("Testing custom sound file...");
            PlayNotificationAction fileAction = new PlayNotificationAction(
                    info.getAvailableSoundFiles().get(0));
            boolean success = testAction(fileAction, 3000);
            LOGGER.info("Custom file test: " + (success ? "✓ Success" : "✗ Failed"));
            LOGGER.info("  File: " + info.getAvailableSoundFiles().get(0));
        }

        // Test system command
        if (!info.getAvailableCommands().isEmpty() && !info.getAvailableSoundFiles().isEmpty()) {
            LOGGER.info("Testing system command...");
            String command = info.getAvailableCommands().get(0) + " "
                    + info.getAvailableSoundFiles().get(0).toString();
            PlayNotificationAction commandAction = new PlayNotificationAction(command);
            boolean success = testAction(commandAction, 5000);
            LOGGER.info("System command test: " + (success ? "✓ Success" : "✗ Failed"));
            LOGGER.info("  Command: " + command);
        }

        LOGGER.info("");
    }

    private void runComprehensiveTests() {
        LOGGER.info("=== Comprehensive Test Suite ===");
        LOGGER.info("Running comprehensive tests (this may take a moment)...");

        Map<String, Boolean> results = SoundSystemUtils.runComprehensiveTest();

        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            String status = entry.getValue() ? "✓ PASS" : "✗ FAIL";
            LOGGER.info(String.format("%-25s: %s", entry.getKey(), status));
        }

        long passCount = results.values().stream().mapToLong(b -> b ? 1 : 0).sum();
        LOGGER.info("");
        LOGGER.info("Results: " + passCount + "/" + results.size() + " tests passed");
        LOGGER.info("");
    }

    private void provideRecommendations(SoundSystemUtils.SoundSystemInfo info) {
        LOGGER.info("=== Recommendations ===");

        // Create optimal action
        PlayNotificationAction optimal = SoundSystemUtils.createOptimalNotificationAction();
        LOGGER.info("Recommended configuration:");
        LOGGER.info("  Method: " + optimal.getSoundType());
        LOGGER.info("  Description: " + optimal.getDescription());

        // Test the recommended configuration
        LOGGER.info("");
        LOGGER.info("Testing recommended configuration...");
        boolean success = testAction(optimal, 3000);
        LOGGER.info("Recommended config test: " + (success ? "✓ Success" : "✗ Failed"));

        // Provide platform-specific advice
        LOGGER.info("");
        LOGGER.info("Platform-specific advice:");
        String os = info.getOperatingSystem();

        if (os.contains("linux")) {
            LOGGER.info("For Linux systems:");
            LOGGER.info("  - Install PulseAudio for best compatibility (paplay command)");
            LOGGER.info("  - ALSA is also well supported (aplay command)");
            LOGGER.info("  - System sound files are typically in /usr/share/sounds/");
            LOGGER.info("  - Consider installing sound-theme-freedesktop package");
        } else if (os.contains("mac") || os.contains("darwin")) {
            LOGGER.info("For macOS systems:");
            LOGGER.info("  - Built-in afplay command should work well");
            LOGGER.info("  - System sounds are in /System/Library/Sounds/");
            LOGGER.info("  - Java Audio API is generally reliable");
        } else if (os.contains("windows")) {
            LOGGER.info("For Windows systems:");
            LOGGER.info("  - Java Audio API is recommended");
            LOGGER.info("  - PowerShell can be used for system sounds");
            LOGGER.info("  - System sounds are in C:\\Windows\\Media\\");
        } else {
            LOGGER.info("For this operating system:");
            LOGGER.info("  - Java Audio API is the most portable option");
            LOGGER.info("  - System beep should work as a fallback");
        }

        // Usage examples
        LOGGER.info("");
        LOGGER.info("Usage examples:");
        LOGGER.info("");

        LOGGER.info("// Simple system beep");
        LOGGER.info("PlayNotificationAction beep = new PlayNotificationAction(");
        LOGGER.info("    PlayNotificationAction.NotificationSound.SYSTEM_BEEP);");
        LOGGER.info("");

        if (!info.getAvailableSoundFiles().isEmpty()) {
            LOGGER.info("// Custom sound file");
            LOGGER.info("PlayNotificationAction custom = new PlayNotificationAction(");
            LOGGER.info("    Paths.get(\"" + info.getAvailableSoundFiles().get(0) + "\"));");
            LOGGER.info("");
        }

        if (!info.getAvailableCommands().isEmpty() && !info.getAvailableSoundFiles().isEmpty()) {
            String command = info.getAvailableCommands().get(0) + " "
                    + info.getAvailableSoundFiles().get(0).toString();
            LOGGER.info("// System command");
            LOGGER.info("PlayNotificationAction command = new PlayNotificationAction(");
            LOGGER.info("    \"" + command + "\");");
            LOGGER.info("");
        }

        LOGGER.info("// Auto-detect best method");
        LOGGER.info("PlayNotificationAction auto = SoundSystemUtils.createOptimalNotificationAction();");
        LOGGER.info("");

        LOGGER.info("// Advanced configuration");
        LOGGER.info("PlayNotificationAction advanced = new PlayNotificationAction(");
        LOGGER.info("    PlayNotificationAction.NotificationSound.SUCCESS)");
        LOGGER.info("    .setVolume(0.8f)");
        LOGGER.info("    .setRepeat(true, 2)");
        LOGGER.info("    .setTimeout(10);");

        LOGGER.info("");
        LOGGER.info("=== Detection Complete ===");
    }

    private boolean testAction(PlayNotificationAction action, long timeoutMs) {
        try {
            // Create a test download
            Download testDownload = new Download(new URI("https://example.com/test.zip"));
            testDownload.setName("test-file.zip");
            testDownload.setDestination(Paths.get(System.getProperty("java.io.tmpdir")));
            testDownload.setStatus(Download.Status.COMPLETED);

            // Execute the action
            boolean result = action.execute(testDownload);

            // Wait for completion
            long startTime = System.currentTimeMillis();
            while (action.isPlaying() && (System.currentTimeMillis() - startTime) < timeoutMs) {
                Thread.sleep(100);
            }

            // Cancel if still playing
            if (action.isPlaying()) {
                action.cancel();
            }

            return result;
        } catch (Exception e) {
            LOGGER.debug("Test failed: " + e.getMessage());
            return false;
        }
    }
}
