package org.odm.ui.handler;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.odm.ui.service.StartShutdownService;

/**
 * Handler class for Startup/Shutdown dialog GTK signal events.
 * 
 * Manages GTK signal handling for the Startup/Shutdown dialog, delegating
 * business logic to the StartupShutdownDialogService.
 * 
 * Note: This dialog typically has minimal user interaction, so few signal
 * handlers are needed.
 */
public class StartShutdownSignalHandler {

    private static final Logger LOGGER = Logger.getLogger(StartShutdownSignalHandler.class.getName());

    /**
     * Creates a new StartupShutdownDialogHandler.
     *
     * @param service The StartShutdownService instance
     * @param ui      The GladeUI instance
     */
    public StartShutdownSignalHandler(StartShutdownService service, GladeUI ui) {
        // Handler initialized but minimal functionality needed for this dialog type
    }

    /**
     * Handles dialog destroy signal (if needed).
     *
     * @param widget The widget that emitted the signal
     * @param data   Signal data
     */
    public void on_startup_shutdown_dialog_destroy(Object widget, Object data) {
        LOGGER.info("Startup/Shutdown dialog destroy signal received");
        // This dialog is typically controlled programmatically, so minimal handling
        // needed
    }
}
