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
    private static final String[] PROXY_TYPES = {"None", "HTTP", "HTTPS", "SOCKS4", "SOCKS5"};
    private static final String[] FILE_ALLOCATIONS = {"none", "prealloc", "falloc"};

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final GtkBuilder builder;
    private final Label statusLabel;

    public SettingsDialog(Window parent, DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
        this.builder = UiLoader.load("/ui/settings.ui");
        this.dialog = Widgets.require(builder, "settings_dialog", Window.class);
        this.statusLabel = Widgets.require(builder, "settings_status_label", Label.class);

        dialog.setTransientFor(parent);

        initDropdown("proxy_type_combo", PROXY_TYPES);
        initDropdown("file_allocation_combo", FILE_ALLOCATIONS);

        // File/folder pickers
        onPick("default_download_folder_chooser", "Select download folder", this::setDefaultDir);
        onPick("monitored_folder_chooser", "Select monitored folder", this::setMonitoredDir);
        onPick("browse_aria2_button", "Select aria2c binary", e -> setText("aria2_path_entry", e));
        onPick("browse_ytdlp_button", "Select yt-dlp binary", e -> setText("ytdlp_path_entry", e));
        onPick("browse_httrack_button", "Select httrack binary", e -> setText("httrack_path_entry", e));
        onPick("browse_proxychains_button", "Select proxychains binary", e -> setText("proxychains_path_entry", e));
        onPick("browse_tor_button", "Select tor binary", e -> setText("tor_path_entry", e));
        onPick("browse_axel_button", "Select axel binary", e -> setText("axel_path_entry", e));

        load();

        Widgets.require(builder, "settings_cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "settings_reset_button", Button.class).onClicked(this::load);
        Widgets.require(builder, "settings_apply_button", Button.class).onClicked(this::onApply);
        Widgets.require(builder, "settings_ok_button", Button.class).onClicked(() -> {
            onApply();
            dialog.close();
        });
    }

    public void present() {
        dialog.present();
    }

    // ---- widget helpers ----

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

    private void onPick(String buttonId, String title, java.util.function.Consumer<String> consumer) {
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
        entry("proxy_host_entry").setText(s.getGlobalProxyAddress() != null ? s.getGlobalProxyAddress() : "");
        // Aria2
        entry("aria2_path_entry").setText(s.getAria2Path() != null ? s.getAria2Path() : "");
        spin("min_split_size_spin1").setValue(s.getIntProperty("aria2.minSplitSizeMb", 10));
        spin("max_peers_spin").setValue(s.getIntProperty("aria2.maxPeers", 100));
        spin("peer_speed_limit_spin").setValue(s.getIntProperty("aria2.peerSpeedLimitKb", 0));
        spin("seed_time_spin").setValue(s.getIntProperty("aria2.seedTimeMin", 60));
        check("continue_download_check").setActive(s.getBooleanProperty("aria2.continueDownload", true));
        check("check_integrity_check").setActive(s.getBooleanProperty("aria2.checkIntegrity", false));
        check("enable_auto_save_check").setActive(s.getBooleanProperty("aria2.autoSave", true));
        check("enable_seeding_check").setActive(s.getBooleanProperty("aria2.enableSeeding", false));
        // Yt-dlp
        entry("ytdlp_path_entry").setText(s.getYtDlpPath() != null ? s.getYtDlpPath() : "");
        entry("video_format_entry").setText(s.getProperty("ytdlp.videoFormat", ""));
        entry("subtitle_language_entry").setText(s.getProperty("ytdlp.subtitleLanguages", ""));
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
        check("enable_scheduling_check").setActive(s.getBooleanProperty("scheduler.enabled", false));
        entry("proxychains_path_entry").setText(s.getProxychainsPath() != null ? s.getProxychainsPath() : "");
        entry("tor_path_entry").setText(s.getTorPath() != null ? s.getTorPath() : "");
        entry("axel_path_entry").setText(s.getProperty("tools.axelPath", ""));
    }

    private void onApply() {
        GlobalSettings s = downloadManager.getGlobalSettings();
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
        String proxyAddress = entry("proxy_host_entry").getText().trim();
        s.setGlobalProxyEnabled(!proxyAddress.isEmpty());
        s.setGlobalProxyAddress(proxyAddress);
        // Aria2
        s.setAria2Path(entry("aria2_path_entry").getText().trim());
        s.setProperty("aria2.minSplitSizeMb", String.valueOf((int) spin("min_split_size_spin1").getValue()));
        s.setProperty("aria2.maxPeers", String.valueOf((int) spin("max_peers_spin").getValue()));
        s.setProperty("aria2.peerSpeedLimitKb", String.valueOf((int) spin("peer_speed_limit_spin").getValue()));
        s.setProperty("aria2.seedTimeMin", String.valueOf((int) spin("seed_time_spin").getValue()));
        s.setProperty("aria2.continueDownload", String.valueOf(check("continue_download_check").getActive()));
        s.setProperty("aria2.checkIntegrity", String.valueOf(check("check_integrity_check").getActive()));
        s.setProperty("aria2.autoSave", String.valueOf(check("enable_auto_save_check").getActive()));
        s.setProperty("aria2.enableSeeding", String.valueOf(check("enable_seeding_check").getActive()));
        // Yt-dlp
        s.setYtDlpPath(entry("ytdlp_path_entry").getText().trim());
        s.setProperty("ytdlp.videoFormat", entry("video_format_entry").getText().trim());
        s.setProperty("ytdlp.subtitleLanguages", entry("subtitle_language_entry").getText().trim());
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
        s.setProperty("scheduler.enabled", String.valueOf(check("enable_scheduling_check").getActive()));
        s.setProxychainsPath(entry("proxychains_path_entry").getText().trim());
        s.setTorPath(entry("tor_path_entry").getText().trim());
        s.setProperty("tools.axelPath", entry("axel_path_entry").getText().trim());

        s.save();
        downloadManager.setGlobalSettings(s);
        statusLabel.setLabel("Settings saved.");
        LOGGER.info("Settings saved to " + GlobalSettings.getConfigFilePath());
    }
}
