package org.odm.gtk4;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Window;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;

/**
 * Settings dialog — 1:1 GTK4 port of settings.glade (7 tabs: General, Network,
 * Aria2, Yt-dlp, HTTrack, Advanced; Cancel/Reset/Apply/OK). Every control is
 * real: core values persist via GlobalSettings.save(); engine defaults are
 * stored as GlobalSettings properties and applied by DownloadSettingsFactory
 * when per-download settings are created.
 *
 * The old dialog shipped 25 widgets whose ids didn't exist in the glade —
 * apply() silently persisted defaults. Here every id is verified by
 * WindowSmokeTest.
 */
public class SettingsDialog {

    private static final Logger LOGGER = Logger.getLogger(SettingsDialog.class.getName());
    private static final String[] FILE_ALLOCATIONS = {"none", "prealloc", "falloc"};

    private static final String[] DAY_LABELS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final org.manager.schedule.ScheduleManager scheduleManager;
    private final java.util.function.Consumer<Boolean> torPreferenceHandler;
    private final GtkBuilder builder;
    private final Label statusLabel;
    private final java.util.concurrent.atomic.AtomicBoolean saveInProgress =
            new java.util.concurrent.atomic.AtomicBoolean();

    private record SettingsApplication(GlobalSettings settings,
            boolean previousStartAtLogin, boolean requestedStartAtLogin,
            boolean schedulingEnabled, boolean[][] hourGrid, boolean torEnabled) {
    }

