package org.odm.ui.handler;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.odm.ui.service.DownloadPropertyService;

import com.sun.jna.Pointer;

/**
 * Handles all GTK signal events for the Download Properties dialog.
 * Delegates business logic to DownloadPropertyService.
 */
public class DownloadPropertySignalHandler {

    private static final Logger LOGGER = Logger.getLogger(DownloadPropertySignalHandler.class.getName());

    private final DownloadPropertyService service;

    public DownloadPropertySignalHandler(DownloadPropertyService service, GladeUI ui) {
        this.service = service;
    }

    // ====== Signal Handlers ======

    /**
     * Handler for OK button click
     */
    public void on_ok_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("OK button clicked");
        service.handleOk();
    }

    /**
     * Handler for Cancel button click
     */
    public void on_cancel_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Cancel button clicked");
        service.handleCancel();
    }

    /**
     * Handler for Apply button click
     */
    public void on_apply_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Apply button clicked");
        service.handleApply();
    }

    /**
     * Handler for Browse button click
     */
    public void on_browse_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Browse button clicked");
        service.handleBrowse();
    }

    /**
     * Handler for Add Tracker button click
     */
    public void on_add_tracker_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Add tracker button clicked");
        service.handleAddTracker();
    }

    /**
     * Handler for Remove Tracker button click
     */
    public void on_remove_tracker_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Remove tracker button clicked");
        service.handleRemoveTracker();
    }

    /**
     * Handler for proxy type combo box changes
     */
    public void on_proxy_type_combo_changed(Pointer widget, Pointer data) {
        LOGGER.info("Proxy type combo changed");
        service.handleProxyTypeChange();
    }

    /**
     * Handler for dialog destroy signal
     */
    public void on_property_dialog_destroy(Pointer widget, Pointer data) {
        LOGGER.info("Properties dialog destroyed");
        service.handleCancel();
    }

    /**
     * Handler for dialog delete event
     */
    public void on_property_dialog_delete_event(Pointer widget, Pointer data) {
        LOGGER.info("Properties dialog delete event");
        service.handleCancel();
    }
}
