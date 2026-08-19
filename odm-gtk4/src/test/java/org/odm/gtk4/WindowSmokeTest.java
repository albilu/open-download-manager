package org.odm.gtk4;

import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TextView;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Builds every window's .ui through the strict Widgets.require path for all
 * ids the code uses. A missing or mistyped widget id fails this test — the
 * "25 dead settings widgets" class of bug cannot compile through here.
 * Requires a display (run under Xvfb, as the Docker test env does).
 */
class WindowSmokeTest {

    @BeforeAll
    static void initGtk() {
        Gtk.init();
    }

    @Test
    @DisplayName("main-window.ui parses and contains all 1:1 widgets")
    void mainWindow() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        Widgets.require(builder, "main_window", ApplicationWindow.class);
        // stores
        for (String id : new String[]{"status_store", "category_store", "download_store",
                "files_store", "global_progress_store", "peers_store", "trackers_store"}) {
            Widgets.require(builder, id, ListStore.class);
        }
        // side panel treeviews + columns
        Widgets.require(builder, "status_treeview", TreeView.class);
        Widgets.require(builder, "status_column", org.gnome.gtk.TreeViewColumn.class);
        Widgets.require(builder, "count_column", org.gnome.gtk.TreeViewColumn.class);
        Widgets.require(builder, "category_treeview", TreeView.class);
        Widgets.require(builder, "category_column", org.gnome.gtk.TreeViewColumn.class);
        Widgets.require(builder, "category_count_column", org.gnome.gtk.TreeViewColumn.class);
        // toolbar buttons
        for (String id : new String[]{"new_download_button", "pause_button", "resume_button",
                "delete_button", "move_up_button", "move_top_button", "move_down_button", "move_bottom_button",
                "settings_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        Widgets.require(builder, "menu_button", org.gnome.gtk.MenuButton.class);
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        // download treeview + columns
        Widgets.require(builder, "download_treeview", TreeView.class);
        for (String id : new String[]{"number_column", "name_column", "complete_column", "size_column",
                "percent_progress_column", "elapsed_column", "left_column", "speed_column", "up_speed_column",
                "retry_column", "start_date_column", "end_date_column", "tor_icon_column"}) {
            Widgets.require(builder, id, org.gnome.gtk.TreeViewColumn.class);
        }
        // info panel
        Widgets.require(builder, "info_notebook", org.gnome.gtk.Notebook.class);
        Widgets.require(builder, "info_progress_bar", ProgressBar.class);
        for (String id : new String[]{"total_size_value", "added_on_value", "info_hash_v1_value",
                "folder_value", "eta_value", "downloaded_value", "connections_value", "seeds_peers_value"}) {
            Widgets.require(builder, id, Label.class);
        }
        Widgets.require(builder, "trackers_view", TreeView.class);
        Widgets.require(builder, "peers_view", TreeView.class);
        Widgets.require(builder, "files_view", TreeView.class);
        // status bar
        Widgets.require(builder, "statusbar", org.gnome.gtk.Statusbar.class);
        for (String id : new String[]{"info_label", "up_speed_label", "down_speed_label",
                "dht_status_label"}) {
            Widgets.require(builder, id, Label.class);
        }
        Widgets.require(builder, "activity_spinner", Spinner.class);
    }

    @Test
    @DisplayName("settings.ui parses with 1:1 original ids (7 tabs)")
    void settings() {
        GtkBuilder builder = UiLoader.load("/ui/settings.ui");
        Widgets.require(builder, "settings_dialog", Window.class);
        Widgets.require(builder, "settings_notebook", org.gnome.gtk.Notebook.class);
        // General
        for (String id : new String[]{"max_concurrent_downloads_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"default_download_folder_chooser", "monitored_folder_chooser",
                "browse_aria2_button", "browse_ytdlp_button", "browse_httrack_button",
                "browse_proxychains_button", "browse_tor_button", "browse_axel_button",
                "settings_cancel_button", "settings_reset_button", "settings_apply_button", "settings_ok_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        for (String id : new String[]{"save_download_history_check", "clipboard_monitor_check",
                "clipboard_silent_check", "system_tray_check", "start_automatically_check",
                "move_torrent_check", "startup_check", "folder_monitoring_check", "folder_recursive_check",
                "move_to_trash_check", "start_automatically_check2", "move_torrent_check2",
                "continue_download_check", "check_integrity_check", "enable_auto_save_check",
                "enable_seeding_check", "write_thumbnail_check", "write_subtitles_check",
                "embed_metadata_check", "extract_audio_check", "use_aria2_external_check",
                "include_archives_check", "enable_scheduling_check"}) {
            Widgets.require(builder, id, CheckButton.class);
        }
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "min_split_size_spin1",
                "max_peers_spin", "peer_speed_limit_spin", "seed_time_spin", "depth_spin", "proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"aria2_path_entry", "ytdlp_path_entry", "httrack_path_entry",
                "referer_entry", "cookie_entry", "user_agent_entry", "proxy_host_entry",
                "proxy_username_entry", "proxy_password_entry", "video_format_entry",
                "subtitle_language_entry", "include_entry", "exclude_entry",
                "proxychains_path_entry", "tor_path_entry", "axel_path_entry"}) {
            Widgets.require(builder, id, Entry.class);
        }
        Widgets.require(builder, "proxy_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "file_allocation_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        Widgets.require(builder, "available_space_label", Label.class);
        Widgets.require(builder, "settings_status_label", Label.class);
    }

