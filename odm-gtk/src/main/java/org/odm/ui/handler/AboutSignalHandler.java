package org.odm.ui.handler;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.odm.ui.service.AboutService;

/**
 * Handler class for About dialog GTK signal events.
 * 
 * Manages GTK signal handling for the About dialog, delegating
 * business logic to the AboutDialogService.
 */
public class AboutSignalHandler {

    private static final Logger LOGGER = Logger.getLogger(AboutSignalHandler.class.getName());

    private final AboutService service;

    /**
     * Creates a new AboutDialogHandler.
     *
     * @param service The AboutService instance
     * @param ui      The GladeUI instance
     */
    public AboutSignalHandler(AboutService service, GladeUI ui) {
        this.service = service;
    }

    /**
     * Handles the about dialog destroy signal.
     *
     * @param widget The widget that emitted the signal
     * @param data   Signal data
     */
    public void on_about_dialog_destroy(Object widget, Object data) {
        LOGGER.info("About dialog destroy signal received");
        service.handleDialogDestroy();
    }
}
