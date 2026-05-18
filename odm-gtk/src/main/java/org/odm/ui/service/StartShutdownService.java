package org.odm.ui.service;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.jgtk.core.GtkNativeLibraries;
import org.jgtk.core.GtkThreadDispatcher;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * Service class for Startup/Shutdown dialog business logic.
 *
 * Handles the core functionality of the Startup/Shutdown dialog including: -
 * Logo loading and management - Progress tracking and updates - Status message
 * management - Dialog visibility and state management
 */
public class StartShutdownService {

    private static final Logger LOGGER = Logger.getLogger(StartShutdownService.class.getName());

    private final GladeUI ui;
    private Pointer dialog;
    private Pointer logoImageWidget;
    private Pointer messageLabel;
    private Pointer progressBar;

    /**
     * Creates a new StartupShutdownDialogService.
     *
     * @param ui The GladeUI instance
     */
    public StartShutdownService(GladeUI ui) {
        this.ui = ui;
    }

    /**
     * Initializes the service and loads the startup/shutdown dialog components.
     */
    public void initializeService() {
        try {
            dialog = ui.getWidget("startup_shutdown_dialog");
            if (dialog == null) {
                throw new RuntimeException("Startup/Shutdown dialog widget not found in Glade file");
            }

            logoImageWidget = ui.getWidget("odm_logo_image");
            messageLabel = ui.getWidget("status_message_label");
            progressBar = ui.getWidget("progress_bar");

            // Load and set the logo programmatically
            loadLogo();

            LOGGER.info("Startup/Shutdown dialog service initialized successfully");

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize startup/shutdown dialog service: " + e.getMessage());
            throw new RuntimeException("Startup/Shutdown dialog service initialization failed", e);
        }
    }

    /**
     * Shows the dialog with proper configuration.
     */
    public void showDialog() {
        if (ui != null && dialog != null) {
            try {
                // Make sure the dialog is not modal during shutdown
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_set_modal(dialog, false);

                // Show the dialog and bring it to front
                ui.show("startup_shutdown_dialog");
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_present(dialog);

                LOGGER.info("Startup/Shutdown dialog displayed");
            } catch (Exception e) {
                LOGGER.warning("Error showing startup/shutdown dialog: " + e.getMessage());
            }
        }
    }

    /**
     * Hides the dialog.
     */
    public void hideDialog() {
        if (ui != null && dialog != null) {
            ui.hide("startup_shutdown_dialog");
            LOGGER.info("Startup/Shutdown dialog hidden");
        }
    }

    /**
     * Updates the status message displayed in the dialog.
     *
     * @param message The message to display.
     */
    public void setStatusMessage(String message) {
        if (ui != null && messageLabel != null) {
            // Use thread-safe dispatch for all GTK operations
            GtkThreadDispatcher.invokeLater(() -> {
                try {
                    ui.setLabelText("status_message_label", message != null ? message : "");
                    // Ensure the dialog is still visible
                    if (dialog != null) {
                        GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_show_all(dialog);
                    }
                } catch (Exception e) {
                    LOGGER.warning("Error setting status message: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Updates the progress bar value.
     *
     * @param fraction Progress fraction (0.0 to 1.0).
     */
    public void setProgress(double fraction) {
        if (ui != null && progressBar != null) {
            // Set progress bar fraction using GTK API with thread safety
            GtkThreadDispatcher.invokeLater(() -> {
                try {
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_progress_bar_set_fraction(progressBar,
                            Math.max(0.0, Math.min(1.0, fraction)));
                } catch (Exception e) {
                    LOGGER.warning("Failed to set progress bar fraction: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Forces the dialog to be visible and on top.
     */
    public void ensureVisible() {
        if (ui != null && dialog != null) {
            try {
                // Make sure dialog is not modal and is visible
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_set_modal(dialog, false);
                GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_show_all(dialog);
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_present(dialog);
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_set_keep_above(dialog, true);

                // Force focus to the dialog
                GtkNativeLibraries.Gtk.INSTANCE.gtk_window_set_focus(dialog, null);

                LOGGER.info("Dialog visibility ensured - shown, presented, and set to keep above");
            } catch (Exception e) {
                LOGGER.warning("Error ensuring dialog visibility: " + e.getMessage());
            }
        }
    }

    /**
     * Loads and sets the ODM logo in the dialog. Follows the
     * AboutDialogController approach.
     */
    private void loadLogo() {
        if (logoImageWidget != null) {
            // Use the actual logo path from resources
            try {
                String logoPath = "/images/logo-128.svg";
                // Extract resource to temp file for GTK
                try (var logoStream = getClass().getResourceAsStream(logoPath)) {
                    if (logoStream != null) {
                        var tempFile = java.nio.file.Files.createTempFile("odm-logo", ".svg");
                        Files.copy(logoStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                        PointerByReference error = new PointerByReference();
                        Pointer pixbuf = GtkNativeLibraries.Gtk.INSTANCE.gdk_pixbuf_new_from_file(
                                tempFile.toString(), error);

                        if (pixbuf != null) {
                            // Set the image pixbuf
                            GtkNativeLibraries.Gtk.INSTANCE.gtk_image_set_from_pixbuf(logoImageWidget, pixbuf);
                            LOGGER.info("Logo loaded successfully from: " + logoPath);
                        } else {
                            LOGGER.warning("Failed to create pixbuf from logo file");
                        }

                        Files.deleteIfExists(tempFile);
                    } else {
                        LOGGER.warning("Logo resource not found: " + logoPath);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to load ODM logo: " + e.getMessage());
            }
        }
    }

    /**
     * Performs cleanup of service resources.
     */
    public void cleanup() {
        try {
            dialog = null;
            logoImageWidget = null;
            messageLabel = null;
            progressBar = null;
            LOGGER.info("Startup/Shutdown dialog service cleanup completed");
        } catch (Exception e) {
            LOGGER.warning("Error during startup/shutdown dialog service cleanup: " + e.getMessage());
        }
    }
}