    @Test
    @DisplayName("new-download.ui parses with 1:1 original ids")
    void newDownload() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        Widgets.require(builder, "new_download_dialog", Window.class);
        Widgets.require(builder, "url_entry", Entry.class);
        Widgets.require(builder, "torrent_file_chooser", Button.class);
        Widgets.require(builder, "save_folder_chooser", Button.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "filename_entry", Entry.class);
        Widgets.require(builder, "files_treeview", TreeView.class);
        Widgets.require(builder, "files_liststore", ListStore.class);
        Widgets.require(builder, "max_connections_spin", SpinButton.class);
        Widgets.require(builder, "retry_limit_spin", SpinButton.class);
        Widgets.require(builder, "max_download_speed_spin", SpinButton.class);
        Widgets.require(builder, "max_upload_speed_spin", SpinButton.class);
        Widgets.require(builder, "retry_after", SpinButton.class);
        Widgets.require(builder, "referrer", Entry.class);
        Widgets.require(builder, "cookie", Entry.class);
        Widgets.require(builder, "user_agent", Entry.class);
        Widgets.require(builder, "proxy_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "proxy_host_entry", Entry.class);
        Widgets.require(builder, "proxy_port_spin", SpinButton.class);
        Widgets.require(builder, "proxy_username_entry", Entry.class);
        Widgets.require(builder, "proxy_password_entry", Entry.class);
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        Widgets.require(builder, "start_automatically_check", CheckButton.class);
        Widgets.require(builder, "move_torrent_check", CheckButton.class);
        Widgets.require(builder, "new_download_spinner", Spinner.class);
        Widgets.require(builder, "new_download_cancel_button", Button.class);
        Widgets.require(builder, "new_download_start_button", Button.class);
    }

    @Test
    @DisplayName("property.ui parses with 1:1 original ids")
    void property() {
        GtkBuilder builder = UiLoader.load("/ui/property.ui");
        Widgets.require(builder, "property_dialog", Window.class);
        Widgets.require(builder, "properties_notebook", org.gnome.gtk.Notebook.class);
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"referrer", "cookie", "user_agent", "proxy_host_entry",
                "proxy_username_entry", "proxy_password_entry"}) {
            Widgets.require(builder, id, Entry.class);
        }
        Widgets.require(builder, "proxy_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        Widgets.require(builder, "start_automatically_check", CheckButton.class);
        Widgets.require(builder, "move_torrent_check", CheckButton.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "apply_button", Button.class);
        Widgets.require(builder, "ok_button", Button.class);
    }

    @Test
    @DisplayName("about.ui parses")
    void about() {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        Widgets.require(builder, "about_dialog", org.gnome.gtk.AboutDialog.class);
    }

    @Test
    @DisplayName("start-shutdown.ui parses")
    void startShutdown() {
        GtkBuilder builder = UiLoader.load("/ui/start-shutdown.ui");
        Widgets.require(builder, "startup_shutdown_dialog", Window.class);
        Widgets.require(builder, "odm_logo_image", org.gnome.gtk.Image.class);
        Widgets.require(builder, "status_message_label", Label.class);
        Widgets.require(builder, "progress_bar", ProgressBar.class);
    }

    @Test
    @DisplayName("import-list.ui parses with 1:1 original ids")
    void importListStructure() {
        GtkBuilder builder = UiLoader.load("/ui/import-list.ui");
        Widgets.require(builder, "import_dialog", Window.class);
        Widgets.require(builder, "options_notebook", org.gnome.gtk.Notebook.class);
        Widgets.require(builder, "clipboard_page", org.gnome.gtk.Box.class);
        Widgets.require(builder, "filter_label", Label.class);
        Widgets.require(builder, "extension_filter_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "url_treeview", TreeView.class);
        Widgets.require(builder, "url_liststore", ListStore.class);
        Widgets.require(builder, "mark_renderer", org.gnome.gtk.CellRendererToggle.class);
        Widgets.require(builder, "folder_destination", Button.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_spinnet", Spinner.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        // Options tab ids
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
    }

    @Test
    @DisplayName("import-sequence.ui parses with 1:1 original ids")
    void importSequence() {
        GtkBuilder builder = UiLoader.load("/ui/import-sequence.ui");
        Widgets.require(builder, "import_sequence_dialog", Window.class);
        Widgets.require(builder, "uri_entry", Entry.class);
        Widgets.require(builder, "num_start_spin", SpinButton.class);
        Widgets.require(builder, "num_vers_spin", SpinButton.class);
        Widgets.require(builder, "num_count_spin", SpinButton.class);
        Widgets.require(builder, "char_entry", Entry.class);
        Widgets.require(builder, "char_vers_entry", Entry.class);
        Widgets.require(builder, "num_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "char_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "preview_treeview", TreeView.class);
        Widgets.require(builder, "preview_liststore", ListStore.class);
        Widgets.require(builder, "destination_folder", Button.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_sequence_spinner", Spinner.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
    }

    @Test
    @DisplayName("MainWindow constructs against its .ui (catches require-type mismatches)")
    void mainWindowConstructs() {
        org.manager.download.DownloadManager stub =
                (org.manager.download.DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                        org.manager.download.DownloadManager.class.getClassLoader(),
                        new Class<?>[]{org.manager.download.DownloadManager.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getGlobalSettings" -> new org.manager.GlobalSettings();
                            case "getAllDownloads" -> java.util.List.of();
                            case "getDownloads" -> java.util.List.of();
                            case "isClipboardMonitoringEnabled", "isTorrentFolderMonitoringEnabled",
                                    "isMetaLinkFolderMonitoringEnabled" -> false;
                            default -> defaultValue(method.getReturnType());
                        });
        MainWindow window = new MainWindow(null, stub, new org.tor.TorService("tor"));
        // Constructing is the test: every Widgets.require in the constructor
        // must resolve. (Null app: the window is a standalone toplevel here.)
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
}
