package org.odm.ui.service;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jgtk.GladeUI;
import org.jgtk.core.GtkNativeLibraries;

/**
 * Service class for About dialog business logic.
 *
 * Handles the core functionality of the About dialog including: - Logo loading
 * and management - Dialog validation and state management - Error handling and
 * fallback messages
 */
public class AboutService {

    private static final Logger LOGGER = Logger.getLogger(AboutService.class.getName());

    private final GladeUI ui;
    private Pointer aboutDialog;
    private Consumer<Boolean> closeDialogCallback;

    /**
     * Creates a new AboutDialogService.
     *
     * @param ui The GladeUI instance
     */
    public AboutService(GladeUI ui) {
        this.ui = ui;
    }

    /**
     * Sets the callback to close the dialog.
     *
     * @param closeDialogCallback Callback function to close the dialog
     */
    public void setCloseDialogCallback(Consumer<Boolean> closeDialogCallback) {
        this.closeDialogCallback = closeDialogCallback;
    }

    /**
     * Initializes the service and loads the about dialog components.
     */
    public void initializeService() {
        try {
            aboutDialog = ui.getWidget("about_dialog");
            if (aboutDialog == null) {
                throw new RuntimeException("About dialog widget not found in Glade file");
            }

            // Load and set the logo programmatically
            loadLogo();

            LOGGER.info("About dialog service initialized successfully");

        } catch (Exception e) {
            LOGGER.severe("Failed to initialize about dialog service: " + e.getMessage());
            throw new RuntimeException("About dialog service initialization failed", e);
        }
    }

    /**
     * Handles the dialog destroy event.
     */
    public void handleDialogDestroy() {
        LOGGER.info("About dialog destroy handled by service");
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    /**
     * Shows a simple about message using a standard message dialog. This is a
     * fallback method when the full about dialog cannot be loaded.
     *
     * @param parentWindow The parent window pointer
     */
    public void showSimpleAbout(Pointer parentWindow) {
        String message = """
                         Open Download Manager
                         Version: 0.0.1
                         Copyright \u00a9 2025 albilu

                         An Open Download Manager for Linux

                         Website: https://github.com/albilu/odm
                         License: GPL-3.0

                         Built with Java """ + System.getProperty("java.version");

        // If we have a UI instance, use it; otherwise log the message
        if (ui != null) {
            ui.showInfoDialog("main_window", message);
        } else {
            LOGGER.log(Level.INFO, "About Information:\n{0}", message);
        }
    }

    /**
     * Checks if the dialog widgets are still valid (not destroyed by GTK).
     *
     * @return true if the dialog is valid and can be used, false otherwise
     */
    public boolean isDialogValid() {
        if (ui == null || aboutDialog == null) {
            return false;
        }

        try {
            // Try to check if the widget is still valid by accessing its type
            // If GTK has destroyed it, this should fail
            return GtkNativeLibraries.Gtk.INSTANCE.g_type_check_instance_is_a(aboutDialog,
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_get_type());
        } catch (Exception e) {
            LOGGER.fine(() -> "Dialog widget no longer valid: " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads the logo for the about dialog programmatically.
     */
    private void loadLogo() {
        try {
            // Try to load the PNG logo from resources
            String logoPath = "/images/logo-128.svg";

            // Get the resource as an input stream to check if it exists
            try (var logoStream = getClass().getResourceAsStream(logoPath)) {
                if (logoStream != null) {
                    // Convert resource path to a format GTK can use
                    // We need to extract the resource to a temporary file
                    // TODO: avoid temp file. use use /home/user/.config/odm
                    // copy file once and reuse
                    var tempFile = java.nio.file.Files.createTempFile("odm-logo", ".svg");
                    java.nio.file.Files.copy(logoStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

                    // Load pixbuf from the temporary file
                    PointerByReference error = new PointerByReference();
                    Pointer pixbuf = GtkNativeLibraries.Gtk.INSTANCE.gdk_pixbuf_new_from_file(
                            tempFile.toString(), error);

                    if (pixbuf != null) {
                        // Set the logo in the about dialog
                        GtkNativeLibraries.Gtk.INSTANCE.gtk_about_dialog_set_logo(aboutDialog, pixbuf);
                        LOGGER.info("Logo loaded successfully from: " + logoPath);
                    } else {
                        LOGGER.warning("Failed to create pixbuf from logo file");
                    }

                    // Clean up temporary file
                    java.nio.file.Files.deleteIfExists(tempFile);
                } else {
                    LOGGER.warning("Logo resource not found: " + logoPath);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load logo: " + e.getMessage());
            // Not a critical error, continue without logo
        }
    }

    /**
     * Performs cleanup of service resources.
     */
    public void cleanup() {
        try {
            aboutDialog = null;
            LOGGER.info("About dialog service cleanup completed");
        } catch (Exception e) {
            LOGGER.warning("Error during about dialog service cleanup: " + e.getMessage());
        }
    }
}
