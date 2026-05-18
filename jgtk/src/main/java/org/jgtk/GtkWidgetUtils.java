package org.jgtk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Utility class for common GTK widget operations.
 *
 * This class provides higher-level convenience methods that build upon the core
 * NewGladeUI functionality, making it easier for controllers to work with
 * common widget patterns.
 */
public class GtkWidgetUtils {

    private static final Logger LOGGER = Logger.getLogger(GtkWidgetUtils.class.getName());

    /**
     * Populates a combo box with a list of items and optionally selects one.
     *
     * @param ui            the GladeUI instance
     * @param comboBoxId    the ID of the combo box
     * @param items         the list of items to add
     * @param selectedIndex the index to select, or -1 for no selection
     */
    public static void populateComboBox(GladeUI ui, String comboBoxId, List<String> items, int selectedIndex) {
        ui.clearComboBox(comboBoxId);

        for (String item : items) {
            ui.addComboBoxItem(comboBoxId, item);
        }

        if (selectedIndex >= 0 && selectedIndex < items.size()) {
            ui.setComboBoxActive(comboBoxId, selectedIndex);
        }
    }

    /**
     * Populates a combo box with items and selects by text value.
     *
     * @param ui           the GladeUI instance
     * @param comboBoxId   the ID of the combo box
     * @param items        the list of items to add
     * @param selectedText the text to select, or null for no selection
     */
    public static void populateComboBox(GladeUI ui, String comboBoxId, List<String> items, String selectedText) {
        populateComboBox(ui, comboBoxId, items, -1);

        if (selectedText != null) {
            selectComboBoxByText(ui, comboBoxId, selectedText);
        }
    }

    /**
     * Selects a combo box item by its text value.
     *
     * @param ui         the GladeUI instance
     * @param comboBoxId the ID of the combo box
     * @param text       the text to select
     * @return true if the text was found and selected, false otherwise
     */
    public static boolean selectComboBoxByText(GladeUI ui, String comboBoxId, String text) {
        if (text == null) {
            return false;
        }

        // Try to find the text by iterating through indices
        // This is a workaround since GTK doesn't provide a direct text-to-index method
        for (int i = 0; i < 100; i++) { // Reasonable upper limit
            ui.setComboBoxActive(comboBoxId, i);
            String currentText = ui.getComboBoxActiveText(comboBoxId);

            if (text.equals(currentText)) {
                return true;
            }

            if (currentText == null) {
                break; // Reached end of items
            }
        }

        ui.setComboBoxActive(comboBoxId, -1); // Clear selection
        return false;
    }

    /**
     * Sets up a file chooser with common defaults for downloads.
     *
     * @param ui            the GladeUI instance
     * @param fileChooserId the ID of the file chooser
     * @param defaultPath   the default path to set
     */
    public static void setupDownloadFileChooser(GladeUI ui, String fileChooserId, String defaultPath) {
        if (defaultPath != null) {
            ui.setFileChooserCurrentFolder(fileChooserId, defaultPath);
        } else {
            // Default to user's Downloads folder
            String downloadsPath = System.getProperty("user.home") + "/Downloads";
            ui.setFileChooserCurrentFolder(fileChooserId, downloadsPath);
        }
    }

    /**
     * Validates and sets an entry text with error handling.
     *
     * @param ui         the GladeUI instance
     * @param entryId    the ID of the entry widget
     * @param text       the text to set
     * @param allowEmpty whether empty text is allowed
     * @return true if the text was valid and set, false otherwise
     */
    public static boolean setAndValidateEntryText(GladeUI ui, String entryId, String text, boolean allowEmpty) {
        var textToSet = text != null ? text : "";

        if (!allowEmpty && textToSet.isBlank()) {
            LOGGER.warning("Empty text not allowed for entry: " + entryId);
            return false;
        }

        ui.setEntryText(entryId, textToSet);
        return true;
    }

    /**
     * Sets up common download categories in a combo box.
     *
     * @param ui               the GladeUI instance
     * @param comboBoxId       the ID of the combo box
     * @param selectedCategory the category to select, or null for default
     */
    public static void setupDownloadCategories(GladeUI ui, String comboBoxId, String selectedCategory) {
        List<String> categories = List.of(
                "Videos",
                "Audio",
                "Documents",
                "Images",
                "Programs",
                "Archives",
                "Others");

        populateComboBox(ui, comboBoxId, categories, selectedCategory != null ? selectedCategory : "Others");
    }

    /**
     * Sets up priority levels in a combo box.
     *
     * @param ui               the GladeUI instance
     * @param comboBoxId       the ID of the combo box
     * @param selectedPriority the priority to select (0=Low, 1=Normal, 2=High)
     */
    public static void setupPriorityLevels(GladeUI ui, String comboBoxId, int selectedPriority) {
        List<String> priorities = List.of("Low", "Normal", "High");
        populateComboBox(ui, comboBoxId, priorities, Math.max(0, Math.min(2, selectedPriority)));
    }

    /**
     * Sets up authentication widgets based on URL requirements.
     *
     * @param ui              the GladeUI instance
     * @param useAuthCheckId  the ID of the authentication checkbox
     * @param usernameEntryId the ID of the username entry
     * @param passwordEntryId the ID of the password entry
     * @param requiresAuth    whether authentication is required
     */
    public static void setupAuthenticationWidgets(GladeUI ui, String useAuthCheckId,
            String usernameEntryId, String passwordEntryId,
            boolean requiresAuth) {
        ui.setToggleButtonActive(useAuthCheckId, requiresAuth);
        ui.setWidgetSensitive(usernameEntryId, requiresAuth);
        ui.setWidgetSensitive(passwordEntryId, requiresAuth);

        if (!requiresAuth) {
            ui.setEntryText(usernameEntryId, "");
            ui.setEntryText(passwordEntryId, "");
        }
    }

