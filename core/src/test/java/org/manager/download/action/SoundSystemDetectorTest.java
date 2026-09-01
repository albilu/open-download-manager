package org.manager.download.action;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the full sound-system detection flow (the class is a self-contained
 * diagnostic program). Under Xvfb/headless audio every probe degrades
 * gracefully; the contract is that the flow completes and the utility
 * methods return platform-consistent data.
 */
@DisplayName("SoundSystemDetector end-to-end detection run")
class SoundSystemDetectorTest {

    @Test
    @DisplayName("runDetection completes without throwing on the current system")
    @org.junit.jupiter.api.Timeout(120)
    void fullDetectionRunCompletes() {
        new SoundSystemDetector().runDetection();
    }

    @Test
    @DisplayName("the comprehensive test reports a non-empty, boolean-valued result map")
    @org.junit.jupiter.api.Timeout(120)
    void comprehensiveResultsAreBooleanValued() {
        Map<String, Boolean> results = SoundSystemUtils.runComprehensiveTest();
        assertNotNull(results);
        assertTrue(results.size() >= 3, "beep, java audio and built-in probes must always run");
        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            assertNotNull(entry.getValue(), entry.getKey() + " must have a result");
        }
    }

    @Test
    @DisplayName("the recommended method never names a probe that failed to run")
    @org.junit.jupiter.api.Timeout(120)
    void recommendedMethodIsBackedByDetection() {
        SoundSystemUtils.SoundSystemInfo info = SoundSystemUtils.detectSoundSystem();
        String recommended = info.getRecommendedMethod();
        assertNotNull(recommended);

        boolean backed = switch (recommended) {
            case "PulseAudio (paplay)", "ALSA (aplay)" ->
                info.getAvailableCommands().contains(
                        recommended.contains("paplay") ? "paplay" : "aplay");
            case "macOS afplay" -> info.getAvailableCommands().contains("afplay");
            case "PowerShell" -> info.getAvailableCommands().contains("powershell");
            case "Java Audio API" -> info.isJavaAudioSupported();
            case "System Beep" -> info.isSystemBeepSupported();
            case "None available" -> info.getAvailableCommands().isEmpty()
                    && !info.isJavaAudioSupported() && !info.isSystemBeepSupported();
            default -> false;
        };
        assertTrue(backed, "recommendation '" + recommended + "' must match detection");
    }

    @Test
    @DisplayName("the main entry point tolerates being invoked directly")
    @org.junit.jupiter.api.Timeout(120)
    void mainEntryPointsDoNotThrow() {
        SoundSystemDetector.main(new String[0]);
        SoundSystemDetector.main(null);
    }
}
