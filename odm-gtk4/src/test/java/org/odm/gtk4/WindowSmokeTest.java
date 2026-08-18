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
                "settings_button", "menu_button"}) {
            Widgets.require(builder, id, Button.class);
        }
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

    void settings() {
        GtkBuilder builder = UiLoader.load("/ui/settings.ui");
        Widgets.require(builder, "settings_dialog", Window.class);
        Widgets.require(builder, "max_concurrent_spin", SpinButton.class);
        Widgets.require(builder, "speed_limit_spin", SpinButton.class);
        Widgets.require(builder, "download_dir_label", Label.class);
        Widgets.require(builder, "download_dir_button", Button.class);
        Widgets.require(builder, "global_proxy_check", CheckButton.class);
        Widgets.require(builder, "global_proxy_entry", Entry.class);
        Widgets.require(builder, "proxy_rotation_check", CheckButton.class);
        Widgets.require(builder, "proxy_list_entry", Entry.class);
        Widgets.require(builder, "proxy_list_button", Button.class);
        Widgets.require(builder, "max_retries_spin", SpinButton.class);
        Widgets.require(builder, "clipboard_check", CheckButton.class);
        Widgets.require(builder, "torrent_folder_check", CheckButton.class);
        Widgets.require(builder, "metalink_folder_check", CheckButton.class);
        Widgets.require(builder, "settings_status_label", Label.class);
        Widgets.require(builder, "settings_cancel_button", Button.class);
        Widgets.require(builder, "settings_save_button", Button.class);
    }

    @Test
    @DisplayName("import-list.ui parses and contains all required widgets")
    void importList() {
        GtkBuilder builder = UiLoader.load("/ui/import-list.ui");
        Widgets.require(builder, "import_list_dialog", Window.class);
        Widgets.require(builder, "urls_textview", TextView.class);
        Widgets.require(builder, "status_label", Label.class);
        Widgets.require(builder, "from_file_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "import_button", Button.class);
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
}