    /** 7x24 toggle buttons of the scheduler grid (row 0 = Monday). */
    private final org.gnome.gtk.ToggleButton[][] schedulerToggles = new org.gnome.gtk.ToggleButton[7][24];

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager) {
        this(parent, downloadManager, scheduleManager, null);
    }

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager,
            java.util.function.Consumer<Boolean> torPreferenceHandler) {
        this.downloadManager = downloadManager;
        this.scheduleManager = scheduleManager;
        this.torPreferenceHandler = torPreferenceHandler;
        this.builder = UiLoader.load("/ui/settings.ui");
        this.dialog = Widgets.require(builder, "settings_dialog", Window.class);
        this.statusLabel = Widgets.require(builder, "settings_status_label", Label.class);

        AccessibilitySupport.label(spin("max_concurrent_downloads_spin"),
                "Maximum concurrent downloads");
        AccessibilitySupport.label(entry("proxy_host_entry"), "Global proxy host");
        AccessibilitySupport.label(spin("proxy_port_spin"), "Global proxy port");
        AccessibilitySupport.label(entry("proxy_username_entry"), "Global proxy username");
        AccessibilitySupport.label(entry("proxy_password_entry"), "Global proxy password");
        AccessibilitySupport.label(entry("subtitle_language_entry"),
                "Preferred subtitle languages, comma separated");

        dialog.setTransientFor(parent);

        initDropdown("proxy_type_combo", DialogOptions.PROXY_TYPES);
        initDropdown("file_allocation_combo", FILE_ALLOCATIONS);

        buildSchedulerGrid();

        // File/folder pickers
        onPickFolder("default_download_folder_chooser", "Select download folder", this::setDefaultDir);
        onPickFolder("monitored_folder_chooser", "Select monitored folder", this::setMonitoredDir);
        onPickFile("browse_aria2_button", "Select aria2c binary", e -> setText("aria2_path_entry", e));
        onPickFile("browse_ytdlp_button", "Select yt-dlp binary", e -> setText("ytdlp_path_entry", e));
        onPickFile("browse_httrack_button", "Select httrack binary", e -> setText("httrack_path_entry", e));
        onPickFile("browse_proxychains_button", "Select proxychains binary", e -> setText("proxychains_path_entry", e));
        onPickFile("browse_tor_button", "Select tor binary", e -> setText("tor_path_entry", e));
        onPickFile("browse_axel_button", "Select axel binary", e -> setText("axel_path_entry", e));
        onPickFile("browse_subliminal_button", "Select Subliminal binary",
                e -> setText("subliminal_path_entry", e));

        mirrorCheckButtons("start_automatically_check", "start_automatically_check2");
        mirrorCheckButtons("move_torrent_check", "move_torrent_check2");

        load();

        Widgets.require(builder, "settings_cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "settings_reset_button", Button.class).onClicked(this::load);
        Widgets.require(builder, "settings_apply_button", Button.class)
                .onClicked(() -> onApply(false));
        Widgets.require(builder, "settings_ok_button", Button.class)
                .onClicked(() -> onApply(true));
    }

    /**
     * Builds the uGet-style 7x24 hour grid inside the
     * {@code scheduler_grid_box} container: one row of day-label + 24 small
     * toggle buttons per weekday. The grid is enabled/disabled together with
     * the "Enable Scheduling" checkbox.
     */
    private void buildSchedulerGrid() {
        org.gnome.gtk.Box gridBox = Widgets.require(builder, "scheduler_grid_box", org.gnome.gtk.Box.class);
        org.gnome.gtk.Grid grid = new org.gnome.gtk.Grid();
        grid.setColumnHomogeneous(true);
        grid.setColumnSpacing(1);
        grid.setRowSpacing(1);

        for (int day = 0; day < 7; day++) {
            org.gnome.gtk.Label dayLabel = new org.gnome.gtk.Label(DAY_LABELS[day]);
            grid.attach(dayLabel, 0, day, 1, 1);
            for (int hour = 0; hour < 24; hour++) {
                org.gnome.gtk.ToggleButton toggle = new org.gnome.gtk.ToggleButton();
                toggle.setLabel(String.valueOf(hour));
                toggle.setHasFrame(false);
                toggle.setSizeRequest(6, -1);
                grid.attach(toggle, hour + 1, day, 1, 1);
                schedulerToggles[day][hour] = toggle;
            }
        }
        gridBox.append(grid);

        CheckButton enableCheck = check("enable_scheduling_check");
        enableCheck.onToggled(() -> gridBox.setSensitive(enableCheck.getActive()));
    }

    public void present() {
        dialog.present();
        spin("max_concurrent_downloads_spin").grabFocus();
    }

    /** Current status label text (test seam for save-outcome reporting). */
    String statusText() {
        return statusLabel.getLabel();
    }

    // ---- widget helpers ----

    /** Fills the scheduler grid toggles from a persisted hex grid. */
    private void loadSchedulerGrid(String hex) {
        boolean[][] grid = org.manager.schedule.WeeklySchedule.hourGridFromString(hex);
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                schedulerToggles[day][hour].setActive(grid[day][hour]);
            }
        }
    }

    /** Reads the current scheduler grid state from the toggles. */
    private boolean[][] readSchedulerGrid() {
        boolean[][] grid = new boolean[7][24];
        for (int day = 0; day < 7; day++) {
            for (int hour = 0; hour < 24; hour++) {
                grid[day][hour] = schedulerToggles[day][hour].getActive();
            }
        }
        return grid;
    }

    /**
     * Applies the scheduling decision to the running scheduler: an enabled
     * grid installs a STRICT global schedule (outside the marked ranges all
     * downloads are prevented, uGet semantics); disabled or an empty grid
     * installs alwaysActive so nothing is restricted. Note that "disabled"
     * must NOT map to neverActive — the enabled flag controls the
     * scheduler's presence, not inverted ranges.
     */
    private void applySchedulerRuntime(boolean enabled, boolean[][] hourGrid) {
        if (scheduleManager == null) {
            return;
        }
        try {
            if (enabled) {
                org.manager.schedule.ScheduleSettings settings =
                        new org.manager.schedule.ScheduleSettings(
                                org.manager.schedule.WeeklySchedule.fromHourGrid(hourGrid));
                settings.setPolicy(org.manager.schedule.ScheduleSettings.SchedulePolicy.STRICT);
                scheduleManager.start();
                scheduleManager.getScheduler().setGlobalSchedule(settings);
                LOGGER.info("Applied scheduler hour grid (STRICT)");
            } else {
                scheduleManager.getScheduler()
                        .setGlobalSchedule(org.manager.schedule.ScheduleSettings.alwaysActive());
                LOGGER.info("Scheduling disabled; downloads unrestricted");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to apply scheduler settings", e);
        }
    }

    private void torSwitchSet(boolean active) {
        Widgets.require(builder, "tor_switch", Switch.class).setActive(active);
    }

    private boolean torSwitchGet() {
        return Widgets.require(builder, "tor_switch", Switch.class).getActive();
    }

    private Entry entry(String id) {
        return Widgets.require(builder, id, Entry.class);
    }

    private CheckButton check(String id) {
        return Widgets.require(builder, id, CheckButton.class);
    }

    private SpinButton spin(String id) {
        return Widgets.require(builder, id, SpinButton.class);
    }

    private void setText(String entryId, String text) {
        entry(entryId).setText(text);
    }

    private void initDropdown(String id, String[] items) {
        StringList list = new StringList(new String[0]);
        for (String item : items) {
            list.append(item);
        }
        Widgets.require(builder, id, DropDown.class).setModel(list);
    }

    private void onPickFolder(String buttonId, String title,
            java.util.function.Consumer<String> consumer) {
        Widgets.require(builder, buttonId, Button.class).onClicked(() -> {
            FileDialog fileDialog = new FileDialog();
            fileDialog.setTitle(title);
            fileDialog.selectFolder(dialog, null, result -> {
                try {
                    File folder = fileDialog.selectFolderFinish(result);
                    if (folder != null && folder.getPath() != null) {
                        consumer.accept(folder.getPath().toString());
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, title + " selection cancelled or failed", e);
                }
            });
        });
    }

    private void onPickFile(String buttonId, String title,
            java.util.function.Consumer<String> consumer) {
        Widgets.require(builder, buttonId, Button.class).onClicked(() -> {
            FileDialog fileDialog = new FileDialog();
            fileDialog.setTitle(title);
            fileDialog.open(dialog, null, result -> {
                try {
                    File file = fileDialog.openFinish(result);
                    if (file != null && file.getPath() != null) {
                        consumer.accept(file.getPath().toString());
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, title + " selection cancelled or failed", e);
                }
            });
        });
    }

    private void mirrorCheckButtons(String firstId, String secondId) {
        CheckButton first = check(firstId);
        CheckButton second = check(secondId);
        first.onToggled(() -> {
            if (second.getActive() != first.getActive()) {
                second.setActive(first.getActive());
            }
        });
        second.onToggled(() -> {
            if (first.getActive() != second.getActive()) {
                first.setActive(second.getActive());
            }
        });
    }

    // ---- load / apply ----

    private void setDefaultDir(String dir) {
        Widgets.require(builder, "default_download_folder_chooser", Button.class).setLabel(dir);
    }

    private void setMonitoredDir(String dir) {
        Widgets.require(builder, "monitored_folder_chooser", Button.class).setLabel(dir);
    }

    private void load() {
        GlobalSettings s = downloadManager.getGlobalSettings();
        // General
        Path dir = s.getDefaultDownloadDirectory();
        setDefaultDir(dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads");
        spin("max_concurrent_downloads_spin").setValue(s.getMaxConcurrentDownloads());
        check("save_download_history_check").setActive(s.isSaveDownloadHistory());
        check("clipboard_monitor_check").setActive(downloadManager.isClipboardMonitoringEnabled());
        check("folder_monitoring_check").setActive(downloadManager.isTorrentFolderMonitoringEnabled()
                || downloadManager.isMetaLinkFolderMonitoringEnabled());
        check("system_tray_check").setActive(s.getBooleanProperty("ui.systemTray", false));
        check("start_automatically_check").setActive(s.getBooleanProperty("ui.startAutomatically", true));
        check("move_torrent_check").setActive(s.getBooleanProperty("ui.moveTorrent", false));
        check("startup_check").setActive(s.getBooleanProperty("ui.startAtLogin", false));
        check("clipboard_silent_check").setActive(s.getBooleanProperty("ui.clipboardSilent", false));
        check("folder_recursive_check").setActive(s.getBooleanProperty("ui.folderRecursive", false));
        check("move_to_trash_check").setActive(s.getBooleanProperty("ui.moveToTrash", false));
        // Restore the persisted monitored folder (empty until first configured)
        String monitoredDir = s.getProperty("folder.monitorPath", "");
        if (!monitoredDir.isBlank()) {
            setMonitoredDir(monitoredDir);
        }
        // Network (aria2 defaults + proxy)
        torSwitchSet(s.getBooleanProperty("tor.enabled", false));
        spin("max_connections_spin").setValue(s.getIntProperty("aria2.maxConnections", 8));
        spin("retry_limit_spin").setValue(s.getIntProperty("aria2.maxTries", 5));
        spin("max_download_speed_spin").setValue(s.getIntProperty("aria2.maxDownloadSpeedKb", 0));
        spin("max_upload_speed_spin").setValue(s.getIntProperty("aria2.maxUploadSpeedKb", 0));
        spin("retry_after").setValue(s.getIntProperty("aria2.retryWait", 0));
        entry("referer_entry").setText(s.getProperty("aria2.referer", ""));
        entry("cookie_entry").setText(s.getProperty("aria2.cookie", ""));
        entry("user_agent_entry").setText(s.getProperty("aria2.userAgent", ""));
        DialogOptions.ProxyFields proxy = s.isGlobalProxyEnabled()
                ? DialogOptions.parseProxy(s.getGlobalProxyAddress())
                : DialogOptions.ProxyFields.none();
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setSelected(proxy.typeIndex());
        entry("proxy_host_entry").setText(proxy.host());
        spin("proxy_port_spin").setValue(proxy.port());
        entry("proxy_username_entry").setText(proxy.username());
        entry("proxy_password_entry").setText(proxy.password());
        // Aria2
        entry("aria2_path_entry").setText(s.getAria2Path() != null ? s.getAria2Path() : "");
        spin("min_split_size_spin1").setValue(s.getIntProperty("aria2.minSplitSizeMb", 10));
        spin("max_peers_spin").setValue(s.getIntProperty("aria2.maxPeers", 100));
        spin("peer_speed_limit_spin").setValue(s.getIntProperty("aria2.peerSpeedLimitKb", 0));
        spin("seed_time_spin").setValue(s.getIntProperty("aria2.seedTimeMin", 60));
        check("continue_download_check").setActive(s.getBooleanProperty("aria2.continueDownload", true));
        check("check_integrity_check").setActive(s.getBooleanProperty("aria2.checkIntegrity", false));
        String fileAllocation = s.getProperty("aria2.fileAllocation", "prealloc");
        int allocationIndex = java.util.Arrays.asList(FILE_ALLOCATIONS).indexOf(fileAllocation);
        Widgets.require(builder, "file_allocation_combo", DropDown.class)
                .setSelected(Math.max(0, allocationIndex));
        check("enable_auto_save_check").setActive(s.getBooleanProperty("aria2.autoSave", true));
        check("enable_seeding_check").setActive(s.getBooleanProperty("aria2.enableSeeding", false));
        entry("tracker_list_entry").setText(s.getProperty("tracker.list", ""));
        spin("tracker_refresh_spin").setValue(s.getIntProperty("tracker.refreshInterval", 0));
        // Yt-dlp
        entry("ytdlp_path_entry").setText(s.getYtDlpPath() != null ? s.getYtDlpPath() : "");
        entry("video_format_entry").setText(s.getProperty("ytdlp.videoFormat", ""));
        entry("subtitle_language_entry").setText(
                s.getProperty("ytdlp.subtitleLanguages", "en"));
        check("write_thumbnail_check").setActive(s.getBooleanProperty("ytdlp.writeThumbnail", false));
        check("write_subtitles_check").setActive(s.getBooleanProperty("ytdlp.writeSubtitles", false));
        check("embed_metadata_check").setActive(s.getBooleanProperty("ytdlp.embedMetadata", true));
        check("extract_audio_check").setActive(s.getBooleanProperty("ytdlp.extractAudio", false));
        check("use_aria2_external_check").setActive(s.getBooleanProperty("ytdlp.useAria2External", true));
        // HTTrack
        entry("httrack_path_entry").setText(s.getHttrackPath() != null ? s.getHttrackPath() : "");
        spin("depth_spin").setValue(s.getIntProperty("httrack.depth", 3));
        entry("include_entry").setText(s.getProperty("httrack.include", ""));
        entry("exclude_entry").setText(s.getProperty("httrack.exclude", ""));
        check("include_archives_check").setActive(s.getBooleanProperty("httrack.includeArchives", false));
        // Advanced
        boolean schedulingEnabled = s.getBooleanProperty("scheduler.enabled", false);
        check("enable_scheduling_check").setActive(schedulingEnabled);
        Widgets.require(builder, "scheduler_grid_box", org.gnome.gtk.Box.class)
                .setSensitive(schedulingEnabled);
        loadSchedulerGrid(s.getProperty("scheduler.grid", ""));
        entry("proxychains_path_entry").setText(s.getProxychainsPath() != null ? s.getProxychainsPath() : "");
        entry("tor_path_entry").setText(s.getTorPath() != null ? s.getTorPath() : "");
        entry("axel_path_entry").setText(s.getProperty("tools.axelPath", ""));
        entry("subliminal_path_entry").setText(
                s.getSubliminalPath() != null ? s.getSubliminalPath() : "");
    }

    private void onApply(boolean closeAfterSave) {
        if (!saveInProgress.compareAndSet(false, true)) {
            return;
        }
        final SettingsApplication application;
        try {
            application = collectSettings();
        } catch (IllegalArgumentException invalidSetting) {
            saveInProgress.set(false);
            AccessibilitySupport.status(statusLabel, invalidSetting.getMessage(),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
            return;
        }
        setSaveButtonsSensitive(false);
        AccessibilitySupport.status(statusLabel, "Saving settings…");
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> persistSettings(application),
                        org.manager.util.ExecutorServiceManager.getInstance().getIoExecutor())
                .whenComplete((saved, error) -> UiThread.marshal(() -> {
                    saveInProgress.set(false);
                    setSaveButtonsSensitive(true);
                    boolean succeeded = error == null && Boolean.TRUE.equals(saved);
                    applyTorPreference(application);
                    reportSaveOutcome(succeeded);
                    if (succeeded && closeAfterSave) {
                        dialog.close();
                    }
                }));
    }

    private void setSaveButtonsSensitive(boolean sensitive) {
        Widgets.require(builder, "settings_apply_button", Button.class).setSensitive(sensitive);
        Widgets.require(builder, "settings_ok_button", Button.class).setSensitive(sensitive);
    }

    /** Applies every setting and persists them, updating the status label
     * with the actual save outcome. Package-private for presenter tests. */
    void applySettings() {
        SettingsApplication application = collectSettings();
        boolean saved = persistSettings(application);
        applyTorPreference(application);
        reportSaveOutcome(saved);
    }

    private SettingsApplication collectSettings() {
        String subtitleLanguages = String.join(",",
                org.manager.download.action.SubtitleDownloadAction.parseLanguages(
                        entry("subtitle_language_entry").getText()));
        GlobalSettings s = downloadManager.getGlobalSettings().copy();
        boolean previousStartAtLogin = s.getBooleanProperty("ui.startAtLogin", false);
        // tor proxy default
        s.setProperty("tor.enabled", String.valueOf(torSwitchGet()));
        // General
        s.setDefaultDownloadDirectory(Path.of(
                Widgets.require(builder, "default_download_folder_chooser", Button.class).getLabel()));
        s.setMaxConcurrentDownloads((int) spin("max_concurrent_downloads_spin").getValue());
        s.setSaveDownloadHistory(check("save_download_history_check").getActive());
        s.setProperty("ui.systemTray", String.valueOf(check("system_tray_check").getActive()));
        s.setProperty("ui.startAutomatically", String.valueOf(check("start_automatically_check").getActive()));
        s.setProperty("ui.moveTorrent", String.valueOf(check("move_torrent_check").getActive()));
        s.setProperty("ui.startAtLogin", String.valueOf(check("startup_check").getActive()));
        s.setProperty("ui.clipboardSilent", String.valueOf(check("clipboard_silent_check").getActive()));
        // Push silent mode into the core clipboard service immediately
        try {
            downloadManager.updateClipboardSettings(
                    downloadManager.getClipboardService().getSettings()
                            .copy()
                            .setSilentMode(check("clipboard_silent_check").getActive()));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Clipboard settings sync skipped", e);
        }
        s.setProperty("ui.folderRecursive", String.valueOf(check("folder_recursive_check").getActive()));
        s.setProperty("ui.moveToTrash", String.valueOf(check("move_to_trash_check").getActive()));
        // Runtime toggles apply immediately
        downloadManager.setClipboardMonitoringEnabled(check("clipboard_monitor_check").getActive());
        boolean folderMonitoring = check("folder_monitoring_check").getActive();
        // Persist the monitored folder so monitoring survives restarts; the
        // chooser button keeps its placeholder label until a folder is picked
        String monitoredDir = Widgets.require(builder, "monitored_folder_chooser", Button.class).getLabel();
        boolean hasMonitoredDir = monitoredDir != null && !monitoredDir.isBlank()
                && !monitoredDir.startsWith("Select");
        s.setProperty("folder.monitorPath", hasMonitoredDir ? monitoredDir : "");
        s.setProperty("folder.monitorEnabled", String.valueOf(folderMonitoring && hasMonitoredDir));
        downloadManager.setTorrentFolderMonitoringEnabled(folderMonitoring && hasMonitoredDir);
        downloadManager.setMetaLinkFolderMonitoringEnabled(folderMonitoring && hasMonitoredDir);
        // Network
        s.setProperty("aria2.maxConnections", String.valueOf((int) spin("max_connections_spin").getValue()));
        s.setProperty("aria2.maxTries", String.valueOf((int) spin("retry_limit_spin").getValue()));
        s.setProperty("aria2.maxDownloadSpeedKb", String.valueOf((int) spin("max_download_speed_spin").getValue()));
        s.setProperty("aria2.maxUploadSpeedKb", String.valueOf((int) spin("max_upload_speed_spin").getValue()));
        s.setProperty("aria2.retryWait", String.valueOf((int) spin("retry_after").getValue()));
        s.setProperty("aria2.referer", entry("referer_entry").getText().trim());
        s.setProperty("aria2.cookie", entry("cookie_entry").getText().trim());
        s.setProperty("aria2.userAgent", entry("user_agent_entry").getText().trim());
        boolean torEnabled = torSwitchGet();
        String proxyAddress = torEnabled
                ? "socks5h://127.0.0.1:9050"
                : DialogOptions.buildProxyAddress(
                        (int) Widgets.require(builder, "proxy_type_combo", DropDown.class).getSelected(),
                        entry("proxy_host_entry").getText(),
                        (int) spin("proxy_port_spin").getValue(),
                        entry("proxy_username_entry").getText(),
                        entry("proxy_password_entry").getText());
        s.setGlobalProxyEnabled(proxyAddress != null);
        s.setGlobalProxyAddress(proxyAddress);
        // Aria2
        s.setAria2Path(entry("aria2_path_entry").getText().trim());
        s.setProperty("aria2.minSplitSizeMb", String.valueOf((int) spin("min_split_size_spin1").getValue()));
        s.setProperty("aria2.maxPeers", String.valueOf((int) spin("max_peers_spin").getValue()));
        s.setProperty("aria2.peerSpeedLimitKb", String.valueOf((int) spin("peer_speed_limit_spin").getValue()));
        s.setProperty("aria2.seedTimeMin", String.valueOf((int) spin("seed_time_spin").getValue()));
        s.setProperty("aria2.continueDownload", String.valueOf(check("continue_download_check").getActive()));
        s.setProperty("aria2.checkIntegrity", String.valueOf(check("check_integrity_check").getActive()));
        long allocationIndex = Widgets.require(builder, "file_allocation_combo", DropDown.class).getSelected();
        if (allocationIndex >= 0 && allocationIndex < FILE_ALLOCATIONS.length) {
            s.setProperty("aria2.fileAllocation", FILE_ALLOCATIONS[(int) allocationIndex]);
        }
        s.setProperty("aria2.autoSave", String.valueOf(check("enable_auto_save_check").getActive()));
        s.setProperty("aria2.enableSeeding", String.valueOf(check("enable_seeding_check").getActive()));
        s.setProperty("tracker.list", entry("tracker_list_entry").getText().trim());
        s.setProperty("tracker.refreshInterval",
                String.valueOf((int) spin("tracker_refresh_spin").getValue()));
        // Yt-dlp
        s.setYtDlpPath(entry("ytdlp_path_entry").getText().trim());
        s.setProperty("ytdlp.videoFormat", entry("video_format_entry").getText().trim());
        s.setProperty("ytdlp.subtitleLanguages", subtitleLanguages);
        s.setProperty("ytdlp.writeThumbnail", String.valueOf(check("write_thumbnail_check").getActive()));
        s.setProperty("ytdlp.writeSubtitles", String.valueOf(check("write_subtitles_check").getActive()));
        s.setProperty("ytdlp.embedMetadata", String.valueOf(check("embed_metadata_check").getActive()));
        s.setProperty("ytdlp.extractAudio", String.valueOf(check("extract_audio_check").getActive()));
        s.setProperty("ytdlp.useAria2External", String.valueOf(check("use_aria2_external_check").getActive()));
        // HTTrack
        s.setHttrackPath(entry("httrack_path_entry").getText().trim());
        s.setProperty("httrack.depth", String.valueOf((int) spin("depth_spin").getValue()));
        s.setProperty("httrack.include", entry("include_entry").getText().trim());
        s.setProperty("httrack.exclude", entry("exclude_entry").getText().trim());
        s.setProperty("httrack.includeArchives", String.valueOf(check("include_archives_check").getActive()));
        // Advanced
        boolean schedulingEnabled = check("enable_scheduling_check").getActive();
        boolean[][] hourGrid = readSchedulerGrid();
        boolean allInactive = true;
        outer:
        for (boolean[] row : hourGrid) {
            for (boolean cell : row) {
                if (cell) {
                    allInactive = false;
                    break outer;
                }
            }
        }
        boolean effectiveSchedulingEnabled = schedulingEnabled && !allInactive;
        s.setProperty("scheduler.enabled", String.valueOf(effectiveSchedulingEnabled));
        s.setProperty("scheduler.grid",
                effectiveSchedulingEnabled
                        ? org.manager.schedule.WeeklySchedule.hourGridToString(hourGrid)
                        : "");
        s.setProxychainsPath(entry("proxychains_path_entry").getText().trim());
        s.setTorPath(entry("tor_path_entry").getText().trim());
        s.setProperty("tools.axelPath", entry("axel_path_entry").getText().trim());
        s.setSubliminalPath(entry("subliminal_path_entry").getText().trim());

        return new SettingsApplication(s, previousStartAtLogin,
                check("startup_check").getActive(), effectiveSchedulingEnabled,
                hourGrid, torEnabled);
    }

    /** Performs filesystem and core/service work away from the GTK thread. */
    private boolean persistSettings(SettingsApplication application) {
        GlobalSettings s = application.settings();
        applySchedulerRuntime(application.schedulingEnabled(), application.hourGrid());
        boolean autostartApplied = true;
        try {
            AutostartManager.setEnabled(application.requestedStartAtLogin());
        } catch (java.io.IOException e) {
            autostartApplied = false;
            // Keep persisted settings consistent with the desktop entry that
            // is still on disk when the external operation fails.
            s.setProperty("ui.startAtLogin",
                    String.valueOf(application.previousStartAtLogin()));
            LOGGER.log(Level.WARNING, "Failed to update the login autostart entry", e);
        }
        boolean settingsSaved = s.save();
        if (!settingsSaved && autostartApplied
                && application.requestedStartAtLogin()
                        != application.previousStartAtLogin()) {
            try {
                AutostartManager.setEnabled(application.previousStartAtLogin());
                s.setProperty("ui.startAtLogin",
                        String.valueOf(application.previousStartAtLogin()));
            } catch (java.io.IOException rollbackFailure) {
                LOGGER.log(Level.WARNING, "Failed to roll back the login autostart entry", rollbackFailure);
            }
        }
        boolean saved = settingsSaved && autostartApplied;
        downloadManager.setGlobalSettings(s);
        return saved;
    }

    private void applyTorPreference(SettingsApplication application) {
        if (torPreferenceHandler != null) {
            torPreferenceHandler.accept(application.torEnabled());
        }
    }

    private void reportSaveOutcome(boolean saved) {
        if (saved) {
            AccessibilitySupport.status(statusLabel, "Settings saved.");
            LOGGER.info("Settings saved to " + GlobalSettings.getConfigFilePath());
        } else {
            AccessibilitySupport.status(statusLabel,
                    "Failed to save settings — check configuration permissions",
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
            LOGGER.severe("Failed to save settings to " + GlobalSettings.getConfigFilePath());
        }
    }
}
