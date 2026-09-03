package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.gnome.gdk.Rectangle;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Align;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererProgress;
import org.gnome.gtk.CellRendererCombo;
import org.gnome.gtk.CellRendererText;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.Frame;
import org.gnome.gtk.Grid;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Paned;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.PopoverMenuBar;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.SortType;
import org.gnome.gtk.TextView;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.TreeViewColumn;
import org.gnome.gtk.TreeViewColumnSizing;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSortable;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.javagi.base.Out;
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
        PopupMenu contextMenu = new PopupMenu()
                .add("Enabled action", true, () -> { })
                .add("Disabled action", false, () -> { });
        assertFalse(contextMenu.getPopover().getHasArrow(),
                "download context menu should match arrowless menubar dropdowns");
        Box contextItems = assertInstanceOf(Box.class,
                contextMenu.getPopover().getChild());
        Button enabledContextItem = assertInstanceOf(Button.class,
                contextItems.getFirstChild());
        Button disabledContextItem = assertInstanceOf(Button.class,
                enabledContextItem.getNextSibling());
        assertTrue(enabledContextItem.hasCssClass("flat"));
        assertTrue(enabledContextItem.hasCssClass("odm-context-menu-item"));
        assertEquals(Align.FILL, enabledContextItem.getHalign());
        Label enabledContextLabel = assertInstanceOf(Label.class,
                enabledContextItem.getChild());
        assertEquals(0.0f, enabledContextLabel.getXalign());
        assertTrue(enabledContextItem.getSensitive());
        assertFalse(disabledContextItem.getSensitive());
        org.gnome.gio.ListModel<org.gnome.gtk.EventController> contextControllers =
                enabledContextItem.observeControllers();
        EventControllerMotion hoverController = null;
        for (int i = 0; i < contextControllers.getNItems(); i++) {
            if (contextControllers.getItem(i) instanceof EventControllerMotion motion) {
                hoverController = motion;
                break;
            }
        }
        assertNotNull(hoverController);
        hoverController.emitEnter(1.0, 1.0);
        assertTrue(enabledContextItem.hasCssClass("odm-context-menu-item-hover"));
        hoverController.emitLeave();
        assertFalse(enabledContextItem.hasCssClass("odm-context-menu-item-hover"));
        // stores
        for (String id : new String[]{"status_store", "category_store", "download_store",
                "completion_details_store", "global_progress_store",
                "peers_store", "trackers_store"}) {
            Widgets.require(builder, id, ListStore.class);
        }
        Widgets.require(builder, "files_store", TreeStore.class);
        // side panel treeviews + columns
        ScrolledWindow statusScrolled = Widgets.require(builder,
                "status_scrolled_window", ScrolledWindow.class);
        ScrolledWindow categoryScrolled = Widgets.require(builder,
                "category_scrolled_window", ScrolledWindow.class);
        assertFalse(statusScrolled.getVexpand());
        assertTrue(categoryScrolled.getVexpand());
        assertTrue(statusScrolled.getPropagateNaturalHeight());
        assertTrue(categoryScrolled.getPropagateNaturalHeight());
        TreeView statusTree = Widgets.require(builder, "status_treeview", TreeView.class);
        Label statusHeading = Widgets.require(builder, "status_label", Label.class);
        Label categoryHeading = Widgets.require(builder, "category_label", Label.class);
        assertTrue(statusHeading.getUseMarkup());
        assertTrue(categoryHeading.getUseMarkup());
        assertEquals("<b>Status</b>", statusHeading.getLabel());
        assertEquals("<b>Categories</b>", categoryHeading.getLabel());
        TreeViewColumn statusColumn = Widgets.require(builder,
                "status_column", TreeViewColumn.class);
        TreeViewColumn countColumn = Widgets.require(builder,
                "count_column", TreeViewColumn.class);
        TreeView categoryTree = Widgets.require(builder, "category_treeview", TreeView.class);
        TreeViewColumn categoryColumn = Widgets.require(builder,
                "category_column", TreeViewColumn.class);
        TreeViewColumn categoryCountColumn = Widgets.require(builder,
                "category_count_column", TreeViewColumn.class);
        assertTrue(statusColumn.getExpand());
        assertTrue(categoryColumn.getExpand());

        ListStore statusStore = Widgets.require(builder, "status_store", ListStore.class);
        TreeIter statusRow = new TreeIter();
        statusStore.append(statusRow);
        ListStoreCells.setString(statusStore, statusRow, 0, "view-list-symbolic");
        ListStoreCells.setInt(statusStore, statusRow, 1, 7);
        ListStoreCells.setString(statusStore, statusRow, 2, "All Status");
        statusColumn.cellSetCellData(statusStore, statusRow, false, false);
        countColumn.cellSetCellData(statusStore, statusRow, false, false);
        assertEquals("All Status", Widgets.require(builder,
                "status_label_renderer", CellRendererText.class).getProperty("text"));
        assertEquals("7", Widgets.require(builder,
                "status_count_renderer", CellRendererText.class).getProperty("text"));

        ListStore categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        TreeIter categoryRow = new TreeIter();
        categoryStore.append(categoryRow);
        ListStoreCells.setString(categoryStore, categoryRow, 0, "video-x-generic-symbolic");
        ListStoreCells.setInt(categoryStore, categoryRow, 1, 3);
        ListStoreCells.setString(categoryStore, categoryRow, 2, "Videos");
        categoryColumn.cellSetCellData(categoryStore, categoryRow, false, false);
        categoryCountColumn.cellSetCellData(categoryStore, categoryRow, false, false);
        assertEquals("Videos", Widgets.require(builder,
                "category_label_renderer", CellRendererText.class).getProperty("text"));
        assertEquals("3", Widgets.require(builder,
                "category_count_renderer", CellRendererText.class).getProperty("text"));
        PopoverMenuBar menuBar = Widgets.require(builder, "menu_bar", PopoverMenuBar.class);
        assertFalse(menuBar.getVexpand());
        Box toolbar = Widgets.require(builder, "download_toolbar", Box.class);
        assertTrue(toolbar.hasCssClass("toolbar"));
        assertFalse(toolbar.getVexpand());
        // toolbar buttons
        for (String id : new String[]{"new_download_button", "pause_button", "resume_button",
                "delete_button", "move_up_button", "move_top_button", "move_down_button", "move_bottom_button",
                "settings_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        assertEquals("Start / Resume",
                Widgets.require(builder, "resume_button", Button.class).getTooltipText());
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        Box toolbarSpacer = Widgets.require(builder, "toolbar_spacer", Box.class);
        Box searchToolItem = Widgets.require(builder, "search_tool_item", Box.class);
        assertTrue(toolbarSpacer.getHexpand());
        assertSame(searchToolItem, toolbar.getLastChild(),
                "the search control must be the toolbar's trailing item");
        // download treeview + columns
        Paned contentPaned = Widgets.require(builder, "content_paned", Paned.class);
        assertTrue(contentPaned.getVexpand());
        assertTrue(contentPaned.getResizeStartChild());
        assertTrue(contentPaned.getResizeEndChild());
        Box downloadList = Widgets.require(builder, "download_list_container", Box.class);
        ScrolledWindow downloadScrolled = Widgets.require(builder,
                "download_scrolled_window", ScrolledWindow.class);
        assertTrue(downloadList.getVexpand());
        assertTrue(downloadScrolled.getVexpand());
        TreeView downloadTree = Widgets.require(builder, "download_treeview", TreeView.class);
        String[] downloadColumnIds = {"number_column", "status_icon_column", "name_column",
                "complete_column", "size_column",
                "percent_progress_column", "elapsed_column", "left_column", "speed_column", "up_speed_column",
                "retry_column", "start_date_column", "end_date_column", "tor_icon_column"};
        int[] sortColumnIds = {0, 16, 1, 17, 18, 19, 20, 21, 22, 23, 8, 24, 25, 26};
        for (int index = 0; index < downloadColumnIds.length; index++) {
            String id = downloadColumnIds[index];
            Widgets.require(builder, id, org.gnome.gtk.TreeViewColumn.class);
            assertEquals(sortColumnIds[index], Widgets.require(builder,
                    id, TreeViewColumn.class).getSortColumnId(),
                    id + " must expose a typed sort key");
        }
        assertEquals(28, Widgets.require(builder, "download_store", ListStore.class).getNColumns());
        assertEquals(7, Widgets.require(builder,
                "completion_details_store", ListStore.class).getNColumns());
        Widgets.require(builder, "completion_details_view", TreeView.class);
        assertEquals("Actions", Widgets.require(builder,
                "actions_tab_label", Label.class).getLabel());
        TreeViewColumn statusIconColumn = Widgets.require(builder,
                "status_icon_column", TreeViewColumn.class);
        TreeViewColumn typeIconColumn = Widgets.require(builder,
                "tor_icon_column", TreeViewColumn.class);
        TreeViewColumn nameColumn = Widgets.require(builder, "name_column", TreeViewColumn.class);
        assertTrue(statusIconColumn.getTitle() == null || statusIconColumn.getTitle().isEmpty(),
                "the icon-only status column must not show a header label");
        assertEquals(TreeViewColumnSizing.FIXED, statusIconColumn.getSizing());
        assertEquals(32, statusIconColumn.getFixedWidth(),
                "the status column should remain close to the icon's natural width");
        assertEquals(TreeViewColumnSizing.FIXED, typeIconColumn.getSizing());
        assertTrue(typeIconColumn.getFixedWidth() <= 48,
                "the Type column should remain icon-sized");
        assertTrue(treeColumnIndex(downloadTree, statusIconColumn)
                        < treeColumnIndex(downloadTree, nameColumn),
                "the lifecycle icon must appear before the download name");
        Widgets.require(builder, "status_icon_renderer", org.gnome.gtk.CellRendererPixbuf.class);
        Widgets.require(builder, "download_progress_renderer", CellRendererProgress.class);
        assertTrue(nameColumn.getMinWidth() >= 200);
        assertTrue(nameColumn.getMaxWidth() >= 360 && nameColumn.getMaxWidth() <= 480,
                "the name column must stay readable without consuming the entire table");
        CellRendererText nameRenderer = Widgets.require(builder,
                "name_renderer", CellRendererText.class);
        assertEquals(org.gnome.pango.EllipsizeMode.END,
                nameRenderer.getProperty("ellipsize"));
        TreeViewColumn progressColumn = Widgets.require(builder,
                "percent_progress_column", TreeViewColumn.class);
        assertEquals(TreeViewColumnSizing.FIXED, progressColumn.getSizing());
        assertTrue(progressColumn.getFixedWidth() >= 110,
                "the progress renderer needs enough room for its percentage");
        // info panel
        Box infoPanel = Widgets.require(builder, "info_panel_box", Box.class);
        Notebook infoNotebook = Widgets.require(builder, "info_notebook", Notebook.class);
        ProgressBar infoProgress = Widgets.require(builder, "info_progress_bar", ProgressBar.class);
        assertTrue(infoPanel.getVexpand());
        assertTrue(infoNotebook.getVexpand());
        assertEquals(PositionType.BOTTOM, infoNotebook.getTabPos());
        assertTrue(infoProgress.getHexpand());
        assertTrue(infoProgress.getShowText(),
                "selected-download progress must expose its precise percentage");
        Box generalColumns = Widgets.require(builder, "general_columns", Box.class);
        assertEquals(Orientation.HORIZONTAL, generalColumns.getOrientation());
        assertTrue(generalColumns.getHomogeneous());
        assertSame(Widgets.require(builder, "information_frame", org.gnome.gtk.Frame.class),
                generalColumns.getFirstChild());
        assertSame(Widgets.require(builder, "transfer_frame", org.gnome.gtk.Frame.class),
                generalColumns.getLastChild());
        assertBoldFrameTitles(builder, "information_frame", "transfer_frame");
        for (String id : new String[]{"total_size_value", "added_on_value", "info_hash_v1_value",
                "folder_value", "engine_value", "eta_value", "downloaded_value",
                "connections_value", "seeds_peers_value"}) {
            Widgets.require(builder, id, Label.class);
        }
        Button folderButton = Widgets.require(builder, "folder_open_button", Button.class);
        Box folderContent = Widgets.require(builder, "folder_open_content", Box.class);
        Label folderValue = Widgets.require(builder, "folder_value", Label.class);
        assertSame(folderContent, folderValue.getParent());
        assertSame(folderButton, folderContent.getParent());
        assertFalse(folderButton.getSensitive(),
                "the folder action must remain disabled until a destination is selected");
        Widgets.require(builder, "engine_icon", Image.class);
        Widgets.require(builder, "trackers_view", TreeView.class);
        Widgets.require(builder, "peers_view", TreeView.class);
        Widgets.require(builder, "files_view", TreeView.class);
        TreeStore detailFilesStore = Widgets.require(builder, "files_store", TreeStore.class);
        assertEquals(FileTreeSupport.COLUMN_COUNT, detailFilesStore.getNColumns(),
                "detail files need hierarchy, aggregate state, sort keys, and full paths");
        TreeViewColumn filesNameColumn = Widgets.require(builder,
                "files_name_column", TreeViewColumn.class);
        assertEquals(420, filesNameColumn.getMaxWidth(),
                "the detail file name column must not grow without bound");
        String[] detailFileColumnIds = {"files_selected_column", "files_name_column",
                "files_size_column", "files_progress_column", "files_priority_column"};
        int[] detailFileSortIds = {0, 1, 7, 8, 12};
        for (int index = 0; index < detailFileColumnIds.length; index++) {
            assertEquals(detailFileSortIds[index], Widgets.require(builder,
                    detailFileColumnIds[index], TreeViewColumn.class).getSortColumnId(),
                    detailFileColumnIds[index] + " must be sortable");
        }
        Widgets.require(builder, "files_priority_store", ListStore.class);
        CellRendererCombo filePriority = Widgets.require(builder,
                "files_priority_renderer", CellRendererCombo.class);
        assertEquals(true, filePriority.getProperty("editable"));
        // status bar
        Widgets.require(builder, "statusbar", org.gnome.gtk.Box.class);
        for (String id : new String[]{"info_label", "up_speed_label", "down_speed_label",
                "dht_status_label"}) {
            Widgets.require(builder, id, Label.class);
        }
        Widgets.require(builder, "activity_spinner", Spinner.class);
        assertFalse(Widgets.require(builder, "statusbar_box", Box.class).getVexpand());
        Box rightStatus = Widgets.require(builder, "statusbar_right_box", Box.class);
        assertChildrenOrdered(rightStatus,
                Widgets.require(builder, "activity_spinner", Spinner.class),
                Widgets.require(builder, "dht_progress_box", Box.class),
                Widgets.require(builder, "upload_speed_box", Box.class),
                Widgets.require(builder, "download_speed_box", Box.class),
                Widgets.require(builder, "global_progress_tree", TreeView.class));
        TreeView globalProgress = Widgets.require(builder, "global_progress_tree", TreeView.class);
        Widgets.require(builder, "global_progress_renderer", CellRendererProgress.class);
        Out<Integer> progressWidth = new Out<>();
        globalProgress.getSizeRequest(progressWidth, new Out<>());
        assertTrue(progressWidth.get() >= 180,
                "global progress should remain readable in the status bar");
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
        for (String id : new String[]{"browse_aria2_button", "browse_ytdlp_button", "browse_httrack_button",
                "browse_proxychains_button", "browse_tor_button", "browse_axel_button",
                "browse_subliminal_button",
                "settings_cancel_button", "settings_reset_button", "settings_apply_button", "settings_ok_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        Widgets.require(builder, "default_download_folder_chooser", MenuButton.class);
        Widgets.require(builder, "monitored_folder_chooser", MenuButton.class);
        for (String id : new String[]{"retain_completed_canceled_history_check",
                "automatic_cleanup_check", "clipboard_monitor_check",
                "clipboard_silent_check", "system_tray_check", "start_automatically_check",
                "move_torrent_check", "startup_check", "folder_monitoring_check", "folder_recursive_check",
                "move_to_trash_check",
                "continue_download_check", "check_integrity_check", "enable_auto_save_check",
                "enable_seeding_check", "write_thumbnail_check", "write_subtitles_check",
                "embed_metadata_check", "extract_audio_check", "use_aria2_external_check",
                "include_archives_check", "enable_scheduling_check"}) {
            Widgets.require(builder, id, CheckButton.class);
        }
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "min_split_size_spin1",
                "max_peers_spin", "peer_speed_limit_spin", "seed_time_spin", "depth_spin", "proxy_port_spin",
                "cleanup_interval_spin", "max_history_records_spin", "max_completed_records_spin",
                "completed_retention_spin", "error_retention_spin",
                "max_import_urls_spin", "max_import_source_size_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"aria2_path_entry", "ytdlp_path_entry", "httrack_path_entry",
                "referer_entry", "cookie_entry", "user_agent_entry", "proxy_host_entry",
                "proxy_username_entry", "proxy_password_entry", "video_format_entry",
                "subtitle_language_entry", "include_entry", "exclude_entry",
                "proxychains_path_entry", "tor_path_entry", "axel_path_entry",
                "subliminal_path_entry"}) {
            Widgets.require(builder, id, Entry.class);
        }
        Widgets.require(builder, "proxy_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "file_allocation_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "antivirus_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "antivirus_detection_label", Label.class);
        org.gnome.gtk.Switch settingsTor = Widgets.require(builder,
                "tor_switch", org.gnome.gtk.Switch.class);
        assertSame(Widgets.require(builder, "tor_settings_grid", Grid.class),
                settingsTor.getParent(),
                "the Settings Tor switch must be a direct grid child");
        Widgets.require(builder, "available_space_label", Label.class);
        Widgets.require(builder, "settings_status_label", Label.class);
        assertDiskLabelBelowChooser(builder, "default_download_folder_chooser",
                "available_space_label");
        Grid generalLayout = Widgets.require(builder, "general_layout_grid", Grid.class);
        assertFlatFieldGrid(generalLayout);
        assertEquals(1, gridColumn(generalLayout,
                Widgets.require(builder, "max_concurrent_downloads_spin", SpinButton.class)));
        assertTrue(Widgets.require(builder, "clipboard_silent_check", CheckButton.class)
                .getMarginStart() >= 18,
                "clipboard silent mode must read as a child of clipboard monitoring");
        assertTrue(Widgets.require(builder, "folder_recursive_check", CheckButton.class)
                .getMarginStart() >= 18,
                "recursive monitoring must read as a child of folder monitoring");
        assertTrue(Widgets.require(builder, "move_to_trash_check", CheckButton.class)
                .getMarginStart() >= 18,
                "processed-descriptor Trash must read as a child of folder monitoring");
        assertSame(Widgets.require(builder, "general_options_grid", Grid.class),
                Widgets.require(builder, "enable_auto_save_check", CheckButton.class).getParent(),
                "ODM auto save belongs to General rather than the Aria2 engine tab");
        CheckButton saveHistory = Widgets.require(builder,
                "retain_completed_canceled_history_check", CheckButton.class);
        assertEquals("Keep completed and canceled records after restart", saveHistory.getLabel());
        assertSame(Widgets.require(builder, "history_cleanup_box", Box.class),
                saveHistory.getParent(),
                "Keep completed and canceled records after restart belongs to Advanced > Download History");
        assertNull(builder.getObject("start_automatically_check2"),
                "the global automatic-start policy must not be duplicated on Network");
        assertNull(builder.getObject("move_torrent_check2"),
                "the global descriptor Trash policy must not be duplicated on Network");
        assertDownloadOptionsLayout(builder);
        for (String id : new String[]{"aria2_layout_grid", "ytdlp_layout_grid",
                "httrack_layout_grid", "advanced_layout_grid"}) {
            assertFlatFieldGrid(Widgets.require(builder, id, Grid.class));
        }
        assertEquals(1, gridColumn(Widgets.require(builder, "aria2_layout_grid", Grid.class),
                Widgets.require(builder, "min_split_size_spin1", SpinButton.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "ytdlp_layout_grid", Grid.class),
                Widgets.require(builder, "video_format_entry", Entry.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "httrack_layout_grid", Grid.class),
                Widgets.require(builder, "depth_spin", SpinButton.class)));
        assertBoldLabels(builder, "download_settings_heading", "http_connection_heading",
                "proxy_settings_heading", "tor_settings_heading",
                "scheduling_heading", "history_cleanup_heading",
                "import_limits_heading", "advanced_tools_heading");
        Widgets.require(builder, "import_limits_grid", Grid.class);
        Widgets.require(builder, "scheduler_selection_label", Label.class);
        Box legend = Widgets.require(builder, "scheduler_legend_box", Box.class);
        assertEquals(Orientation.VERTICAL, legend.getOrientation());
        assertTrue(Widgets.require(builder, "scheduler_active_swatch", Box.class)
                .hasCssClass("scheduler-active-swatch"));
        assertTrue(Widgets.require(builder, "scheduler_inactive_swatch", Box.class)
                .hasCssClass("scheduler-inactive-swatch"));
    }

    @Test
    @DisplayName("new-download.ui parses with 1:1 original ids")
    void newDownload() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        Widgets.require(builder, "new_download_dialog", Window.class);
        Widgets.require(builder, "url_entry", Entry.class);
        Widgets.require(builder, "torrent_file_chooser", Button.class);
        Widgets.require(builder, "save_folder_chooser", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "filename_entry", Entry.class);
        Widgets.require(builder, "files_treeview", TreeView.class);
        Widgets.require(builder, "files_status_label", Label.class);
        Widgets.require(builder, "select_all_files_check", CheckButton.class);
        TreeStore filesStore = Widgets.require(builder, "files_liststore", TreeStore.class);
        assertEquals(FileTreeSupport.COLUMN_COUNT, filesStore.getNColumns(),
                "new-download files need the shared hierarchical file schema");
        String[] fileColumnIds = {"new_files_selected_column", "new_files_name_column",
                "new_files_size_column", "new_files_priority_column"};
        int[] fileSortIds = {0, 1, 7, 12};
        for (int index = 0; index < fileColumnIds.length; index++) {
            assertEquals(fileSortIds[index], Widgets.require(builder,
                    fileColumnIds[index], TreeViewColumn.class).getSortColumnId(),
                    fileColumnIds[index] + " must be sortable");
        }
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
        assertNull(builder.getObject("start_automatically_check"));
        assertNull(builder.getObject("move_torrent_check"));
        Widgets.require(builder, "new_download_spinner", Spinner.class);
        Widgets.require(builder, "new_download_cancel_button", Button.class);
        Widgets.require(builder, "new_download_start_button", Button.class);
        assertDiskLabelBelowChooser(builder, "save_folder_chooser", "disk_space_label");
        assertDownloadOptionsLayout(builder);
    }

    @Test
    @DisplayName("new-media.ui parses with expected ids")
    void newMedia() {
        GtkBuilder builder = UiLoader.load("/ui/new-media.ui");
        Widgets.require(builder, "new_media_dialog", Window.class);
        Widgets.require(builder, "media_url_entry", Entry.class);
        Widgets.require(builder, "fetch_info_button", Button.class);
        Widgets.require(builder, "media_status_label", Label.class);
        Widgets.require(builder, "media_info_label", Label.class);
        Widgets.require(builder, "format_drop", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "audio_only_check", CheckButton.class);
        Widgets.require(builder, "playlist_check", CheckButton.class);
        Widgets.require(builder, "subtitles_check", CheckButton.class);
        Widgets.require(builder, "subtitle_lang_entry", Entry.class);
        Widgets.require(builder, "cookie_file_chooser", Button.class);
        Widgets.require(builder, "media_folder_chooser", MenuButton.class);
        Widgets.require(builder, "media_disk_space_label", Label.class);
        Widgets.require(builder, "media_cancel_button", Button.class);
        Widgets.require(builder, "media_start_button", Button.class);
        Grid fields = Widgets.require(builder, "media_fields_grid", Grid.class);
        assertFieldGrid(fields);
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "media_url_entry", Entry.class).getParent()));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "format_drop", org.gnome.gtk.DropDown.class)));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "subtitle_lang_entry", Entry.class)));
        assertDiskLabelBelowChooser(builder, "media_folder_chooser",
                "media_disk_space_label");
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
        org.gnome.gtk.Switch propertyTor = Widgets.require(builder,
                "tor_switch", org.gnome.gtk.Switch.class);
        assertSame(Widgets.require(builder, "tor_settings_grid", Grid.class),
                propertyTor.getParent());
        assertNull(builder.getObject("start_automatically_check"));
        assertNull(builder.getObject("move_torrent_check"));
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "apply_button", Button.class);
        Widgets.require(builder, "ok_button", Button.class);
        assertDownloadOptionsLayout(builder);
    }

    @Test
    @DisplayName("about.ui parses")
    void about() {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        Widgets.require(builder, "about_dialog", org.gnome.gtk.AboutDialog.class);
        assertTrue(AboutDialogPresenter.LOGO_RESOURCE.endsWith(".svg"));
        assertNotNull(AboutDialogPresenter.loadLogo(), "the SVG logo must load as a paintable");
    }

    @Test
    @DisplayName("start-shutdown.ui parses with 1:1 original ids")
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
        assertEquals(2, Widgets.require(builder, "extension_column",
                TreeViewColumn.class).getSortColumnId(),
                "the imported URL extension must be sortable");
        Widgets.require(builder, "folder_destination", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_spinnet", Spinner.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        assertDiskLabelBelowChooser(builder, "folder_destination", "disk_space_label");
        // Options tab ids
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        assertNull(builder.getObject("start_automatically_check1"));
        assertNull(builder.getObject("move_torrent_check1"));
        assertDownloadOptionsLayout(builder);
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
        Widgets.require(builder, "destination_folder", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_sequence_spinner", Spinner.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        assertDiskLabelBelowChooser(builder, "destination_folder", "disk_space_label");
        assertNull(builder.getObject("start_automatically_check1"));
        assertNull(builder.getObject("move_torrent_check1"));
        assertDownloadOptionsLayout(builder);
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
        MainWindow window = new MainWindow(null, stub, new org.tor.TorService("tor"),
                new org.manager.schedule.ScheduleManager(stub));
        // Constructing is the test: every Widgets.require in the constructor
        // must resolve. (Null app: the window is a standalone toplevel here.)
        assertEquals(org.gnome.gtk.SelectionMode.MULTIPLE, window.downloadSelectionMode());
        assertEquals(PropagationPhase.CAPTURE, window.downloadContextClickPhase(),
                "right-click handling must run before TreeView child gestures consume it");
        assertEquals(List.of("#", "Status", "Name", "Completed", "Size", "Progress",
                "Elapsed", "Left", "Down Speed", "Up Speed", "Retry", "Start Date",
                "End Date", "Type"), MainWindow.downloadColumnLabels());
        assertEquals(5, window.mainMenuTopLevelCount());
        for (String key : List.of("notify", "antivirus", "subtitles",
                "suspend", "shutdown", "custom")) {
            assertNull(window.menuActionParameterType("completion-" + key));
            assertTrue(window.menuActionEnabled("completion-" + key));
        }
        assertEquals("s", window.menuActionParameterType("schedule"));
        assertTrue(window.menuActionEnabled("schedule"));
        assertFalse(window.menuActionEnabled("open-file"));
        assertFalse(window.menuActionEnabled("open-folder"));
        window.dispose();
    }

    @Test
    @DisplayName("the first download row resolves from right-click widget coordinates")
    void firstDownloadRowContextHitTesting() throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ApplicationWindow window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        store.append(new TreeIter());

        window.present();
        try {
            awaitGtk(() -> tree.getWidth() > 0 && tree.getHeight() > 0,
                    "GTK did not allocate the download tree");
            TreePath first = TreePath.fromString("0");
            TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
            Rectangle cell = new Rectangle();
            tree.getCellArea(first, name, cell);
            Out<Integer> widgetX = new Out<>();
            Out<Integer> widgetY = new Out<>();
            tree.convertBinWindowToWidgetCoords(
                    cell.readX() + Math.max(1, cell.readWidth() / 2),
                    cell.readY() + Math.max(1, cell.readHeight() / 2), widgetX, widgetY);

            TreePath resolved = MainWindow.pathAtWidgetPosition(
                    tree, widgetX.get(), widgetY.get(), new Out<>());

            assertNotNull(resolved, "row zero must not be lost behind the header offset");
            assertArrayEquals(new int[]{0}, resolved.getIndices());
        } finally {
            window.close();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("sorting by raw size keeps visible rows mapped to their Downloads")
    void typedDownloadSortingPreservesRowIdentity() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore statusStore = Widgets.require(builder, "status_store", ListStore.class);
        ListStore categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        ListStore globalProgressStore = Widgets.require(builder,
                "global_progress_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(statusStore, categoryStore,
                downloadStore, globalProgressStore,
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download small = new org.manager.download.Download(
                URI.create("https://example.com/small.bin"));
        small.setName("small.bin");
        small.setSize(100);
        small.setStatus(org.manager.download.Download.Status.QUEUED);
        org.manager.download.Download large = new org.manager.download.Download(
                URI.create("https://example.com/large.bin"));
        large.setName("large.bin");
        large.setSize(200);
        large.setStatus(org.manager.download.Download.Status.QUEUED);

        presenter.refresh(List.of(small, large));
        ((TreeSortable) downloadStore).setSortColumnId(18, SortType.DESCENDING);
        assertSame(large, presenter.rowAt(0));

        small.setSize(300);
        presenter.refresh(List.of(small, large));
        assertSame(small, presenter.rowAt(0),
                "an in-place update may reorder the GTK model without corrupting identity");
    }

    @Test
    @DisplayName("upload rate is visible in download rows and aggregate totals")
    void uploadRatePopulatesRowAndSummary() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                downloadStore,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download download = new org.manager.download.Download(
                URI.create("magnet:?xt=urn:btih:abababababababababababababababababababab"));
        download.setStatus(org.manager.download.Download.Status.DOWNLOADING);
        download.setUploadSpeed(2_048);

        DownloadListPresenter.RefreshSummary summary = presenter.refresh(List.of(download));

        TreeIter row = new TreeIter();
        assertTrue(downloadStore.getIterFirst(row));
        assertEquals("2 KB/s", ListStoreCells.getString(downloadStore, row, 7));
        assertEquals(2_048, summary.upBytesPerSec());
        download.setUploadSpeed(0);
        presenter.refresh(List.of(download));
        assertEquals("0 B/s", ListStoreCells.getString(downloadStore, row, 7),
                "zero upload must remain visible instead of becoming an ambiguous dash");
    }

    @Test
    @DisplayName("running completion actions pulse a completed row at 100 percent")
    void completionActionUsesIndeterminateRowProgress() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                downloadStore,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download download = new org.manager.download.Download(
                URI.create("https://example.com/finished.bin"));
        download.setSize(100);
        download.setDownloaded(100);
        download.setStatus(org.manager.download.Download.Status.COMPLETED);
        var running = new org.manager.download.action.CompletionActionResult(
                "running-action",
                org.manager.download.action.AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                "Antivirus check",
                org.manager.download.action.CompletionActionResult.Status.RUNNING,
                "Running…",
                org.manager.download.action.AfterCompletionAction.Severity.HIGH,
                java.time.Instant.now(), null);
        download.setCompletionActionResults(List.of(running));

        presenter.refresh(List.of(download));
        TreeIter iter = new TreeIter();
        assertTrue(downloadStore.getIterFirst(iter));
        assertEquals(100, ListStoreCells.getInt(downloadStore, iter, 12));
        assertEquals("100% · Finalizing…", ListStoreCells.getString(downloadStore, iter, 14));
        presenter.pulseCompletionRows();
        assertTrue(ListStoreCells.getInt(downloadStore, iter, 27) > 0);

        download.finishCompletionAction("running-action",
                org.manager.download.action.CompletionActionResult.Status.SUCCEEDED,
                "No threats detected");
        presenter.refresh(List.of(download));
        assertEquals(-1, ListStoreCells.getInt(downloadStore, iter, 27));
        assertEquals("100.00%", ListStoreCells.getString(downloadStore, iter, 14));

        var sound = new org.manager.download.action.CompletionActionResult(
                "running-sound",
                org.manager.download.action.AfterCompletionAction.ActionType.PLAY_SOUND,
                "Play notification",
                org.manager.download.action.CompletionActionResult.Status.RUNNING,
                "Running…",
                org.manager.download.action.AfterCompletionAction.Severity.LOW,
                java.time.Instant.now(), null);
        download.setCompletionActionResults(List.of(sound));
        presenter.refresh(List.of(download));
        assertEquals(-1, ListStoreCells.getInt(downloadStore, iter, 27));
        assertEquals("100.00%", ListStoreCells.getString(downloadStore, iter, 14),
                "sound and power actions must not show file-finalization progress");
    }

    @Test
    @DisplayName("import extensions and New Download files use their declared sort keys")
    void auxiliaryFileTablesSortByRawValues() {
        GtkBuilder importBuilder = UiLoader.load("/ui/import-list.ui");
        ListStore importStore = Widgets.require(importBuilder, "url_liststore", ListStore.class);
        TreeIter zip = new TreeIter();
        importStore.append(zip);
        ListStoreCells.setBoolean(importStore, zip, 0, true);
        ListStoreCells.setString(importStore, zip, 1, "https://example.com/archive.zip");
        ListStoreCells.setString(importStore, zip, 2, "zip");
        TreeIter mp4 = new TreeIter();
        importStore.append(mp4);
        ListStoreCells.setBoolean(importStore, mp4, 0, true);
        ListStoreCells.setString(importStore, mp4, 1, "https://example.com/video.mp4");
        ListStoreCells.setString(importStore, mp4, 2, "mp4");

        ((TreeSortable) importStore).setSortColumnId(2, SortType.ASCENDING);
        TreeIter first = new TreeIter();
        assertTrue(importStore.getIterFirst(first));
        assertEquals("mp4", ListStoreCells.getString(importStore, first, 2));

        GtkBuilder downloadBuilder = UiLoader.load("/ui/new-download.ui");
        TreeStore filesStore = Widgets.require(downloadBuilder,
                "files_liststore", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        FileTreeSupport.reconcile(filesStore, rows, List.of(
                new FileTreeSupport.Entry(true, "ten-kib.bin", 10 * 1024L, 0, 1, "Normal"),
                new FileTreeSupport.Entry(true, "nine-kib.bin", 9 * 1024L, 0, 2, "High")), null);

        ((TreeSortable) filesStore).setSortColumnId(7, SortType.ASCENDING);
        assertTrue(filesStore.getIterFirst(first));
        assertEquals("nine-kib.bin", TreeStoreCells.getString(filesStore, first, 1),
                "numeric size sorting must not use the rendered size text");

        ((TreeSortable) filesStore).setSortColumnId(12, SortType.DESCENDING);
        assertTrue(filesStore.getIterFirst(first));
        assertEquals("nine-kib.bin", TreeStoreCells.getString(filesStore, first, 1),
                "High priority must sort above Normal priority");
        FileTreeSupport.freeReferences(rows);
    }

    @Test
    @DisplayName("shared file trees build folders and propagate selection and priority")
    void hierarchicalFileTreeInteractions() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        TreeStore store = Widgets.require(builder, "files_liststore", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        try {
            FileTreeSupport.reconcile(store, rows, List.of(
                    new FileTreeSupport.Entry(true,
                            "/downloads/Show/Season 1/one.mkv", 100, 25, 1, "Normal"),
                    new FileTreeSupport.Entry(false,
                            "/downloads/Show/Season 1/two.mkv", 200, 50, 2, "Low"),
                    new FileTreeSupport.Entry(true,
                            "/downloads/Show/readme.txt", 10, 10, 3, "Normal")),
                    java.nio.file.Path.of("/downloads"));

            TreeIter root = new TreeIter();
            assertTrue(store.getIterFirst(root));
            assertEquals("Show", TreeStoreCells.getString(store, root,
                    FileTreeSupport.NAME_COLUMN));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.FOLDER_COLUMN));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));
            assertEquals(310L, TreeStoreCells.getLong(store, root,
                    FileTreeSupport.SIZE_SORT_COLUMN));

            assertTrue(FileTreeSupport.toggleSelection(store, "0:0"));
            assertEquals(List.of(1, 2, 3), FileTreeSupport.selectedIndexes(store));
            assertFalse(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));

            FileTreeSupport.setPriority(store, "0", "High");
            assertEquals(Map.of(1, "High", 2, "High", 3, "High"),
                    FileTreeSupport.priorities(store));

            FileTreeSupport.selectAll(store, false);
            assertEquals(List.of(), FileTreeSupport.selectedIndexes(store));
            assertTrue(FileTreeSupport.toggleSelection(store, "0:0:0"));
            assertEquals(List.of(1), FileTreeSupport.selectedIndexes(store));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));

            assertEquals("2,3,7", NewDownloadDialog.encodeFileSelection(
                    List.of(7, 3, 2, 3)));
        } finally {
            FileTreeSupport.freeReferences(rows);
        }
    }

    @Test
    @DisplayName("download-record file priority edits update persisted aria2 metadata")
    void downloadRecordFilePriorityIsEditable() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        TreeStore store = Widgets.require(builder, "files_store", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        try {
            FileTreeSupport.reconcile(store, rows, List.of(
                    new FileTreeSupport.Entry(true, "Show/one.mkv", 100, 50, 1, "Normal"),
                    new FileTreeSupport.Entry(true, "Show/two.mkv", 100, 25, 2, "Low")), null);
            org.manager.download.Download download = new org.manager.download.Download(
                    URI.create("magnet:?xt=urn:btih:cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"));

            assertTrue(MainWindow.applyFilePriority(store, download, "0", "High"));

            org.aria2.Aria2Settings settings = assertInstanceOf(
                    org.aria2.Aria2Settings.class, download.getSettings());
            assertEquals(Map.of(1, "High", 2, "High"), settings.getFilePriorities());
            assertFalse(MainWindow.applyFilePriority(store, download, "9", "Low"));
        } finally {
            FileTreeSupport.freeReferences(rows);
        }
    }

    @Test
    @DisplayName("right-click targets the clicked row without discarding an existing group")
    void rightClickSelectionTargeting() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        for (int row = 0; row < 3; row++) {
            store.append(new TreeIter());
        }
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        TreePath first = TreePath.fromString("0");
        TreePath second = TreePath.fromString("1");
        TreePath third = TreePath.fromString("2");
        tree.getSelection().selectPath(first);
        tree.getSelection().selectPath(second);

        MainWindow.selectContextTarget(tree, second,
                Widgets.require(builder, "name_column", TreeViewColumn.class));
        assertEquals(2, tree.getSelection().countSelectedRows(),
                "right-clicking inside the selected group must preserve it");

        MainWindow.selectContextTarget(tree, third,
                Widgets.require(builder, "name_column", TreeViewColumn.class));
        assertEquals(1, tree.getSelection().countSelectedRows());
        assertFalse(tree.getSelection().pathIsSelected(first));
        assertTrue(tree.getSelection().pathIsSelected(third),
                "an unselected clicked row must become the context target");
    }

    @Test
    @DisplayName("appending a history page preserves the current multi-selection")
    void paginationAppendPreservesMultiSelection() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                store,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download first = new org.manager.download.Download(
                URI.create("https://example.com/first.bin"));
        org.manager.download.Download second = new org.manager.download.Download(
                URI.create("https://example.com/second.bin"));
        org.manager.download.Download third = new org.manager.download.Download(
                URI.create("https://example.com/third.bin"));
        first.setStatus(org.manager.download.Download.Status.COMPLETED);
        second.setStatus(org.manager.download.Download.Status.COMPLETED);
        third.setStatus(org.manager.download.Download.Status.COMPLETED);

        presenter.refresh(List.of(first, second));
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        TreePath firstPath = TreePath.fromString("0");
        TreePath secondPath = TreePath.fromString("1");
        tree.getSelection().selectPath(firstPath);
        tree.getSelection().selectPath(secondPath);

        DownloadListPresenter.RefreshSummary summary = presenter.refresh(
                List.of(first, second, third));

        assertFalse(summary.modelRebuilt());
        assertEquals(2, tree.getSelection().countSelectedRows());
        assertTrue(tree.getSelection().pathIsSelected(firstPath));
        assertTrue(tree.getSelection().pathIsSelected(secondPath));
        assertEquals(3, store.iterNChildren(null));
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

    private static void assertChildrenOrdered(Widget parent, Widget... expected) {
        int previous = -1;
        for (Widget child : expected) {
            int index = childIndex(parent, child);
            assertTrue(index > previous, "status-bar child order is incorrect");
            previous = index;
        }
    }

    private static int childIndex(Widget parent, Widget expected) {
        int index = 0;
        for (Widget child = parent.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (child == expected) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int treeColumnIndex(TreeView tree, TreeViewColumn expected) {
        int index = 0;
        for (TreeViewColumn column : tree.getColumns()) {
            if (column == expected) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int gridColumn(Grid grid, Widget child) {
        Out<Integer> column = new Out<>();
        grid.queryChild(child, column, new Out<>(), new Out<>(), new Out<>());
        return column.get();
    }

    private static void drainGtkEvents() {
        MainContext context = MainContext.default_();
        while (context.pending()) {
            context.iteration(false);
        }
    }

    private static void awaitGtk(BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            drainGtkEvents();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage);
    }

    private static void assertFieldGrid(Grid grid) {
        assertTrue(grid.getHexpand(), "field grids must consume the available page width");
        assertTrue(grid.getRowSpacing() >= 8,
                "field rows need consistent vertical separation");
        assertTrue(grid.getColumnSpacing() >= 12,
                "labels and controls need consistent horizontal separation");
    }

    private static void assertFlatFieldGrid(Grid grid) {
        assertFieldGrid(grid);
        assertFalse(grid.getParent() instanceof Frame,
                "reference layouts use flat forms without framed section borders");
    }

    private static void assertDownloadOptionsLayout(GtkBuilder builder) {
        for (String id : new String[]{"settings_grid", "http_connection_grid",
                "proxy_settings_grid"}) {
            assertFlatFieldGrid(Widgets.require(builder, id, Grid.class));
        }
        Grid columns = Widgets.require(builder, "options_columns_grid", Grid.class);
        Box left = Widgets.require(builder, "options_left_column", Box.class);
        Box right = Widgets.require(builder, "options_right_column", Box.class);
        assertTrue(columns.getHexpand());
        assertTrue(columns.getColumnSpacing() >= 16);
        assertEquals(Orientation.VERTICAL, left.getOrientation());
        assertEquals(Orientation.VERTICAL, right.getOrientation());
        assertEquals(0, gridColumn(columns, left));
        assertEquals(1, gridColumn(columns, right));
        assertEquals(1, gridColumn(Widgets.require(builder, "settings_grid", Grid.class),
                Widgets.require(builder, "retry_limit_spin", SpinButton.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "proxy_settings_grid", Grid.class),
                Widgets.require(builder, "proxy_host_entry", Entry.class)));
        assertBoldLabels(builder, "download_settings_heading", "http_connection_heading",
                "proxy_settings_heading", "tor_settings_heading");
    }

    private static void assertBoldLabels(GtkBuilder builder, String... labelIds) {
        for (String labelId : labelIds) {
            Label title = Widgets.require(builder, labelId, Label.class);
            assertTrue(title.getUseMarkup(), labelId + " must enable markup");
            assertTrue(title.getLabel().startsWith("<b>")
                            && title.getLabel().endsWith("</b>"),
                    labelId + " must be bold");
        }
    }

    private static void assertBoldFrameTitles(GtkBuilder builder, String... frameIds) {
        for (String frameId : frameIds) {
            Frame frame = Widgets.require(builder, frameId, Frame.class);
            assertTrue(frame.getLabelWidget() instanceof Label,
                    frameId + " must use an explicit label widget");
            Label title = (Label) frame.getLabelWidget();
            assertTrue(title.getUseMarkup(), frameId + " title must enable markup");
            assertTrue(title.getLabel().startsWith("<b>")
                            && title.getLabel().endsWith("</b>"),
                    frameId + " title must be bold");
        }
    }

    private static void assertDiskLabelBelowChooser(GtkBuilder builder, String chooserId,
            String labelId) {
        MenuButton chooser = Widgets.require(builder, chooserId, MenuButton.class);
        Label label = Widgets.require(builder, labelId, Label.class);
        Box stack = (Box) chooser.getParent();
        assertSame(stack, label.getParent());
        assertEquals(Orientation.VERTICAL, stack.getOrientation());
        assertSame(label, chooser.getNextSibling());
        assertEquals(Align.END, label.getHalign());
    }
}
