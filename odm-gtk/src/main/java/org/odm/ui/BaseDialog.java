package org.odm.ui;

import java.util.logging.Logger;

import org.jgtk.GladeUI;

import com.sun.jna.Pointer;

/**
 * Base class for dialog controllers providing common functionality.
 * Implements common dialog handling patterns, UI setup, and lifecycle
 * management.
 */
public abstract class BaseDialog {

    private static final Logger LOGGER = Logger.getLogger(BaseDialog.class.getName());

    protected GladeUI ui;
    protected boolean dialogResult = false;
    protected boolean shouldClose = false;

    /**
     * Resource path to the Glade UI definition file.
     * Must be overridden by subclasses.
     */
    protected abstract String getGladeResourcePath();

    /**
     * Name of the main dialog widget.
     * Must be overridden by subclasses.
     */
    protected abstract String getDialogName();

    /**
     * Initialize dialog with default values and settings.
     * Should be implemented by subclasses.
     */
    protected abstract void initializeDialog();

    /**
     * Setup signal handlers for the dialog.
     * Should be implemented by subclasses.
     */
    protected abstract void setupSignalHandlers();

    /**
     * Shows the dialog and blocks until it's closed.
     * Handles common dialog setup and cleanup.
     * 
     * @param parentWindow Parent window pointer
     * @return true if the dialog was confirmed, false otherwise
     */
    public boolean showDialog(Pointer parentWindow) {
        try {
            this.shouldClose = false; // Reset close flag

            ui = new GladeUI();
            boolean loaded = ui.loadFromResource(getGladeResourcePath());
            if (!loaded) {
                throw new RuntimeException("Failed to load Glade file: " + getGladeResourcePath());
            }

            // Set up dialog
            setupSignalHandlers();
            initializeDialog();
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

            return dialogResult;

        } catch (Exception e) {
            LOGGER.severe("Error showing dialog: " + e.getMessage());
            return false;
        } finally {
            cleanup();
        }
    }

    /**
     * Closes the dialog.
     */
    protected void closeDialog() {
        if (ui != null) {
            ui.hide(getDialogName());
        }
    }

    /**
     * Performs cleanup when the dialog is closed.
     */
    protected void cleanup() {
        try {
            if (ui != null) {
                ui.destroy();
                ui = null;
            }
            LOGGER.info("Dialog cleanup completed for " + getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Sets dialog result and requests dialog close.
     * 
     * @param result The dialog result
     */
    protected void setResultAndClose(boolean result) {
        dialogResult = result;
        shouldClose = true;
        closeDialog();
    }
}