    /**
     * Formats file size for display in widgets.
     *
     * @param bytes the size in bytes
     * @return formatted string (e.g., "1.5 MB", "2.3 GB")
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 0) {
            return "Unknown";
        }

        var units = new String[] { "B", "KB", "MB", "GB", "TB" };
        var unitIndex = 0;
        var size = (double) bytes;

        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return unitIndex == 0 ? String.format(java.util.Locale.US, "%d %s", (long) size, units[unitIndex])
                : String.format(java.util.Locale.US, "%.1f %s", size, units[unitIndex]);
    }

    /**
     * Formats speed for display in widgets.
     *
     * @param bytesPerSecond the speed in bytes per second
     * @return formatted string (e.g., "1.2 MB/s")
     */
    public static String formatSpeed(long bytesPerSecond) {
        return formatFileSize(bytesPerSecond) + "/s";
    }

    /**
     * Formats time duration for display.
     *
     * @param seconds the duration in seconds
     * @return formatted string (e.g., "2h 15m", "45s")
     */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "Unknown";
        }

        return switch ((int) (seconds / 60)) {
            case 0 -> seconds + "s";
            default -> {
                if (seconds < 3600) {
                    var minutes = seconds / 60;
                    var remainingSeconds = seconds % 60;
                    yield minutes + "m " + remainingSeconds + "s";
                } else {
                    var hours = seconds / 3600;
                    var minutes = (seconds % 3600) / 60;
                    yield hours + "h " + minutes + "m";
                }
            }
        };
    }

    /**
     * Validates a URL string.
     *
     * @param url the URL to validate
     * @return true if the URL appears valid, false otherwise
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        url = url.trim();
        return url.startsWith("http://") || url.startsWith("https://")
                || url.startsWith("ftp://") || url.startsWith("ftps://");
    }

    /**
     * Safely gets a numeric value from a spin button with bounds checking.
     *
     * @param ui           the GladeUI instance
     * @param spinButtonId the ID of the spin button
     * @param minValue     the minimum allowed value
     * @param maxValue     the maximum allowed value
     * @param defaultValue the default value if out of bounds
     * @return the validated value
     */
    public static int getSafeSpinButtonValue(GladeUI ui, String spinButtonId, int minValue, int maxValue,
            int defaultValue) {
        int value = ui.getSpinButtonValueAsInt(spinButtonId);

        if (value < minValue || value > maxValue) {
            LOGGER.warning(String.format("Spin button value %d out of bounds [%d, %d], using default %d",
                    value, minValue, maxValue, defaultValue));
            return defaultValue;
        }

        return value;
    }

    /**
     * Creates a map of widget values for easy batch operations.
     *
     * @param ui        the GladeUI instance
     * @param widgetIds the IDs of widgets to capture
     * @return a map of widget ID to current value
     */
    public static Map<String, Object> captureWidgetValues(GladeUI ui, String... widgetIds) {
        Map<String, Object> values = new HashMap<>();

        for (String widgetId : widgetIds) {
            try {
                // Try different widget types - this is a simple heuristic
                if (widgetId.contains("entry") || widgetId.contains("text")) {
                    values.put(widgetId, ui.getEntryText(widgetId));
                } else if (widgetId.contains("spin") || widgetId.contains("number")) {
                    values.put(widgetId, ui.getSpinButtonValueAsInt(widgetId));
                } else if (widgetId.contains("check") || widgetId.contains("toggle")) {
                    values.put(widgetId, ui.getToggleButtonActive(widgetId));
                } else if (widgetId.contains("combo")) {
                    values.put(widgetId, ui.getComboBoxActiveText(widgetId));
                } else if (widgetId.contains("chooser") || widgetId.contains("file")) {
                    values.put(widgetId, ui.getFileChooserFilename(widgetId));
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to capture value for widget " + widgetId + ": " + e.getMessage());
            }
        }

        return values;
    }

    /**
     * Restores widget values from a map.
     *
     * @param ui     the GladeUI instance
     * @param values the map of widget ID to value
     */
    public static void restoreWidgetValues(GladeUI ui, Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String widgetId = entry.getKey();
            Object value = entry.getValue();

            try {
                if (value instanceof String) {
                    if (widgetId.contains("entry") || widgetId.contains("text")) {
                        ui.setEntryText(widgetId, (String) value);
                    } else if (widgetId.contains("combo")) {
                        selectComboBoxByText(ui, widgetId, (String) value);
                    } else if (widgetId.contains("chooser") || widgetId.contains("file")) {
                        ui.setFileChooserFilename(widgetId, (String) value);
                    }
                } else if (value instanceof Integer) {
                    if (widgetId.contains("spin") || widgetId.contains("number")) {
                        ui.setSpinButtonValue(widgetId, ((Integer) value).doubleValue());
                    } else if (widgetId.contains("combo")) {
                        ui.setComboBoxActive(widgetId, (Integer) value);
                    }
                } else if (value instanceof Boolean) {
                    if (widgetId.contains("check") || widgetId.contains("toggle")) {
                        ui.setToggleButtonActive(widgetId, (Boolean) value);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to restore value for widget " + widgetId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Enables or disables a group of widgets.
     *
     * @param ui        the GladeUI instance
     * @param enabled   true to enable, false to disable
     * @param widgetIds the IDs of widgets to modify
     */
    public static void setWidgetsEnabled(GladeUI ui, boolean enabled, String... widgetIds) {
        for (String widgetId : widgetIds) {
            ui.setWidgetSensitive(widgetId, enabled);
        }
    }
}
