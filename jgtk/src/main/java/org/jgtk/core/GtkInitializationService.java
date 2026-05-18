package org.jgtk.core;

import java.util.logging.Logger;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/**
 * GTK initialization and error handling service.
 * Manages the GTK lifecycle and provides error reporting functionality.
 */
public final class GtkInitializationService {

    private static final Logger LOGGER = Logger.getLogger(GtkInitializationService.class.getName());
    private static boolean gtkInitialized = false;

    private GtkInitializationService() {
        // Utility class - prevent instantiation
    }

    /**
     * Initializes GTK if not already initialized.
     * This method is thread-safe and will only initialize GTK once.
     */
    public static synchronized void initializeGtk() {
        if (gtkInitialized) {
            return;
        }

        try {
            // Initialize X11 threads first
            GtkNativeLibraries.X11.INSTANCE.XInitThreads();

            // Initialize GTK
            GtkNativeLibraries.Gtk.INSTANCE.gtk_init(null, null);

            // Set the current thread as the GTK main thread
            GtkThreadDispatcher.setGtkMainThread(Thread.currentThread());

            gtkInitialized = true;
            LOGGER.info("GTK initialized successfully on thread: " + Thread.currentThread().getName());
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize GTK: " + e.getMessage());
            throw new RuntimeException("Failed to initialize GTK", e);
        }
    }

    /**
     * Checks if GTK is initialized.
     *
     * @return true if GTK is initialized, false otherwise
     */
    public static boolean isInitialized() {
        return gtkInitialized;
    }

    /**
     * Processes pending GTK events without blocking.
     * This is useful for keeping the UI responsive during long operations.
     */
    public static void processEvents() {
        if (!gtkInitialized) {
            return;
        }

        while (GtkNativeLibraries.Gtk.INSTANCE.gtk_events_pending()) {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_main_iteration();
        }
    }

    /**
     * Runs the GTK main loop.
     * This will block until quit() is called.
     */
    public static void runMainLoop() {
        if (!gtkInitialized) {
            initializeGtk();
        }
        GtkNativeLibraries.Gtk.INSTANCE.gtk_main();
    }

    /**
     * Quits the GTK main loop.
     * This will cause runMainLoop() to return.
     */
    public static void quitMainLoop() {
        if (gtkInitialized) {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_main_quit();
        }
    }

    /**
     * Error information extracted from GError struct
     */
    public static final class GErrorInfo {
        public final int domain;
        public final int code;
        public final String message;

        public GErrorInfo(Pointer errorPtr) {
            if (errorPtr == null) {
                this.domain = 0;
                this.code = 0;
                this.message = "Unknown error";
                return;
            }

            try {
                // Read GError struct fields directly
                // struct GError { GQuark domain; gint code; gchar* message; }
                this.domain = errorPtr.getInt(0);
                this.code = errorPtr.getInt(4);
                var messagePtr = errorPtr.getPointer(8);
                this.message = messagePtr != null ? messagePtr.getString(0) : "Unknown error";
            } catch (Exception e) {
                throw new RuntimeException("Failed to read GError", e);
            }
        }

        @Override
        public String toString() {
            var domainStr = switch (domain) {
                case 0 -> "unknown";
                default -> {
                    try {
                        yield GtkNativeLibraries.GLib.INSTANCE.g_quark_to_string(domain);
                    } catch (Exception e) {
                        yield "unknown";
                    }
                }
            };
            return "GError[domain=" + domainStr + "(" + domain + "), code=" + code + ", message='" + message + "']";
        }
    }

    /**
     * Creates a GErrorInfo from a GError pointer and optionally frees the error.
     *
     * @param errorPtr  the GError pointer
     * @param freeError whether to free the error after reading it
     * @return the error information
     */
    public static GErrorInfo createErrorInfo(Pointer errorPtr, boolean freeError) {
        if (errorPtr == null) {
            return null;
        }

        GErrorInfo info = new GErrorInfo(errorPtr);

        if (freeError) {
            try {
                GtkNativeLibraries.GLib.INSTANCE.g_error_free(errorPtr);
            } catch (Exception e) {
                LOGGER.warning("Failed to free GError: " + e.getMessage());
            }
        }

        return info;
    }

    /**
     * Logs and handles a GTK error from a PointerByReference.
     *
     * @param error   the error reference
     * @param context the context where the error occurred
     * @return true if there was an error, false otherwise
     */
    public static boolean handleError(PointerByReference error, String context) {
        if (error == null || error.getValue() == null) {
            return false;
        }

        GErrorInfo errorInfo = createErrorInfo(error.getValue(), true);
        LOGGER.severe(String.format("GTK Error in %s: %s", context, errorInfo));
        return true;
    }
}
