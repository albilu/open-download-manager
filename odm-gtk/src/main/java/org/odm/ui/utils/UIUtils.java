package org.odm.ui.utils;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.jgtk.GladeUI;

/**
 * Utility class for common UI operations.
 */
public final class UIUtils {

    private static final Logger LOGGER = Logger.getLogger(UIUtils.class.getName());

    private UIUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Updates disk space display in a label.
     *
     * @param ui        GladeUI instance
     * @param labelName Name of the label widget
     * @param path      Path to check disk space for
     */
    public static void updateDiskSpaceDisplay(GladeUI ui, String labelName, Path path) {
        try {
            if (path != null && ui != null && java.nio.file.Files.exists(path)) {
                long freeSpace = path.toFile().getFreeSpace();
                String formattedSpace = FormatUtils.formatFileSize(freeSpace);
                ui.setLabelText(labelName, formattedSpace + " libres");
            } else {
                ui.setLabelText(labelName, "Espace disponible inconnu");
            }
        } catch (Exception e) {
            LOGGER.severe("Error updating disk space display: " + e.getMessage());
        }
    }

    /**
     * Loads default connection settings into UI components.
     *
     * @param ui GladeUI instance
     */
    public static void loadDefaultConnectionSettingsToUI(GladeUI ui) {
        try {
            // Default connection settings
            ui.setSpinButtonValue("max_connections_spin", 5);
            ui.setSpinButtonValue("retry_limit_spin", 5);

            // Default speed settings
            ui.setSpinButtonValue("max_download_speed_spin", 0);
            ui.setSpinButtonValue("max_upload_speed_spin", 0);
            ui.setSpinButtonValue("retry_after", 1);

        } catch (Exception e) {
            LOGGER.severe("Error loading default connection settings to UI: " + e.getMessage());
        }
    }

    /**
     * Loads default proxy settings into UI components.
     *
     * @param ui GladeUI instance
     */
    public static void loadDefaultProxySettingsToUI(GladeUI ui) {
        try {
            // Default proxy settings
            ui.setComboBoxActive("proxy_type_combo", 0); // None
            ui.setEntryText("proxy_host_entry", "");
            ui.setSpinButtonValue("proxy_port_spin", 8080);
            ui.setEntryText("proxy_username_entry", "");
            ui.setEntryText("proxy_password_entry", "");

        } catch (Exception e) {
            LOGGER.severe("Error loading default proxy settings to UI: " + e.getMessage());
        }
    }

    /**
     * Loads all default settings into UI components.
     *
     * @param ui GladeUI instance
     */
    public static void loadDefaultSettingsToUI(GladeUI ui) {
        try {
            // Load connection settings
            loadDefaultConnectionSettingsToUI(ui);

            // Load proxy settings
            loadDefaultProxySettingsToUI(ui);

            // Load other settings
            ui.setSwitchActive("tor_switch", false);
            ui.setToggleButtonActive("start_automatically_check1", true);
            ui.setToggleButtonActive("move_torrent_check1", false);

            LOGGER.info("Loaded default settings to UI");
        } catch (Exception e) {
            LOGGER.severe("Error loading default settings to UI: " + e.getMessage());
        }
    }

    /**
     * Updates button states based on condition.
     *
     * @param ui         GladeUI instance
     * @param buttonName Name of the button to update
     * @param condition  Condition determining if button should be sensitive
     */
    public static void updateButtonState(GladeUI ui, String buttonName, boolean condition) {
        try {
            ui.setWidgetSensitive(buttonName, condition);
            LOGGER.fine("Updated button state for " + buttonName + ": " + (condition ? "enabled" : "disabled"));
        } catch (Exception e) {
            LOGGER.severe("Error updating button state: " + e.getMessage());
        }
    }
}
