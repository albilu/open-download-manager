package org.jgtk.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for GTK signal types and their enhanced callback interfaces.
 * Provides mapping from signal names to appropriate callback types for
 * automatic parameter handling.
 */
public final class GtkSignalRegistry {

    private GtkSignalRegistry() {
        // Utility class - prevent instantiation
    }

    /**
     * Signal type enumeration for categorizing GTK signals
     */
    public enum SignalType {
        BASIC, // Standard 2-parameter signals
        NOTEBOOK_SWITCH, // Notebook switch-page signal
        SPIN_BUTTON, // Spin button value-changed signal
        COMBO_BOX, // Combo box changed signal
        TREE_ROW_ACTIVATED, // Tree view row-activated signal
        FILE_CHOOSER, // File chooser file-set signal
        SWITCH_STATE, // Switch state-set signal
        FOCUS_EVENT, // Focus in/out events
        BUTTON_PRESS_EVENT, // Button press events
        KEY_PRESS_EVENT // Key press events
    }

    private static final Map<String, SignalType> SIGNAL_TYPE_MAP = new HashMap<>();

    static {
        // Initialize signal type mappings
        SIGNAL_TYPE_MAP.put("switch-page", SignalType.NOTEBOOK_SWITCH);
        SIGNAL_TYPE_MAP.put("value-changed", SignalType.SPIN_BUTTON);
        SIGNAL_TYPE_MAP.put("changed", SignalType.COMBO_BOX);
        SIGNAL_TYPE_MAP.put("row-activated", SignalType.TREE_ROW_ACTIVATED);
        SIGNAL_TYPE_MAP.put("file-set", SignalType.FILE_CHOOSER);
        SIGNAL_TYPE_MAP.put("state-set", SignalType.SWITCH_STATE);
        SIGNAL_TYPE_MAP.put("focus-in-event", SignalType.FOCUS_EVENT);
        SIGNAL_TYPE_MAP.put("focus-out-event", SignalType.FOCUS_EVENT);
        SIGNAL_TYPE_MAP.put("button-press-event", SignalType.BUTTON_PRESS_EVENT);
        SIGNAL_TYPE_MAP.put("key-press-event", SignalType.KEY_PRESS_EVENT);

        // Add more signal mappings as needed
    }

    /**
     * Gets the signal type for the given signal name.
     * 
     * @param signalName the GTK signal name
     * @return the signal type, or BASIC if not found
     */
    public static SignalType getSignalType(String signalName) {
        return SIGNAL_TYPE_MAP.getOrDefault(signalName, SignalType.BASIC);
    }

    /**
     * Checks if a signal has enhanced parameter support.
     * 
     * @param signalName the GTK signal name
     * @return true if the signal has enhanced parameter support
     */
    public static boolean hasEnhancedSupport(String signalName) {
        return SIGNAL_TYPE_MAP.containsKey(signalName);
    }

    /**
     * Gets the callback interface class for a signal type.
     * 
     * @param signalType the signal type
     * @return the appropriate callback interface class
     */
    public static Class<?> getCallbackInterface(SignalType signalType) {
        return switch (signalType) {
            case NOTEBOOK_SWITCH -> GtkCallbacks.NotebookSwitchPageCallback.class;
            case TREE_ROW_ACTIVATED -> GtkCallbacks.TreeViewRowActivatedCallback.class;
            case SWITCH_STATE -> GtkCallbacks.SwitchStateSetCallback.class;
            case SPIN_BUTTON, COMBO_BOX, FILE_CHOOSER, FOCUS_EVENT, BUTTON_PRESS_EVENT, KEY_PRESS_EVENT, BASIC ->
                GtkCallbacks.GtkCallback.class;
        };
    }

    /**
     * Gets a description of the signal parameters for debugging.
     * 
     * @param signalType the signal type
     * @return a description of the expected parameters
     */
    public static String getSignalDescription(SignalType signalType) {
        return switch (signalType) {
            case NOTEBOOK_SWITCH -> "(GtkNotebook *notebook, GtkWidget *page, guint page_num, gpointer user_data)";
            case SPIN_BUTTON -> "(GtkSpinButton *spin_button, gpointer user_data)";
            case COMBO_BOX -> "(GtkComboBox *combo_box, gpointer user_data)";
            case TREE_ROW_ACTIVATED ->
                "(GtkTreeView *tree_view, GtkTreePath *path, GtkTreeViewColumn *column, gpointer user_data)";
            case FILE_CHOOSER -> "(GtkFileChooser *chooser, gpointer user_data)";
            case SWITCH_STATE -> "(GtkSwitch *widget, gboolean state, gpointer user_data)";
            case FOCUS_EVENT -> "(GtkEntry *entry, GdkEventFocus *event, gpointer user_data)";
            case BUTTON_PRESS_EVENT -> "(GtkWidget *widget, GdkEventButton *event, gpointer user_data)";
            case KEY_PRESS_EVENT -> "(GtkWidget *widget, GdkEventKey *event, gpointer user_data)";
            case BASIC -> "(GtkWidget *widget, gpointer user_data)";
        };
    }
}
