package org.jgtk.widget;

import static org.jgtk.core.GtkNativeLibraries.Gtk.INSTANCE;

import java.util.logging.Logger;

import org.jgtk.core.GtkThreadDispatcher;

import com.sun.jna.Pointer;

/**
 * Service for managing basic GTK widget operations.
 * Handles visibility, sensitivity, tooltips, and other common widget
 * operations.
 */
public class BasicWidgetService {

    private static final Logger LOGGER = Logger.getLogger(BasicWidgetService.class.getName());

    private BasicWidgetService() {
        // Utility class - prevent instantiation
    }

    /**
     * Shows a widget and all its children.
     *
     * @param widget the widget to show
     */
    public static void showAll(Pointer widget) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("GTK widget showAll operation failed: invalid widget pointer");
            return;
        }

        // Ensure GTK operations are executed on the main thread
        GtkThreadDispatcher.invokeLater(() -> {
            try {
                INSTANCE.gtk_widget_show_all(widget);
                LOGGER.fine(() -> "Successfully executed showAll on GTK widget");
            } catch (Exception e) {
                LOGGER.severe("GTK widget showAll operation failed with exception: " +
                        e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }

    /**
     * Shows a widget.
     *
     * @param widget the widget to show
     */
    public static void show(Pointer widget) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("GTK widget show operation failed: invalid widget pointer");
            return;
        }

        try {
            INSTANCE.gtk_widget_show(widget);
            LOGGER.fine(() -> "Successfully executed show on GTK widget");
        } catch (Exception e) {
            LOGGER.severe("GTK widget show operation failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * Hides a widget.
     *
     * @param widget the widget to hide
     */
    public static void hide(Pointer widget) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("GTK widget hide operation failed: invalid widget pointer");
            return;
        }

        try {
            INSTANCE.gtk_widget_hide(widget);
            LOGGER.fine(() -> "Successfully executed hide on GTK widget");
        } catch (Exception e) {
            LOGGER.severe("GTK widget hide operation failed with exception: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * Sets the visibility of a widget.
     *
     * @param widget  the widget
     * @param visible true to show, false to hide
     */
    public static void setVisible(Pointer widget, boolean visible) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("GTK widget setVisible operation failed: invalid widget pointer");
            return;
        }

        try {
            INSTANCE.gtk_widget_set_visible(widget, visible);
            LOGGER.fine(() -> "Successfully set GTK widget visibility to: " + visible);
        } catch (Exception e) {
            LOGGER.severe("GTK widget setVisible operation failed with exception (visibility=" + visible + "): " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * Gets the visibility state of a widget.
     *
     * @param widget the widget
     * @return true if visible, false if hidden
     */
    public static boolean isVisible(Pointer widget) {
        if (!isValidWidget(widget)) {
            return false;
        }

        try {
            return INSTANCE.gtk_widget_get_visible(widget);
        } catch (Exception e) {
            LOGGER.severe("Error getting widget visibility: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the tooltip text for a widget.
     *
     * @param widget  the widget
     * @param tooltip the tooltip text
     */
    public static void setTooltip(Pointer widget, String tooltip) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("Invalid widget for setTooltip");
            return;
        }

        try {
            INSTANCE.gtk_widget_set_tooltip_text(widget, tooltip);
        } catch (Exception e) {
            LOGGER.severe("Error setting widget tooltip: " + e.getMessage());
        }
    }

    /**
     * Sets the sensitivity (enabled/disabled state) of a widget.
     *
     * @param widget    the widget
     * @param sensitive true to enable, false to disable
     */
    public static void setSensitive(Pointer widget, boolean sensitive) {
        if (!isValidWidget(widget)) {
            LOGGER.warning("Invalid widget for setSensitive");
            return;
        }

        try {
            INSTANCE.gtk_widget_set_sensitive(widget, sensitive);
        } catch (Exception e) {
            LOGGER.severe("Error setting widget sensitivity: " + e.getMessage());
        }
    }

    /**
     * Gets the sensitivity state of a widget.
     *
     * @param widget the widget
     * @return true if enabled, false if disabled
     */
    public static boolean isSensitive(Pointer widget) {
        if (!isValidWidget(widget)) {
            return false;
        }

        try {
            return INSTANCE.gtk_widget_get_sensitive(widget);
        } catch (Exception e) {
            LOGGER.severe("Error getting widget sensitivity: " + e.getMessage());
            return false;
        }
    }

    /**
     * Destroys a widget and frees its resources.
     *
     * @param widget the widget to destroy
     */
    public static void destroy(Pointer widget) {
        if (!isValidWidget(widget)) {
            return;
        }

        try {
            INSTANCE.gtk_widget_destroy(widget);
        } catch (Exception e) {
            LOGGER.severe("Error destroying widget: " + e.getMessage());
        }
    }

    /**
     * Checks if a widget is valid (not null and is a GTK widget).
     *
     * @param widget the widget pointer to check
     * @return true if the widget is valid, false otherwise
     */
    public static boolean isValidWidget(Pointer widget) {
        if (widget == null) {
            return false;
        }

        try {
            var widgetType = INSTANCE.gtk_widget_get_type();
            return INSTANCE.g_type_check_instance_is_a(widget, widgetType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a widget is a toggle button.
     *
     * @param widget the widget pointer to check
     * @return true if the widget is a toggle button, false otherwise
     */
    public static boolean isToggleButton(Pointer widget) {
        if (!isValidWidget(widget)) {
            return false;
        }

        try {
            var toggleButtonType = INSTANCE.gtk_toggle_button_get_type();
            return INSTANCE.g_type_check_instance_is_a(widget, toggleButtonType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a widget is a check menu item.
     *
     * @param widget the widget pointer to check
     * @return true if the widget is a check menu item, false otherwise
     */
    public static boolean isCheckMenuItem(Pointer widget) {
        if (!isValidWidget(widget)) {
            return false;
        }

        try {
            var checkMenuItemType = INSTANCE.gtk_check_menu_item_get_type();
            return INSTANCE.g_type_check_instance_is_a(widget, checkMenuItemType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a widget is a switch.
     *
     * @param widget the widget pointer to check
     * @return true if the widget is a switch, false otherwise
     */
    public static boolean isSwitch(Pointer widget) {
        if (!isValidWidget(widget)) {
            return false;
        }

        try {
            var switchType = INSTANCE.gtk_switch_get_type();
            return INSTANCE.g_type_check_instance_is_a(widget, switchType);
        } catch (Exception e) {
            return false;
        }
    }
}
