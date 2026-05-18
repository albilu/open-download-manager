package org.jgtk.widget;

import com.sun.jna.Pointer;
import org.jgtk.core.GtkNativeLibraries;

import java.util.logging.Logger;

/**
 * Service for managing interactive GTK widgets like toggle buttons, switches,
 * and combo boxes.
 */
public class InteractiveWidgetService {

    private static final Logger LOGGER = Logger.getLogger(InteractiveWidgetService.class.getName());

    private InteractiveWidgetService() {
        // Utility class - prevent instantiation
    }

    /**
     * Sets the active state of a toggle button or checkbox.
     *
     * @param toggleButton the toggle button widget
     * @param active       true to check/activate, false to uncheck/deactivate
     */
    public static void setToggleButtonActive(Pointer toggleButton, boolean active) {
        if (toggleButton == null) {
            LOGGER.warning("GTK toggle button state change failed: widget pointer is null");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_toggle_button_set_active(toggleButton, active);
            LOGGER.fine(() -> "Successfully set GTK toggle button active state to: " + active);
        } catch (Exception e) {
            LOGGER.severe("GTK toggle button state change failed with exception (target state=" + active + "): " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * Gets the active state of a toggle button or checkbox.
     *
     * @param toggleButton the toggle button widget
     * @return true if checked/active, false otherwise
     */
    public static boolean getToggleButtonActive(Pointer toggleButton) {
        if (toggleButton == null) {
            LOGGER.warning("GTK toggle button state retrieval failed: widget pointer is null");
            return false;
        }

        try {
            var isActive = GtkNativeLibraries.Gtk.INSTANCE.gtk_toggle_button_get_active(toggleButton);
            LOGGER.fine(() -> "Successfully retrieved GTK toggle button active state: " + isActive);
            return isActive;
        } catch (Exception e) {
            LOGGER.severe("GTK toggle button state retrieval failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the active state of a check menu item.
     *
     * @param checkMenuItem the check menu item widget
     * @param active        true to check, false to uncheck
     */
    public static void setCheckMenuItemActive(Pointer checkMenuItem, boolean active) {
        if (checkMenuItem == null) {
            LOGGER.warning("Check menu item widget is null");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_check_menu_item_set_active(checkMenuItem, active);
        } catch (Exception e) {
            LOGGER.severe("Error setting check menu item active state: " + e.getMessage());
        }
    }

    /**
     * Gets the active state of a check menu item.
     *
     * @param checkMenuItem the check menu item widget
     * @return true if checked/active, false otherwise
     */
    public static boolean getCheckMenuItemActive(Pointer checkMenuItem) {
        if (checkMenuItem == null) {
            LOGGER.warning("Check menu item widget is null");
            return false;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_check_menu_item_get_active(checkMenuItem);
        } catch (Exception e) {
            LOGGER.severe("Error getting check menu item active state: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the active state of a switch widget.
     *
     * @param switchWidget the switch widget
     * @param active       true to activate, false to deactivate
     */
    public static void setSwitchActive(Pointer switchWidget, boolean active) {
        if (switchWidget == null) {
            LOGGER.warning("Switch widget is null");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_switch_set_active(switchWidget, active);
        } catch (Exception e) {
            LOGGER.severe("Error setting switch active state: " + e.getMessage());
        }
    }

    /**
     * Gets the active state of a switch widget.
     *
     * @param switchWidget the switch widget
     * @return true if active, false otherwise
     */
    public static boolean getSwitchActive(Pointer switchWidget) {
        if (switchWidget == null) {
            LOGGER.warning("Switch widget is null");
            return false;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_switch_get_active(switchWidget);
        } catch (Exception e) {
            LOGGER.severe("Error getting switch active state: " + e.getMessage());
            return false;
        }
    }

    /**
     * Clears all items from a combo box.
     *
     * @param comboBox the combo box widget
     */
    public static void clearComboBox(Pointer comboBox) {
        if (comboBox == null) {
            LOGGER.warning("Combo box widget is null");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_combo_box_text_remove_all(comboBox);
        } catch (Exception e) {
            LOGGER.severe("Error clearing combo box: " + e.getMessage());
        }
    }

    /**
     * Adds an item to a combo box.
     *
     * @param comboBox the combo box widget
     * @param text     the text to add
     */
    public static void addComboBoxItem(Pointer comboBox, String text) {
        if (comboBox == null) {
            LOGGER.warning("Combo box widget is null");
            return;
        }

        if (text == null) {
            text = "";
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_combo_box_text_append_text(comboBox, text);
        } catch (Exception e) {
            LOGGER.severe("Error adding combo box item: " + e.getMessage());
        }
    }

    /**
     * Sets the active item in a combo box by index.
     *
     * @param comboBox the combo box widget
     * @param index    the index to select (0-based)
     */
    public static void setComboBoxActive(Pointer comboBox, int index) {
        if (comboBox == null) {
            LOGGER.warning("Combo box widget is null");
            return;
        }

        try {
            GtkNativeLibraries.Gtk.INSTANCE.gtk_combo_box_set_active(comboBox, index);
        } catch (Exception e) {
            LOGGER.severe("Error setting combo box active index: " + e.getMessage());
        }
    }

    /**
     * Gets the active item index from a combo box.
     *
     * @param comboBox the combo box widget
     * @return the active index, or -1 if no selection or widget not found
     */
    public static int getComboBoxActive(Pointer comboBox) {
        if (comboBox == null) {
            LOGGER.warning("Combo box widget is null");
            return -1;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_combo_box_get_active(comboBox);
        } catch (Exception e) {
            LOGGER.severe("Error getting combo box active index: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the active text from a combo box.
     *
     * @param comboBox the combo box widget
     * @return the active text, or null if no selection or widget not found
     */
    public static String getComboBoxActiveText(Pointer comboBox) {
        if (comboBox == null) {
            LOGGER.warning("Combo box widget is null");
            return null;
        }

        try {
            return GtkNativeLibraries.Gtk.INSTANCE.gtk_combo_box_text_get_active_text(comboBox);
        } catch (Exception e) {
            LOGGER.severe("Error getting combo box active text: " + e.getMessage());
            return null;
        }
    }
}
