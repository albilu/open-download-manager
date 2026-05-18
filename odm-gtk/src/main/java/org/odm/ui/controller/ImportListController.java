package org.odm.ui.controller;

import java.util.List;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.odm.ui.BaseDialog;
import org.odm.ui.handler.ImportListSignalHandler;
import org.odm.ui.service.ImportListService;

import com.sun.jna.Pointer;

/**
 * Controller for the Import from Text File dialog.
 *
 * Manages the dialog for importing downloads from text files containing URLs,
 * clipboard content, or manual URL entry with filtering and validation.
 * 
 * This controller follows the refactored architecture:
 * - ImportListController: Extends BaseDialogController, manages dialog
 * lifecycle
 * - ImportListHandler: Handles GTK signal events
 * - ImportListService: Contains business logic and core functionality
 */
public class ImportListController extends BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(ImportListController.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;

    // Refactored architecture components
    private ImportListService service;
    private ImportListSignalHandler handler;

    /**
     * Constructor.
     */
    public ImportListController(DownloadManager downloadManager, GlobalSettings settings) {
        this.downloadManager = downloadManager;
        this.settings = settings;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getGladeResourcePath() {
        return "/glade/import-from/import-list.glade";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDialogName() {
        return "import_dialog";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initializeDialog() {
        // Initialize service and handler now that UI is available
        service = new ImportListService(downloadManager, settings, ui);
        service.setCloseDialogCallback(this::setResultAndClose);

        handler = new ImportListSignalHandler(service, ui);

        // Initialize the service (replaces old initialization logic)
        service.initializeService();

        LOGGER.info("Import list dialog initialized with refactored architecture");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setupSignalHandlers() {
        // Dialog signals
        ui.on("on_import_dialog_response", (widget, data) -> handler.on_import_dialog_response(widget, data));

        // Button signals
        ui.on("on_cancel_button_clicked", (widget, data) -> handler.on_cancel_button_clicked(widget, data));
        ui.on("on_validate_button_clicked", (widget, data) -> handler.on_validate_button_clicked(widget, data));

        // Notebook page switch
        ui.on("on_options_notebook_switch_page",
                (widget, data) -> handler.on_options_notebook_switch_page(widget, data));

        // Filter and tree view signals
        ui.on("on_extension_filter_combo_changed",
                (widget, data) -> handler.on_extension_filter_combo_changed(widget, data));
        ui.on("on_url_treeview_row_activated", (widget, data) -> handler.on_url_treeview_row_activated(widget, data));
        ui.on("on_url_treeview_selection_changed",
                (widget, data) -> handler.on_url_treeview_selection_changed(widget, data));
        ui.on("on_mark_renderer_toggled", (widget, data) -> handler.on_mark_renderer_toggled(widget, data));

        // Settings spin button signals
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

        // Network settings signals
        ui.on("on_tor_switch_state_set", (widget, data) -> handler.on_tor_switch_state_set(widget, data));
        ui.on("on_start_automatically_check_toggled",
                (widget, data) -> handler.on_start_automatically_check_toggled(widget, data));

        // Connect all signals
        ui.connectSignals();

        LOGGER.info("Import list dialog signal handlers configured");
    }

    /**
     * Shows the dialog with pre-loaded URLs from clipboard or file.
     * 
     * @param parentWindow Parent window pointer
     * @param urls         List of URLs to pre-load
     * @return true if the dialog was confirmed, false otherwise
     */
    public boolean showDialog(Pointer parentWindow, List<String> urls) {
        // Set URLs before showing dialog
        if (urls != null && !urls.isEmpty()) {
            // We need to store this temporarily since service isn't initialized yet
            // The service will be initialized when showDialog() calls initializeDialog()
            showDialogWithUrls(parentWindow, urls);
            return dialogResult;
        }
        return showDialog(parentWindow);
    }

    /**
     * Shows the dialog with pre-loaded URLs.
     */
    private void showDialogWithUrls(Pointer parentWindow, List<String> urls) {
        try {
            this.shouldClose = false; // Reset close flag

            ui = new org.jgtk.GladeUI();
            boolean loaded = ui.loadFromResource(getGladeResourcePath());
            if (!loaded) {
                throw new RuntimeException("Failed to load Glade file: " + getGladeResourcePath());
            }

            // Set up dialog
            setupSignalHandlers();
            initializeDialog();

            // Set the URLs after service is initialized
            if (service != null) {
                service.setImportedUrls(urls);
            }

            dialogResult = false;

            // Show dialog
            ui.showAll(getDialogName());

            // Simple event loop until the dialog should close
            try {
                while (!shouldClose && ui != null) {
                    ui.processEvents();
                    try {
                        Thread.sleep(10); // Small delay to prevent busy waiting
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Dialog event loop interrupted: " + e.getMessage());
            }

        } catch (Exception e) {
            LOGGER.severe("Error showing dialog: " + e.getMessage());
        } finally {
            cleanup();
        }
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
            LOGGER.info("Import list dialog cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        } finally {
            // Call parent cleanup to handle UI resources
            super.cleanup();
        }
    }
}
