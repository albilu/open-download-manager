package org.odm.ui.service.state;

import org.manager.GlobalSettings;
import org.manager.download.Download;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Service for managing application UI state.
 *
 * This service manages UI-specific state such as window positions,
 * selection states, view preferences, and other UI-related data
 * that needs to persist across application sessions.
 */
public class SessionStateService {

    private static final Logger LOGGER = Logger.getLogger(SessionStateService.class.getName());

    // UI state keys
    public static final String MAIN_WINDOW_WIDTH = "ui.main_window.width";
    public static final String MAIN_WINDOW_HEIGHT = "ui.main_window.height";
    public static final String MAIN_WINDOW_X = "ui.main_window.x";
    public static final String MAIN_WINDOW_Y = "ui.main_window.y";
    public static final String MAIN_WINDOW_MAXIMIZED = "ui.main_window.maximized";
    public static final String SIDEBAR_WIDTH = "ui.sidebar.width";
    public static final String DETAILS_HEIGHT = "ui.details.height";
    public static final String SELECTED_CATEGORY = "ui.selected_category";
    public static final String DOWNLOAD_LIST_SORT_COLUMN = "ui.download_list.sort_column";
    public static final String DOWNLOAD_LIST_SORT_ORDER = "ui.download_list.sort_order";
    public static final String TOOLBAR_VISIBLE = "ui.toolbar.visible";
    public static final String STATUSBAR_VISIBLE = "ui.statusbar.visible";
    public static final String DETAILS_PANEL_VISIBLE = "ui.details_panel.visible";

    // Dependencies
    private final GlobalSettings settings;

    // Runtime state
    private final Map<String, Object> runtimeState = new ConcurrentHashMap<>();
    private final Set<UIStateListener> listeners = new CopyOnWriteArraySet<>();

    // Current selections
    private String selectedDownloadId = null;
    private String selectedCategory = "All Categories";
    private Download selectedDownload;

    // View state
    private boolean toolbarVisible = true;
    private boolean statusbarVisible = true;
    private boolean detailsPanelVisible = true;
    private boolean sidebarVisible = true;

    /**
     * Interface for listening to UI state changes.
     */
    public interface UIStateListener {
        /**
         * Called when UI state changes.
         *
         * @param key The state key that changed
         * @param oldValue The previous value
         * @param newValue The new value
         */
        void onStateChanged(String key, Object oldValue, Object newValue);
    }

    /**
     * Creates a new UIStateService.
     *
     * @param settings The global settings instance
     */
    public SessionStateService(GlobalSettings settings) {
        this.settings = settings;
        LOGGER.info("UIStateService initialized");
    }

    /**
     * Initializes the service and loads saved state.
     */
    public void initialize() {
        try {
            loadSavedState();
            LOGGER.info("UI state loaded successfully");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error loading UI state", e);
        }
    }

    /**
     * Loads saved UI state from settings.
     */
    private void loadSavedState() {
        // Load window state
        setMainWindowWidth(settings.getIntProperty(MAIN_WINDOW_WIDTH, 1000));
        setMainWindowHeight(settings.getIntProperty(MAIN_WINDOW_HEIGHT, 700));
        setMainWindowX(settings.getIntProperty(MAIN_WINDOW_X, -1));
        setMainWindowY(settings.getIntProperty(MAIN_WINDOW_Y, -1));
        setMainWindowMaximized(settings.getBooleanProperty(MAIN_WINDOW_MAXIMIZED, false));

        // Load panel sizes
        setSidebarWidth(settings.getIntProperty(SIDEBAR_WIDTH, 200));
        setDetailsHeight(settings.getIntProperty(DETAILS_HEIGHT, 200));

        // Load view preferences
        setSelectedCategory(settings.getProperty(SELECTED_CATEGORY, "All Categories"));
        setToolbarVisible(settings.getBooleanProperty(TOOLBAR_VISIBLE, true));
        setStatusbarVisible(settings.getBooleanProperty(STATUSBAR_VISIBLE, true));
        setDetailsPanelVisible(settings.getBooleanProperty(DETAILS_PANEL_VISIBLE, true));

        // Load download list preferences
        setDownloadListSortColumn(settings.getProperty(DOWNLOAD_LIST_SORT_COLUMN, "filename"));
        setDownloadListSortOrder(settings.getProperty(DOWNLOAD_LIST_SORT_ORDER, "ascending"));
    }

