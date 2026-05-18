package org.jgtk.widget;

import com.sun.jna.Pointer;
import org.jgtk.core.GtkNativeLibraries;
import org.jgtk.core.GtkConstants;

import java.util.logging.Logger;

/**
 * Service for managing dialog widgets and file choosers.
 */
public class DialogWidgetService {

    private static final Logger LOGGER = Logger.getLogger(DialogWidgetService.class.getName());

    private DialogWidgetService() {
        // Utility class - prevent instantiation
    }

    /**
     * Sets the current folder for a file chooser.
     *
     * @param fileChooser the file chooser widget
     * @param folder      the folder path to set
     */
    public static void setFileChooserCurrentFolder(Pointer fileChooser, String folder) {
        if (fileChooser == null) {
            LOGGER.warning("GTK file chooser folder change failed: widget pointer is null");
            return;
        }

        if (folder == null || folder.trim().isEmpty()) {
            LOGGER.warning("GTK file chooser folder change failed: folder path is null or empty");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_set_current_folder(fileChooser, folder);
            LOGGER.fine(() -> "Successfully set GTK file chooser folder to: " + folder);
        } catch (Exception e) {
            LOGGER.severe("GTK file chooser folder change failed with exception (target folder='" + folder + "'): " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * Gets the current folder from a file chooser.
     *
     * @param fileChooser the file chooser widget
     * @return the current folder path, or null if widget not found
     */
    public static String getFileChooserCurrentFolder(Pointer fileChooser) {
        if (fileChooser == null) {
            LOGGER.warning("File chooser widget is null");
            return null;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_get_current_folder(fileChooser);
        } catch (Exception e) {
            LOGGER.severe("Error getting file chooser folder: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sets the filename for a file chooser.
     *
     * @param fileChooser the file chooser widget
     * @param filename    the filename to set
     */
    public static void setFileChooserFilename(Pointer fileChooser, String filename) {
        if (fileChooser == null) {
            LOGGER.warning("File chooser widget is null");
            return;
        }

        if (filename == null || filename.trim().isEmpty()) {
            LOGGER.warning("Filename is null or empty");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_set_filename(fileChooser, filename);
        } catch (Exception e) {
            LOGGER.severe("Error setting file chooser filename: " + e.getMessage());
        }
    }

    /**
     * Gets the filename from a file chooser.
     *
     * @param fileChooser the file chooser widget
     * @return the selected filename, or null if no selection or widget not found
     */
    public static String getFileChooserFilename(Pointer fileChooser) {
        if (fileChooser == null) {
            LOGGER.warning("File chooser widget is null");
            return null;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_get_filename(fileChooser);
        } catch (Exception e) {
            LOGGER.severe("Error getting file chooser filename: " + e.getMessage());
            return null;
        }
    }

    /**
     * Shows a message dialog.
     *
     * @param parent      the parent window, or null
     * @param message     the message to display
     * @param messageType the GTK message type (INFO, WARNING, QUESTION, ERROR)
     * @param buttons     the GTK buttons type
     * @return the response ID
     */
    public static int showMessageDialog(Pointer parent, String message, int messageType, int buttons) {
        if (message == null) {
            message = "";
        }

        try {
            Pointer dialog = GtkNativeLibraries.Gtk.INSTANCE.gtk_message_dialog_new(
                    parent,
                    GtkConstants.GTK_DIALOG_MODAL | GtkConstants.GTK_DIALOG_DESTROY_WITH_PARENT,
                    messageType,
                    buttons,
                    message);

            if (dialog == null) {
                LOGGER.severe("Failed to create message dialog");
                return GtkConstants.GTK_RESPONSE_NONE;
            }

            int response = GtkNativeLibraries.Gtk.INSTANCE.gtk_dialog_run(dialog);
            GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_destroy(dialog);

            return response;
        } catch (Exception e) {
            LOGGER.severe("Error showing message dialog: " + e.getMessage());
            return GtkConstants.GTK_RESPONSE_NONE;
        }
    }

    /**
     * Shows an error message dialog.
     *
     * @param parent  the parent window, or null for no parent
     * @param message the error message to display
     */
    public static void showErrorDialog(Pointer parent, String message) {
        showMessageDialog(parent, message, GtkConstants.GTK_MESSAGE_ERROR, GtkConstants.GTK_BUTTONS_OK);
    }

    /**
     * Shows an information message dialog.
     *
     * @param parent  the parent window, or null for no parent
     * @param message the information message to display
     */
    public static void showInfoDialog(Pointer parent, String message) {
        showMessageDialog(parent, message, GtkConstants.GTK_MESSAGE_INFO, GtkConstants.GTK_BUTTONS_OK);
    }

    /**
     * Shows a warning message dialog.
     *
     * @param parent  the parent window, or null for no parent
     * @param message the warning message to display
     */
    public static void showWarningDialog(Pointer parent, String message) {
        showMessageDialog(parent, message, GtkConstants.GTK_MESSAGE_WARNING, GtkConstants.GTK_BUTTONS_OK);
    }

    /**
     * Shows a question dialog with Yes/No buttons.
     *
     * @param parent  the parent window, or null for no parent
     * @param message the question to display
     * @return true if Yes was clicked, false if No was clicked
     */
    public static boolean showQuestionDialog(Pointer parent, String message) {
        int response = showMessageDialog(parent, message, GtkConstants.GTK_MESSAGE_QUESTION,
                GtkConstants.GTK_BUTTONS_YES_NO);
        return response == GtkConstants.GTK_RESPONSE_YES;
    }

    /**
     * Shows a file chooser dialog.
     *
     * @param parent        the parent window, or null for no parent
     * @param title         the title of the dialog
     * @param action        the file chooser action (OPEN, SAVE, SELECT_FOLDER,
     *                      CREATE_FOLDER)
     * @param currentFolder the initial folder to show
     * @return the selected file/directory path, or null if cancelled
     */
    public static String showFileChooserDialog(Pointer parent, String title, int action, String currentFolder) {
        if (title == null) {
            title = "Choose File";
        }

        try {
            Pointer dialog = GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_dialog_new(
                    title, parent, action, "Cancel", GtkConstants.GTK_RESPONSE_CANCEL);

            if (dialog == null) {
                LOGGER.severe("Failed to create file chooser dialog");
                return null;
            }

            // Add appropriate action button
            String actionButtonText = (action == GtkConstants.GTK_FILE_CHOOSER_ACTION_SAVE) ? "Save"
                    : (action == GtkConstants.GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER ||
                            action == GtkConstants.GTK_FILE_CHOOSER_ACTION_CREATE_FOLDER) ? "Select" : "Open";

            GtkNativeLibraries.Gtk.INSTANCE.gtk_dialog_add_button(dialog, actionButtonText,
                    GtkConstants.GTK_RESPONSE_ACCEPT);

            if (currentFolder != null && !currentFolder.trim().isEmpty()) {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_set_current_folder(dialog, currentFolder);
            }

            int response = GtkNativeLibraries.Gtk.INSTANCE.gtk_dialog_run(dialog);
            String result = null;

            if (response == GtkConstants.GTK_RESPONSE_ACCEPT) {
                result = GtkNativeLibraries.Gtk.INSTANCE.gtk_file_chooser_get_filename(dialog);
            }

            GtkNativeLibraries.Gtk.INSTANCE.gtk_widget_destroy(dialog);
            return result;

        } catch (Exception e) {
            LOGGER.severe("Error showing file chooser dialog: " + e.getMessage());
            return null;
        }
    }

    /**
     * Shows a file chooser dialog for selecting a file.
     *
     * @param parent        the parent window, or null for no parent
     * @param title         the title of the dialog
     * @param currentFolder the initial folder to show
     * @return the selected file path, or null if cancelled
     */
    public static String showFileOpenDialog(Pointer parent, String title, String currentFolder) {
        return showFileChooserDialog(parent, title, GtkConstants.GTK_FILE_CHOOSER_ACTION_OPEN, currentFolder);
    }

    /**
     * Shows a file chooser dialog for selecting a directory.
     *
     * @param parent        the parent window, or null for no parent
     * @param title         the title of the dialog
     * @param currentFolder the initial folder to show
     * @return the selected directory path, or null if cancelled
     */
    public static String showDirectoryChooserDialog(Pointer parent, String title, String currentFolder) {
        return showFileChooserDialog(parent, title, GtkConstants.GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER, currentFolder);
    }

    /**
     * Sets the title of a window.
     *
     * @param window the window widget
     * @param title  the title to set
     */
    public static void setWindowTitle(Pointer window, String title) {
        if (window == null) {
            LOGGER.warning("Window widget is null");
            return;
        }

        if (title == null) {
            title = "";
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_window_set_title(window, title);
        } catch (Exception e) {
            LOGGER.severe("Error setting window title: " + e.getMessage());
        }
    }

    /**
     * Gets the title of a window.
     *
     * @param window the window widget
     * @return the window title, or null if widget not found
     */
    public static String getWindowTitle(Pointer window) {
        if (window == null) {
            LOGGER.warning("Window widget is null");
            return null;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_window_get_title(window);
        } catch (Exception e) {
            LOGGER.severe("Error getting window title: " + e.getMessage());
            return null;
        }
    }
}
