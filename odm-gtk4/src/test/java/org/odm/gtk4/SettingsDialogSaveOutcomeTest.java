package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
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

    private static final Set<String> EXPECTED_DOCUMENTED_SETTINGS = Set.of(
            // General
            "default_download_folder_chooser", "max_concurrent_downloads_spin",
            "monitored_folder_chooser", "folder_monitoring_check",
            "folder_recursive_check", "move_to_trash_check", "clipboard_monitor_check",
            "clipboard_silent_check", "system_tray_check", "startup_check",
            "start_automatically_check", "move_torrent_check", "enable_auto_save_check",
            // Network
            "max_connections_spin", "retry_limit_spin", "retry_after",
            "max_download_speed_spin", "max_upload_speed_spin", "referer_entry",
            "cookie_entry", "user_agent_entry", "proxy_type_combo", "proxy_host_entry",
            "proxy_port_spin", "proxy_username_entry", "proxy_password_entry", "tor_switch",
            // aria2
            "aria2_path_entry", "browse_aria2_button", "min_split_size_spin1",
            "file_allocation_combo", "max_peers_spin", "peer_speed_limit_spin",
            "enable_seeding_check", "seed_time_spin", "tracker_refresh_spin",
            "tracker_list_entry", "continue_download_check", "check_integrity_check",
            "aria2_rpc_port_spin", "honor_external_aria2_config_check",
            // yt-dlp
            "ytdlp_path_entry", "browse_ytdlp_button", "video_format_entry",
            "subtitle_language_entry", "write_thumbnail_check", "write_subtitles_check",
            "embed_metadata_check", "extract_audio_check", "use_aria2_external_check",
            "honor_external_ytdlp_config_check",
            // HTTrack
            "httrack_path_entry", "browse_httrack_button", "depth_spin", "include_entry",
            "exclude_entry", "include_archives_check",
            // Advanced
            "enable_scheduling_check", "retain_completed_canceled_history_check",
            "automatic_cleanup_check", "cleanup_interval_spin", "max_history_records_spin",
            "max_completed_records_spin", "completed_retention_spin", "error_retention_spin",
            "max_import_urls_spin", "max_import_source_size_spin", "proxychains_path_entry",
            "browse_proxychains_button", "tor_path_entry", "browse_tor_button",
            "curl_path_entry", "browse_curl_button", "subliminal_path_entry",
            "browse_subliminal_button", "antivirus_type_combo",
            "antivirus_command_entry", "antivirus_timeout_spin");

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
    @DisplayName("every interactive setting has explanatory help")
    void everySettingHasExplanatoryHelp() {
        assertEquals(EXPECTED_DOCUMENTED_SETTINGS, SettingsDialog.documentedSettingIds(),
                "add meaningful help whenever a setting is added or removed");

        SettingsDialog dialog = buildDialog();
        for (String id : EXPECTED_DOCUMENTED_SETTINGS) {
            String tooltip = dialog.settingTooltip(id);
            assertNotNull(tooltip, id + " must have a tooltip");
            assertFalse(tooltip.isBlank(), id + " must have a non-blank tooltip");
            assertTrue(tooltip.length() >= 20, id + " must explain the setting");
        }
        assertTrue(dialog.settingTooltip("default_download_folder_chooser")
                .contains("Current:"),
                "folder help must retain the currently selected path");
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
    @DisplayName("history cleanup is opt-in and all destructive rules persist from Advanced settings")
    void historyCleanupRequiresExplicitOptIn() throws Exception {
        Path configHome = tempDir.resolve("cleanup-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(new GlobalSettings());
            SettingsDialog dialog = new SettingsDialog(null, newStubManager(settings), null);

            assertTrue(dialog.retainCompletedAndCanceledHistory());
            assertFalse(settings.get().isAutomaticCleanupEnabled());
            assertFalse(dialog.historyCleanupControlsSensitive());

            dialog.setRetainCompletedAndCanceledHistory(false);
            dialog.setHistoryCleanupEnabled(true);
            assertTrue(dialog.historyCleanupControlsSensitive());
            dialog.setHistoryCleanupValues(12, 10_000, 7_500, 0, 45);
            dialog.applySettings();

            GlobalSettings saved = settings.get();
            assertFalse(saved.isRetainCompletedAndCanceledHistory());
            assertTrue(saved.isAutomaticCleanupEnabled());
            assertEquals(12, saved.getCleanupIntervalHours());
            assertEquals(10_000, saved.getMaxDownloadsInMemory());
            assertEquals(7_500, saved.getMaxCompletedDownloadsToKeep());
            assertEquals(0, saved.getCompletedDownloadRetentionDays());
            assertEquals(45, saved.getErrorDownloadRetentionDays());
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("Advanced import guards use defaults and persist user-selected limits")
    void importGuardsAreUserControlled() throws Exception {
        Path configHome = tempDir.resolve("import-limits-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(new GlobalSettings());
            SettingsDialog dialog = new SettingsDialog(null, newStubManager(settings), null);

            assertEquals(ImportLimits.DEFAULT_MAX_URLS, dialog.maximumImportUrls());
            assertEquals(ImportLimits.DEFAULT_MAX_SOURCE_SIZE_MIB,
                    dialog.maximumImportSourceSizeMiB());

            dialog.setImportLimits(7_500, 32);
            dialog.applySettings();

            ImportLimits saved = ImportLimits.from(settings.get());
            assertEquals(7_500, saved.maxUrls());
            assertEquals(32, saved.maxSourceSizeMiB());
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("Reset loads defaults but does not commit until Apply")
    void resetUsesRealDefaultsAndKeepsApplyAsCommitPoint() throws Exception {
        Path configHome = tempDir.resolve("reset-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setMaxConcurrentDownloads(17);
            initial.setAutomaticCleanupEnabled(true);
            initial.setAria2RpcPort(6900);
            initial.setHonorExternalAria2Configuration(true);
            initial.setHonorExternalYtDlpConfiguration(true);
            new org.manager.download.DownloadSettingsFactory.NetworkDefaults(
                    16, 5, 0, 0, 0, "", "", "").saveTo(initial);
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            dialog.resetToDefaults();
            assertSame(initial, settings.get(),
                    "Reset alone must not mutate the live settings");

            dialog.applySettings();
            GlobalSettings reset = settings.get();
            assertEquals(3, reset.getMaxConcurrentDownloads());
            assertFalse(reset.isAutomaticCleanupEnabled());
            assertEquals(GlobalSettings.DEFAULT_ARIA2_RPC_PORT,
                    reset.getAria2RpcPort());
            assertFalse(reset.isHonorExternalAria2Configuration());
            assertFalse(reset.isHonorExternalYtDlpConfiguration());
            assertEquals(org.manager.download.DownloadSettingsFactory.DEFAULT_NETWORK_MAX_CONNECTIONS,
                    org.manager.download.DownloadSettingsFactory.NetworkDefaults.from(reset)
                            .maxConnections());
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("engine configuration opt-ins survive a Preferences save")
    void engineConfigurationOptInsRoundTripThroughDialog() throws Exception {
        Path configHome = tempDir.resolve("engine-config-policy");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setAria2RpcPort(6812);
            initial.setHonorExternalAria2Configuration(true);
            initial.setHonorExternalYtDlpConfiguration(true);
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            dialog.applySettings();

            GlobalSettings saved = settings.get();
            assertEquals(6812, saved.getAria2RpcPort());
            assertTrue(saved.isHonorExternalAria2Configuration());
            assertTrue(saved.isHonorExternalYtDlpConfiguration());
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("yt-dlp video format offers common policies with Automatic selected by default")
    void ytDlpVideoFormatUsesCommonDropdownChoices() throws Exception {
        Path configHome = tempDir.resolve("ytdlp-format-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            assertEquals(7, dialog.videoFormatChoiceCount());
            assertEquals("Automatic (yt-dlp default)", dialog.videoFormatChoiceLabel(0));
            assertEquals("", dialog.selectedVideoFormat());

            dialog.selectVideoFormatChoice(3);
            String selected = dialog.selectedVideoFormat();
            assertTrue(selected.contains("height<=?1080"));
            dialog.applySettings();

            assertEquals(selected,
                    settings.get().getProperty("ytdlp.videoFormat", null));
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("an existing custom yt-dlp selector survives opening and applying Settings")
    void customYtDlpVideoFormatIsPreserved() throws Exception {
        Path configHome = tempDir.resolve("ytdlp-custom-format-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            String custom = "bestvideo[ext=webm]+bestaudio[ext=webm]/best";
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setProperty("ytdlp.videoFormat", custom);
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);

            assertEquals(8, dialog.videoFormatChoiceCount());
            assertTrue(dialog.videoFormatChoiceLabel(7).startsWith("Custom"));
            assertEquals(custom, dialog.selectedVideoFormat());
            dialog.applySettings();

            assertEquals(custom,
                    settings.get().getProperty("ytdlp.videoFormat", null));
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("Network controls persist one engine-neutral preference set")
    void networkControlsPersistCanonicalDefaults() throws Exception {
        Path configHome = tempDir.resolve("network-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            AtomicReference<GlobalSettings> settings =
                    new AtomicReference<>(new GlobalSettings());
            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);
            org.manager.download.DownloadSettingsFactory.NetworkDefaults expected =
                    new org.manager.download.DownloadSettingsFactory.NetworkDefaults(
                            11, 7, 640, 96, 4,
                            "https://referrer.test/", "ODM test", "session=abc");

            dialog.setNetworkDefaults(expected);
            dialog.applySettings();

            GlobalSettings saved = settings.get();
            assertEquals(expected,
                    org.manager.download.DownloadSettingsFactory.NetworkDefaults.from(saved));
            assertNull(saved.getProperty("aria2.maxConnections", null));
            assertNull(saved.getProperty("aria2.maxTries", null));
            assertNull(saved.getProperty("ytdlp.format", null));
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("Tor keeps the user's explicit proxy separate from its active loopback route")
    void torDoesNotOverwriteManualProxyFields() throws Exception {
        Path configHome = tempDir.resolve("tor-proxy-config");
        Files.createDirectories(configHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            initial.setProperty("tor.enabled", "true");
            initial.setGlobalProxyEnabled(true);
            initial.setGlobalProxyAddress("socks5h://127.0.0.1:9050");
            DialogOptions.rememberManualProxy(initial,
                    "http://user:secret@proxy.example:8080");
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);

            SettingsDialog dialog = new SettingsDialog(null,
                    newStubManager(settings), null);
            dialog.applySettings();

            GlobalSettings saved = settings.get();
            assertEquals("socks5h://127.0.0.1:9050",
                    saved.getGlobalProxyAddress(), "Tor remains the active route");
            assertEquals("http://user:secret@proxy.example:8080",
                    DialogOptions.manualProxyAddress(saved),
                    "the Preferences proxy must survive a save while Tor is enabled");
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
    @DisplayName("a failed save does not apply the unsaved snapshot to live services")
    void failedSaveDoesNotApplyRuntimeSettings() throws Exception {
        Path blocker = tempDir.resolve("runtime-config-blocker");
        Files.writeString(blocker, "not a directory");

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", blocker.toString()).execute(() -> {
            GlobalSettings initial = new GlobalSettings();
            initial.setDefaultDownloadDirectory(tempDir);
            AtomicReference<GlobalSettings> settings = new AtomicReference<>(initial);
            java.util.concurrent.atomic.AtomicInteger liveCallbacks =
                    new java.util.concurrent.atomic.AtomicInteger();
            SettingsDialog dialog = new SettingsDialog(null, newStubManager(settings), null,
                    active -> liveCallbacks.incrementAndGet());

            dialog.applySettings();

            assertSame(initial, settings.get(),
                    "setGlobalSettings must not receive a snapshot that was not saved");
            assertEquals(0, liveCallbacks.get(),
                    "Tor and other post-save services must remain unchanged");
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
