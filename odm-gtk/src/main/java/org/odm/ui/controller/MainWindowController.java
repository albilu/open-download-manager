package org.odm.ui.controller;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.jgtk.core.GtkNativeLibraries;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.odm.ui.BaseWindow;
import org.odm.ui.handler.MainWindowSignalHandler;
import org.odm.ui.service.MainWindowService;
import org.odm.ui.service.core.DownloadCoordinatorService;
import org.odm.ui.service.state.SessionStateService;
import org.tor.TorService;

import com.sun.jna.Pointer;

/**
 * Controller for the Main Application Window.
 *
 * Manages the main application window including download management, UI
 * updates, file operations, and settings management. Uses the refactored
 * architecture with separate Service and Handler components.
 *
 * This controller follows the refactored architecture: - MainWindowController:
 * Extends BaseWindow, manages window lifecycle - MainWindowHandler: Handles GTK
 * signal events - MainWindowService: Contains business logic and core
 * functionality
 */
public class MainWindowController extends BaseWindow implements DownloadListener {

        private static final Logger LOGGER = Logger.getLogger(MainWindowController.class.getName());

        // Core dependencies
        private final DownloadManager downloadManager;
        private final GlobalSettings settings;
        private final SessionStateService uiStateService;
        private final DownloadCoordinatorService downloadUIService;
        private final TorService torService;

        // Refactored architecture components
        private MainWindowService service;
        private MainWindowSignalHandler handler;

        // Application shutdown callback
        private Runnable applicationShutdownCallback;

        /**
         * Constructor.
         */
        public MainWindowController(DownloadManager downloadManager, GlobalSettings settings,
                        SessionStateService uiStateService, DownloadCoordinatorService downloadUIService,
                        TorService torService) {
                this.downloadManager = downloadManager;
                this.settings = settings;
                this.uiStateService = uiStateService;
                this.downloadUIService = downloadUIService;
                this.torService = torService;
                // Service and handler will be initialized in initializeWindow() when UI is
                // available
        }

