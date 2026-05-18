package org.odm.ui.controller;

import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.odm.ui.BaseDialog;
import org.odm.ui.handler.NewDownloadSignalHandler;
import org.odm.ui.service.NewDownloadService;

import com.sun.jna.Pointer;

/**
 * Controller for the New Download dialog.
 *
 * Manages the dialog for creating new downloads with various options. Uses the
 * new GladeUI architecture with JNA for GTK interaction.
 *
 * This controller follows the refactored architecture: - NewDownloadController:
 * Extends BaseDialog, manages dialog lifecycle - NewDownloadHandler: Handles
 * GTK signal events - NewDownloadService: Contains business logic and core
 * functionality
 */
public class NewDownloadController extends BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(NewDownloadController.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;

    // Refactored architecture components
    private NewDownloadService service;
    private NewDownloadSignalHandler handler;

    // Store URL for pre-filling when dialog is initialized
    private String prefilledUrl = "";

    /**
     * Creates a new NewDownloadController.
     *
     * @param downloadManager The core download manager
     * @param settings        Application settings
     */
    public NewDownloadController(DownloadManager downloadManager, GlobalSettings settings) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        // Service and handler will be initialized in initializeDialog() when UI is
        // available
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getGladeResourcePath() {
        return "/glade/new/new-download.glade";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDialogName() {
        return "new_download_dialog";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initializeDialog() {
        // Initialize service and handler now that UI is available
        service = new NewDownloadService(downloadManager, settings, ui);
        service.setCloseDialogCallback(this::setResultAndClose);

        handler = new NewDownloadSignalHandler(service, ui);

        // Initialize the service (replaces old initialization logic)
        service.initializeService();

        // Pre-fill URL if provided
        if (!prefilledUrl.isEmpty()) {
            service.setCurrentUrl(prefilledUrl);
            service.prefillUrl(prefilledUrl);
        }

        LOGGER.info("New download dialog initialized with refactored architecture");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setupSignalHandlers() {
        // Dialog window signals
        ui.on("on_new_download_dialog_destroy", (widget, data) -> handler.onDialogDestroy(widget, data));
        ui.on("on_new_download_dialog_delete_event", (widget, data) -> handler.onDialogDeleteEvent(widget, data));

        // Button signals
        ui.on("on_ok_button_clicked", (widget, data) -> handler.onOkButtonClicked(widget, data));
        ui.on("on_cancel_button_clicked", (widget, data) -> handler.onCancelButtonClicked(widget, data));
        ui.on("on_browse_button_clicked", (widget, data) -> handler.onBrowseButtonClicked(widget, data));
        ui.on("on_analyze_button_clicked", (widget, data) -> handler.onAnalyzeButtonClicked(widget, data));
        ui.on("on_new_download_cancel_button_clicked",
                (widget, data) -> handler.on_new_download_cancel_button_clicked(widget, data));
        ui.on("on_new_download_start_button_clicked",
                (widget, data) -> handler.on_new_download_start_button_clicked(widget, data));

        // Notebook page switch - Two approaches available:

        // Approach 1: Legacy approach (backward compatible)
        // ui.on("on_options_notebook_switch_page",
        // (widget, data) -> handler.on_options_notebook_switch_page(widget, data));

        // Approach 2: Enhanced approach with direct page parameter access
        // Uncomment the line below and comment the one above to use the enhanced
        // approach:
        ui.onNotebookSwitchPage("on_options_notebook_switch_page",
                (notebook, page, pageNum, userData) -> handler.onNotebookPageSwitchEnhanced(notebook, page, pageNum,
                        userData));

        // URL entry signals
        ui.on("on_url_entry_changed", (widget, data) -> handler.onUrlEntryChanged(widget, data));
        ui.on("on_url_entry_activate", (widget, data) -> handler.onUrlEntryActivated(widget, data));

        // File chooser signals
        ui.on("on_torrent_file_chooser_file_set",
                (widget, data) -> handler.on_torrent_file_chooser_file_set(widget, data));
        ui.on("on_save_folder_chooser_file_set",
                (widget, data) -> handler.on_save_folder_chooser_file_set(widget, data));

        // Entry change signals
        ui.on("on_filename_entry_changed", (widget, data) -> handler.on_filename_entry_changed(widget, data));

        // TreeView signals
        ui.on("on_files_treeview_row_activated",
                (widget, data) -> handler.on_files_treeview_row_activated(widget, data));
        ui.on("on_files_treeview_selection_changed",
                (widget, data) -> handler.on_files_treeview_selection_changed(widget, data));
        ui.on("on_selected_renderer_toggled", (widget, data) -> handler.on_selected_renderer_toggled(widget, data));

        // Spin button value change signals
        ui.on("on_max_connections_spin_value_changed",
                (widget, data) -> handler.on_max_connections_spin_value_changed(widget, data));
        ui.on("on_retry_limit_spin_value_changed",
                (widget, data) -> handler.on_retry_limit_spin_value_changed(widget, data));
        ui.on("on_max_download_speed_spin_value_changed",
                (widget, data) -> handler.on_max_download_speed_spin_value_changed(widget, data));
        ui.on("on_max_upload_speed_spin_value_changed",
                (widget, data) -> handler.on_max_upload_speed_spin_value_changed(widget, data));
        ui.on("on_retry_after_value_changed", (widget, data) -> handler.on_retry_after_value_changed(widget, data));

        // Proxy settings signals
        ui.on("on_proxy_type_combo_changed", (widget, data) -> handler.on_proxy_type_combo_changed(widget, data));
        ui.on("on_proxy_host_entry_changed", (widget, data) -> handler.on_proxy_host_entry_changed(widget, data));
        ui.on("on_proxy_port_spin_value_changed",
                (widget, data) -> handler.on_proxy_port_spin_value_changed(widget, data));
        ui.on("on_proxy_username_entry_changed",
                (widget, data) -> handler.on_proxy_username_entry_changed(widget, data));
        ui.on("on_proxy_password_entry_changed",
                (widget, data) -> handler.on_proxy_password_entry_changed(widget, data));

        // Authentication checkbox
        ui.on("on_use_auth_check_toggled", (widget, data) -> handler.onUseAuthToggled(widget, data));

        // Proxy checkbox
        ui.on("on_use_proxy_check_toggled", (widget, data) -> handler.onUseProxyToggled(widget, data));

        // Input method switching signals
        ui.on("on_url_entry_focus_in_event", (widget, data) -> handler.on_url_entry_focus_in_event(widget, data));
        ui.on("on_torrent_file_chooser_button_press_event",
                (widget, data) -> handler.on_torrent_file_chooser_button_press_event(widget, data));
        ui.on("on_clear_url_button_clicked", (widget, data) -> handler.on_clear_url_button_clicked(widget, data));
        ui.on("on_clear_torrent_button_clicked",
                (widget, data) -> handler.on_clear_torrent_button_clicked(widget, data));

        // Options tab signals
        ui.on("on_start_automatically_check_toggled",
                (widget, data) -> handler.on_start_automatically_check_toggled(widget, data));
        ui.on("on_move_torrent_check_toggled",
                (widget, data) -> handler.on_move_torrent_check_toggled(widget, data));
        ui.on("on_tor_switch_state_set", (widget, data) -> handler.on_tor_switch_state_set(widget, data));

        // Connect all signals
        ui.connectSignals();

        LOGGER.info("New download dialog signal handlers configured");
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
            LOGGER.info("New download dialog cleanup completed");
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
     * Shows the new download dialog.
     *
     * @param parentWindow the parent window pointer (can be null)
     * @return true if user clicked OK and download was created
     */
    public boolean showDialog(Pointer parentWindow) {
        return showDialog(parentWindow, "");
    }

    /**
     * Shows the new download dialog with default parameters. Used by
     * MainWindowController when dialog controllers are available.
     *
     * @return true if user clicked OK and download was created
     */
    public boolean show() {
        return showDialog(null, "");
    }

    /**
     * Convenience method to show new download dialog with a pre-filled URL.
     *
     * @param url the URL to pre-fill
     * @return true if user clicked OK and download was created
     */
    public boolean show(String url) {
        return showDialog(null, url);
    }

    /**
     * Shows the new download dialog with a pre-filled URL.
     *
     * @param parentWindow the parent window pointer (can be null)
     * @param url          the URL to pre-fill
     * @return true if user clicked OK and download was created
     */
    public boolean showDialog(Pointer parentWindow, String url) {
        try {
            // Store URL for pre-filling when dialog is initialized
            this.prefilledUrl = url != null ? url : "";

            // Use the BaseDialog showDialog method
            return super.showDialog(parentWindow);

        } catch (Exception e) {
            LOGGER.severe("Error showing new download dialog: " + e.getMessage());
            return false;
        }
    }

    /**
     * Switches input method to URL entry by clearing torrent file selection.
     */
    public void switchToUrlEntry() {
        if (service != null) {
            service.handleClearTorrentButton();
        }
    }

    /**
     * Switches input method to torrent file chooser by clearing URL entry.
     */
    public void switchToTorrentFileChooser() {
        if (service != null) {
            service.handleClearUrlButton();
        }
    }

    /**
     * Previews what download type would be detected for the current URL. Useful
     * for debugging and testing type detection logic.
     */
    public org.manager.download.Download.Type previewDownloadType() {
        if (service != null) {
            return service.previewDownloadType();
        }
        return org.manager.download.Download.Type.ARIA2; // Safe fallback
    }

    /**
     * Sets the current URL for pre-filling (used when URL is provided before
     * dialog is shown).
     *
     * @param url the URL to pre-fill
     */
    public void setPrefilledUrl(String url) {
        this.prefilledUrl = url != null ? url : "";
        if (service != null) {
            service.setCurrentUrl(url);
        } else {
            // Store for later use when service is initialized
            LOGGER.info("Storing URL for later use: " + url);
        }
    }
}
