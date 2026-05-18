package org.odm.ui.controller;

import java.util.logging.Logger;

import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.odm.ui.BaseDialog;
import org.odm.ui.handler.DownloadPropertySignalHandler;
import org.odm.ui.service.DownloadPropertyService;

import com.sun.jna.Pointer;

/**
 * Controller for the Download Properties dialog.
 *
 * Manages the dialog that displays detailed information about a download
 * including general information, progress, files, trackers, peers, and
 * settings. Provides functionality to modify download settings such as: -
 * Maximum connections and retry limits - Bandwidth limits (download/upload
 * speed) - Proxy configuration (type, host, port, credentials) - Retry behavior
 * and timing
 *
 * This controller follows the refactored architecture:
 * - DownloadPropertyController: Extends BaseDialog, manages dialog lifecycle
 * - DownloadPropertyHandler: Handles GTK signal events
 * - DownloadPropertyService: Contains business logic and core functionality
 */
public class DownloadPropertyController extends BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(DownloadPropertyController.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;

    // Refactored architecture components
    private DownloadPropertyService service;
    private DownloadPropertySignalHandler handler;

    /**
     * Constructor.
     */
    public DownloadPropertyController(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
        // Service will be initialized in initializeDialog() when UI is available
    }

    /**
     * Shows the properties dialog for a download item.
     *
     * @param parentWindow the parent window pointer (can be null)
     * @param download     the download item to show properties for
     * @return true if the dialog was accepted, false if canceled
     */
    public boolean showDialog(Pointer parentWindow, Download download) {
        if (download == null) {
            LOGGER.warning("Cannot show properties for null download");
            return false;
        }

        try {
            // Set the download first, then show the dialog
            // We need to create a temporary service to set the download
            // The actual service will be created in initializeDialog()
            currentDownload = download;
            return showDialog(parentWindow);
        } catch (Exception e) {
            LOGGER.severe("Error showing properties dialog: " + e.getMessage());
            return false;
        }
    }

    /**
     * Convenience method to show properties dialog with default parent.
     *
     * @param download the download item to show properties for
     * @return true if the dialog was accepted, false if canceled
     */
    public boolean showProperties(Download download) {
        return showDialog(null, download);
    }

    // Temporary storage for the download until service is initialized
    private Download currentDownload;

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getGladeResourcePath() {
        return "/glade/download-property/property.glade";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDialogName() {
        return "property_dialog";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initializeDialog() {
        // Initialize service and handler now that UI is available
        service = new DownloadPropertyService(downloadManager, ui);
        service.setCloseDialogCallback(this::setResultAndClose);

        // Set the current download if we have one
        if (currentDownload != null) {
            service.setCurrentDownload(currentDownload);
        }

        handler = new DownloadPropertySignalHandler(service, ui);

        // Initialize the service (replaces old initialization logic)
        service.initializeService();

        LOGGER.info("Download properties dialog initialized with refactored architecture");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setupSignalHandlers() {
        // Dialog window signals
        ui.on("on_property_dialog_destroy", (widget, data) -> handler.on_property_dialog_destroy(widget, data));
        ui.on("on_property_dialog_delete_event",
                (widget, data) -> handler.on_property_dialog_delete_event(widget, data));

        // Button signals
        ui.on("on_ok_button_clicked", (widget, data) -> handler.on_ok_button_clicked(widget, data));
        ui.on("on_cancel_button_clicked", (widget, data) -> handler.on_cancel_button_clicked(widget, data));
        ui.on("on_apply_button_clicked", (widget, data) -> handler.on_apply_button_clicked(widget, data));
        ui.on("on_browse_button_clicked", (widget, data) -> handler.on_browse_button_clicked(widget, data));

        // Tracker management signals
        ui.on("on_add_tracker_button_clicked", (widget, data) -> handler.on_add_tracker_button_clicked(widget, data));
        ui.on("on_remove_tracker_button_clicked",
                (widget, data) -> handler.on_remove_tracker_button_clicked(widget, data));

        // Settings signals
        ui.on("on_proxy_type_combo_changed", (widget, data) -> handler.on_proxy_type_combo_changed(widget, data));

        // Connect all signals
        ui.connectSignals();

        LOGGER.info("Download properties dialog signal handlers configured");
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
            LOGGER.info("Download properties dialog cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        } finally {
            // Call parent cleanup to handle UI resources
            super.cleanup();
        }
    }
}