        /**
         * Sets the application shutdown callback.
         */
        public void setApplicationShutdownCallback(Runnable callback) {
                this.applicationShutdownCallback = callback;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getGladeResourcePath() {
                return "/glade/main-window/main-window.glade";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getWindowName() {
                return "main_window";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void initializeWindow() {
                // Initialize service and handler now that UI is available
                service = new MainWindowService(downloadManager, settings, uiStateService, downloadUIService, ui,
                                torService);
                service.setApplicationShutdownCallback(applicationShutdownCallback);

                handler = new MainWindowSignalHandler(service, ui);

                // Initialize the service (replaces old initialization logic)
                service.initializeService();

                // Register as download manager listener
                downloadManager.addDownloadListener(this);

                LOGGER.info("Main window initialized with refactored architecture");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void setupSignalHandlers() {
                // Window signals
                ui.on("on_main_window_delete_event",
                                (widget, data) -> handler.on_main_window_delete_event(widget, data));
                ui.on("on_main_window_destroy", (widget, data) -> handler.on_main_window_destroy(widget, data));

                // File menu signals
                ui.on("on_new_download_menu_item_activate",
                                (widget, data) -> handler.on_new_download_menu_item_activate(widget, data));
                ui.on("on_new_website_scraper_menu_item_activate",
                                (widget, data) -> handler.on_new_website_scraper_menu_item_activate(widget, data));
                ui.on("on_batch_process_menu_item_activate",
                                (widget, data) -> handler.on_batch_process_menu_item_activate(widget, data));
                ui.on("on_clipboard_import_menu_item_activate",
                                (widget, data) -> handler.on_clipboard_import_menu_item_activate(widget, data));
                ui.on("on_import_url_sequence_menu_item_activate",
                                (widget, data) -> handler.on_import_url_sequence_menu_item_activate(widget, data));
                ui.on("on_import_file_menu_item_activate",
                                (widget, data) -> handler.on_import_file_menu_item_activate(widget, data));
                ui.on("on_import_html_menu_item_activate",
                                (widget, data) -> handler.on_import_html_menu_item_activate(widget, data));
                ui.on("on_export_file_menu_item_activate",
                                (widget, data) -> handler.on_export_file_menu_item_activate(widget, data));
                ui.on("on_offline_menu_item_toggled",
                                (widget, data) -> handler.on_offline_menu_item_toggled(widget, data));
                ui.on("on_exit_menu_item_activate", (widget, data) -> handler.on_exit_menu_item_activate(widget, data));

                // Options menu signals
                ui.on("on_clipboard_monitoring_menu_item_toggled",
                                (widget, data) -> handler.on_clipboard_monitoring_menu_item_toggled(widget, data));
                ui.on("on_clipboard_silent_menu_item_toggled",
                                (widget, data) -> handler.on_clipboard_silent_menu_item_toggled(widget, data));
                ui.on("on_completion_none_menu_item_toggled",
                                (widget, data) -> handler.on_completion_none_menu_item_toggled(widget, data));
                ui.on("on_completion_suspend_menu_item_toggled",
                                (widget, data) -> handler.on_completion_suspend_menu_item_toggled(widget, data));
                ui.on("on_completion_shutdown_menu_item_toggled",
                                (widget, data) -> handler.on_completion_shutdown_menu_item_toggled(widget, data));
                ui.on("on_completion_custom_menu_item_toggled",
                                (widget, data) -> handler.on_completion_custom_menu_item_toggled(widget, data));
                ui.on("on_preferences_menu_item_activate",
                                (widget, data) -> handler.on_preferences_menu_item_activate(widget, data));

                // View menu signals
                ui.on("on_left_panel_menu_item_toggled",
                                (widget, data) -> handler.on_left_panel_menu_item_toggled(widget, data));
                ui.on("on_info_panel_menu_item_toggled",
                                (widget, data) -> handler.on_info_panel_menu_item_toggled(widget, data));

                // Column menu signals
                ui.on("on_column_number_menu_item_toggled",
                                (widget, data) -> handler.on_column_number_menu_item_toggled(widget, data));
                ui.on("on_column_name_menu_item_toggled",
                                (widget, data) -> handler.on_column_name_menu_item_toggled(widget, data));
                ui.on("on_column_complete_menu_item_toggled",
                                (widget, data) -> handler.on_column_complete_menu_item_toggled(widget, data));
                ui.on("on_column_progress_menu_item_toggled",
                                (widget, data) -> handler.on_column_progress_menu_item_toggled(widget, data));
                ui.on("on_column_size_menu_item_toggled",
                                (widget, data) -> handler.on_column_size_menu_item_toggled(widget, data));
                ui.on("on_column_elapsed_menu_item_toggled",
                                (widget, data) -> handler.on_column_elapsed_menu_item_toggled(widget, data));
                ui.on("on_column_left_menu_item_toggled",
                                (widget, data) -> handler.on_column_left_menu_item_toggled(widget, data));
                ui.on("on_column_speed_menu_item_toggled",
                                (widget, data) -> handler.on_column_speed_menu_item_toggled(widget, data));
                ui.on("on_column_up_speed_menu_item_toggled",
                                (widget, data) -> handler.on_column_up_speed_menu_item_toggled(widget, data));
                ui.on("on_column_retry_menu_item_toggled",
                                (widget, data) -> handler.on_column_retry_menu_item_toggled(widget, data));
                ui.on("on_column_start_date_menu_item_toggled",
                                (widget, data) -> handler.on_column_start_date_menu_item_toggled(widget, data));
                ui.on("on_column_end_date_menu_item_toggled",
                                (widget, data) -> handler.on_column_end_date_menu_item_toggled(widget, data));
                ui.on("on_column_tor_icon_menu_item_toggled",
                                (widget, data) -> handler.on_column_tor_icon_menu_item_toggled(widget, data));

                // Download menu signals
                ui.on("on_open_menu_item_activate", (widget, data) -> handler.on_open_menu_item_activate(widget, data));
                ui.on("on_open_folder_menu_item_activate",
                                (widget, data) -> handler.on_open_folder_menu_item_activate(widget, data));
                ui.on("on_force_download_menu_item_activate",
                                (widget, data) -> handler.on_force_download_menu_item_activate(widget, data));
                ui.on("on_pause_all_menu_item_activate",
                                (widget, data) -> handler.on_pause_all_menu_item_activate(widget, data));
                ui.on("on_resume_all_menu_item_activate",
                                (widget, data) -> handler.on_resume_all_menu_item_activate(widget, data));
                ui.on("on_delete_menu_item_activate",
                                (widget, data) -> handler.on_delete_menu_item_activate(widget, data));
                ui.on("on_delete_with_files_menu_item_activate",
                                (widget, data) -> handler.on_delete_with_files_menu_item_activate(widget, data));
                ui.on("on_remove_all_finished_menu_item_activate",
                                (widget, data) -> handler.on_remove_all_finished_menu_item_activate(widget, data));
                ui.on("on_properties_menu_item_activate",
                                (widget, data) -> handler.on_properties_menu_item_activate(widget, data));
                ui.on("on_statistics_menu_item_activate",
                                (widget, data) -> handler.on_statistics_menu_item_activate(widget, data));

                // Help menu signals
                ui.on("on_donation_menu_item_activate",
                                (widget, data) -> handler.on_donation_menu_item_activate(widget, data));
                ui.on("on_about_menu_item_activate",
                                (widget, data) -> handler.on_about_menu_item_activate(widget, data));

                // TreeView signals
                ui.on("on_status_treeview_cursor_changed",
                                (widget, data) -> handler.on_status_treeview_cursor_changed(widget, data));
                ui.on("on_category_treeview_cursor_changed",
                                (widget, data) -> handler.on_category_treeview_cursor_changed(widget, data));
                ui.on("on_download_treeview_cursor_changed",
                                (widget, data) -> handler.on_download_treeview_cursor_changed(widget, data));
                ui.on("on_download_treeview_row_activated",
                                (widget, data) -> handler.on_download_treeview_row_activated(widget, data));
                ui.on("on_download_treeview_button_press_event",
                                (widget, data) -> handler.on_download_treeview_button_press_event(widget, data));
                ui.on("on_download_treeview_key_press_event",
                                (widget, data) -> handler.on_download_treeview_key_press_event(widget, data));
                ui.on("on_download_treeview_popup_menu",
                                (widget, data) -> handler.on_download_treeview_popup_menu(widget, data));
                ui.on("on_files_view_button_press_event",
                                (widget, data) -> handler.on_files_view_button_press_event(widget, data));
                ui.on("on_files_view_cursor_changed",
                                (widget, data) -> handler.on_files_view_cursor_changed(widget, data));
                ui.on("on_files_selected_renderer_toggled",
                                (widget, data) -> handler.on_files_selected_renderer_toggled(widget, data));

                // Toolbar button signals
                ui.on("on_new_download_button_clicked",
                                (widget, data) -> handler.on_new_download_button_clicked(widget, data));
                ui.on("on_start_button_clicked", (widget, data) -> handler.on_start_button_clicked(widget, data));
                ui.on("on_pause_button_clicked", (widget, data) -> handler.on_pause_button_clicked(widget, data));
                ui.on("on_delete_button_clicked", (widget, data) -> handler.on_delete_button_clicked(widget, data));
                ui.on("on_delete_with_files_button_clicked",
                                (widget, data) -> handler.on_delete_with_files_button_clicked(widget, data));
                ui.on("on_move_up_button_clicked", (widget, data) -> handler.on_move_up_button_clicked(widget, data));
                ui.on("on_move_top_button_clicked", (widget, data) -> handler.on_move_top_button_clicked(widget, data));
                ui.on("on_move_down_button_clicked",
                                (widget, data) -> handler.on_move_down_button_clicked(widget, data));
                ui.on("on_move_bottom_button_clicked",
                                (widget, data) -> handler.on_move_bottom_button_clicked(widget, data));
                ui.on("on_settings_button_clicked", (widget, data) -> handler.on_settings_button_clicked(widget, data));

                // Widget signals
                ui.on("on_tor_switch_state_set", (widget, data) -> handler.on_tor_switch_state_set(widget, data));
                ui.on("on_search_entry_activate", (widget, data) -> handler.on_search_entry_activate(widget, data));
                ui.on("on_search_entry_search_changed",
                                (widget, data) -> handler.on_search_entry_search_changed(widget, data));
                ui.on("on_search_entry_key_press_event",
                                (widget, data) -> handler.on_search_entry_key_press_event(widget, data));

                // Context menu signals
                ui.on("on_context_open_file_activate",
                                (widget, data) -> handler.on_context_open_file_activate(widget, data));
                ui.on("on_context_open_folder_activate",
                                (widget, data) -> handler.on_context_open_folder_activate(widget, data));
                ui.on("on_context_copy_magnet_activate",
                                (widget, data) -> handler.on_context_copy_magnet_activate(widget, data));
                ui.on("on_context_change_destination_activate",
                                (widget, data) -> handler.on_context_change_destination_activate(widget, data));
                ui.on("on_context_verify_data_activate",
                                (widget, data) -> handler.on_context_verify_data_activate(widget, data));
                ui.on("on_context_properties_activate",
                                (widget, data) -> handler.on_context_properties_activate(widget, data));

                // Connect all signals
                ui.connectSignals();

                LOGGER.info("Main window signal handlers configured");
        }

        // ====== Public API Methods ======
        /**
         * Shows the main window with proper presentation.
         */
        @Override
        public void show() {
                try {
                        if (ui != null && isInitialized) {
                                // Show the window and all its children
                                ui.showAll(getWindowName());

                                // Present the window to bring it to foreground
                                Pointer window = getWindow();
                                if (window != null) {
                                        GtkNativeLibraries.Gtk.INSTANCE.gtk_window_present(window);
                                        LOGGER.info("Main window displayed successfully");
                                }
                        }
                } catch (Exception e) {
                        LOGGER.severe("Error showing main window: " + e.getMessage());
                        // Fall back to base implementation
                        super.show();
                }
        }

        /**
         * Hides the main window.
         */
        public void hide() {
                if (ui != null && isInitialized) {
                        ui.hide(getWindowName());
                        LOGGER.info("Main window hidden");
                }
        }

        /**
         * Gets the UI instance for access by the main application.
         *
         * @return the GladeUI instance, or null if not yet initialized
         */
        public GladeUI getUI() {
                return ui;
        }

        /**
         * Gets the main window widget pointer.
         */
        public Pointer getMainWindow() {
                return getWindow();
        }

        /**
         * Updates the status bar with a message.
         */
        public void updateStatusBarMessage(String message) {
                if (service != null) {
                        service.updateStatusBarMessage(message);
                }
        }

        /**
         * Updates the status bar.
         */
        public void updateStatusBar() {
                if (service != null) {
                        service.updateStatusBar();
                }
        }

        /**
         * Refreshes the download list.
         */
        public void refreshDownloadList() {
                if (service != null) {
                        service.refreshDownloadList();
                }
        }

        /**
         * Clears the current search filter - Requirement-8: Clear search results.
         */
        public void clearSearch() {
                if (service != null) {
                        service.clearSearch();
                }
        }

        /**
         * Gets the current search text.
         * 
         * @return the current search text
         */
        public String getCurrentSearchText() {
                if (service != null) {
                        return service.getCurrentSearchText();
                }
                return "";
        }

        /**
         * Checks if search is currently active.
         * 
         * @return true if there is an active search filter
         */
        public boolean hasActiveSearch() {
                if (service != null) {
                        return service.hasActiveSearch();
                }
                return false;
        }

        // ====== DownloadListener Implementation ======
        @Override
        public void onDownloadStart(Download download) {
                LOGGER.info("Download started: " + download.getName());
                if (service != null) {
                        service.onDownloadStart(download);
                }
        }

        @Override
        public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                        long totalBytes, float speed) {
                // Progress updates are handled by periodic refresh to avoid overwhelming the UI
                if (service != null) {
                        service.onDownloadProgress(download);
                }
        }

        @Override
        public void onDownloadPause(Download download) {
                LOGGER.info("Download paused: " + download.getName());
                if (service != null) {
                        service.onDownloadPause(download);
                }
        }

        @Override
        public void onDownloadResume(Download download) {
                LOGGER.info("Download resumed: " + download.getName());
                if (service != null) {
                        service.onDownloadResume(download);
                }
        }

        @Override
        public void onDownloadComplete(Download download) {
                LOGGER.info("Download completed: " + download.getName());
                if (service != null) {
                        service.onDownloadComplete(download);
                }
        }

        @Override
        public void onDownloadError(Download download, String error) {
                LOGGER.warning("Download error for " + download.getName() + ": " + error);
                if (service != null) {
                        service.onDownloadError(download);
                }
        }

        @Override
        public void onDownloadCanceled(Download download) {
                LOGGER.info("Download cancelled: " + download.getName());
                if (service != null) {
                        service.onDownloadCanceled(download);
                }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void cleanup() {
                try {
                        // Remove download manager listener
                        if (downloadManager != null) {
                                downloadManager.removeDownloadListener(this);
                                LOGGER.info("Removed download manager listener");
                        }

                        if (service != null) {
                                service.cleanup();
                        }
                        LOGGER.info("Main window cleanup completed");
                } catch (Exception e) {
                        LOGGER.severe("Error during cleanup: " + e.getMessage());
                } finally {
                        // Call parent cleanup to handle UI resources
                        super.cleanup();
                }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdown() {
                try {
                        if (service != null) {
                                service.saveWindowState();
                        }
                } catch (Exception e) {
                        LOGGER.warning("Error saving window state during shutdown: " + e.getMessage());
                } finally {
                        super.shutdown();
                }
        }
}
