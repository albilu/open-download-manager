package org.manager.download.action;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import org.manager.download.Download;

/**
 * Utility class for detecting and testing sound system capabilities. Provides
 * methods to check available audio devices, formats, and system commands.
 */
public class SoundSystemUtils {

    private static final Logger LOGGER = Logger.getLogger(SoundSystemUtils.class.getName());

    // Common system sound files across different operating systems
    private static final String[] COMMON_SOUND_FILES = {
            // Linux
            "/usr/share/sounds/alsa/Front_Left.wav",
            "/usr/share/sounds/alsa/Front_Right.wav",
            "/usr/share/sounds/sound-icons/bell.wav",
            "/usr/share/sounds/sound-icons/prompt.wav",
            "/usr/share/sounds/generic/button-3.wav",
            "/usr/share/sounds/purple/alert.wav",
            "/usr/share/sounds/ubuntu/stereo/bell.ogg",
            "/usr/share/sounds/freedesktop/stereo/bell.oga",
            // macOS
            "/System/Library/Sounds/Ping.aiff",
            "/System/Library/Sounds/Pop.aiff",
            "/System/Library/Sounds/Sosumi.aiff",
            "/System/Library/Sounds/Submarine.aiff",
            "/System/Library/Sounds/Glass.aiff",
            // Windows
            "C:\\Windows\\Media\\Windows Ding.wav",
            "C:\\Windows\\Media\\Windows Notify.wav",
            "C:\\Windows\\Media\\chimes.wav",
            "C:\\Windows\\Media\\ding.wav",
            "C:\\Windows\\Media\\notify.wav"
    };

    // Common sound playback commands
    private static final String[] SOUND_COMMANDS = {
            // Linux - PulseAudio
            "paplay",
            "pacat",
            // Linux - ALSA
            "aplay",
            "speaker-test",
            // Linux - OSS
            "ossplay",
            // Cross-platform
            "ffplay",
            "mplayer",
            "vlc",
            // macOS
            "afplay",
            // Windows (through WSL or Cygwin)
            "powershell"
    };

    /**
     * Information about the sound system capabilities.
     */
    public static class SoundSystemInfo {

        private final boolean javaAudioSupported;
        private final boolean systemBeepSupported;
        private final List<String> availableCommands;
        private final List<Path> availableSoundFiles;
        private final List<String> supportedFormats;
        private final List<String> availableDevices;
        private final String operatingSystem;
        private final String recommendedMethod;

        public SoundSystemInfo(boolean javaAudioSupported, boolean systemBeepSupported,
                List<String> availableCommands, List<Path> availableSoundFiles,
                List<String> supportedFormats, List<String> availableDevices,
                String operatingSystem, String recommendedMethod) {
            this.javaAudioSupported = javaAudioSupported;
            this.systemBeepSupported = systemBeepSupported;
            this.availableCommands = new ArrayList<>(availableCommands);
            this.availableSoundFiles = new ArrayList<>(availableSoundFiles);
            this.supportedFormats = new ArrayList<>(supportedFormats);
            this.availableDevices = new ArrayList<>(availableDevices);
            this.operatingSystem = operatingSystem;
            this.recommendedMethod = recommendedMethod;
        }

        // Getters
        public boolean isJavaAudioSupported() {
            return javaAudioSupported;
        }

        public boolean isSystemBeepSupported() {
            return systemBeepSupported;
        }

        public List<String> getAvailableCommands() {
            return new ArrayList<>(availableCommands);
        }

        public List<Path> getAvailableSoundFiles() {
            return new ArrayList<>(availableSoundFiles);
        }

        public List<String> getSupportedFormats() {
            return new ArrayList<>(supportedFormats);
        }

        public List<String> getAvailableDevices() {
            return new ArrayList<>(availableDevices);
        }

        public String getOperatingSystem() {
            return operatingSystem;
        }

        public String getRecommendedMethod() {
            return recommendedMethod;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Sound System Information:\n");
            sb.append("Operating System: ").append(operatingSystem).append("\n");
            sb.append("Recommended Method: ").append(recommendedMethod).append("\n");
            sb.append("Java Audio Supported: ").append(javaAudioSupported).append("\n");
            sb.append("System Beep Supported: ").append(systemBeepSupported).append("\n");
            sb.append("Available Commands: ").append(availableCommands).append("\n");
            sb.append("Available Sound Files: ").append(availableSoundFiles.size()).append(" found\n");
            sb.append("Supported Formats: ").append(supportedFormats).append("\n");
            sb.append("Available Devices: ").append(availableDevices.size()).append(" found\n");
            return sb.toString();
        }
    }

