package org.odm.ui.controller;

import org.odm.ui.BaseDialog;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.odm.ui.handler.ImportSequenceSignalHandler;
import org.odm.ui.service.ImportSequenceService;

/**
 * Controller for the Import URL Sequence dialog.
 *
 * Manages the dialog for importing downloads from URL sequences with patterns,
 * allowing users to generate multiple URLs based on numeric or character
 * sequences. Uses the new NewGladeUI architecture with JNA for GTK interaction.
 *
 * This controller follows the refactored architecture:
 * - ImportSequenceController: Extends BaseDialogController, manages dialog lifecycle
 * - ImportSequenceHandler: Handles GTK signal events
 * - ImportSequenceService: Contains business logic and core functionality
 */
public class ImportSequenceController extends BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(ImportSequenceController.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;

    // Refactored architecture components
    private ImportSequenceService service;
    private ImportSequenceSignalHandler handler;

    /**
     * Constructor.
     */
    public ImportSequenceController(DownloadManager downloadManager, GlobalSettings settings) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        // Service will be initialized in initializeDialog() when UI is available
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getGladeResourcePath() {
        return "/glade/import-from/import-sequence.glade";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDialogName() {
        return "import_sequence_dialog";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initializeDialog() {
        // Initialize service and handler now that UI is available
        service = new ImportSequenceService(downloadManager, settings, ui);
        service.setCloseDialogCallback(this::setResultAndClose);

        handler = new ImportSequenceSignalHandler(service, ui);

        // Initialize the service (replaces old initialization logic)
        service.initializeService();

        LOGGER.info("Import sequence dialog initialized with refactored architecture");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setupSignalHandlers() {
        // Dialog signals - with gtk_dialog_run(), we don't need the response signal
        // handler
        // The response is returned directly from gtk_dialog_run()
        // ui.on("on_import_sequence_dialog_response", (widget, data) ->
        // on_import_sequence_dialog_response(widget, data));

        // Button signals - not needed with action-widgets and gtk_dialog_run()
        ui.on("on_cancel_button_clicked", (widget, data) -> handler.on_cancel_button_clicked(widget, data));
        ui.on("on_validate_button_clicked", (widget, data) -> handler.on_validate_button_clicked(widget, data));

        // Notebook page switch
        ui.on("on_main_notebook_switch_page", (widget, data) -> handler.on_main_notebook_switch_page(widget, data));

        // URI entry signals
        ui.on("on_uri_entry_changed", (widget, data) -> handler.on_uri_entry_changed(widget, data));

        // Numeric sequence signals
        ui.on("on_num_combo_changed", (widget, data) -> handler.on_num_combo_changed(widget, data));
        ui.on("on_num_start_spin_value_changed", (widget, data) -> handler.on_num_start_spin_value_changed(widget, data));
        ui.on("on_num_vers_spin_value_changed", (widget, data) -> handler.on_num_vers_spin_value_changed(widget, data));
        ui.on("on_num_count_spin_value_changed", (widget, data) -> handler.on_num_count_spin_value_changed(widget, data));

        // Character sequence signals
        ui.on("on_char_combo_changed", (widget, data) -> handler.on_char_combo_changed(widget, data));
        ui.on("on_char_entry_changed", (widget, data) -> handler.on_char_entry_changed(widget, data));
        ui.on("on_char_vers_entry_changed", (widget, data) -> handler.on_char_vers_entry_changed(widget, data));

        // Preview tree view signals
        ui.on("on_preview_treeview_row_activated", (widget, data) -> handler.on_preview_treeview_row_activated(widget, data));
        ui.on("on_preview_treeview_selection_changed",
                (widget, data) -> handler.on_preview_treeview_selection_changed(widget, data));

        // Settings spin button signals
        ui.on("on_max_connections_spin_value_changed",
                (widget, data) -> handler.on_max_connections_spin_value_changed(widget, data));
        ui.on("on_retry_limit_spin_value_changed", (widget, data) -> handler.on_retry_limit_spin_value_changed(widget, data));
        ui.on("on_max_download_speed_spin_value_changed",
                (widget, data) -> handler.on_max_download_speed_spin_value_changed(widget, data));
        ui.on("on_max_upload_speed_spin_value_changed",
                (widget, data) -> handler.on_max_upload_speed_spin_value_changed(widget, data));
        ui.on("on_retry_after_value_changed", (widget, data) -> handler.on_retry_after_value_changed(widget, data));

        // Proxy settings signals
        ui.on("on_proxy_type_combo_changed", (widget, data) -> handler.on_proxy_type_combo_changed(widget, data));
        ui.on("on_proxy_host_entry_changed", (widget, data) -> handler.on_proxy_host_entry_changed(widget, data));
        ui.on("on_proxy_port_spin_value_changed", (widget, data) -> handler.on_proxy_port_spin_value_changed(widget, data));
        ui.on("on_proxy_username_entry_changed", (widget, data) -> handler.on_proxy_username_entry_changed(widget, data));
        ui.on("on_proxy_password_entry_changed", (widget, data) -> handler.on_proxy_password_entry_changed(widget, data));

        // Additional widget signals
        ui.on("on_tor_switch_state_set", (widget, data) -> handler.on_tor_switch_state_set(widget, data));
        ui.on("on_start_automatically_check_toggled",
                (widget, data) -> handler.on_start_automatically_check_toggled(widget, data));
        ui.on("on_move_torrent_check_toggled", (widget, data) -> handler.on_move_torrent_check_toggled(widget, data));

        // Connect all signals
        ui.connectSignals();

        LOGGER.info("Import sequence dialog signal handlers configured");
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
            LOGGER.info("Import sequence dialog cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        } finally {
            // Call parent cleanup to handle UI resources
            super.cleanup();
        }
    }
}
