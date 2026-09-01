package org.manager.download.action;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.action.PlayNotificationAction.NotificationSound;
import org.manager.download.action.AfterCompletionAction.ActionType;
import org.manager.download.action.AfterCompletionAction.Severity;

@DisplayName("PlayNotificationAction playback semantics")
class PlayNotificationActionTest {

    @TempDir
    Path tempDir;

    private Download completedDownload() {
        Download download = new Download(URI.create("https://example.test/sound.zip"));
        download.setName("sound.zip");
        return download;
    }

    @Test
    @DisplayName("a system command that exits 0 reports success and finishes playing")
    @Timeout(30)
    void systemCommandSuccess() {
        PlayNotificationAction action = new PlayNotificationAction("true");

        assertTrue(action.execute(completedDownload()));
        assertEquals(PlayNotificationAction.NotificationSound.SYSTEM_COMMAND, action.getSoundType());
        await().atMost(Duration.ofSeconds(10)).until(() -> !action.isPlaying());
    }

    @Test
    @DisplayName("a failing system command still reports that playback started but finishes")
    @Timeout(30)
    void systemCommandFailingExitIsLoggedNotThrown() {
        PlayNotificationAction action = new PlayNotificationAction("sh -c 'exit 3'");

        // execute reports whether the playback pipeline started, not the
        // command's exit status; it must not throw
        assertTrue(action.execute(completedDownload()));
        await().atMost(Duration.ofSeconds(10)).until(() -> !action.isPlaying());
    }

    @Test
    @DisplayName("an empty system command is rejected")
    void emptySystemCommandRejected() {
        PlayNotificationAction action = new PlayNotificationAction("   ");
        assertFalse(action.execute(completedDownload()));
        assertFalse(action.isPlaying());
    }

    @Test
    @DisplayName("an unknown command still starts (sh -c) and finishes with isPlaying reset")
    @Timeout(30)
    void unknownCommandStartsShellAndFinishes() {
        PlayNotificationAction action = new PlayNotificationAction("definitely-not-a-real-command-xyz");
        // execute reports that the playback pipeline started; the shell's
        // 127 exit status is consumed asynchronously
        assertTrue(action.execute(completedDownload()));
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> !action.isPlaying());
    }

    @Test
    @DisplayName("cancel stops a long-running sound process immediately")
    @Timeout(30)
    void cancelStopsLongRunningProcess() {
        PlayNotificationAction action = new PlayNotificationAction("sleep 30");

        assertTrue(action.execute(completedDownload()));
        assertTrue(action.isPlaying());
        assertTrue(action.cancel());
        await().atMost(Duration.ofSeconds(10)).until(() -> !action.isPlaying());
    }

    @Test
    @DisplayName("cancel before any playback is a harmless no-op")
    void cancelBeforePlaybackIsNoOp() {
        PlayNotificationAction action = new PlayNotificationAction(NotificationSound.SYSTEM_BEEP);
        assertTrue(action.cancel());
    }

    @Test
    @DisplayName("a missing custom sound file is rejected")
    void missingCustomFileRejected() {
        PlayNotificationAction action = new PlayNotificationAction(tempDir.resolve("ghost.wav"));
        assertFalse(action.execute(completedDownload()));
        assertEquals(tempDir.resolve("ghost.wav"), action.getCustomSoundFile());
    }

    @Test
    @DisplayName("an invalid audio file is rejected without throwing")
    void invalidAudioFileRejected() throws IOException {
        Path notAudio = Files.writeString(tempDir.resolve("noise.wav"), "this is not audio");
        PlayNotificationAction action = new PlayNotificationAction(notAudio);

        // either the decoder rejects the file (false) or the audio line is
        // unavailable headless (false); it must never throw
        assertFalse(action.execute(completedDownload()));
    }

    @Test
    @DisplayName("built-in generated sounds fail gracefully headless or start playing")
    @Timeout(30)
    void builtInSoundDegradesGracefully() {
        PlayNotificationAction action = new PlayNotificationAction(NotificationSound.SUCCESS);
        boolean started = action.execute(completedDownload());
        if (started) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !action.isPlaying());
        } else {
            assertFalse(action.isPlaying());
        }
    }

    @Test
    @DisplayName("configuration setters clamp invalid values and descriptions reflect the setup")
    void configurationClamping() {
        PlayNotificationAction action = new PlayNotificationAction(NotificationSound.NOTIFICATION)
                .setVolume(5.0f)
                .setRepeat(true, -3)
                .setTimeout(0);

        assertEquals(1.0f, action.getVolume(), "volume must clamp to [0,1]");
        assertTrue(action.isRepeat());
        assertEquals(1, action.getRepeatCount(), "repeat count must clamp to >= 1");
        assertEquals(1, action.getTimeoutSeconds(), "timeout must clamp to >= 1");

        assertEquals(0.0f, new PlayNotificationAction(NotificationSound.COMPLETE).setVolume(-1.0f).getVolume());

        PlayNotificationAction repeated = new PlayNotificationAction(NotificationSound.SUCCESS)
                .setRepeat(true, 3);
        assertTrue(repeated.getDescription().contains("3 times"));

        PlayNotificationAction file = new PlayNotificationAction(Path.of("bell.wav"));
        assertTrue(file.getDescription().contains("bell.wav"));

        PlayNotificationAction command = new PlayNotificationAction("paplay bell.wav");
        assertTrue(command.getDescription().contains("paplay bell.wav"));

        assertEquals(Severity.LOW, action.getSeverity());
        assertEquals(ActionType.PLAY_SOUND, action.getType());
        assertNull(new PlayNotificationAction(NotificationSound.SYSTEM_BEEP).getSystemCommand());
        assertNotNull(new PlayNotificationAction(NotificationSound.SYSTEM_BEEP).getDescription());
    }

    @Test
    @DisplayName("createWithFallbacks returns a usable action without throwing")
    @Timeout(30)
    void createWithFallbacksReturnsUsableAction() {
        PlayNotificationAction action = PlayNotificationAction.createWithFallbacks();
        assertNotNull(action);
        assertNotNull(action.getDescription());
        assertNotNull(action.getSoundType());
    }
}
