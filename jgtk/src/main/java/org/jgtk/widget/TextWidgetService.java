package org.jgtk.widget;

import static org.jgtk.core.GtkNativeLibraries.Gtk.INSTANCE;

import java.util.logging.Logger;

import org.jgtk.core.GtkThreadDispatcher;

import com.sun.jna.Pointer;

/**
 * Service for managing text-based GTK widgets like entries and labels.
 */
public class TextWidgetService {

    private static final Logger LOGGER = Logger.getLogger(TextWidgetService.class.getName());

    private TextWidgetService() {
        // Utility class - prevent instantiation
    }

    /**
     * Sets the text of an entry widget.
     *
     * @param entry the entry widget
     * @param text  the text to set
     */
    public static void setEntryText(Pointer entry, String text) {
        if (entry == null) {
            LOGGER.warning("GTK entry text update failed: entry widget pointer is null");
            return;
        }

        var textToSet = text != null ? text : "";

        // Ensure GTK operations are executed on the main thread
        GtkThreadDispatcher.invokeLater(() -> {
            try {
                INSTANCE.gtk_entry_set_text(entry, textToSet);
                LOGGER.fine(() -> "Successfully set GTK entry text (length=" + textToSet.length() + ")");
            } catch (Exception e) {
                LOGGER.severe("GTK entry text update failed with exception: " +
                        e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }

    /**
     * Gets the text from an entry widget.
     *
     * @param entry the entry widget
     * @return the text in the entry, or null if widget not found
     */
    public static String getEntryText(Pointer entry) {
        if (entry == null) {
            LOGGER.warning("GTK entry text retrieval failed: entry widget pointer is null");
            return null;
        }

        try {
            var text = INSTANCE.gtk_entry_get_text(entry);
            LOGGER.fine(() -> "Successfully retrieved GTK entry text (length=" +
                    (text != null ? text.length() : 0) + ")");
            return text;
        } catch (Exception e) {
            LOGGER.severe("GTK entry text retrieval failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Sets the text of a label widget.
     *
     * @param label the label widget
     * @param text  the text to set
     */
    public static void setLabelText(Pointer label, String text) {
        if (label == null) {
            LOGGER.warning("GTK label text update failed: label widget pointer is null");
            return;
        }

        var textToSet = text != null ? text : "";

        // Ensure GTK operations are executed on the main thread
        GtkThreadDispatcher.invokeLater(() -> {
            try {
                INSTANCE.gtk_label_set_text(label, textToSet);
                LOGGER.fine(() -> "Successfully set GTK label text (length=" + textToSet.length() + ")");
            } catch (Exception e) {
                LOGGER.severe("GTK label text update failed with exception: " +
                        e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }

    /**
     * Gets the text of a label widget.
     *
     * @param label the label widget
     * @return the text of the label, or null if not found
     */
    public static String getLabelText(Pointer label) {
        if (label == null) {
            LOGGER.warning("GTK label text retrieval failed: label widget pointer is null");
            return null;
        }

        try {
            var text = INSTANCE.gtk_label_get_text(label);
            LOGGER.fine(() -> "Successfully retrieved GTK label text (length=" +
                    (text != null ? text.length() : 0) + ")");
            return text;
        } catch (Exception e) {
            LOGGER.severe("GTK label text retrieval failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * Sets the value of a spin button widget.
     *
     * @param spinButton the spin button widget
     * @param value      the value to set
     */
    public static void setSpinButtonValue(Pointer spinButton, double value) {
        if (spinButton == null) {
            LOGGER.warning("Spin button widget is null");
            return;
        }

        // Ensure GTK operations are executed on the main thread
        GtkThreadDispatcher.invokeLater(() -> {
            try {
                INSTANCE.gtk_spin_button_set_value(spinButton, value);
            } catch (Exception e) {
                LOGGER.severe("Error setting spin button value: " + e.getMessage());
            }
        });
    }

    /**
     * Gets the value from a spin button widget as an integer.
     *
     * @param spinButton the spin button widget
     * @return the value, or 0 if widget not found
     */
    public static int getSpinButtonValueAsInt(Pointer spinButton) {
        if (spinButton == null) {
            LOGGER.warning("Spin button widget is null");
            return 0;
        }

        try {
            return INSTANCE.gtk_spin_button_get_value_as_int(spinButton);
        } catch (Exception e) {
            LOGGER.severe("Error getting spin button value: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Gets the value from a spin button widget as a double.
     *
     * @param spinButton the spin button widget
     * @return the value, or 0.0 if widget not found
     */
    public static double getSpinButtonValue(Pointer spinButton) {
        if (spinButton == null) {
            LOGGER.warning("Spin button widget is null");
            return 0.0;
        }

        try {
            return INSTANCE.gtk_spin_button_get_value(spinButton);
        } catch (Exception e) {
            LOGGER.severe("Error getting spin button value: " + e.getMessage());
            return 0.0;
        }
    }
}
