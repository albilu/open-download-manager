package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.gnome.glib.MainLoop;
import org.gnome.gtk.Gtk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;

/**
 * The settings dialog must report the ACTUAL save outcome: save() failures
 * (unwritable config directory) must surface in the status label instead of
 * the unconditional "Settings saved.".
 */
@DisplayName("SettingsDialog save outcome reporting")
class SettingsDialogSaveOutcomeTest {

    private static MainLoop loop;
    private static java.util.concurrent.ExecutorService loopThread;

    @BeforeAll
    static void initGtk() {
        Gtk.init();
        loop = new MainLoop(null, false);
        loopThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-glib-loop");
            t.setDaemon(true);
            return t;
        });
        loopThread.submit(loop::run);
    }

    @AfterAll
    static void tearDown() {
        loop.quit();
        loopThread.shutdownNow();
    }

    @TempDir
    Path tempDir;

    private static DownloadManager newStubManager() {
        return newStubManager(new AtomicReference<>(new GlobalSettings()));
    }

    private static DownloadManager newStubManager(
            AtomicReference<GlobalSettings> settings) {
        return (DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                DownloadManager.class.getClassLoader(),
                new Class<?>[]{DownloadManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalSettings" -> settings.get();
                    case "setGlobalSettings" -> {
                        settings.set((GlobalSettings) args[0]);
                        yield null;
                    }
                    case "getAllDownloads", "getDownloads" -> java.util.List.of();
                    case "isClipboardMonitoringEnabled", "isTorrentFolderMonitoringEnabled",
                            "isMetaLinkFolderMonitoringEnabled" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private SettingsDialog buildDialog() {
        return new SettingsDialog(null, newStubManager(), null);
    }

    @Test
    @DisplayName("scheduler exposes 7 x 24 styled cells and describes the selected hour")
    void schedulerGridDescribesSelectedHour() {
        SettingsDialog dialog = buildDialog();

        assertEquals(168, dialog.schedulerCellCount());
        assertTrue(dialog.availableSpaceText().contains("GB free"));
        dialog.setSchedulerCellActive(0, 3, false);
        dialog.setSchedulerCellActive(0, 3, true);

        assertEquals("Mon 03:00–03:59 — downloads allowed",
                dialog.schedulerSelectionText());
    }

    @Test
    @Timeout(60)
    @DisplayName("a preset populates the grid and a manual edit clears the preset")
    void schedulePresetAndGridStaySynchronized() throws Exception {
        Path configHome = tempDir.resolve("schedule-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setProperty("scheduler.enabled", "true");
            initial.setProperty("scheduler.preset", "business");
            initial.setProperty("scheduler.grid", "");
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            assertFalse(dialog.schedulerCellActive(0, 8));
            assertTrue(dialog.schedulerCellActive(0, 9));
            assertTrue(dialog.schedulerCellActive(4, 16));
            assertFalse(dialog.schedulerCellActive(4, 17));
            assertFalse(dialog.schedulerCellActive(5, 12));

            dialog.setSchedulerCellActive(0, 8, true);
            dialog.applySettings();

            assertEquals("none", settings.get().getProperty("scheduler.preset", null));
            boolean[][] persisted = org.manager.schedule.WeeklySchedule.hourGridFromString(
                    settings.get().getProperty("scheduler.grid", ""));
            assertTrue(persisted[0][8]);
            assertTrue(settings.get().getBooleanProperty("scheduler.enabled", false));
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("the Never preset remains an enabled all-inactive grid")
    void neverPresetSurvivesSettingsSave() throws Exception {
        Path configHome = tempDir.resolve("never-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setProperty("scheduler.enabled", "true");
            initial.setProperty("scheduler.preset", "never");
            initial.setProperty("scheduler.grid", "");
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            assertFalse(dialog.schedulerCellActive(0, 0));
            dialog.applySettings();

            assertEquals("never", settings.get().getProperty("scheduler.preset", null));
            assertTrue(settings.get().getBooleanProperty("scheduler.enabled", false));
            assertEquals("0".repeat(42),
                    settings.get().getProperty("scheduler.grid", ""));
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("an unwritable settings path shows an error in the status label")
    void unwritableSettingsPathShowsError() throws Exception {
        // Block the config directory with a regular file so save() cannot
        // even create the directory
        Path blocker = tempDir.resolve("config-blocker");
        Files.writeString(blocker, "not a directory");

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", blocker.toString()).execute(() -> {
            SettingsDialog dialog = buildDialog();
            assertNotNull(dialog);

            dialog.applySettings();

            String status = dialog.statusText();
            assertTrue(status.toLowerCase().contains("failed"),
                    "the dialog must report the save failure, got: " + status);
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("a writable settings path reports success")
    void writableSettingsPathReportsSuccess() throws Exception {
        Path configHome = tempDir.resolve("config-ok");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            SettingsDialog dialog = buildDialog();

            dialog.applySettings();

            String status = dialog.statusText();
            assertTrue(status.toLowerCase().contains("saved"),
                    "the dialog must report the successful save, got: " + status);
        });
    }
}
