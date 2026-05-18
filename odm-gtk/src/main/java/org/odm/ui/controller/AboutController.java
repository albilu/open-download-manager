package org.odm.ui.controller;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.odm.ui.BaseDialog;
import org.odm.ui.handler.AboutSignalHandler;
import org.odm.ui.service.AboutService;

import com.sun.jna.Pointer;

/**
 * Controller for the About dialog.
 *
 * Manages the About dialog which uses properties defined in the Glade file.
 * Only handles dialog lifecycle and signal management.
 *
 * This controller follows the refactored architecture: - AboutDialogController:
 * Extends BaseDialogController, manages dialog lifecycle - AboutDialogHandler:
 * Handles GTK signal events - AboutDialogService: Contains business logic and
 * core functionality
 */
public class AboutController extends BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(AboutController.class.getName());

    // Refactored architecture components
    private AboutService service;
    private AboutSignalHandler handler;

    /**
     * Creates a new AboutDialogController.
     */
    public AboutController() {
        // Service will be initialized in initializeDialog() when UI is available
    }

    /**
     * Convenience method to show about dialog with default parent.
     */
    public void show() {
        showDialog(null);
    }

    /**
     * Shows the About dialog with proper error handling and fallback.
     *
     * @param parentWindow The parent window for the dialog
     * @return true if the dialog was shown successfully, false otherwise
     */
    @Override
    public boolean showDialog(Pointer parentWindow) {
        try {
            // Check if we need to create or recreate the UI (similar to original logic)
            if (ui == null || service == null || !service.isDialogValid()) {
                LOGGER.log(Level.INFO, "Creating new About dialog UI (ui={0}, service={1}, valid={2})",
                        new Object[] { ui == null ? "null" : "exists", service == null ? "null" : "exists",
                                service != null ? service.isDialogValid() : "false" });

                // Reset state if needed
                if (ui != null) {
                    cleanup();
                }

                // Create new dialog
                return super.showDialog(parentWindow);
            }

            // Show existing dialog
            if (ui != null) {
                ui.show(getDialogName());
                LOGGER.info("About dialog displayed");
                return true;
            }

            return super.showDialog(parentWindow);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to show about dialog: {0}", e.getMessage());
            // Show a simple error message if about dialog fails
            service.showSimpleAbout(parentWindow);
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getGladeResourcePath() {
        return "/glade/about.glade";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getDialogName() {
        return "about_dialog";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void initializeDialog() {
        // Initialize service and handler now that UI is available
        service = new AboutService(ui);
        service.setCloseDialogCallback(this::setResultAndClose);

        handler = new AboutSignalHandler(service, ui);

        // Initialize the service (replaces old initialization logic)
        service.initializeService();

        LOGGER.info("About dialog initialized with refactored architecture");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setupSignalHandlers() {
        // Handle dialog response (close button, escape key, etc.)
        ui.on("on_about_dialog_destroy", (widget, data) -> {
            handler.on_about_dialog_destroy(widget, data);
            resetDialogState();
        });

        // Connect all signals
        ui.connectSignals();

        LOGGER.info("About dialog signal handlers setup completed");
    }

    /**
     * Resets the dialog state after GTK destroys the widgets. This allows a
     * fresh dialog to be created on next invocation.
     */
    private void resetDialogState() {
        LOGGER.info("Resetting About dialog state after GTK destruction");
        // Don't call ui.destroy() here as GTK has already destroyed the widgets
        service = null;
        handler = null;
        ui = null;
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
            LOGGER.info("About dialog cleanup completed");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during cleanup: {0}", e.getMessage());
        } finally {
            // Call parent cleanup to handle UI resources
            super.cleanup();
        }
    }

}
