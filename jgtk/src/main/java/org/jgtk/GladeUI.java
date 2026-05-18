package org.jgtk;

import java.util.logging.Logger;

import org.jgtk.core.GtkCallbacks;
import org.jgtk.core.GtkInitializationService;
import org.jgtk.core.GtkNativeLibraries;
import org.jgtk.service.ResourceLoadingService;
import org.jgtk.service.SignalManagementService;
import org.jgtk.service.WidgetManagementService;

import com.sun.jna.Pointer;

/**
 * Modular and flexible GTK Glade UI loader.
 *
 * This class has been refactored from a monolithic design to a modular
 * architecture that separates concerns while maintaining backward
 * compatibility. The main components are:
 *
 * - Core: GTK initialization, constants, and native library interfaces -
 * Services: Resource loading, signal management, widget management - Widgets:
 * Specialized services for different widget types
 *
 * This design provides better maintainability, testability, and flexibility
 * while preserving the simple API that existing code expects.
 */
public class GladeUI {

    private static final Logger LOGGER = Logger.getLogger(GladeUI.class.getName());

    private final ResourceLoadingService resourceService;
    private final SignalManagementService signalService;
    private final WidgetManagementService widgetService;

    /**
     * Creates a new GladeUI instance and initializes GTK if needed. This
     * constructor creates all necessary services for resource loading, signal
     * management, and widget operations.
     */
    public GladeUI() {
        GtkInitializationService.initializeGtk();
        this.resourceService = new ResourceLoadingService();
        this.signalService = new SignalManagementService(resourceService.getBuilder());
        this.widgetService = new WidgetManagementService(resourceService);

        LOGGER.fine("GladeUI instance created with modular architecture");
    }

    /**
     * Creates a new GladeUI instance and loads the specified Glade file.
     *
     * @param filename the path to the Glade file to load
     */
    public GladeUI(String filename) {
        this();
        loadFromFile(filename);
    }

    /**
     * Creates an isolated GladeUI instance for loading a dialog or window. This
     * is useful when you need a separate UI context that won't interfere with
     * existing builders.
     *
     * @param resourcePath the path to the resource (e.g.,
     *                     "/glade/settings/settings.glade")
     * @return a new GladeUI instance with the resource loaded, or null if
     *         loading failed
     */
    public static GladeUI createForDialog(String resourcePath) {
        try {
            GladeUI ui = new GladeUI();
            if (ui.loadFromResource(resourcePath)) {
                return ui;
            } else {
                ui.destroy();
                return null;
            }
        } catch (Exception e) {
            LOGGER.severe("F ailed to create NewGladeUI for dialog: " + e.getMessage());
            return null;
        }
    }

