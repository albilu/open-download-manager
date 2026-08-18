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

    @Test
    @DisplayName("new-download.ui parses and contains all required widgets")
    void newDownload() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        Widgets.require(builder, "new_download_dialog", Window.class);
        Widgets.require(builder, "url_entry", Entry.class);
        Widgets.require(builder, "torrent_file_label", Label.class);
        Widgets.require(builder, "folder_label", Label.class);
        Widgets.require(builder, "max_connections_spin", SpinButton.class);
        Widgets.require(builder, "max_speed_spin", SpinButton.class);
        Widgets.require(builder, "use_proxy_check", CheckButton.class);
        Widgets.require(builder, "proxy_entry", Entry.class);
        Widgets.require(builder, "error_label", Label.class);
        Widgets.require(builder, "torrent_choose_button", Button.class);
        Widgets.require(builder, "folder_choose_button", Button.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "start_button", Button.class);
    }

    @Test
    @DisplayName("settings.ui parses and contains all required widgets")
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
}