    /**
     * Detect and analyze the sound system capabilities.
     *
     * @return SoundSystemInfo containing all detected capabilities
     */
    public static SoundSystemInfo detectSoundSystem() {
        String os = System.getProperty("os.name").toLowerCase();

        boolean javaAudioSupported = testJavaAudioSupport();
        boolean systemBeepSupported = testSystemBeepSupport();
        List<String> availableCommands = detectAvailableCommands();
        List<Path> availableSoundFiles = detectAvailableSoundFiles();
        List<String> supportedFormats = detectSupportedFormats();
        List<String> availableDevices = detectAudioDevices();

        String recommendedMethod = determineRecommendedMethod(os, javaAudioSupported,
                systemBeepSupported, availableCommands);

        return new SoundSystemInfo(javaAudioSupported, systemBeepSupported, availableCommands,
                availableSoundFiles, supportedFormats, availableDevices, os, recommendedMethod);
    }

    /**
     * Test if Java Audio API is supported and working.
     */
    public static boolean testJavaAudioSupport() {
        try {
            // Test if we can get a basic audio format
            AudioFormat format = new AudioFormat(22050, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            if (AudioSystem.isLineSupported(info)) {
                // Try to actually open a line
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.close();
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Java Audio test failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Test if system beep is supported.
     */
    public static boolean testSystemBeepSupport() {
        try {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "System beep test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Detect available sound playback commands on the system.
     */
    public static List<String> detectAvailableCommands() {
        List<String> available = new ArrayList<>();
        String os = System.getProperty("os.name").toLowerCase();

        for (String command : SOUND_COMMANDS) {
            if (isCommandAvailable(command, os)) {
                available.add(command);
            }
        }

        return available;
    }

    /**
     * Detect available sound files on the system.
     */
    public static List<Path> detectAvailableSoundFiles() {
        List<Path> available = new ArrayList<>();

        for (String soundFile : COMMON_SOUND_FILES) {
            try {
                Path path = Paths.get(soundFile);
                if (Files.exists(path) && Files.isReadable(path)) {
                    available.add(path);
                }
            } catch (Exception e) {
                // Skip invalid paths
            }
        }

        return available;
    }

    /**
     * Detect supported audio formats.
     */
    public static List<String> detectSupportedFormats() {
        List<String> formats = new ArrayList<>();

        try {
            AudioFileFormat.Type[] types = AudioSystem.getAudioFileTypes();
            for (AudioFileFormat.Type type : types) {
                formats.add(type.getExtension().toUpperCase());
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to detect audio formats: " + e.getMessage());
        }

        return formats;
    }

    /**
     * Detect available audio devices.
     */
    public static List<String> detectAudioDevices() {
        List<String> devices = new ArrayList<>();

        try {
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            for (Mixer.Info mixerInfo : mixerInfos) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    Line.Info[] lineInfos = mixer.getSourceLineInfo();
                    if (lineInfos.length > 0) {
                        devices.add(mixerInfo.getName() + " (" + mixerInfo.getDescription() + ")");
                    }
                } catch (Exception e) {
                    // Skip problematic mixers
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to detect audio devices: " + e.getMessage());
        }

        return devices;
    }

    /**
     * Create the best PlayNotificationAction for the current system.
     */
    public static PlayNotificationAction createOptimalNotificationAction() {
        SoundSystemInfo info = detectSoundSystem();

        // Try system commands first (usually most reliable)
        if (!info.getAvailableCommands().isEmpty() && !info.getAvailableSoundFiles().isEmpty()) {
            String command = info.getAvailableCommands().get(0);
            Path soundFile = info.getAvailableSoundFiles().get(0);
            return new PlayNotificationAction(command + " " + soundFile.toString());
        }

        // Try custom sound file with Java Audio
        if (info.isJavaAudioSupported() && !info.getAvailableSoundFiles().isEmpty()) {
            return new PlayNotificationAction(info.getAvailableSoundFiles().get(0));
        }

        // Try built-in generated sounds
        if (info.isJavaAudioSupported()) {
            return new PlayNotificationAction(PlayNotificationAction.NotificationSound.SUCCESS);
        }

        // Fallback to system beep
        if (info.isSystemBeepSupported()) {
            return new PlayNotificationAction(PlayNotificationAction.NotificationSound.SYSTEM_BEEP);
        }

        // Last resort - return a non-functional action
        LOGGER.warning("No suitable sound method found on this system");
        return new PlayNotificationAction(PlayNotificationAction.NotificationSound.SYSTEM_BEEP);
    }

    /**
     * Test a specific sound method.
     */
    public static boolean testSoundMethod(PlayNotificationAction action, long timeoutMs) {
        try {
            // Create a minimal mock download for testing
            Download testDownload = new Download(new java.net.URI("http://test.com/file.txt"));
            testDownload.setName("test-file.txt");
            testDownload.setDestination(Paths.get(System.getProperty("java.io.tmpdir")));

            long startTime = System.currentTimeMillis();
            boolean result = action.execute(testDownload);

            // Wait for completion or timeout
            while (action.isPlaying() && (System.currentTimeMillis() - startTime) < timeoutMs) {
                Thread.sleep(100);
            }

            // Cancel if still playing
            if (action.isPlaying()) {
                action.cancel();
            }

            return result;
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Sound test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Run a comprehensive test of all sound capabilities.
     */
    public static Map<String, Boolean> runComprehensiveTest() {
        Map<String, Boolean> results = new HashMap<>();

        // Test system beep
        results.put("System Beep", testSystemBeepSupport());

        // Test Java Audio
        results.put("Java Audio", testJavaAudioSupport());

        // Test built-in sounds
        PlayNotificationAction builtInAction = new PlayNotificationAction(
                PlayNotificationAction.NotificationSound.SUCCESS);
        results.put("Built-in Sounds", testSoundMethod(builtInAction, 3000));

        // Test available commands
        List<String> commands = detectAvailableCommands();
        List<Path> soundFiles = detectAvailableSoundFiles();

        if (!commands.isEmpty() && !soundFiles.isEmpty()) {
            String testCommand = commands.get(0) + " " + soundFiles.get(0).toString();
            PlayNotificationAction commandAction = new PlayNotificationAction(testCommand);
            results.put("System Command (" + commands.get(0) + ")",
                    testSoundMethod(commandAction, 5000));
        }

        // Test custom file
        if (!soundFiles.isEmpty()) {
            PlayNotificationAction fileAction = new PlayNotificationAction(soundFiles.get(0));
            results.put("Custom File", testSoundMethod(fileAction, 3000));
        }

        return results;
    }

    // Private helper methods
    private static boolean isCommandAvailable(String command, String os) {
        try {
            String testCommand;
            if (os.contains("windows")) {
                testCommand = "where " + command;
            } else {
                testCommand = "which " + command;
            }

            ProcessBuilder pb = new ProcessBuilder();
            if (os.contains("windows")) {
                pb.command("cmd", "/c", testCommand);
            } else {
                pb.command("sh", "-c", testCommand);
            }

            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String determineRecommendedMethod(String os, boolean javaAudio,
            boolean systemBeep, List<String> commands) {
        if (os.contains("linux")) {
            if (commands.contains("paplay")) {
                return "PulseAudio (paplay)";
            }
            if (commands.contains("aplay")) {
                return "ALSA (aplay)";
            }
            if (javaAudio) {
                return "Java Audio API";
            }
            if (systemBeep) {
                return "System Beep";
            }
        } else if (os.contains("mac") || os.contains("darwin")) {
            if (commands.contains("afplay")) {
                return "macOS afplay";
            }
            if (javaAudio) {
                return "Java Audio API";
            }
            if (systemBeep) {
                return "System Beep";
            }
        } else if (os.contains("windows")) {
            if (javaAudio) {
                return "Java Audio API";
            }
            if (commands.contains("powershell")) {
                return "PowerShell";
            }
            if (systemBeep) {
                return "System Beep";
            }
        }

        // Generic fallback
        if (javaAudio) {
            return "Java Audio API";
        }
        if (systemBeep) {
            return "System Beep";
        }
        return "None available";
    }

    /**
     * Get platform-specific sound file recommendations.
     */
    public static List<String> getPlatformSoundFiles() {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> recommendations = new ArrayList<>();

        if (os.contains("linux")) {
            recommendations.addAll(Arrays.asList(
                    "/usr/share/sounds/alsa/Front_Left.wav",
                    "/usr/share/sounds/sound-icons/bell.wav",
                    "/usr/share/sounds/freedesktop/stereo/bell.oga",
                    "/usr/share/sounds/ubuntu/stereo/bell.ogg"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            recommendations.addAll(Arrays.asList(
                    "/System/Library/Sounds/Ping.aiff",
                    "/System/Library/Sounds/Pop.aiff",
                    "/System/Library/Sounds/Glass.aiff"));
        } else if (os.contains("windows")) {
            recommendations.addAll(Arrays.asList(
                    "C:\\Windows\\Media\\Windows Ding.wav",
                    "C:\\Windows\\Media\\chimes.wav",
                    "C:\\Windows\\Media\\notify.wav"));
        }

        return recommendations;
    }

    /**
     * Get platform-specific command recommendations.
     */
    public static List<String> getPlatformCommands() {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> recommendations = new ArrayList<>();

        if (os.contains("linux")) {
            recommendations.addAll(Arrays.asList("paplay", "aplay", "speaker-test"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            recommendations.addAll(Arrays.asList("afplay"));
        } else if (os.contains("windows")) {
            recommendations.addAll(Arrays.asList("powershell"));
        }

        return recommendations;
    }
}