    /**
     * Creates an isolated GladeUI instance using a specific classloader.
     *
     * @param resourcePath the path to the resource
     * @param classLoader  the classloader to use for loading the resource
     * @return a new GladeUI instance with the resource loaded, or null if
     *         loading failed
     */
    public static GladeUI createForDialog(String resourcePath, ClassLoader classLoader) {
        try {
            GladeUI ui = new GladeUI();
            if (ui.loadFromResource(resourcePath, classLoader)) {
                return ui;
            } else {
                ui.destroy();
                return null;
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to create NewGladeUI for dialog: " + e.getMessage());
            return null;
        }
    }

    // === Resource Loading Methods ===
    /**
     * Loads a Glade file from the filesystem.
     *
     * @param filename the path to the Glade file
     * @return true if the file was loaded successfully, false otherwise
     */
    public boolean loadFromFile(String filename) {
        return resourceService.loadFromFile(filename);
    }

    /**
     * Loads a Glade UI from a string containing the Glade XML content.
     *
     * @param gladeContent the Glade XML content as a string
     * @return true if the content was loaded successfully, false otherwise
     */
    public boolean loadFromString(String gladeContent) {
        return resourceService.loadFromString(gladeContent);
    }

    /**
     * Loads a Glade file from the classpath resources.
     *
     * @param resourcePath the path to the resource (e.g.,
     *                     "/glade/main-window/main-window.glade")
     * @return true if the resource was loaded successfully, false otherwise
     */
    public boolean loadFromResource(String resourcePath) {
        return resourceService.loadFromResource(resourcePath);
    }

    /**
     * Loads a Glade file from the classpath resources using a specific
     * classloader.
     *
     * @param resourcePath the path to the resource
     * @param classLoader  the classloader to use for loading the resource
     * @return true if the resource was loaded successfully, false otherwise
     */
    public boolean loadFromResource(String resourcePath, ClassLoader classLoader) {
        return resourceService.loadFromResource(resourcePath, classLoader);
    }

    // === Signal Management Methods ===
    /**
     * Registers a signal handler with the specified name.
     *
     * @param handlerName the name of the handler (as specified in the Glade
     *                    file)
     * @param callback    the callback to execute when the signal is triggered
     */
    public void on(String handlerName, GtkCallbacks.GtkCallback callback) {
        signalService.registerHandler(handlerName, callback);
    }

    /**
     * Registers an enhanced signal handler for notebook switch-page events.
     * Provides direct access to the page number parameter.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param callback    the enhanced callback with page number parameter
     */
    public void onNotebookSwitchPage(String handlerName, GtkCallbacks.NotebookSwitchPageCallback callback) {
        signalService.registerEnhancedHandler(handlerName, callback);
    }

    /**
     * Registers an enhanced signal handler for tree view row-activated events.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param callback    the enhanced callback for tree view events
     */
    public void onTreeViewRowActivated(String handlerName, GtkCallbacks.TreeViewRowActivatedCallback callback) {
        signalService.registerEnhancedHandler(handlerName, callback);
    }

    /**
     * Registers an enhanced signal handler for switch state-set events.
     *
     * @param handlerName the name of the handler (as specified in the Glade file)
     * @param callback    the enhanced callback for switch events
     */
    public void onSwitchStateSet(String handlerName, GtkCallbacks.SwitchStateSetCallback callback) {
        signalService.registerEnhancedHandler(handlerName, callback);
    }

    /**
     * Convenience method to register a signal handler using a Runnable.
     *
     * @param handlerName the name of the handler (as specified in the Glade
     *                    file)
     * @param action      the action to execute when the signal is triggered
     */
    public void on(String handlerName, Runnable action) {
        signalService.registerHandler(handlerName, action);
    }

    /**
     * Connects all registered signal handlers. This must be called after
     * registering handlers and before showing widgets.
     */
    public void connectSignals() {
        signalService.connectSignals();
    }

    /**
     * Connects a signal directly to a widget.
     *
     * @param widget     the widget to connect the signal to
     * @param signalName the name of the signal (e.g., "clicked", "destroy")
     * @param callback   the callback to execute
     */
    public void connectSignal(Pointer widget, String signalName, GtkCallbacks.GtkCallback callback) {
        signalService.connectSignal(widget, signalName, callback);
    }

    /**
     * Connects a signal to a widget by ID.
     *
     * @param widgetId   the ID of the widget
     * @param signalName the name of the signal
     * @param callback   the callback to execute
     */
    public void connectSignal(String widgetId, String signalName, GtkCallbacks.GtkCallback callback) {
        Pointer widget = getWidget(widgetId);
        if (widget != null) {
            signalService.connectSignal(widget, signalName, callback);
        }
    }

    // === Widget Access and Basic Operations ===
    /**
     * Gets a widget by its ID.
     *
     * @param id the ID of the widget as specified in the Glade file
     * @return the widget pointer, or null if not found
     */
    public Pointer getWidget(String id) {
        return widgetService.getWidget(id);
    }

    public void showAll(String widgetId) {
        widgetService.showAll(widgetId);
    }

    public void show(String widgetId) {
        widgetService.show(widgetId);
    }

    public void hide(String widgetId) {
        widgetService.hide(widgetId);
    }

    public void setWidgetVisible(String widgetId, boolean visible) {
        widgetService.setWidgetVisible(widgetId, visible);
    }

    public boolean getWidgetVisible(String widgetId) {
        return widgetService.getWidgetVisible(widgetId);
    }

    public void setWidgetSensitive(String widgetId, boolean sensitive) {
        widgetService.setWidgetSensitive(widgetId, sensitive);
    }

    public boolean getWidgetSensitive(String widgetId) {
        return widgetService.getWidgetSensitive(widgetId);
    }

    public void setWidgetTooltip(String widgetId, String tooltip) {
        widgetService.setWidgetTooltip(widgetId, tooltip);
    }

    // === Text Widget Methods ===
    public void setEntryText(String widgetId, String text) {
        widgetService.setEntryText(widgetId, text);
    }

    public String getEntryText(String widgetId) {
        return widgetService.getEntryText(widgetId);
    }

    public void setLabelText(String widgetId, String text) {
        widgetService.setLabelText(widgetId, text);
    }

    public String getLabelText(String widgetId) {
        return widgetService.getLabelText(widgetId);
    }

    public void setSpinButtonValue(String widgetId, double value) {
        widgetService.setSpinButtonValue(widgetId, value);
    }

    public int getSpinButtonValueAsInt(String widgetId) {
        return widgetService.getSpinButtonValueAsInt(widgetId);
    }

    public double getSpinButtonValue(String widgetId) {
        return widgetService.getSpinButtonValue(widgetId);
    }

    // === Interactive Widget Methods ===
    public void setToggleButtonActive(String widgetId, boolean active) {
        widgetService.setToggleButtonActive(widgetId, active);
    }

    public boolean getToggleButtonActive(String widgetId) {
        return widgetService.getToggleButtonActive(widgetId);
    }

    public void setCheckMenuItemActive(String widgetId, boolean active) {
        widgetService.setCheckMenuItemActive(widgetId, active);
    }

    public boolean getCheckMenuItemActive(String widgetId) {
        return widgetService.getCheckMenuItemActive(widgetId);
    }

    public void setSwitchActive(String widgetId, boolean active) {
        widgetService.setSwitchActive(widgetId, active);
    }

    public boolean getSwitchActive(String widgetId) {
        return widgetService.getSwitchActive(widgetId);
    }

    public boolean getWidgetActive(String widgetId) {
        return widgetService.getWidgetActive(widgetId);
    }

    public void setWidgetActive(String widgetId, boolean active) {
        widgetService.setWidgetActive(widgetId, active);
    }

    // === Combo Box Methods ===
    public void clearComboBox(String widgetId) {
        widgetService.clearComboBox(widgetId);
    }

    public void addComboBoxItem(String widgetId, String text) {
        widgetService.addComboBoxItem(widgetId, text);
    }

    public void setComboBoxActive(String widgetId, int index) {
        widgetService.setComboBoxActive(widgetId, index);
    }

    public int getComboBoxActive(String widgetId) {
        return widgetService.getComboBoxActive(widgetId);
    }

    public String getComboBoxActiveText(String widgetId) {
        return widgetService.getComboBoxActiveText(widgetId);
    }

    // === File Chooser Methods ===
    public void setFileChooserCurrentFolder(String widgetId, String folder) {
        widgetService.setFileChooserCurrentFolder(widgetId, folder);
    }

    public String getFileChooserCurrentFolder(String widgetId) {
        return widgetService.getFileChooserCurrentFolder(widgetId);
    }

    public void setFileChooserFilename(String widgetId, String filename) {
        widgetService.setFileChooserFilename(widgetId, filename);
    }

    public String getFileChooserFilename(String widgetId) {
        return widgetService.getFileChooserFilename(widgetId);
    }

    // === Dialog Methods ===
    public void showErrorDialog(String parentWidgetId, String message) {
        widgetService.showErrorDialog(parentWidgetId, message);
    }

    public void showInfoDialog(String parentWidgetId, String message) {
        widgetService.showInfoDialog(parentWidgetId, message);
    }

    public void showWarningDialog(String parentWidgetId, String message) {
        widgetService.showWarningDialog(parentWidgetId, message);
    }

    public boolean showQuestionDialog(String parentWidgetId, String message) {
        return widgetService.showQuestionDialog(parentWidgetId, message);
    }

    public String showFileChooserDialog(String parentWidgetId, String title, String currentFolder) {
        return widgetService.showFileChooserDialog(parentWidgetId, title, currentFolder);
    }

    public String showDirectoryChooserDialog(String parentWidgetId, String title, String currentFolder) {
        return widgetService.showDirectoryChooserDialog(parentWidgetId, title, currentFolder);
    }

    // === Window Methods ===
    public void setWindowTitle(String widgetId, String title) {
        widgetService.setWindowTitle(widgetId, title);
    }

    public String getWindowTitle(String widgetId) {
        return widgetService.getWindowTitle(widgetId);
    }

    // === GTK Lifecycle Methods ===
    /**
     * Runs the GTK main loop. This will block until quit() is called.
     */
    public void run() {
        GtkInitializationService.runMainLoop();
    }

    /**
     * Quits the GTK main loop. This will cause run() to return.
     */
    public void quit() {
        GtkInitializationService.quitMainLoop();
    }

    /**
     * Processes pending GTK events without blocking.
     */
    public void processEvents() {
        GtkInitializationService.processEvents();
    }

    /**
     * Destroys this GladeUI instance and releases all associated resources.
     */
    public void destroy() {
        if (resourceService != null) {
            resourceService.destroy();
        }
        LOGGER.fine("GladeUI instance destroyed");
    }

    // === Service Access (for advanced usage) ===
    /**
     * Gets the resource loading service for advanced operations. This method is
     * provided for users who need direct access to the underlying services.
     *
     * @return the resource loading service
     */
    public ResourceLoadingService getResourceService() {
        return resourceService;
    }

    /**
     * Gets the signal management service for advanced operations.
     *
     * @return the signal management service
     */
    public SignalManagementService getSignalService() {
        return signalService;
    }

    /**
     * Gets the widget management service for advanced operations.
     *
     * @return the widget management service
     */
    public WidgetManagementService getWidgetService() {
        return widgetService;
    }

    // === Advanced Widget Methods (TreeView, ListStore, etc.) ===
    /**
     * Shows a column in a tree view.
     */
    public void showColumn(String columnId) {
        Pointer column = getWidget(columnId);
        if (column != null) {
            try {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_column_set_visible(column, true);
            } catch (Exception e) {
                LOGGER.warning("Error showing column: " + e.getMessage());
            }
        }
    }

    /**
     * Hides a column in a tree view.
     */
    public void hideColumn(String columnId) {
        Pointer column = getWidget(columnId);
        if (column != null) {
            try {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_column_set_visible(column, false);
            } catch (Exception e) {
                LOGGER.warning("Error hiding column: " + e.getMessage());
            }
        }
    }

    /**
     * Gets the current page of a notebook widget.
     */
    public int getNotebookCurrentPage(String widgetId) {
        Pointer notebook = getWidget(widgetId);
        if (notebook != null) {
            try {
                return GtkNativeLibraries.Gtk.INSTANCE.gtk_notebook_get_current_page(notebook);
            } catch (Exception e) {
                LOGGER.warning("Error getting notebook current page: " + e.getMessage());
            }
        }
        return -1;
    }

    /**
     * Gets the selection from a tree view.
     */
    public Pointer getTreeViewSelection(String treeViewId) {
        Pointer treeView = getWidget(treeViewId);
        if (treeView != null) {
            try {
                return GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_get_selection(treeView);
            } catch (Exception e) {
                LOGGER.warning("Error getting tree view selection: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Checks if a tree selection has any selection.
     */
    public boolean treeSelectionHasSelection(Pointer selection) {
        if (selection != null) {
            try {
                return GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_count_selected_rows(selection) > 0;
            } catch (Exception e) {
                LOGGER.warning("Error checking tree selection: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Gets the iterator for the selected item in a tree selection.
     */
    public Pointer getTreeSelectionIter(Pointer selection) {
        if (selection != null) {
            try {
                // Allocate memory for GtkTreeIter structures (typically 32 bytes each)
                com.sun.jna.Memory iter = new com.sun.jna.Memory(32);
                com.sun.jna.Memory model = new com.sun.jna.Memory(8); // Pointer size
                iter.clear(); // Initialize to zero
                model.clear(); // Initialize to zero
                boolean hasSelection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_get_selected(
                        selection, model, iter);
                return hasSelection ? iter : null;
            } catch (Exception e) {
                LOGGER.warning("Error getting tree selection iter: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Gets the model from a tree selection.
     */
    public Pointer getTreeSelectionModel(Pointer selection) {
        if (selection != null) {
            try {
                // Allocate memory for pointer (typically 8 bytes on 64-bit systems)
                com.sun.jna.Memory model = new com.sun.jna.Memory(8);
                com.sun.jna.Memory iter = new com.sun.jna.Memory(32);
                model.clear(); // Initialize to zero
                iter.clear(); // Initialize to zero

                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_get_selected(selection, model, iter);
                return model;
            } catch (Exception e) {
                LOGGER.warning("Error getting tree selection model: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Gets the index of a tree iterator.
     */
    public int getTreeIterIndex(Pointer iter) {
        // This is a complex operation that would require GTK tree model operations
        // For now, return -1 as placeholder
        LOGGER.warning("getTreeIterIndex not fully implemented in modular architecture");
        return -1;
    }

    /**
     * Gets the path from a cell renderer toggle.
     */
    public String getCellRendererTogglePath(Pointer cellRenderer) {
        // This is a complex operation that would require signal data parsing
        // For now, return null as placeholder
        LOGGER.warning("getCellRendererTogglePath not fully implemented in modular architecture");
        return null;
    }

    /**
     * Sets a value in a tree model at the specified position.
     */
    public void setTreeModelValue(String treeViewId, int row, int column, Object value) {
        try {
            Pointer listStore = getTreeViewListStore(treeViewId);
            if (listStore != null) {
                // This is a complex operation requiring proper tree model iteration
                LOGGER.warning("setTreeModelValue not fully implemented in modular architecture");
            }
        } catch (Exception e) {
            LOGGER.warning("Error setting tree model value: " + e.getMessage());
        }
    }

    /**
     * Gets the list store from a tree view.
     */
    public Pointer getTreeViewListStore(String treeViewId) {
        Pointer treeView = getWidget(treeViewId);
        if (treeView != null) {
            try {
                return GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_get_model(treeView);
            } catch (Exception e) {
                LOGGER.warning("Error getting tree view list store: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Appends a new row to a list store.
     */
    public Pointer listStoreAppend(Pointer listStore) {
        if (listStore == null) {
            LOGGER.warning("listStoreAppend: listStore is null");
            return null;
        }

        try {
            // Allocate memory for GtkTreeIter structure (typically 32 bytes)
            com.sun.jna.Memory iter = new com.sun.jna.Memory(32);
            iter.clear(); // Initialize memory to zero

            // Call GTK function to append row
            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_append(listStore, iter);

            LOGGER.finest("listStoreAppend: Successfully appended row to list store");
            return iter;

        } catch (Exception e) {
            LOGGER.warning("Error appending to list store: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sets a value in a list store.
     */
    public void listStoreSetValue(Pointer listStore, Pointer iter, int column, Object value) {
        if (listStore == null) {
            LOGGER.warning("listStoreSetValue: listStore is null");
            return;
        }

        if (iter == null) {
            LOGGER.warning("listStoreSetValue: iter is null");
            return;
        }

        try {
            // Sanitize and validate the value
            Object safeValue = sanitizeListStoreValue(value);

            switch (safeValue) {
                case Boolean boolValue ->
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, boolValue, -1);
                case String stringValue ->
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, stringValue, -1);
                case Integer intValue ->
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, intValue, -1);
                case null, default -> {
                    // Set empty string for unsupported types or null values
                    GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, "", -1);
                }
            }

            LOGGER.finest("listStoreSetValue: Set column " + column + " to: " + safeValue);

        } catch (Exception e) {
            LOGGER.warning("Error setting list store value at column " + column + ": " + e.getMessage());
        }
    }

    /**
     * Sanitizes values for list store to prevent native crashes.
     */
    private Object sanitizeListStoreValue(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof String stringValue) {
            // Remove control characters and limit length
            String sanitized = stringValue.replaceAll("[\u0000-\u001f\u007f-\u009f]", "");
            if (sanitized.length() > 1000) { // Conservative limit
                sanitized = sanitized.substring(0, 997) + "...";
            }
            return sanitized;
        }

        return value;
    }

    /**
     * Clears all items from a list store.
     */
    public void listStoreClear(Pointer listStore) {
        if (listStore != null) {
            try {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_clear(listStore);
            } catch (Exception e) {
                LOGGER.warning("Error clearing list store: " + e.getMessage());
            }
        }
    }
}
