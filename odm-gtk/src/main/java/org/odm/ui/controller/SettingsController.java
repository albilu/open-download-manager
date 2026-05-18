package org.odm.ui.controller;

import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.odm.ui.BaseDialog;
import org.odm.ui.handler.SettingsSignalHandler;
import org.odm.ui.service.SettingsService;

import com.sun.jna.Pointer;

/**
 * Controller for the Settings dialog.
 *
 * Manages the application settings dialog including download preferences,
 * network settings, proxy configuration, and other user preferences. Uses the
 * new GladeUI architecture with JNA for GTK interaction.
 * 
 * This controller follows the refactored architecture:
 * - SettingsController: Extends BaseDialog, manages dialog lifecycle
 * - SettingsHandler: Handles GTK signal events
 * - SettingsService: Contains business logic and core functionality
 */
public class SettingsController extends BaseDialog {

        private static final Logger LOGGER = Logger.getLogger(SettingsController.class.getName());

        // Core dependencies
        private final GlobalSettings settings;

        // Refactored architecture components
        private SettingsService service;
        private SettingsSignalHandler handler;

        /**
         * Creates a new SettingsController.
         *
         * @param settings Application settings
         */
        public SettingsController(GlobalSettings settings) {
                this.settings = settings;
                // Service and handler will be initialized in initializeDialog() when UI is
                // available
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getGladeResourcePath() {
                return "/glade/settings/settings.glade";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected String getDialogName() {
                return "settings_dialog";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void initializeDialog() {
                // Initialize service and handler now that UI is available
                service = new SettingsService(settings, ui);
                service.setCloseDialogCallback(this::setResultAndClose);

                handler = new SettingsSignalHandler(service, ui);

                // Initialize the service (replaces old initialization logic)
                service.initializeService();

                LOGGER.info("Settings dialog initialized with refactored architecture");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void setupSignalHandlers() {
                // Dialog window signals
                ui.on("on_settings_dialog_destroy", (widget, data) -> handler.onDialogDestroy(widget, data));
                ui.on("on_settings_dialog_delete_event", (widget, data) -> handler.onDialogDeleteEvent(widget, data));
                ui.on("on_settings_dialog_response",
                                (widget, data) -> handler.on_settings_dialog_response(widget, data));

                // Button signals
                ui.on("on_ok_button_clicked", (widget, data) -> handler.onOkButtonClicked(widget, data));
                ui.on("on_cancel_button_clicked", (widget, data) -> handler.onCancelButtonClicked(widget, data));
                ui.on("on_apply_button_clicked", (widget, data) -> handler.onApplyButtonClicked(widget, data));
                ui.on("on_reset_button_clicked", (widget, data) -> handler.onResetButtonClicked(widget, data));
                ui.on("on_settings_reset_button_clicked",
                                (widget, data) -> handler.on_settings_reset_button_clicked(widget, data));
                ui.on("on_settings_cancel_button_clicked",
                                (widget, data) -> handler.on_settings_cancel_button_clicked(widget, data));
                ui.on("on_settings_apply_button_clicked",
                                (widget, data) -> handler.on_settings_apply_button_clicked(widget, data));
                ui.on("on_settings_ok_button_clicked",
                                (widget, data) -> handler.on_settings_ok_button_clicked(widget, data));

                // Notebook page switch
                ui.on("on_settings_notebook_switch_page",
                                (widget, data) -> handler.on_settings_notebook_switch_page(widget, data));

                // Directory chooser buttons and file choosers
                ui.on("on_default_directory_button_clicked",
                                (widget, data) -> handler.onDefaultDirectoryButtonClicked(widget, data));
                ui.on("on_temp_directory_button_clicked",
                                (widget, data) -> handler.onTempDirectoryButtonClicked(widget, data));
                ui.on("on_default_download_folder_chooser_file_set",
                                (widget, data) -> handler.onDefaultDownloadFolderChooserFileSet(widget, data));

                // Proxy test button
                ui.on("on_test_proxy_button_clicked", (widget, data) -> handler.onTestProxyButtonClicked(widget, data));

                // Checkbox toggles that affect widget sensitivity
                ui.on("on_use_proxy_check_toggled", (widget, data) -> handler.onUseProxyToggled(widget, data));
                ui.on("on_enable_logging_check_toggled",
                                (widget, data) -> handler.onEnableLoggingToggled(widget, data));

                // General settings checkboxes
                ui.on("on_save_download_history_check_toggled",
                                (widget, data) -> handler.on_save_download_history_check_toggled(widget, data));
                ui.on("on_clipboard_monitor_check_toggled",
                                (widget, data) -> handler.on_clipboard_monitor_check_toggled(widget, data));
                ui.on("on_system_tray_check_toggled",
                                (widget, data) -> handler.on_system_tray_check_toggled(widget, data));
                ui.on("on_start_automatically_check1_toggled",
                                (widget, data) -> handler.on_start_automatically_check1_toggled(widget, data));
                ui.on("on_move_torrent_check1_toggled",
                                (widget, data) -> handler.on_move_torrent_check1_toggled(widget, data));
                ui.on("on_start_automatically_check2_toggled",
                                (widget, data) -> handler.on_start_automatically_check2_toggled(widget, data));
                ui.on("on_move_torrent_check2_toggled",
                                (widget, data) -> handler.on_move_torrent_check2_toggled(widget, data));
                ui.on("on_continue_download_check_toggled",
                                (widget, data) -> handler.on_continue_download_check_toggled(widget, data));
                ui.on("on_check_integrity_check_toggled",
                                (widget, data) -> handler.on_check_integrity_check_toggled(widget, data));
                ui.on("on_write_thumbnail_check_toggled",
                                (widget, data) -> handler.on_write_thumbnail_check_toggled(widget, data));
                ui.on("on_write_subtitles_check_toggled",
                                (widget, data) -> handler.on_write_subtitles_check_toggled(widget, data));
                ui.on("on_embed_metadata_check_toggled",
                                (widget, data) -> handler.on_embed_metadata_check_toggled(widget, data));
                ui.on("on_extract_audio_check_toggled",
                                (widget, data) -> handler.on_extract_audio_check_toggled(widget, data));

                // Spin button value changes
                ui.on("on_max_concurrent_downloads_spin_value_changed",
                                (widget, data) -> handler.on_max_concurrent_downloads_spin_value_changed(widget, data));
                ui.on("on_max_connections_spin_value_changed",
                                (widget, data) -> handler.on_max_connections_spin_value_changed(widget, data));
                ui.on("on_min_split_size_spin_value_changed",
                                (widget, data) -> handler.on_min_split_size_spin_value_changed(widget, data));
                ui.on("on_max_download_speed_spin_value_changed",
                                (widget, data) -> handler.on_max_download_speed_spin_value_changed(widget, data));
                ui.on("on_max_upload_speed_spin_value_changed",
                                (widget, data) -> handler.on_max_upload_speed_spin_value_changed(widget, data));
                ui.on("on_retry_after_value_changed",
                                (widget, data) -> handler.on_retry_after_value_changed(widget, data));
                ui.on("on_proxy_port_spin_value_changed",
                                (widget, data) -> handler.on_proxy_port_spin_value_changed(widget, data));

                // Combo box changes
                ui.on("on_proxy_type_combo_changed",
                                (widget, data) -> handler.on_proxy_type_combo_changed(widget, data));
                ui.on("on_file_allocation_combo_changed",
                                (widget, data) -> handler.on_file_allocation_combo_changed(widget, data));

                // Entry changes
                ui.on("on_proxy_host_entry_changed",
                                (widget, data) -> handler.on_proxy_host_entry_changed(widget, data));
                ui.on("on_proxy_username_entry_changed",
                                (widget, data) -> handler.on_proxy_username_entry_changed(widget, data));
                ui.on("on_proxy_password_entry_changed",
                                (widget, data) -> handler.on_proxy_password_entry_changed(widget, data));
                ui.on("on_aria2_path_entry_changed",
                                (widget, data) -> handler.on_aria2_path_entry_changed(widget, data));
                ui.on("on_ytdlp_path_entry_changed",
                                (widget, data) -> handler.on_ytdlp_path_entry_changed(widget, data));
                ui.on("on_video_format_entry_changed",
                                (widget, data) -> handler.on_video_format_entry_changed(widget, data));
                ui.on("on_subtitle_language_entry_changed",
                                (widget, data) -> handler.on_subtitle_language_entry_changed(widget, data));
                ui.on("on_httrack_path_entry_changed",
                                (widget, data) -> handler.on_httrack_path_entry_changed(widget, data));
                ui.on("on_curl_path_entry4_changed",
                                (widget, data) -> handler.on_curl_path_entry4_changed(widget, data));
                ui.on("on_curl_path_entry5_changed",
                                (widget, data) -> handler.on_curl_path_entry5_changed(widget, data));
                ui.on("on_curl_path_entry_7_changed",
                                (widget, data) -> handler.on_curl_path_entry_7_changed(widget, data));

                // Browse button clicks
                ui.on("on_browse_aria2_button_clicked",
                                (widget, data) -> handler.on_browse_aria2_button_clicked(widget, data));
                ui.on("on_browse_ytdlp_button_clicked",
                                (widget, data) -> handler.on_browse_ytdlp_button_clicked(widget, data));
                ui.on("on_browse_httrack_button_clicked",
                                (widget, data) -> handler.on_browse_httrack_button_clicked(widget, data));
                ui.on("on_browse_curl_button4_clicked",
                                (widget, data) -> handler.on_browse_curl_button4_clicked(widget, data));
                ui.on("on_browse_curl_button5_clicked",
                                (widget, data) -> handler.on_browse_curl_button5_clicked(widget, data));
                ui.on("on_browse_curl_button6_clicked",
                                (widget, data) -> handler.on_browse_curl_button6_clicked(widget, data));

                // Tor switch
                ui.on("on_tor_switch_state_set", (widget, data) -> handler.on_tor_switch_state_set(widget, data));

                // Connect all signals
                ui.connectSignals();

                LOGGER.info("Settings dialog signal handlers configured");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void cleanup() {
                try {
                        if (service != null) {
                                service.cleanup();
                        }
                        LOGGER.info("Settings dialog cleanup completed");
                } catch (Exception e) {
                        LOGGER.severe("Error during cleanup: " + e.getMessage());
                } finally {
                        // Call parent cleanup to handle UI resources
                        super.cleanup();
                }
        }

        // ================ Legacy Public API Methods ================
        // These methods maintain compatibility with existing callers

        /**
         * Shows the settings dialog.
         *
         * @param parentWindow Parent window pointer (can be null)
         * @return true if user clicked OK or Apply
         */
        public boolean showDialog(Pointer parentWindow) {
                return super.showDialog(parentWindow);
        }

        /**
         * Convenience method to show settings dialog with default parent.
         *
         * @return true if user clicked OK or Apply
         */
        public boolean show() {
                return showDialog(null);
        }
}
