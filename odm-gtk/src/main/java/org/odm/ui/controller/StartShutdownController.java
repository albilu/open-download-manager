package org.odm.ui.controller;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.odm.ui.service.StartShutdownService;

/**
 * Controller for the Startup/Shutdown progress dialog.
 * 
 * Displays ODM logo, status message, and progress bar during startup and
 * shutdown.
 * This is a specialized controller that doesn't follow the standard modal
 * dialog pattern
 * as it's used for programmatic progress display rather than user interaction.
 * 
 * This controller follows the refactored architecture:
 * - StartupShutdownDialogController: Manages dialog lifecycle and provides
 * public API
 * - StartupShutdownDialogHandler: Handles GTK signal events (minimal for this
 * dialog)
 * - StartupShutdownDialogService: Contains business logic and core
 * functionality
 */
public class StartShutdownController {

    private static final Logger LOGGER = Logger.getLogger(StartShutdownController.class.getName());

    // Refactored architecture components
    private GladeUI ui;
    private StartShutdownService service;

    /**
     * Creates a new StartupShutdownDialogController.
     */
    public StartShutdownController() {
        loadUI();
    }

    /**
     * Loads the dialog UI from Glade and initializes the service/handler
     * architecture.
     */
    private void loadUI() {
        try {
            // Load the Glade file for the startup/shutdown dialog
            ui = new GladeUI();
            boolean loaded = ui.loadFromResource("/glade/start-shutdown.glade");
            if (!loaded) {
                throw new RuntimeException("Failed to load startup/shutdown dialog Glade file");
            }

            // Initialize service and handler
            service = new StartShutdownService(ui);

            // Initialize the service (replaces old initialization logic)
            service.initializeService();

            LOGGER.info("Startup/Shutdown dialog loaded successfully with refactored architecture");

        } catch (Exception e) {
            LOGGER.severe("Failed to load startup/shutdown dialog: " + e.getMessage());
            throw new RuntimeException("Startup/Shutdown dialog loading failed", e);
        }
    }

    /**
     * Shows the dialog.
     */
    public void showDialog() {
        if (service != null) {
            service.showDialog();
        }
    }

    /**
     * Hides the dialog.
     */
    public void hideDialog() {
        if (service != null) {
            service.hideDialog();
        }
    }

    /**
     * Updates the status message displayed in the dialog.
     *
     * @param message The message to display.
     */
    public void setStatusMessage(String message) {
        if (service != null) {
            service.setStatusMessage(message);
        }
    }

    /**
     * Updates the progress bar value.
     *
     * @param fraction Progress fraction (0.0 to 1.0).
     */
    public void setProgress(double fraction) {
        if (service != null) {
            service.setProgress(fraction);
        }
    }

    /**
     * Forces the dialog to be visible and on top.
     */
    public void ensureVisible() {
        if (service != null) {
            service.ensureVisible();
        }
    }

    /**
     * Destroys the dialog and releases resources.
     */
    public void destroy() {
        try {
            if (service != null) {
                service.cleanup();
            }
            if (ui != null) {
                ui.destroy();
                ui = null;
            }
            service = null;
            LOGGER.info("Startup/Shutdown dialog destroyed");
        } catch (Exception e) {
            LOGGER.warning("Error during startup/shutdown dialog cleanup: " + e.getMessage());
        }
    }

    /**
     * Gets the UI instance for external access (maintains compatibility).
     * 
     * @return The GladeUI instance
     */
    public GladeUI getUI() {
        return ui;
    }
}
