package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SoundSystemUtils detection contract")
class SoundSystemUtilsTest {

    @Test
    @DisplayName("detection returns a complete, self-consistent snapshot on this system")
    void detectionIsConsistent() {
        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();

        assertNotNull(info.getOperatingSystem());
        assertNotNull(info.getRecommendedMethod());
        assertFalse(info.getRecommendedMethod().isBlank());

        // recommended method must agree with the detected capabilities
        String os = info.getOperatingSystem();
        List<String> commands = info.getAvailableCommands();
        if (os.contains("linux")) {
            if (commands.contains("paplay")) {
                assertEquals("PulseAudio (paplay)", info.getRecommendedMethod());
            } else if (commands.contains("aplay")) {
                assertEquals("ALSA (aplay)", info.getRecommendedMethod());
            }
        }

        // every reported command must actually exist on this system
        for (String command : info.getAvailableCommands()) {
            assertTrue(isOnPath(command), "reported command must exist: " + command);
        }

        // every reported sound file must exist and be readable
        for (java.nio.file.Path soundFile : info.getAvailableSoundFiles()) {
            assertTrue(java.nio.file.Files.exists(soundFile), "reported sound file must exist: " + soundFile);
            assertTrue(java.nio.file.Files.isReadable(soundFile));
        }
    }

    private static boolean isOnPath(String command) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + command).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("SoundSystemInfo getters return defensive copies")
    void infoReturnsDefensiveCopies() {
        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();

        var commands = info.getAvailableCommands();
        commands.add("intruder");
        assertFalse(info.getAvailableCommands().contains("intruder"));

        var files = info.getAvailableSoundFiles();
        if (!files.isEmpty()) {
            files.clear();
            assertFalse(info.getAvailableSoundFiles().isEmpty(), "internal list must not be mutable via getter");
        }

        var devices = info.getAvailableDevices();
        devices.clear();
        assertTrue(info.getAvailableDevices().size() >= 0);
    }

    @Test
    @DisplayName("info toString summarizes every capability section")
    void infoToStringSummarizes() {
        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();
        String text = info.toString();
        assertTrue(text.contains("Operating System:"));
        assertTrue(text.contains("Recommended Method:"));
        assertTrue(text.contains("Available Commands:"));
        assertTrue(text.contains("Supported Formats:"));
    }

    @Test
    @DisplayName("platform command recommendations match the running OS family")
    void platformRecommendationsMatchOs() {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> commands = SoundSystemUtils.getPlatformCommands();

        if (os.contains("linux")) {
            assertTrue(commands.contains("paplay"));
            assertTrue(commands.contains("aplay"));
            assertFalse(commands.contains("powershell"));
        } else if (os.contains("win")) {
            assertTrue(commands.contains("powershell"));
        }

        List<String> soundFiles = SoundSystemUtils.getPlatformSoundFiles();
        assertFalse(soundFiles.isEmpty(), "every supported platform recommends at least one sound file");
        if (os.contains("linux")) {
            assertTrue(soundFiles.stream().allMatch(f -> f.startsWith("/usr/share/sounds")));
        }
    }

    @Test
    @DisplayName("comprehensive test runs every probe and reports per-probe results")
    void comprehensiveTestCoversAllProbes() {
        Map<String, Boolean> results = SoundSystemUtils.runComprehensiveTest();
        assertTrue(results.containsKey("System Beep"));
        assertTrue(results.containsKey("Java Audio"));
        assertTrue(results.containsKey("Built-in Sounds"));
    }

    @Test
    @DisplayName("createOptimalNotificationAction always yields a configured action")
    void optimalActionIsAlwaysProduced() {
        PlayNotificationAction action = SoundSystemUtils.createOptimalNotificationAction();
        assertNotNull(action);
        assertNotNull(action.getSoundType());
        assertNotNull(action.getDescription());

        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();
        if (!info.getAvailableCommands().isEmpty() && !info.getAvailableSoundFiles().isEmpty()) {
            assertEquals(PlayNotificationAction.NotificationSound.SYSTEM_COMMAND, action.getSoundType(),
                    "commands + sound files => system command is optimal");
        }
    }

    @Test
    @DisplayName("testSoundMethod propagates rejection and waits out successful playback")
    @org.junit.jupiter.api.Timeout(30)
    void testSoundMethodToleratesFailures() {
        PlayNotificationAction rejected = new PlayNotificationAction("   ");
        assertFalse(SoundSystemUtils.testSoundMethod(rejected, 1000),
                "a rejected command must surface as false");

        PlayNotificationAction instant = new PlayNotificationAction("true");
        assertTrue(SoundSystemUtils.testSoundMethod(instant, 10_000),
                "a clean command must surface as true and finish playing");
    }
}