    /**
     * Saves current UI state to settings.
     */
    public void saveState() {
        try {
            // Save window state
            settings.setProperty(MAIN_WINDOW_WIDTH, String.valueOf(getMainWindowWidth()));
            settings.setProperty(MAIN_WINDOW_HEIGHT, String.valueOf(getMainWindowHeight()));
            settings.setProperty(MAIN_WINDOW_X, String.valueOf(getMainWindowX()));
            settings.setProperty(MAIN_WINDOW_Y, String.valueOf(getMainWindowY()));
            settings.setProperty(MAIN_WINDOW_MAXIMIZED, String.valueOf(isMainWindowMaximized()));

            // Save panel sizes
            settings.setProperty(SIDEBAR_WIDTH, String.valueOf(getSidebarWidth()));
            settings.setProperty(DETAILS_HEIGHT, String.valueOf(getDetailsHeight()));

            // Save view preferences
            settings.setProperty(SELECTED_CATEGORY, getSelectedCategory());
            settings.setProperty(TOOLBAR_VISIBLE, String.valueOf(isToolbarVisible()));
            settings.setProperty(STATUSBAR_VISIBLE, String.valueOf(isStatusbarVisible()));
            settings.setProperty(DETAILS_PANEL_VISIBLE, String.valueOf(isDetailsPanelVisible()));

            // Save download list preferences
            settings.setProperty(DOWNLOAD_LIST_SORT_COLUMN, getDownloadListSortColumn());
            settings.setProperty(DOWNLOAD_LIST_SORT_ORDER, getDownloadListSortOrder());

            // Persist settings
            settings.save();

            LOGGER.fine("UI state saved successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error saving UI state", e);
        }
    }

