package org.odm.gtk4;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gdk.Display;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.ToggleButton;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.aria2.Aria2GlobalOptions;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;

/**
 * Settings dialog (General, Network, Aria2, Yt-dlp, HTTrack, Search Engine,
 * Advanced; Cancel/Reset/Apply/OK). Every control is
 * real: core values persist via GlobalSettings.save(); engine defaults are
 * stored as GlobalSettings properties and applied by DownloadSettingsFactory
 * when per-download settings are created.
 *
 * The old dialog shipped 25 widgets whose ids didn't exist in the glade —
 * apply() silently persisted defaults. Here every id is verified by
 * WindowSmokeTest.
 */
public class SettingsDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(SettingsDialog.class);
    private static final String[] FILE_ALLOCATIONS = {"none", "prealloc", "falloc"};
    private static final String[] SEEDING_POLICIES = {
        "Disabled", "Ratio limit", "Time limit", "Ratio or time", "Unlimited"
    };
    private static final String[] NATIVE_TOGGLE_OVERRIDES = {
        "Engine default", "Enabled", "Disabled"
    };
    private static final String[] TORRENT_ENCRYPTION_POLICIES = {
        "Engine default", "Require obfuscated handshake", "Require encrypted payload"
    };
    private static final Map<String, String> SETTING_TOOLTIPS = Map.ofEntries(
            // General
            Map.entry("default_download_folder_chooser",
                    "Folder used for new downloads unless another destination is chosen in a download dialog."),
            Map.entry("max_concurrent_downloads_spin",
                    "Maximum downloads ODM may run at once; additional downloads remain queued."),
            Map.entry("monitored_folder_chooser",
                    "Folder watched for new .torrent, .metalink, and .meta4 descriptor files when folder monitoring is enabled."),
            Map.entry("folder_monitoring_check",
                    "Automatically create downloads from supported descriptor files placed in the monitored folder."),
            Map.entry("folder_recursive_check",
                    "Also watch subfolders inside the monitored folder."),
            Map.entry("move_to_trash_check",
                    "After creating the download, move the original watched descriptor to the Linux/XDG Trash."),
            Map.entry("clipboard_monitor_check",
                    "Detect supported download URLs copied by other applications."),
            Map.entry("clipboard_silent_check",
                    "Add detected clipboard URLs without opening a confirmation dialog."),
            Map.entry("system_tray_check",
                    "Show the ODM status icon in the desktop system tray when supported."),
            Map.entry("startup_check",
                    "Start ODM automatically when the desktop session begins."),
            Map.entry("start_automatically_check",
                    "Automatically start downloads admitted by clipboard and folder monitoring; user-triggered dialogs and imports are unaffected."),
            Map.entry("move_torrent_check",
                    "After creating a download from a selected descriptor, move the original .torrent, .metalink, or .meta4 file to the Linux/XDG Trash."),
            Map.entry("enable_auto_save_check",
                    "Periodically save ODM download and session state so it can be recovered after a crash."),

            // Network
            Map.entry("max_connections_spin",
                    "Default maximum connections for new downloads, capped to each engine's supported limit: aria2 16, HTTrack 8; yt-dlp fragments can exceed 16."),
            Map.entry("retry_limit_spin",
                    "Maximum attempts for new downloads when supported; 0 uses the selected engine's default."),
            Map.entry("retry_after",
                    "Seconds between retry attempts when supported; 0 uses the selected engine's default."),
            Map.entry("max_download_speed_spin",
                    "Default download speed limit for supporting engines; 0 means unlimited."),
            Map.entry("max_upload_speed_spin",
                    "Default upload speed limit for supporting bidirectional engines; 0 means unlimited."),
            Map.entry("referer_entry",
                    "HTTP Referer header sent by new downloads when supported; leave empty to use the engine default."),
            Map.entry("cookie_entry",
                    "HTTP Cookie header sent by new downloads when supported; leave empty to omit it."),
            Map.entry("user_agent_entry",
                    "User-Agent used by new downloads when supported; leave empty for the engine default."),
            Map.entry("proxy_type_combo",
                    "Global proxy protocol used by new downloads; select None to disable the global proxy."),
            Map.entry("proxy_host_entry",
                    "Host name or IP address of the global proxy server."),
            Map.entry("proxy_port_spin",
                    "TCP port of the global proxy server."),
            Map.entry("proxy_username_entry",
                    "Optional username used to authenticate with the global proxy."),
            Map.entry("proxy_password_entry",
                    "Optional password used to authenticate with the global proxy."),
            Map.entry("tor_switch",
                    "Route new downloads through the Tor service managed by ODM."),

            // aria2
            Map.entry("aria2_path_entry",
                    "Path to the aria2c executable; leave empty to discover it automatically. A running ODM aria2 daemon changes on restart."),
            Map.entry("browse_aria2_button",
                    "Choose the aria2c executable used when ODM next starts its aria2 daemon."),
            Map.entry("min_split_size_spin1",
                    "Smallest file segment aria2 creates when splitting a download across connections."),
            Map.entry("file_allocation_combo",
                    "How aria2 reserves disk space before downloading: none, prealloc, or Linux falloc."),
            Map.entry("max_peers_spin",
                    "Maximum peers per BitTorrent download; 0 means unlimited."),
            Map.entry("peer_speed_limit_spin",
                    "Preferred BitTorrent speed threshold. When every torrent is slower, aria2 may temporarily request more peers; 0 disables the threshold."),
            Map.entry("seeding_policy_combo",
                    "Choose whether completed torrents stop immediately, at a ratio, after a time, when either limit is reached, or never."),
            Map.entry("seed_ratio_spin",
                    "Upload-to-download share ratio at which aria2 stops seeding; used by ratio-based policies."),
            Map.entry("seed_time_spin",
                    "Minutes to seed after completion; used by time-based policies."),
            Map.entry("torrent_listen_ports_entry",
                    "TCP peer and UDP DHT listen port or range, such as 6881-6999. Blank keeps aria2's native setting; changes require restarting ODM."),
            Map.entry("ipv6_dht_combo",
                    "Override aria2's IPv6 DHT discovery policy. Engine default emits no ODM option; changes require restarting ODM."),
            Map.entry("peer_exchange_combo",
                    "Override BitTorrent Peer Exchange for new downloads. Private torrents still disable peer exchange."),
            Map.entry("local_peer_discovery_combo",
                    "Override local-network peer discovery for new torrents. aria2's native default is disabled."),
            Map.entry("torrent_encryption_combo",
                    "Keep aria2's native encryption policy, require an obfuscated handshake, or require ARC4 payload encryption."),
            Map.entry("tracker_refresh_spin",
                    "How often ODM reapplies the extra tracker list to active torrents; 0 disables periodic refresh."),
            Map.entry("tracker_list_entry",
                    "Comma-, space-, or line-separated tracker announce URLs added to BitTorrent downloads."),
            Map.entry("continue_download_check",
                    "Resume partial files instead of restarting them when a download is retried or resumed."),
            Map.entry("check_integrity_check",
                    "Ask aria2 to verify available checksums before accepting downloaded data."),
            Map.entry("aria2_rpc_port_spin",
                    "Local RPC port used by ODM's aria2 daemon. The default 6801 avoids aria2's conventional port 6800; changes apply after restarting ODM."),
            Map.entry("honor_external_aria2_config_check",
                    "Allow an aria2 daemon started by ODM to load the user's normal aria2 configuration file. Disabled keeps ODM settings authoritative."),
            Map.entry("remote_time_check",
                    "Use the server's last-modified time for supported file downloads. Applies when a download starts or resumes."),

            // yt-dlp
            Map.entry("ytdlp_path_entry",
                    "Path to the yt-dlp executable; leave empty to discover it automatically."),
            Map.entry("browse_ytdlp_button",
                    "Choose the yt-dlp executable used by ODM."),
            Map.entry("write_thumbnail_check",
                    "Save the media thumbnail as a separate image file next to the download."),
            Map.entry("embed_thumbnail_check",
                    "Embed the media thumbnail as cover art when the selected output supports it. This may require FFmpeg."),
            Map.entry("embed_metadata_check",
                    "Embed available title, artist, chapter, and other metadata in the media file."),
            Map.entry("use_aria2_external_check",
                    "Let yt-dlp use aria2 for supported media fragments and direct media URLs."),
            Map.entry("honor_external_ytdlp_config_check",
                    "Allow ODM's yt-dlp commands to load system and user yt-dlp configuration files. Disabled keeps ODM settings authoritative."),
            Map.entry("skip_downloaded_media_check",
                    "Remember successfully downloaded videos across playlists and restarts, even after removing records. Applies when a media download starts or resumes; disable this preference to download a video again."),

            // HTTrack
            Map.entry("httrack_path_entry",
                    "Path to the HTTrack executable; leave empty to discover it automatically. Changes take effect after restarting ODM."),
            Map.entry("browse_httrack_button",
                    "Choose the HTTrack executable used by ODM after it restarts."),
            Map.entry("httrack_max_total_size_spin",
                    "Stop after this many MiB have been mirrored; 0 means unlimited. The first-run default is 1024 MiB."),
            Map.entry("httrack_max_non_html_size_spin",
                    "Skip individual non-HTML files larger than this many MiB; 0 means unlimited."),
            Map.entry("httrack_max_html_size_spin",
                    "Skip individual HTML files larger than this many MiB; 0 means unlimited."),
            Map.entry("httrack_max_duration_spin",
                    "Stop the crawl after this many minutes; 0 means unlimited."),
            Map.entry("httrack_max_links_spin",
                    "Stop after discovering this many links; 0 disables HTTrack's link-count limit."),
            Map.entry("httrack_connections_per_second_spin",
                    "Maximum new connections HTTrack opens per second. Lower values are gentler on remote servers; 0 disables this throttle."),
            Map.entry("httrack_delay_between_files_spin",
                    "Minimum pause in seconds between file requests; 0 adds no ODM delay."),

            // Advanced
            Map.entry("override_output_path_check",
                    "Delete the existing output file or folder before starting a new download. Existing downloads keep the engine's resume policy."),
            Map.entry("enable_scheduling_check",
                    "Apply the weekly grid globally: inactive hours pause active downloads and prevent queued downloads from starting."),
            Map.entry("retain_completed_canceled_history_check",
                    "When enabled, keep completed and canceled records across restarts. When disabled, omit only those records from persisted history. Downloaded files are never deleted; automatic cleanup is controlled separately."),
            Map.entry("automatic_cleanup_check",
                    "Periodically remove old records from ODM history according to the limits below. Downloaded files are never removed."),
            Map.entry("cleanup_interval_spin",
                    "How often ODM evaluates the enabled history cleanup rules."),
            Map.entry("max_history_records_spin",
                    "Maximum total history records kept when automatic cleanup runs; 0 means unlimited. Active downloads are never removed."),
            Map.entry("max_completed_records_spin",
                    "Maximum completed records kept when automatic cleanup runs; 0 means unlimited."),
            Map.entry("completed_retention_spin",
                    "Remove completed records older than this many days when automatic cleanup runs; 0 disables age-based removal."),
            Map.entry("error_retention_spin",
                    "Remove error records older than this many days when automatic cleanup runs; 0 disables age-based removal."),
            Map.entry("max_import_urls_spin",
                    "Maximum URLs accepted by one URL-list, generated-sequence, or HTML import."),
            Map.entry("max_import_source_size_spin",
                    "Maximum size of a local URL list or local/remote HTML source parsed in memory."),
            Map.entry("proxychains_path_entry",
                    "Path to the proxychains executable used for SOCKS and Tor-routed downloads; changes take effect after restarting ODM."),
            Map.entry("browse_proxychains_button",
                    "Choose the proxychains executable used by ODM after it restarts."),
            Map.entry("tor_path_entry",
                    "Path to the Tor executable managed by ODM; changes take effect after restarting ODM."),
            Map.entry("browse_tor_button",
                    "Choose the Tor executable managed by ODM after it restarts."),
            Map.entry("tor_check_interval_spin",
                    "Minutes between automatic checks while Tor and the circuit monitor are running."),
            Map.entry("tor_circuit_monitor_switch",
                    "Monitor the Tor circuit while Tor is running. A failed automatic check enables Offline Mode and sends a desktop notification."),
            Map.entry("curl_path_entry",
                    "Path to the curl executable used by fallback downloads; changes take effect after restarting ODM."),
            Map.entry("browse_curl_button",
                    "Choose the curl executable used by fallback downloads after ODM restarts."),
            Map.entry("subliminal_path_entry",
                    "Path to the Subliminal executable used by the Download Subtitles completion action; leave empty to discover it automatically."),
            Map.entry("browse_subliminal_button",
                    "Choose the Subliminal executable used by completion actions."),
            Map.entry("antivirus_type_combo",
                    "Scanner used by Antivirus Scan completion actions; Automatic chooses the first validated installed scanner."),
            Map.entry("antivirus_command_entry",
                    "Custom file scanner command; include {file} as an argument. Quotes group arguments. Exit 0 means the command completed; inspect its output for the verdict."),
            Map.entry("antivirus_timeout_spin",
                    "Maximum antivirus scan duration in seconds; 0 waits without a timeout."));

    record AntivirusChoice(String key, String label) {
    }

    private static final String[] DAY_LABELS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String SCHEDULER_CSS = """
            .scheduler-hour {
              min-width: 20px;
              min-height: 20px;
              padding: 0;
              margin: 0;
              border-radius: 0;
              color: transparent;
              background: alpha(@theme_fg_color, 0.12);
              border: 1px solid alpha(@theme_fg_color, 0.45);
            }
            .scheduler-hour:checked {
              color: transparent;
              background: #20c53a;
              border-color: #118527;
            }
            .scheduler-active-swatch,
            .scheduler-inactive-swatch {
              min-width: 20px;
              min-height: 20px;
              border-radius: 0;
            }
            .scheduler-active-swatch {
              background: #20c53a;
              border: 1px solid #118527;
            }
            .scheduler-inactive-swatch {
              background: alpha(@theme_fg_color, 0.12);
              border: 1px solid alpha(@theme_fg_color, 0.45);
            }
            """;
    private static CssProvider schedulerCssProvider;

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final org.manager.schedule.ScheduleManager scheduleManager;
    private final java.util.function.Consumer<Boolean> torPreferenceHandler;
    private final GtkBuilder builder;
    private final Notebook settingsNotebook;
    private final Label statusLabel;
    private final Label availableSpaceLabel;
    private final Label antivirusDetectionLabel;
    private final PathChooserButton defaultDirectoryChooser;
    private final PathChooserButton monitoredDirectoryChooser;
    private final java.util.concurrent.atomic.AtomicBoolean saveInProgress =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private java.util.List<AntivirusChoice> antivirusChoices = java.util.List.of();
    private static final String ANTIVIRUS_AUTO = "auto";
    private String requestedAntivirusKey = ANTIVIRUS_AUTO;
    private record SettingsApplication(GlobalSettings settings,
            boolean previousStartAtLogin, boolean requestedStartAtLogin,
            boolean schedulingEnabled, boolean[][] hourGrid, boolean torEnabled,
            boolean clipboardMonitoring, boolean clipboardSilent,
            boolean folderMonitoring) {
    }

    /** 7x24 toggle buttons of the scheduler grid (row 0 = Monday). */
    private final ToggleButton[][] schedulerToggles = new ToggleButton[7][24];
    private final org.tor.TorService torService;
    private boolean torAvailable;
    private Label schedulerSelectionLabel;
    private boolean loadingSchedulerGrid;
    private boolean schedulerGridEdited;

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager) {
        this(parent, downloadManager, scheduleManager, null);
    }

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager,
            java.util.function.Consumer<Boolean> torPreferenceHandler) {
        this(parent, downloadManager, scheduleManager, torPreferenceHandler, null);
    }

    private final JackettSettingsPane jackett;

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager,
            java.util.function.Consumer<Boolean> torPreferenceHandler,
            org.tor.TorService torService) {
        this(parent, downloadManager, scheduleManager, torPreferenceHandler, torService, null);
    }

    public SettingsDialog(Window parent, DownloadManager downloadManager,
            org.manager.schedule.ScheduleManager scheduleManager,
            java.util.function.Consumer<Boolean> torPreferenceHandler,
            org.tor.TorService torService, org.jackett.JackettService jackettService) {
        this.downloadManager = downloadManager;
        this.scheduleManager = scheduleManager;
        this.torPreferenceHandler = torPreferenceHandler;
        this.torService = torService;
        this.builder = UiLoader.load("/ui/settings.ui");
        this.dialog = Widgets.require(builder, "settings_dialog", Window.class);
        this.settingsNotebook = Widgets.require(builder, "settings_notebook", Notebook.class);
        this.jackett = new JackettSettingsPane(dialog, downloadManager.getGlobalSettings(), jackettService);
        settingsNotebook.insertPage(jackett.widget(), new Label("Search Engine"), 5);
        this.statusLabel = Widgets.require(builder, "settings_status_label", Label.class);
        this.availableSpaceLabel = Widgets.require(builder, "available_space_label", Label.class);
        this.antivirusDetectionLabel = Widgets.require(
                builder, "antivirus_detection_label", Label.class);

        AccessibilitySupport.label(spin("max_concurrent_downloads_spin"),
                "Maximum concurrent downloads");
        AccessibilitySupport.label(check("automatic_cleanup_check"),
                "Automatically remove old download records");
        AccessibilitySupport.label(check("retain_completed_canceled_history_check"),
                "Save download history");
        AccessibilitySupport.label(spin("cleanup_interval_spin"),
                "History cleanup interval in hours");
        AccessibilitySupport.label(spin("tor_check_interval_spin"),
                "Tor check interval in minutes");
        AccessibilitySupport.label(Widgets.require(builder, "tor_circuit_monitor_switch", Switch.class),
                "Enable Tor circuit monitor");
        AccessibilitySupport.label(spin("max_history_records_spin"),
                "Maximum download history records, zero for unlimited");
        AccessibilitySupport.label(spin("max_completed_records_spin"),
                "Maximum completed history records, zero for unlimited");
        AccessibilitySupport.label(spin("completed_retention_spin"),
                "Completed record retention in days, zero to never remove by age");
        AccessibilitySupport.label(spin("error_retention_spin"),
                "Error record retention in days, zero to never remove by age");
        AccessibilitySupport.label(spin("max_import_urls_spin"),
                "Maximum URLs accepted by one import");
        AccessibilitySupport.label(spin("max_import_source_size_spin"),
                "Maximum import source size in MiB");
        AccessibilitySupport.label(entry("proxy_host_entry"), "Global proxy host");
        AccessibilitySupport.label(spin("proxy_port_spin"), "Global proxy port");
        AccessibilitySupport.label(entry("proxy_username_entry"), "Global proxy username");
        AccessibilitySupport.label(entry("proxy_password_entry"), "Global proxy password");
        AccessibilitySupport.label(spin("aria2_rpc_port_spin"),
                "aria2 RPC port");
        AccessibilitySupport.label(Widgets.require(builder,
                "seeding_policy_combo", DropDown.class), "Torrent seeding policy");
        AccessibilitySupport.label(spin("seed_ratio_spin"),
                "Torrent seed ratio limit");
        AccessibilitySupport.label(spin("seed_time_spin"),
                "Torrent seed time limit in minutes");
        AccessibilitySupport.label(entry("torrent_listen_ports_entry"),
                "Torrent peer and DHT listen ports");
        AccessibilitySupport.label(Widgets.require(builder,
                "ipv6_dht_combo", DropDown.class), "IPv6 DHT policy");
        AccessibilitySupport.label(Widgets.require(builder,
                "peer_exchange_combo", DropDown.class), "Peer exchange policy");
        AccessibilitySupport.label(Widgets.require(builder,
                "local_peer_discovery_combo", DropDown.class),
                "Local peer discovery policy");
        AccessibilitySupport.label(Widgets.require(builder,
                "torrent_encryption_combo", DropDown.class),
                "BitTorrent encryption policy");
        AccessibilitySupport.label(entry("antivirus_command_entry"),
                "Custom antivirus command including file placeholder");
        AccessibilitySupport.label(spin("antivirus_timeout_spin"),
                "Antivirus scan timeout in seconds, zero for no timeout");
        AccessibilitySupport.label(spin("httrack_max_total_size_spin"),
                "Maximum mirror size in MiB, zero for unlimited");
        AccessibilitySupport.label(spin("httrack_max_non_html_size_spin"),
                "Maximum non-HTML file size in MiB, zero for unlimited");
        AccessibilitySupport.label(spin("httrack_max_html_size_spin"),
                "Maximum HTML file size in MiB, zero for unlimited");
        AccessibilitySupport.label(spin("httrack_max_duration_spin"),
                "Maximum mirror duration in minutes, zero for unlimited");
        AccessibilitySupport.label(spin("httrack_max_links_spin"),
                "Maximum mirror link count, zero for unlimited");
        AccessibilitySupport.label(spin("httrack_connections_per_second_spin"),
                "Maximum HTTrack connections per second");
        AccessibilitySupport.label(spin("httrack_delay_between_files_spin"),
                "Delay between HTTrack files in seconds");

        DialogSupport.configureIndependent(dialog, parent);

        initDropdown("proxy_type_combo", DialogOptions.PROXY_TYPES);
        initDropdown("file_allocation_combo", FILE_ALLOCATIONS);
        initDropdown("seeding_policy_combo", SEEDING_POLICIES);
        initDropdown("ipv6_dht_combo", NATIVE_TOGGLE_OVERRIDES);
        initDropdown("peer_exchange_combo", NATIVE_TOGGLE_OVERRIDES);
        initDropdown("local_peer_discovery_combo", NATIVE_TOGGLE_OVERRIDES);
        initDropdown("torrent_encryption_combo", TORRENT_ENCRYPTION_POLICIES);
        initDropdown("antivirus_type_combo", new String[]{"Detecting installed scanners…"});
        Widgets.require(builder, "antivirus_type_combo", DropDown.class).setSensitive(false);
        AccessibilitySupport.label(Widgets.require(builder, "antivirus_type_combo", DropDown.class),
                "Antivirus scanner used by completion actions");

        buildSchedulerGrid();

        // File/folder pickers. The folder controls reproduce GTK3's
        // GtkFileChooserButton visuals while using GTK4's async FileDialog.
        MenuButton defaultDirectoryButton = Widgets.require(builder,
                "default_download_folder_chooser", MenuButton.class);
        MenuButton monitoredDirectoryButton = Widgets.require(builder,
                "monitored_folder_chooser", MenuButton.class);
        AccessibilitySupport.label(defaultDirectoryButton, "Default download folder");
        AccessibilitySupport.label(monitoredDirectoryButton, "Monitored folder");
        this.defaultDirectoryChooser = PathChooserButton.forFolder(defaultDirectoryButton,
                dialog, "Select download folder", null,
                path -> setDefaultDir(path.toString()));
        this.monitoredDirectoryChooser = PathChooserButton.forFolder(monitoredDirectoryButton,
                dialog, "Select monitored folder", null,
                path -> setMonitoredDir(path.toString()));
        onPickFile("browse_aria2_button", "Select aria2c binary", e -> setText("aria2_path_entry", e));
        onPickFile("browse_ytdlp_button", "Select yt-dlp binary", e -> setText("ytdlp_path_entry", e));
        onPickFile("browse_httrack_button", "Select httrack binary", e -> setText("httrack_path_entry", e));
        onPickFile("browse_proxychains_button", "Select proxychains binary", e -> setText("proxychains_path_entry", e));
        onPickFile("browse_tor_button", "Select tor binary", e -> setText("tor_path_entry", e));
        onPickFile("browse_curl_button", "Select curl binary", e -> setText("curl_path_entry", e));
        onPickFile("browse_subliminal_button", "Select Subliminal binary",
                e -> setText("subliminal_path_entry", e));

        configureSettingTooltips();
        load();
        Switch torControl = Widgets.require(builder, "tor_switch", Switch.class);
        Widgets.require(builder, "tor_circuit_monitor_switch", Switch.class)
                .onNotify("active", ignored -> refreshTorMonitorControls());
        TorControlBinding.bind(dialog, torService, available -> {
            torAvailable = available;
            torControl.setSensitive(available);
            refreshTorMonitorControls();
            torControl.setTooltipText(available ? SETTING_TOOLTIPS.get("tor_switch")
                    : "Start Tor from Edit → Tor to change this option.");
        });
        discoverAvailableAntiviruses();
        bindFolderMonitoringChildren();
        bindHistoryCleanupControls();
        bindAntivirusControls();
        bindSeedingPolicyControls();
        dialog.onCloseRequest(() -> {
            closed.set(true);
            jackett.close();
            return false;
        });

        Widgets.require(builder, "settings_cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "settings_reset_button", Button.class)
                .onClicked(this::resetToDefaults);
        Widgets.require(builder, "settings_apply_button", Button.class)
                .onClicked(() -> onApply(false));
        Widgets.require(builder, "settings_ok_button", Button.class)
                .onClicked(() -> onApply(true));
    }

    /**
     * Builds the uGet-style 7x24 hour grid inside the
     * {@code scheduler_grid_box} container. Hour headers, square state cells,
     * a current-cell caption and the active/inactive legend mirror the visual
     * language of the GTK3 scheduler reference while preserving the existing
     * persisted 7x24 schedule contract.
     */
    private void buildSchedulerGrid() {
        installSchedulerCss();
        org.gnome.gtk.Box gridBox = Widgets.require(builder, "scheduler_grid_box", org.gnome.gtk.Box.class);
        schedulerSelectionLabel = Widgets.require(builder, "scheduler_selection_label", Label.class);
        org.gnome.gtk.Grid grid = new org.gnome.gtk.Grid();
        grid.setHexpand(true);
        grid.setColumnHomogeneous(false);
        grid.setColumnSpacing(1);
        grid.setRowSpacing(1);

        for (int hour = 0; hour < 24; hour++) {
            Label hourLabel = new Label(hour % 3 == 0 ? String.format("%02d", hour) : "");
            hourLabel.addCssClass("caption");
            hourLabel.setTooltipText(String.format("%02d:00–%02d:59", hour, hour));
            grid.attach(hourLabel, hour + 1, 0, 1, 1);
        }

        for (int day = 0; day < 7; day++) {
            Label dayLabel = new Label(DAY_LABELS[day]);
            dayLabel.setXalign(1);
            dayLabel.setMarginEnd(6);
            grid.attach(dayLabel, 0, day + 1, 1, 1);
            for (int hour = 0; hour < 24; hour++) {
                ToggleButton toggle = new ToggleButton();
                toggle.setLabel("");
                toggle.setHasFrame(true);
                toggle.setSizeRequest(22, 22);
                toggle.addCssClass("scheduler-hour");
                String hourDescription = String.format("%s %02d:00–%02d:59",
                        DAY_LABELS[day], hour, hour);
                toggle.setTooltipText(hourDescription);
                AccessibilitySupport.label(toggle, hourDescription);
                int selectedDay = day;
                int selectedHour = hour;
                toggle.onToggled(() -> {
                    if (!loadingSchedulerGrid) {
                        schedulerGridEdited = true;
                        showSchedulerSelection(selectedDay, selectedHour, toggle.getActive());
                    }
                });
                grid.attach(toggle, hour + 1, day + 1, 1, 1);
                schedulerToggles[day][hour] = toggle;
            }
        }
        gridBox.append(grid);

        CheckButton enableCheck = check("enable_scheduling_check");
        enableCheck.onToggled(() -> gridBox.setSensitive(enableCheck.getActive()));
    }

    private static synchronized void installSchedulerCss() {
        if (schedulerCssProvider != null) {
            return;
        }
        Display display = Display.getDefault();
        if (display == null) {
            return;
        }
        CssProvider provider = new CssProvider();
        provider.loadFromString(SCHEDULER_CSS);
        Gtk.styleContextAddProviderForDisplay(display, provider,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        schedulerCssProvider = provider;
    }

    private void showSchedulerSelection(int day, int hour, boolean active) {
        schedulerSelectionLabel.setLabel(String.format("%s %02d:00–%02d:59 — %s",
                DAY_LABELS[day], hour, hour,
                active ? "downloads allowed" : "downloads paused"));
    }

    public void present() {
        dialog.present();
        spin("max_concurrent_downloads_spin").grabFocus();
    }

    /** Current status label text (test seam for save-outcome reporting). */
    String statusText() {
        return statusLabel.getLabel();
    }

    int schedulerCellCount() {
        return schedulerToggles.length * schedulerToggles[0].length;
    }

    void setSchedulerCellActive(int day, int hour, boolean active) {
        schedulerToggles[day][hour].setActive(active);
    }

    boolean schedulerCellActive(int day, int hour) {
        return schedulerToggles[day][hour].getActive();
    }

    String schedulerSelectionText() {
        return schedulerSelectionLabel.getLabel();
    }

    String availableSpaceText() {
        return availableSpaceLabel.getLabel();
    }

    static java.util.Set<String> documentedSettingIds() {
        return SETTING_TOOLTIPS.keySet();
    }

    String settingTooltip(String id) {
        return Widgets.require(builder, id, Widget.class).getTooltipText();
    }

    void setHistoryCleanupEnabled(boolean enabled) {
        check("automatic_cleanup_check").setActive(enabled);
    }

    boolean overrideOutputPath() {
        return check("override_output_path_check").getActive();
    }

    void setOverrideOutputPath(boolean override) {
        check("override_output_path_check").setActive(override);
    }

    boolean retainCompletedAndCanceledHistory() {
        return check("retain_completed_canceled_history_check").getActive();
    }

    void setRetainCompletedAndCanceledHistory(boolean retain) {
        check("retain_completed_canceled_history_check").setActive(retain);
    }

    boolean historyCleanupControlsSensitive() {
        return Widgets.require(builder, "history_cleanup_controls_grid",
                org.gnome.gtk.Grid.class).getSensitive();
    }

    void setHistoryCleanupValues(int intervalHours, int maximumRecords,
            int maximumCompletedRecords, int completedRetentionDays,
            int errorRetentionDays) {
        spin("cleanup_interval_spin").setValue(intervalHours);
        spin("max_history_records_spin").setValue(maximumRecords);
        spin("max_completed_records_spin").setValue(maximumCompletedRecords);
        spin("completed_retention_spin").setValue(completedRetentionDays);
        spin("error_retention_spin").setValue(errorRetentionDays);
    }

    int maximumImportUrls() {
        return (int) spin("max_import_urls_spin").getValue();
    }

    int maximumImportSourceSizeMiB() {
        return (int) spin("max_import_source_size_spin").getValue();
    }

    void setImportLimits(int maximumUrls, int maximumSourceSizeMiB) {
        spin("max_import_urls_spin").setValue(maximumUrls);
        spin("max_import_source_size_spin").setValue(maximumSourceSizeMiB);
    }

    void setNetworkDefaults(DownloadSettingsFactory.NetworkDefaults network) {
        spin("max_connections_spin").setValue(network.maxConnections());
        spin("retry_limit_spin").setValue(network.maxRetries());
        spin("max_download_speed_spin").setValue(network.downloadLimitKb());
        spin("max_upload_speed_spin").setValue(network.uploadLimitKb());
        spin("retry_after").setValue(network.retryDelaySeconds());
        entry("referer_entry").setText(network.referer());
        entry("cookie_entry").setText(network.cookie());
        entry("user_agent_entry").setText(network.userAgent());
    }

    void setYtDlpOutputDefaults(boolean saveThumbnail, boolean embedThumbnail) {
        check("write_thumbnail_check").setActive(saveThumbnail);
        check("embed_thumbnail_check").setActive(embedThumbnail);
    }

    DownloadSettingsFactory.NetworkDefaults networkDefaultsFromControls() {
        return new DownloadSettingsFactory.NetworkDefaults(
                (int) spin("max_connections_spin").getValue(),
                (int) spin("retry_limit_spin").getValue(),
                (int) spin("max_download_speed_spin").getValue(),
                (int) spin("max_upload_speed_spin").getValue(),
                (int) spin("retry_after").getValue(),
                entry("referer_entry").getText(),
                entry("user_agent_entry").getText(),
                entry("cookie_entry").getText());
    }

    // ---- widget helpers ----

    /** Fills the scheduler grid toggles from a persisted hex grid. */
    private void loadSchedulerGrid(String hex) {
        boolean[][] grid = org.manager.schedule.WeeklySchedule.hourGridFromString(hex);
        loadingSchedulerGrid = true;
        try {
            for (int day = 0; day < 7; day++) {
                for (int hour = 0; hour < 24; hour++) {
                    schedulerToggles[day][hour].setActive(grid[day][hour]);
                }
            }
        } finally {
            loadingSchedulerGrid = false;
            schedulerGridEdited = false;
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
     * downloads are prevented, uGet semantics). An enabled, empty grid is
     * therefore the Never preset; disabling scheduling installs alwaysActive
     * so no scheduler restriction remains.
     */
    private void applySchedulerRuntime(boolean enabled, boolean[][] hourGrid) {
        if (scheduleManager == null) {
            return;
        }
        try {
            if (enabled) {
                scheduleManager.start();
                scheduleManager.setGlobalHourGrid(hourGrid);
                LOGGER.info("Applied scheduler hour grid (STRICT)");
            } else {
                scheduleManager.getScheduler()
                        .setGlobalSchedule(org.manager.schedule.ScheduleSettings.alwaysActive());
                LOGGER.info("Scheduling disabled; downloads unrestricted");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to apply scheduler settings", e);
        }
    }

    private void torSwitchSet(boolean active) {
        Widgets.require(builder, "tor_switch", Switch.class).setActive(active);
    }

    private void refreshTorMonitorControls() {
        Widgets.require(builder, "tor_check_interval_box", org.gnome.gtk.Box.class)
                .setSensitive(torAvailable && Widgets.require(builder,
                        "tor_circuit_monitor_switch", Switch.class).getActive());
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

    private <E extends Enum<E>> E selectedEnum(String id, E[] values, E fallback) {
        long selected = Widgets.require(builder, id, DropDown.class).getSelected();
        return selected >= 0 && selected < values.length ? values[(int) selected] : fallback;
    }

    private static double settingDouble(GlobalSettings settings, String key,
            double fallback) {
        try {
            double value = Double.parseDouble(settings.getProperty(key,
                    Double.toString(fallback)));
            return Double.isFinite(value) && value >= 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private void configureSettingTooltips() {
        SETTING_TOOLTIPS.forEach((id, help) ->
                Widgets.require(builder, id, Widget.class).setTooltipText(help));
    }

    private void updateSettingTooltipWithCurrentValue(String id, String currentValue) {
        String help = SETTING_TOOLTIPS.get(id);
        if (help == null) {
            throw new IllegalArgumentException("No settings help registered for " + id);
        }
        String tooltip = currentValue == null || currentValue.isBlank()
                ? help
                : help + "\nCurrent: " + currentValue;
        Widgets.require(builder, id, Widget.class).setTooltipText(tooltip);
    }

    /**
     * Discovers and performs each scanner's real basic validation away from
     * the GTK thread. Automatic selection and the custom-command option are
     * always retained.
     */
    static java.util.concurrent.CompletableFuture<java.util.List<AntivirusChoice>>
            discoverAvailableAntiviruses(ToolManagerFactory factory) {
        if (factory == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    antivirusChoicesWithAutomatic(java.util.List.of()));
        }
        java.util.List<java.util.concurrent.CompletableFuture<AntivirusChoice>> checks =
                factory.getAntivirusManagers().stream()
                        .map(manager -> manager.checkAvailabilityAsync().thenApply(available -> {
                            if (!available) {
                                return null;
                            }
                            try {
                                manager.validateTool();
                                String version = manager.getVersion();
                                String label = manager.getScanner().label()
                                        + (version == null || version.isBlank()
                                                ? "" : " (" + version + ")");
                                return new AntivirusChoice(
                                        manager.getScanner().key(), label);
                            } catch (org.manager.tools.ToolManager.ToolException e) {
                                LOGGER.debug(manager.getScanner().label()
                                        + " was found but failed validation", e);
                                return null;
                            }
                        }).exceptionally(error -> {
                            LOGGER.debug("Antivirus discovery failed for "
                                    + manager.getScanner().label(), error);
                            return null;
                        }))
                        .toList();
        return java.util.concurrent.CompletableFuture
                .allOf(checks.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(ignored -> {
                    java.util.List<AntivirusChoice> validated = new java.util.ArrayList<>();
                    checks.stream().map(java.util.concurrent.CompletableFuture::join)
                            .filter(java.util.Objects::nonNull)
                            .forEach(validated::add);
                    return antivirusChoicesWithAutomatic(validated);
                });
    }

    private static java.util.List<AntivirusChoice> antivirusChoicesWithAutomatic(
            java.util.List<AntivirusChoice> validated) {
        java.util.List<AntivirusChoice> choices = new java.util.ArrayList<>();
        boolean available = validated != null && !validated.isEmpty();
        choices.add(new AntivirusChoice(ANTIVIRUS_AUTO,
                available ? "Automatic (first available)"
                        : "Automatic (no scanner available)"));
        if (available) {
            choices.addAll(validated);
        }
        choices.add(new AntivirusChoice("custom", "Custom command"));
        return java.util.List.copyOf(choices);
    }

    private void discoverAvailableAntiviruses() {
        final ToolManagerFactory factory;
        try {
            factory = org.manager.ApplicationContext.getToolManagerFactory();
        } catch (Exception e) {
            LOGGER.warn("Could not access antivirus tool managers", e);
            applyAntivirusChoices(antivirusChoicesWithAutomatic(java.util.List.of()), true);
            return;
        }
        discoverAvailableAntiviruses(factory).whenComplete((choices, error) ->
                UiThread.marshal(() -> {
                    if (closed.get()) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.warn("Could not discover antivirus scanners", error);
                        applyAntivirusChoices(
                                antivirusChoicesWithAutomatic(java.util.List.of()), true);
                    } else {
                        applyAntivirusChoices(choices, false);
                    }
                }));
    }

    private void applyAntivirusChoices(java.util.List<AntivirusChoice> choices,
            boolean discoveryFailed) {
        antivirusChoices = choices == null || choices.isEmpty()
                ? antivirusChoicesWithAutomatic(java.util.List.of())
                : java.util.List.copyOf(choices);
        StringList model = new StringList(new String[0]);
        antivirusChoices.forEach(choice -> model.append(choice.label()));
        DropDown dropdown = Widgets.require(builder, "antivirus_type_combo", DropDown.class);
        dropdown.setModel(model);
        dropdown.setSensitive(true);

        int requestedIndex = -1;
        for (int i = 0; i < antivirusChoices.size(); i++) {
            if (antivirusChoices.get(i).key().equals(requestedAntivirusKey)) {
                requestedIndex = i;
                break;
            }
        }
        dropdown.setSelected(requestedIndex >= 0 ? requestedIndex : 0);
        updateAntivirusControlSensitivity();
        long validatedCount = antivirusChoices.stream()
                .filter(choice -> !"custom".equals(choice.key())
                        && !ANTIVIRUS_AUTO.equals(choice.key())).count();
        String message = discoveryFailed
                ? "Scanner discovery failed; custom command remains available."
                : validatedCount == 0
                        ? "No supported antivirus scanner was found and validated."
                        : "Validated " + validatedCount + " installed antivirus scanner"
                                + (validatedCount == 1 ? "." : "s.");
        if (requestedIndex < 0 && !"custom".equals(requestedAntivirusKey)) {
            message += " The configured scanner is unavailable.";
        }
        antivirusDetectionLabel.setLabel(message);
    }

    private void onPickFile(String buttonId, String title,
            java.util.function.Consumer<String> consumer) {
        Widgets.require(builder, buttonId, Button.class).onClicked(() -> {
            FileDialog fileDialog = new FileDialog();
            DialogSupport.configureIndependent(fileDialog);
            fileDialog.setTitle(title);
            fileDialog.open(dialog, null, result -> {
                try {
                    File file = fileDialog.openFinish(result);
                    if (file != null && file.getPath() != null) {
                        consumer.accept(file.getPath().toString());
                    }
                } catch (Exception e) {
                    LOGGER.debug(title + " selection cancelled or failed", e);
                }
            });
        });
    }

    private void bindFolderMonitoringChildren() {
        CheckButton enabled = check("folder_monitoring_check");
        CheckButton recursive = check("folder_recursive_check");
        CheckButton trashProcessed = check("move_to_trash_check");
        Runnable updateSensitivity = () -> {
            boolean active = enabled.getActive();
            recursive.setSensitive(active);
            trashProcessed.setSensitive(active);
        };
        enabled.onToggled(() -> updateSensitivity.run());
        updateSensitivity.run();
    }

    private void bindHistoryCleanupControls() {
        CheckButton enabled = check("automatic_cleanup_check");
        org.gnome.gtk.Grid controls = Widgets.require(builder,
                "history_cleanup_controls_grid", org.gnome.gtk.Grid.class);
        Runnable updateSensitivity = () -> controls.setSensitive(enabled.getActive());
        enabled.onToggled(updateSensitivity::run);
        updateSensitivity.run();
    }

    private void bindAntivirusControls() {
        Widgets.require(builder, "antivirus_type_combo", DropDown.class)
                .onNotify("selected", ignored -> updateAntivirusControlSensitivity());
        updateAntivirusControlSensitivity();
    }

    private void bindSeedingPolicyControls() {
        Widgets.require(builder, "seeding_policy_combo", DropDown.class)
                .onNotify("selected", ignored -> updateSeedingControlSensitivity());
        updateSeedingControlSensitivity();
    }

    private void updateSeedingControlSensitivity() {
        Aria2GlobalOptions.SeedingPolicy policy = selectedEnum(
                "seeding_policy_combo", Aria2GlobalOptions.SeedingPolicy.values(),
                Aria2GlobalOptions.SeedingPolicy.DISABLED);
        spin("seed_ratio_spin").setSensitive(
                policy == Aria2GlobalOptions.SeedingPolicy.RATIO
                || policy == Aria2GlobalOptions.SeedingPolicy.RATIO_OR_TIME);
        spin("seed_time_spin").setSensitive(
                policy == Aria2GlobalOptions.SeedingPolicy.TIME
                || policy == Aria2GlobalOptions.SeedingPolicy.RATIO_OR_TIME);
    }

    private void updateAntivirusControlSensitivity() {
        long selected = Widgets.require(builder, "antivirus_type_combo", DropDown.class)
                .getSelected();
        boolean customSelected = selected >= 0 && selected < antivirusChoices.size()
                && "custom".equals(antivirusChoices.get((int) selected).key());
        entry("antivirus_command_entry").setSensitive(customSelected);
    }

    // ---- load / apply ----

    private void setDefaultDir(String dir) {
        Path directory = Path.of(dir);
        defaultDirectoryChooser.setPath(directory);
        updateSettingTooltipWithCurrentValue(
                "default_download_folder_chooser", directory.toString());
        try {
            long free = directory.toFile().getUsableSpace();
            availableSpaceLabel.setLabel(DownloadFormats.size(free) + " free");
        } catch (RuntimeException invalidDirectory) {
            availableSpaceLabel.setLabel("");
        }
    }

    private void setMonitoredDir(String dir) {
        if (dir == null || dir.isBlank()) {
            monitoredDirectoryChooser.clear();
            updateSettingTooltipWithCurrentValue("monitored_folder_chooser", null);
        } else {
            Path directory = Path.of(dir);
            monitoredDirectoryChooser.setPath(directory);
            updateSettingTooltipWithCurrentValue(
                    "monitored_folder_chooser", directory.toString());
        }
    }

    private void load() {
        load(downloadManager.getGlobalSettings(), true);
    }

    /** Loads defaults for the focused tab; Apply/OK remains the commit point. */
    void resetToDefaults() {
        resetTabToDefaults(settingsNotebook.getCurrentPage());
    }

    void selectTab(int page) {
        settingsNotebook.setCurrentPage(page);
    }

    /** Package-private seam used by tests and future tab-specific entry points. */
    void resetTabToDefaults(int page) {
        GlobalSettings defaults = new GlobalSettings();
        String tabName = switch (page) {
            case 0 -> {
                loadGeneral(defaults, false);
                yield "General";
            }
            case 1 -> {
                loadNetwork(defaults);
                yield "Network";
            }
            case 2 -> {
                loadAria2(defaults);
                yield "Aria2";
            }
            case 3 -> {
                loadYtDlp(defaults);
                yield "Yt-dlp";
            }
            case 4 -> {
                loadHttrack(defaults);
                yield "HTTrack";
            }
            case 5 -> {
                jackett.reset();
                yield "Search Engine";
            }
            case 6 -> {
                loadAdvanced(defaults);
                yield "Advanced";
            }
            default -> throw new IllegalArgumentException("Unknown settings tab: " + page);
        };
        AccessibilitySupport.status(statusLabel,
                tabName + " defaults loaded. Press Apply or OK to save them.");
    }

    private void load(GlobalSettings s, boolean useRuntimeMonitoringState) {
        jackett.load(s);
        loadGeneral(s, useRuntimeMonitoringState);
        loadNetwork(s);
        loadAria2(s);
        loadYtDlp(s);
        loadHttrack(s);
        loadAdvanced(s);
    }

    private void loadGeneral(GlobalSettings s, boolean useRuntimeMonitoringState) {
        Path dir = s.getDefaultDownloadDirectory();
        setDefaultDir(dir != null ? dir.toString()
                : org.manager.util.OdmPaths.downloadDirectory().toString());
        spin("max_concurrent_downloads_spin").setValue(s.getMaxConcurrentDownloads());
        check("clipboard_monitor_check").setActive(useRuntimeMonitoringState
                ? downloadManager.isClipboardMonitoringEnabled()
                : s.getClipboardSettings() != null
                        && s.getClipboardSettings().isMonitoringEnabled());
        check("folder_monitoring_check").setActive(useRuntimeMonitoringState
                ? downloadManager.isTorrentFolderMonitoringEnabled()
                        || downloadManager.isMetaLinkFolderMonitoringEnabled()
                : s.getBooleanProperty("folder.monitorEnabled", false));
        check("system_tray_check").setActive(s.getBooleanProperty("ui.systemTray", false));
        check("start_automatically_check").setActive(s.getBooleanProperty("ui.startAutomatically", true));
        check("move_torrent_check").setActive(s.getBooleanProperty("ui.moveTorrent", false));
        check("startup_check").setActive(s.getBooleanProperty("ui.startAtLogin", false));
        check("clipboard_silent_check").setActive(s.getBooleanProperty("ui.clipboardSilent", false));
        check("folder_recursive_check").setActive(s.getBooleanProperty("ui.folderRecursive", false));
        check("move_to_trash_check").setActive(s.getBooleanProperty("ui.moveToTrash", false));
        // Restore the persisted monitored folder (empty until first configured)
        String monitoredDir = s.getProperty("folder.monitorPath", "");
        setMonitoredDir(monitoredDir);
        check("enable_auto_save_check").setActive(s.isOdmAutoSaveEnabled());
    }

    private void loadNetwork(GlobalSettings s) {
        torSwitchSet(s.getBooleanProperty("tor.enabled", false));
        setNetworkDefaults(DownloadSettingsFactory.NetworkDefaults.from(s));
        DialogOptions.ProxyFields proxy = DialogOptions.parseProxy(
                DialogOptions.manualProxyAddress(s));
        Widgets.require(builder, "proxy_type_combo", DropDown.class).setSelected(proxy.typeIndex());
        entry("proxy_host_entry").setText(proxy.host());
        spin("proxy_port_spin").setValue(proxy.port());
        entry("proxy_username_entry").setText(proxy.username());
        entry("proxy_password_entry").setText(proxy.password());
    }

    private void loadAria2(GlobalSettings s) {
        entry("aria2_path_entry").setText(s.getAria2Path() != null ? s.getAria2Path() : "");
        spin("min_split_size_spin1").setValue(s.getIntProperty("aria2.minSplitSizeMb",
                org.manager.download.DownloadSettingsFactory.DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB));
        spin("max_peers_spin").setValue(s.getIntProperty("aria2.maxPeers",
                org.manager.download.DownloadSettingsFactory.DEFAULT_ARIA2_MAX_PEERS));
        spin("peer_speed_limit_spin").setValue(s.getIntProperty("aria2.peerSpeedLimitKb",
                org.manager.download.DownloadSettingsFactory.DEFAULT_ARIA2_PEER_SPEED_LIMIT_KB));
        Aria2GlobalOptions.SeedingPolicy seedingPolicy =
                Aria2GlobalOptions.seedingPolicy(s);
        Widgets.require(builder, "seeding_policy_combo", DropDown.class)
                .setSelected(seedingPolicy.ordinal());
        spin("seed_ratio_spin").setValue(Aria2GlobalOptions.seedRatio(s));
        spin("seed_time_spin").setValue(Aria2GlobalOptions.seedTimeMinutes(s));
        entry("torrent_listen_ports_entry").setText(
                Aria2GlobalOptions.configuredListenPorts(s));
        Widgets.require(builder, "ipv6_dht_combo", DropDown.class)
                .setSelected(Aria2GlobalOptions.ipv6Dht(s).ordinal());
        Widgets.require(builder, "peer_exchange_combo", DropDown.class)
                .setSelected(Aria2GlobalOptions.peerExchange(s).ordinal());
        Widgets.require(builder, "local_peer_discovery_combo", DropDown.class)
                .setSelected(Aria2GlobalOptions.localPeerDiscovery(s).ordinal());
        Widgets.require(builder, "torrent_encryption_combo", DropDown.class)
                .setSelected(Aria2GlobalOptions.encryptionPolicy(s).ordinal());
        check("continue_download_check").setActive(s.getBooleanProperty("aria2.continueDownload", true));
        check("check_integrity_check").setActive(s.getBooleanProperty("aria2.checkIntegrity", false));
        check("remote_time_check").setActive(s.getBooleanProperty("aria2.remoteTime", false));
        spin("aria2_rpc_port_spin").setValue(s.getAria2RpcPort());
        check("honor_external_aria2_config_check").setActive(
                s.isHonorExternalAria2Configuration());
        String fileAllocation = s.getProperty("aria2.fileAllocation", "prealloc");
        int allocationIndex = java.util.Arrays.asList(FILE_ALLOCATIONS).indexOf(fileAllocation);
        Widgets.require(builder, "file_allocation_combo", DropDown.class)
                .setSelected(Math.max(0, allocationIndex));
        entry("tracker_list_entry").setText(s.getProperty("tracker.list", ""));
        spin("tracker_refresh_spin").setValue(s.getIntProperty("tracker.refreshInterval", 0));
        updateSeedingControlSensitivity();
    }

    private void loadYtDlp(GlobalSettings s) {
        entry("ytdlp_path_entry").setText(s.getYtDlpPath() != null ? s.getYtDlpPath() : "");
        check("write_thumbnail_check").setActive(s.getBooleanProperty("ytdlp.writeThumbnail", false));
        check("embed_thumbnail_check").setActive(s.getBooleanProperty("ytdlp.embedThumbnail", false));
        check("embed_metadata_check").setActive(s.getBooleanProperty("ytdlp.embedMetadata", false));
        check("use_aria2_external_check").setActive(s.getBooleanProperty("ytdlp.useAria2External", false));
        check("skip_downloaded_media_check").setActive(s.getBooleanProperty("ytdlp.skipDownloaded", true));
        check("honor_external_ytdlp_config_check").setActive(
                s.isHonorExternalYtDlpConfiguration());
    }

    private void loadHttrack(GlobalSettings s) {
        entry("httrack_path_entry").setText(s.getHttrackPath() != null ? s.getHttrackPath() : "");
        spin("httrack_max_total_size_spin").setValue(s.getIntProperty(
                "httrack.maxTotalSizeMb",
                DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_TOTAL_SIZE_MB));
        spin("httrack_max_non_html_size_spin").setValue(s.getIntProperty(
                "httrack.maxNonHtmlFileSizeMb",
                DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_NON_HTML_FILE_SIZE_MB));
        spin("httrack_max_html_size_spin").setValue(s.getIntProperty(
                "httrack.maxHtmlFileSizeMb",
                DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_HTML_FILE_SIZE_MB));
        spin("httrack_max_duration_spin").setValue(s.getIntProperty(
                "httrack.maxDurationMinutes",
                DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_DURATION_MINUTES));
        spin("httrack_max_links_spin").setValue(s.getIntProperty(
                "httrack.maxLinks", DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_LINKS));
        spin("httrack_connections_per_second_spin").setValue(settingDouble(s,
                "httrack.connectionsPerSecond",
                DownloadSettingsFactory.DEFAULT_HTTRACK_CONNECTIONS_PER_SECOND));
        spin("httrack_delay_between_files_spin").setValue(s.getIntProperty(
                "httrack.delayBetweenFilesSeconds",
                DownloadSettingsFactory.DEFAULT_HTTRACK_DELAY_BETWEEN_FILES_SECONDS));
    }

    private void loadAdvanced(GlobalSettings s) {
        check("override_output_path_check").setActive(s.isOverrideOutputPath());
        check("retain_completed_canceled_history_check").setActive(
                s.isRetainCompletedAndCanceledHistory());
        check("automatic_cleanup_check").setActive(s.isAutomaticCleanupEnabled());
        spin("cleanup_interval_spin").setValue(s.getCleanupIntervalHours());
        spin("max_history_records_spin").setValue(s.getMaxDownloadsInMemory());
        spin("max_completed_records_spin").setValue(s.getMaxCompletedDownloadsToKeep());
        spin("completed_retention_spin").setValue(s.getCompletedDownloadRetentionDays());
        spin("error_retention_spin").setValue(s.getErrorDownloadRetentionDays());
        ImportLimits importLimits = ImportLimits.from(s);
        spin("max_import_urls_spin").setValue(importLimits.maxUrls());
        spin("max_import_source_size_spin").setValue(importLimits.maxSourceSizeMiB());
        boolean schedulingEnabled = s.getBooleanProperty("scheduler.enabled", false);
        check("enable_scheduling_check").setActive(schedulingEnabled);
        Widgets.require(builder, "scheduler_grid_box", org.gnome.gtk.Box.class)
                .setSensitive(schedulingEnabled);
        String persistedGrid = s.getProperty("scheduler.grid", "");
        if (persistedGrid.isBlank()) {
            String preset = s.getProperty("scheduler.preset", "always");
            if (!"none".equalsIgnoreCase(preset)) {
                try {
                    persistedGrid = org.manager.schedule.WeeklySchedule.hourGridToString(
                            org.manager.schedule.ScheduleManager.hourGridForPreset(preset));
                } catch (IllegalArgumentException invalidPreset) {
                    LOGGER.warn("Ignoring unknown scheduler preset " + preset, invalidPreset);
                }
            }
        }
        loadSchedulerGrid(persistedGrid);
        entry("proxychains_path_entry").setText(s.getProxychainsPath() != null ? s.getProxychainsPath() : "");
        entry("tor_path_entry").setText(s.getTorPath() != null ? s.getTorPath() : "");
        Widgets.require(builder, "tor_circuit_monitor_switch", Switch.class)
                .setActive(s.isTorCircuitMonitorEnabled());
        spin("tor_check_interval_spin").setValue(s.getTorCheckIntervalMinutes());
        entry("curl_path_entry").setText(s.getCurlPath() != null ? s.getCurlPath() : "");
        entry("subliminal_path_entry").setText(
                s.getSubliminalPath() != null ? s.getSubliminalPath() : "");
        entry("antivirus_command_entry").setText(
                s.getProperty("antivirus.command", ""));
        spin("antivirus_timeout_spin").setValue(
                Math.max(0, s.getIntProperty("antivirus.timeout", 600)));
        requestedAntivirusKey = s.getProperty("antivirus.scanner", ANTIVIRUS_AUTO)
                .toLowerCase(java.util.Locale.ROOT);
        if (!antivirusChoices.isEmpty()) {
            applyAntivirusChoices(antivirusChoices, false);
        }
        updateAntivirusControlSensitivity();
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
            AccessibilitySupport.status(statusLabel, UiErrors.message(invalidSetting),
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
                    if (succeeded) {
                        applyTorPreference(application);
                    }
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
        if (saved) {
            applyTorPreference(application);
        }
        reportSaveOutcome(saved);
    }

    private SettingsApplication collectSettings() {
        GlobalSettings s = downloadManager.getGlobalSettings().copy();
        jackett.collect(s);
        boolean previousStartAtLogin = s.getBooleanProperty("ui.startAtLogin", false);
        // tor proxy default
        s.setProperty("tor.enabled", String.valueOf(torSwitchGet()));
        // General
        Path defaultDirectory = defaultDirectoryChooser.getPath();
        if (defaultDirectory == null) {
            throw new IllegalArgumentException("Select a default download folder");
        }
        s.setDefaultDownloadDirectory(defaultDirectory);
        s.setMaxConcurrentDownloads((int) spin("max_concurrent_downloads_spin").getValue());
        s.setRetainCompletedAndCanceledHistory(
                check("retain_completed_canceled_history_check").getActive());
        s.setProperty("ui.systemTray", String.valueOf(check("system_tray_check").getActive()));
        s.setProperty("ui.startAutomatically", String.valueOf(check("start_automatically_check").getActive()));
        s.setProperty("ui.moveTorrent", String.valueOf(check("move_torrent_check").getActive()));
        s.setProperty("ui.startAtLogin", String.valueOf(check("startup_check").getActive()));
        boolean clipboardSilent = check("clipboard_silent_check").getActive();
        s.setProperty("ui.clipboardSilent", String.valueOf(clipboardSilent));
        s.setProperty("ui.folderRecursive", String.valueOf(check("folder_recursive_check").getActive()));
        s.setProperty("ui.moveToTrash", String.valueOf(check("move_to_trash_check").getActive()));
        boolean clipboardMonitoring = check("clipboard_monitor_check").getActive();
        org.manager.clipboard.ClipboardSettings clipboardSettings =
                s.getClipboardSettings() == null
                        ? new org.manager.clipboard.ClipboardSettings()
                        : s.getClipboardSettings().copy();
        s.setClipboardSettings(clipboardSettings
                .setMonitoringEnabled(clipboardMonitoring)
                .setSilentMode(clipboardSilent));
        boolean folderMonitoring = check("folder_monitoring_check").getActive();
        // Persist the monitored folder so monitoring survives restarts.
        Path monitoredDirectory = monitoredDirectoryChooser.getPath();
        String monitoredDir = monitoredDirectory == null ? "" : monitoredDirectory.toString();
        boolean hasMonitoredDir = !monitoredDir.isBlank();
        s.setProperty("folder.monitorPath", hasMonitoredDir ? monitoredDir : "");
        s.setProperty("folder.monitorEnabled", String.valueOf(folderMonitoring && hasMonitoredDir));
        boolean effectiveFolderMonitoring = folderMonitoring && hasMonitoredDir;
        // Network
        networkDefaultsFromControls().saveTo(s);
        boolean torEnabled = torSwitchGet();
        String manualProxyAddress = DialogOptions.buildProxyAddress(
                (int) Widgets.require(builder, "proxy_type_combo", DropDown.class).getSelected(),
                entry("proxy_host_entry").getText(),
                (int) spin("proxy_port_spin").getValue(),
                entry("proxy_username_entry").getText(),
                entry("proxy_password_entry").getText());
        DialogOptions.rememberManualProxy(s, manualProxyAddress);
        String proxyAddress = torEnabled
                ? DialogOptions.managedTorProxyAddress(
                        DialogOptions.torSocksPort(torService))
                : manualProxyAddress;
        s.setGlobalProxyEnabled(proxyAddress != null);
        s.setGlobalProxyAddress(proxyAddress);
        // Aria2
        s.setAria2Path(entry("aria2_path_entry").getText().trim());
        s.setProperty("aria2.minSplitSizeMb", String.valueOf((int) spin("min_split_size_spin1").getValue()));
        s.setProperty("aria2.maxPeers", String.valueOf((int) spin("max_peers_spin").getValue()));
        s.setProperty("aria2.peerSpeedLimitKb", String.valueOf((int) spin("peer_speed_limit_spin").getValue()));
        Aria2GlobalOptions.SeedingPolicy seedingPolicy = selectedEnum(
                "seeding_policy_combo", Aria2GlobalOptions.SeedingPolicy.values(),
                Aria2GlobalOptions.SeedingPolicy.DISABLED);
        s.setProperty(Aria2GlobalOptions.SEEDING_POLICY_KEY,
                seedingPolicy.settingValue());
        s.setProperty(Aria2GlobalOptions.SEED_RATIO_KEY,
                Double.toString(spin("seed_ratio_spin").getValue()));
        s.setProperty(Aria2GlobalOptions.SEED_TIME_KEY,
                Integer.toString((int) spin("seed_time_spin").getValue()));
        s.setProperty(Aria2GlobalOptions.LISTEN_PORTS_KEY,
                Aria2GlobalOptions.normalizeListenPorts(
                        entry("torrent_listen_ports_entry").getText()));
        s.setProperty(Aria2GlobalOptions.IPV6_DHT_KEY, selectedEnum(
                "ipv6_dht_combo", Aria2GlobalOptions.ToggleOverride.values(),
                Aria2GlobalOptions.ToggleOverride.ENGINE_DEFAULT).settingValue());
        s.setProperty(Aria2GlobalOptions.PEER_EXCHANGE_KEY, selectedEnum(
                "peer_exchange_combo", Aria2GlobalOptions.ToggleOverride.values(),
                Aria2GlobalOptions.ToggleOverride.ENGINE_DEFAULT).settingValue());
        s.setProperty(Aria2GlobalOptions.LOCAL_PEER_DISCOVERY_KEY, selectedEnum(
                "local_peer_discovery_combo", Aria2GlobalOptions.ToggleOverride.values(),
                Aria2GlobalOptions.ToggleOverride.ENGINE_DEFAULT).settingValue());
        s.setProperty(Aria2GlobalOptions.ENCRYPTION_POLICY_KEY, selectedEnum(
                "torrent_encryption_combo", Aria2GlobalOptions.EncryptionPolicy.values(),
                Aria2GlobalOptions.EncryptionPolicy.ENGINE_DEFAULT).settingValue());
        s.setProperty("aria2.continueDownload", String.valueOf(check("continue_download_check").getActive()));
        s.setProperty("aria2.checkIntegrity", String.valueOf(check("check_integrity_check").getActive()));
        s.setProperty("aria2.remoteTime", String.valueOf(check("remote_time_check").getActive()));
        s.setAria2RpcPort((int) spin("aria2_rpc_port_spin").getValue());
        s.setHonorExternalAria2Configuration(
                check("honor_external_aria2_config_check").getActive());
        long allocationIndex = Widgets.require(builder, "file_allocation_combo", DropDown.class).getSelected();
        if (allocationIndex >= 0 && allocationIndex < FILE_ALLOCATIONS.length) {
            s.setProperty("aria2.fileAllocation", FILE_ALLOCATIONS[(int) allocationIndex]);
        }
        s.setOdmAutoSaveEnabled(check("enable_auto_save_check").getActive());
        s.setProperty("tracker.list", entry("tracker_list_entry").getText().trim());
        s.setProperty("tracker.refreshInterval",
                String.valueOf((int) spin("tracker_refresh_spin").getValue()));
        // Yt-dlp
        s.setYtDlpPath(entry("ytdlp_path_entry").getText().trim());
        s.setProperty("ytdlp.writeThumbnail", String.valueOf(check("write_thumbnail_check").getActive()));
        s.setProperty("ytdlp.embedThumbnail", String.valueOf(check("embed_thumbnail_check").getActive()));
        s.setProperty("ytdlp.embedMetadata", String.valueOf(check("embed_metadata_check").getActive()));
        s.setProperty("ytdlp.useAria2External", String.valueOf(check("use_aria2_external_check").getActive()));
        s.setProperty("ytdlp.skipDownloaded", String.valueOf(check("skip_downloaded_media_check").getActive()));
        s.setHonorExternalYtDlpConfiguration(
                check("honor_external_ytdlp_config_check").getActive());
        // HTTrack
        s.setHttrackPath(entry("httrack_path_entry").getText().trim());
        s.setProperty("httrack.maxTotalSizeMb", String.valueOf(
                (int) spin("httrack_max_total_size_spin").getValue()));
        s.setProperty("httrack.maxNonHtmlFileSizeMb", String.valueOf(
                (int) spin("httrack_max_non_html_size_spin").getValue()));
        s.setProperty("httrack.maxHtmlFileSizeMb", String.valueOf(
                (int) spin("httrack_max_html_size_spin").getValue()));
        s.setProperty("httrack.maxDurationMinutes", String.valueOf(
                (int) spin("httrack_max_duration_spin").getValue()));
        s.setProperty("httrack.maxLinks", String.valueOf(
                (int) spin("httrack_max_links_spin").getValue()));
        s.setProperty("httrack.connectionsPerSecond", Double.toString(
                spin("httrack_connections_per_second_spin").getValue()));
        s.setProperty("httrack.delayBetweenFilesSeconds", String.valueOf(
                (int) spin("httrack_delay_between_files_spin").getValue()));
        // Advanced
        s.setOverrideOutputPath(check("override_output_path_check").getActive());
        s.setAutomaticCleanupEnabled(check("automatic_cleanup_check").getActive());
        s.setCleanupIntervalHours((long) spin("cleanup_interval_spin").getValue());
        s.setMaxDownloadsInMemory((int) spin("max_history_records_spin").getValue());
        s.setMaxCompletedDownloadsToKeep((int) spin("max_completed_records_spin").getValue());
        s.setCompletedDownloadRetentionDays((long) spin("completed_retention_spin").getValue());
        s.setErrorDownloadRetentionDays((long) spin("error_retention_spin").getValue());
        new ImportLimits((int) spin("max_import_urls_spin").getValue(),
                (int) spin("max_import_source_size_spin").getValue()).applyTo(s);
        boolean schedulingEnabled = check("enable_scheduling_check").getActive();
        boolean[][] hourGrid = readSchedulerGrid();
        s.setProperty("scheduler.enabled", String.valueOf(schedulingEnabled));
        s.setProperty("scheduler.grid",
                org.manager.schedule.WeeklySchedule.hourGridToString(hourGrid));
        if (schedulerGridEdited) {
            s.setProperty("scheduler.preset", "none");
        }
        s.setProxychainsPath(entry("proxychains_path_entry").getText().trim());
        s.setTorPath(entry("tor_path_entry").getText().trim());
        s.setTorCircuitMonitorEnabled(Widgets.require(builder,
                "tor_circuit_monitor_switch", Switch.class).getActive());
        s.setTorCheckIntervalMinutes((int) spin("tor_check_interval_spin").getValue());
        s.setCurlPath(entry("curl_path_entry").getText().trim());
        s.setSubliminalPath(entry("subliminal_path_entry").getText().trim());
        long antivirusIndex = Widgets.require(builder, "antivirus_type_combo", DropDown.class)
                .getSelected();
        if (antivirusIndex >= 0 && antivirusIndex < antivirusChoices.size()) {
            String scanner = antivirusChoices.get((int) antivirusIndex).key();
            String customCommand = entry("antivirus_command_entry").getText().trim();
            if ("custom".equals(scanner)) {
                if (customCommand.isBlank()) {
                    throw new IllegalArgumentException(
                            "Enter a command for the Custom antivirus scanner");
                }
                if (!customCommand.contains("{file}")) {
                    throw new IllegalArgumentException(
                            "The Custom antivirus command must include {file}");
                }
            }
            s.setProperty("antivirus.scanner", scanner);
            s.setProperty("antivirus.command", customCommand);
            s.setProperty("antivirus.timeout",
                    String.valueOf((int) spin("antivirus_timeout_spin").getValue()));
        }

        return new SettingsApplication(s, previousStartAtLogin,
                check("startup_check").getActive(), schedulingEnabled,
                hourGrid, torEnabled, clipboardMonitoring, clipboardSilent,
                effectiveFolderMonitoring);
    }

    /** Performs filesystem and core/service work away from the GTK thread. */
    private boolean persistSettings(SettingsApplication application) {
        GlobalSettings s = application.settings();
        boolean autostartChanged = application.requestedStartAtLogin()
                != application.previousStartAtLogin();
        if (autostartChanged) {
            try {
                AutostartManager.setEnabled(application.requestedStartAtLogin());
            } catch (java.io.IOException e) {
                LOGGER.warn("Failed to update the login autostart entry", e);
                return false;
            }
        }
        boolean settingsSaved = s.save();
        if (!settingsSaved && autostartChanged) {
            try {
                AutostartManager.setEnabled(application.previousStartAtLogin());
            } catch (java.io.IOException rollbackFailure) {
                LOGGER.warn("Failed to roll back the login autostart entry", rollbackFailure);
            }
        }
        if (!settingsSaved) {
            return false;
        }

        // Only a durably saved snapshot may change live services. Previously
        // a failed write still changed manager, scheduler, monitoring and Tor
        // state until restart while the dialog correctly reported failure.
        downloadManager.setGlobalSettings(s);
        jackett.networkSettingsChanged();
        applySchedulerRuntime(application.schedulingEnabled(), application.hourGrid());
        applyMonitoringPreferences(application);
        return true;
    }

    /** Applies monitoring only after the manager sees the newly collected
     * settings, so folder action/recursion changes cannot restart a watcher
     * against stale values. */
    private void applyMonitoringPreferences(SettingsApplication application) {
        try {
            downloadManager.updateClipboardSettings(
                    downloadManager.getClipboardService().getSettings()
                            .copy()
                            .setSilentMode(application.clipboardSilent()));
        } catch (Exception e) {
            LOGGER.debug("Clipboard settings sync skipped", e);
        }
        downloadManager.setClipboardMonitoringEnabled(application.clipboardMonitoring());
        downloadManager.setTorrentFolderMonitoringEnabled(application.folderMonitoring());
        downloadManager.setMetaLinkFolderMonitoringEnabled(application.folderMonitoring());
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
            LOGGER.error("Failed to save settings to " + GlobalSettings.getConfigFilePath());
        }
    }
}