    /**
     * Adds a UI state listener.
     *
     * @param listener The listener to add
     */
    public void addStateListener(UIStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a UI state listener.
     *
     * @param listener The listener to remove
     */
    public void removeStateListener(UIStateListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifies listeners of state changes.
     */
    private void notifyStateChanged(String key, Object oldValue, Object newValue) {
        for (UIStateListener listener : listeners) {
            try {
                listener.onStateChanged(key, oldValue, newValue);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying state listener", e);
            }
        }
    }

    /**
     * Sets a runtime state value.
     */
    private void setState(String key, Object value) {
        Object oldValue = runtimeState.put(key, value);
        notifyStateChanged(key, oldValue, value);
    }

    /**
     * Gets a runtime state value.
     */
    private Object getState(String key, Object defaultValue) {
        return runtimeState.getOrDefault(key, defaultValue);
    }

    // Main window state

    public int getMainWindowWidth() {
        return (Integer) getState(MAIN_WINDOW_WIDTH, 1000);
    }

    public void setMainWindowWidth(int width) {
        setState(MAIN_WINDOW_WIDTH, width);
    }

    public int getMainWindowHeight() {
        return (Integer) getState(MAIN_WINDOW_HEIGHT, 700);
    }

    public void setMainWindowHeight(int height) {
        setState(MAIN_WINDOW_HEIGHT, height);
    }

    public int getMainWindowX() {
        return (Integer) getState(MAIN_WINDOW_X, -1);
    }

    public void setMainWindowX(int x) {
        setState(MAIN_WINDOW_X, x);
    }

    public int getMainWindowY() {
        return (Integer) getState(MAIN_WINDOW_Y, -1);
    }

    public void setMainWindowY(int y) {
        setState(MAIN_WINDOW_Y, y);
    }

    public boolean isMainWindowMaximized() {
        return (Boolean) getState(MAIN_WINDOW_MAXIMIZED, false);
    }

    public void setMainWindowMaximized(boolean maximized) {
        setState(MAIN_WINDOW_MAXIMIZED, maximized);
    }

    // Panel sizes

    public int getSidebarWidth() {
        return (Integer) getState(SIDEBAR_WIDTH, 200);
    }

    public void setSidebarWidth(int width) {
        setState(SIDEBAR_WIDTH, width);
    }

    public int getDetailsHeight() {
        return (Integer) getState(DETAILS_HEIGHT, 200);
    }

    public void setDetailsHeight(int height) {
        setState(DETAILS_HEIGHT, height);
    }

    // Selection state

    public String getSelectedDownloadId() {
        return selectedDownloadId;
    }

    public void setSelectedDownloadId(String downloadId) {
        String oldValue = this.selectedDownloadId;
        this.selectedDownloadId = downloadId;
        notifyStateChanged("selected_download_id", oldValue, downloadId);
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public void setSelectedCategory(String category) {
        String oldValue = this.selectedCategory;
        this.selectedCategory = category;
        setState(SELECTED_CATEGORY, category);
    }

    public Download getSelectedDownload() {
        return selectedDownload;
    }

    public void setSelectedDownload(Download download) {
        Download oldValue = this.selectedDownload;
        this.selectedDownload = download;

        if (download != null) {
            setSelectedDownloadId(download.getId());
        } else {
            setSelectedDownloadId(null);
        }

        notifyStateChanged("selected_download", oldValue, download);
    }

    // View preferences

    public boolean isToolbarVisible() {
        return toolbarVisible;
    }

    public void setToolbarVisible(boolean visible) {
        boolean oldValue = this.toolbarVisible;
        this.toolbarVisible = visible;
        setState(TOOLBAR_VISIBLE, visible);
    }

    public boolean isStatusbarVisible() {
        return statusbarVisible;
    }

    public void setStatusbarVisible(boolean visible) {
        boolean oldValue = this.statusbarVisible;
        this.statusbarVisible = visible;
        setState(STATUSBAR_VISIBLE, visible);
    }

    public boolean isDetailsPanelVisible() {
        return detailsPanelVisible;
    }

    public void setDetailsPanelVisible(boolean visible) {
        boolean oldValue = this.detailsPanelVisible;
        this.detailsPanelVisible = visible;
        setState(DETAILS_PANEL_VISIBLE, visible);
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    public void setSidebarVisible(boolean visible) {
        boolean oldValue = this.sidebarVisible;
        this.sidebarVisible = visible;
        notifyStateChanged("sidebar_visible", oldValue, visible);
    }

    // Download list preferences

    public String getDownloadListSortColumn() {
        return (String) getState(DOWNLOAD_LIST_SORT_COLUMN, "filename");
    }

    public void setDownloadListSortColumn(String column) {
        setState(DOWNLOAD_LIST_SORT_COLUMN, column);
    }

    public String getDownloadListSortOrder() {
        return (String) getState(DOWNLOAD_LIST_SORT_ORDER, "ascending");
    }

    public void setDownloadListSortOrder(String order) {
        setState(DOWNLOAD_LIST_SORT_ORDER, order);
    }

    // Utility methods

    /**
     * Checks if any downloads are selected.
     */
    public boolean hasSelectedDownload() {
        return selectedDownload != null;
    }

    /**
     * Checks if the selected download can be started.
     */
    public boolean canStartSelectedDownload() {
        return hasSelectedDownload() &&
               (selectedDownload.getStatus() == Download.Status.PAUSED ||
                selectedDownload.getStatus() == Download.Status.CANCELED ||
                selectedDownload.getStatus() == Download.Status.ERROR);
    }

    /**
     * Checks if the selected download can be paused.
     */
    public boolean canPauseSelectedDownload() {
        return hasSelectedDownload() &&
               selectedDownload.getStatus() == Download.Status.DOWNLOADING;
    }

    /**
     * Checks if the selected download can be stopped.
     */
    public boolean canStopSelectedDownload() {
        return hasSelectedDownload() &&
               (selectedDownload.getStatus() == Download.Status.DOWNLOADING ||
                selectedDownload.getStatus() == Download.Status.PAUSED);
    }

    /**
     * Checks if the selected download can be removed.
     */
    public boolean canRemoveSelectedDownload() {
        return hasSelectedDownload() &&
               selectedDownload.getStatus() != Download.Status.DOWNLOADING;
    }

    /**
     * Checks if the selected download can show properties.
     */
    public boolean canShowSelectedDownloadProperties() {
        return hasSelectedDownload();
    }

    /**
     * Gets the current view mode for download display.
     */
    public String getDownloadViewMode() {
        return (String) getState("download_view_mode", "list");
    }

    /**
     * Sets the view mode for download display.
     */
    public void setDownloadViewMode(String mode) {
        setState("download_view_mode", mode);
    }

    /**
     * Gets the current filter text for downloads.
     */
    public String getDownloadFilterText() {
        return (String) getState("download_filter_text", "");
    }

    /**
     * Sets the filter text for downloads.
     */
    public void setDownloadFilterText(String filterText) {
        setState("download_filter_text", filterText != null ? filterText : "");
    }

    /**
     * Gets the current status filter for downloads.
     */
    public Download.Status getDownloadStatusFilter() {
        return (Download.Status) getState("download_status_filter", null);
    }

    /**
     * Sets the status filter for downloads.
     */
    public void setDownloadStatusFilter(Download.Status status) {
        setState("download_status_filter", status);
    }

    /**
     * Clears all filters.
     */
    public void clearFilters() {
        setDownloadFilterText("");
        setDownloadStatusFilter(null);
        setSelectedCategory("All Categories");
    }

    /**
     * Resets UI state to defaults.
     */
    public void resetToDefaults() {
        LOGGER.info("Resetting UI state to defaults");

        // Reset window state
        setMainWindowWidth(1000);
        setMainWindowHeight(700);
        setMainWindowX(-1);
        setMainWindowY(-1);
        setMainWindowMaximized(false);

        // Reset panel sizes
        setSidebarWidth(200);
        setDetailsHeight(200);

        // Reset view preferences
        setSelectedCategory("All Categories");
        setToolbarVisible(true);
        setStatusbarVisible(true);
        setDetailsPanelVisible(true);
        setSidebarVisible(true);

        // Reset download list preferences
        setDownloadListSortColumn("filename");
        setDownloadListSortOrder("ascending");

        // Clear selections and filters
        setSelectedDownload(null);
        clearFilters();

        LOGGER.info("UI state reset to defaults");
    }

    /**
     * Performs cleanup when the service is no longer needed.
     */
    public void cleanup() {
        try {
            // Save current state
            saveState();

            // Clear listeners
            listeners.clear();

            // Clear runtime state
            runtimeState.clear();

            // Clear selections
            selectedDownload = null;
            selectedDownloadId = null;

            LOGGER.info("UIStateService cleanup completed");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during cleanup", e);
        }
    }
}
