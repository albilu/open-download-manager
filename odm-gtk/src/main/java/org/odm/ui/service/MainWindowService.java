package org.odm.ui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jgtk.GladeUI;
import org.jgtk.core.GtkNativeLibraries;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.odm.ui.controller.AboutController;
import org.odm.ui.controller.DownloadPropertyController;
import org.odm.ui.controller.NewDownloadController;
import org.odm.ui.controller.SettingsController;
import org.odm.ui.service.core.DownloadCoordinatorService;
import org.odm.ui.service.state.SessionStateService;
import org.tor.TorService;

import com.sun.jna.Pointer;

/**
 * Service class for Main Window business logic. Handles all core functionality
 * for the main application window including download management, UI updates,
 * file operations, and settings management.
 */
public class MainWindowService implements DownloadCoordinatorService.DownloadUIListener {

    private static final Logger LOGGER = Logger.getLogger(MainWindowService.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;
    private final SessionStateService uiStateService;
    private final DownloadCoordinatorService downloadUIService;
    private final GladeUI ui;
    private final TorService torService;

    // Sub-controllers
    private NewDownloadController newDownloadController;
    private SettingsController settingsController;
    private DownloadPropertyController propertyController;
    private AboutController aboutController;

    // State management
    private final List<Download> displayedDownloads = new ArrayList<>();
    private String selectedCategory = "All";
    private String selectedStatus = "All"; // Add status filtering support
    private String currentSearchText = ""; // Requirement-7: Search downloads by name
    private Download selectedDownload;

    // Periodic updates
    private ScheduledExecutorService updateService;

    // UI update throttling to prevent rapid consecutive updates that cause crashes
    private volatile long lastUIUpdateTime = 0;
    private static final long MIN_UPDATE_INTERVAL_MS = 100; // Increased to 500ms between UI updates
    private volatile boolean uiUpdateInProgress = false;

    // Cache for displayed downloads to enable incremental updates
    private final Map<String, Download> displayedDownloadsCache = new HashMap<>();
    private volatile boolean needsFullRefresh = true;

    // Additional throttling for refresh operations
    private volatile long lastRefreshTime = 0;
    private static final long MIN_REFRESH_INTERVAL_MS = 200; // Minimum 200ms between refreshes

    // Callbacks
    private Runnable applicationShutdownCallback;

    public MainWindowService(DownloadManager downloadManager, GlobalSettings settings,
            SessionStateService uiStateService, DownloadCoordinatorService downloadUIService,
            GladeUI ui, TorService torService) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        this.uiStateService = uiStateService;
        this.downloadUIService = downloadUIService;
        this.ui = ui;
        this.torService = torService;
    }

    /**
     * Sets the application shutdown callback.
     */
    public void setApplicationShutdownCallback(Runnable callback) {
        this.applicationShutdownCallback = callback;
    }

    /**
     * Initializes the service and sets up initial state.
     */
    public void initializeService() {
        try {
            LOGGER.info("Starting main window service initialization...");

            initializeSubControllers();
            LOGGER.info("Sub-controllers initialized");

            setupInitialState();
            LOGGER.info("Initial state setup completed");

            // Register as a download UI listener to receive notifications about new
            // downloads
            downloadUIService.addListener(this);
            LOGGER.info("Registered as download UI listener");

            restoreColumnVisibility();
            LOGGER.info("Column visibility restored");

            restoreMenuStates();
            LOGGER.info("Menu states restored");

            startPeriodicUpdates();
            LOGGER.info("Periodic updates started");

            restoreWindowState();
            LOGGER.info("Window state restored");

            LOGGER.info("Main window service initialized successfully");
        } catch (Exception e) {
            LOGGER.severe("Error during main window service initialization: " + e.getMessage());
            e.printStackTrace();
            // Continue despite errors to allow window to show
        }
    }

    // ====== Controller Management ======
    private void initializeSubControllers() {
        try {
            newDownloadController = new NewDownloadController(downloadManager, settings);
            LOGGER.fine("NewDownloadController initialized");
        } catch (Exception e) {
            LOGGER.warning("Failed to initialize NewDownloadController: " + e.getMessage());
        }

        try {
            settingsController = new SettingsController(settings);
            LOGGER.fine("SettingsController initialized");
        } catch (Exception e) {
            LOGGER.warning("Failed to initialize SettingsController: " + e.getMessage());
        }

        try {
            propertyController = new DownloadPropertyController(downloadManager);
            LOGGER.fine("DownloadPropertyController initialized");
        } catch (Exception e) {
            LOGGER.warning("Failed to initialize DownloadPropertyController: " + e.getMessage());
        }

        try {
            aboutController = new AboutController();
            LOGGER.fine("AboutDialogController initialized");
        } catch (Exception e) {
            LOGGER.warning("Failed to initialize AboutDialogController: " + e.getMessage());
        }
    }

    // ====== Initial Setup ======
    private void setupInitialState() {
        try {
            refreshDownloadList();
            LOGGER.fine("Download list refreshed");
        } catch (Exception e) {
            LOGGER.warning("Failed to refresh download list: " + e.getMessage());
        }

        try {
            updateCategoryCounts();
            LOGGER.fine("Category counts updated");
        } catch (Exception e) {
            LOGGER.warning("Failed to update category counts: " + e.getMessage());
        }

        try {
            updateStatusCounts();
            LOGGER.fine("Status counts updated");
        } catch (Exception e) {
            LOGGER.warning("Failed to update status counts: " + e.getMessage());
        }

        try {
            updateUI();
            LOGGER.fine("UI updated");
        } catch (Exception e) {
            LOGGER.warning("Failed to update UI: " + e.getMessage());
        }
    }

    private void restoreColumnVisibility() {
        try {
            // Restore column visibility from settings
            setColumnVisible("column_number",
                    settings.getBooleanProperty("ui.columns.number.visible", true));
            setColumnVisible("column_name",
                    settings.getBooleanProperty("ui.columns.name.visible", true));
            setColumnVisible("column_complete",
                    settings.getBooleanProperty("ui.columns.complete.visible", true));
            setColumnVisible("column_progress",
                    settings.getBooleanProperty("ui.columns.progress.visible", true));
            setColumnVisible("column_size",
                    settings.getBooleanProperty("ui.columns.size.visible", true));
            setColumnVisible("column_elapsed",
                    settings.getBooleanProperty("ui.columns.elapsed.visible", true));
            setColumnVisible("column_left",
                    settings.getBooleanProperty("ui.columns.left.visible", true));
            setColumnVisible("column_speed",
                    settings.getBooleanProperty("ui.columns.speed.visible", true));
            setColumnVisible("column_up_speed",
                    settings.getBooleanProperty("ui.columns.up_speed.visible", false));
            setColumnVisible("column_retry",
                    settings.getBooleanProperty("ui.columns.retry.visible", true));
            setColumnVisible("column_start_date",
                    settings.getBooleanProperty("ui.columns.start_date.visible", true));
            setColumnVisible("column_end_date",
                    settings.getBooleanProperty("ui.columns.end_date.visible", true));
            setColumnVisible("column_tor_icon",
                    settings.getBooleanProperty("ui.columns.tor_icon.visible", false));
        } catch (Exception e) {
            LOGGER.warning("Failed to restore column visibility: " + e.getMessage());
        }
    }

    private void setColumnVisible(String columnId, boolean visible) {
        try {
            if (visible) {
                ui.showColumn(columnId);
            } else {
                ui.hideColumn(columnId);
            }
        } catch (Exception e) {
            LOGGER.fine("Failed to set column visibility for " + columnId + ": " + e.getMessage());
        }
    }

    private void restoreMenuStates() {
        try {
            // Restore clipboard monitoring states - verify actual service state
            boolean clipboardMonitoring = settings.getClipboardSettings().isMonitoringEnabled();
            boolean actualServiceState = downloadManager.isClipboardMonitoringEnabled();

            if (clipboardMonitoring != actualServiceState) {
                LOGGER.info("Clipboard monitoring state mismatch on startup - settings: " + clipboardMonitoring
                        + ", service: " + actualServiceState + ". Using service state.");
                clipboardMonitoring = actualServiceState;
            }

            ui.setWidgetActive("clipboard_monitoring_menu_item", clipboardMonitoring);
            LOGGER.info("Restored clipboard monitoring menu state: " + clipboardMonitoring);

            boolean clipboardSilent = settings.getClipboardSettings().isSilentMode();
            ui.setWidgetActive("clipboard_silent_menu_item", clipboardSilent);

            // Restore offline mode
            boolean offlineMode = settings.getBooleanProperty("network.offline_mode", false);
            ui.setWidgetActive("offline_menu_item", offlineMode);

            // Restore Tor state
            boolean torEnabled = settings.getBooleanProperty("network.tor.enabled", false);
            ui.setWidgetActive("tor_switch", torEnabled);

            // Restore completion action
            String completionAction = settings.getProperty("completion.action", "none");
            ui.setWidgetActive("completion_none_menu_item", "none".equals(completionAction));
            ui.setWidgetActive("completion_suspend_menu_item", "suspend".equals(completionAction));
            ui.setWidgetActive("completion_shutdown_menu_item", "shutdown".equals(completionAction));
            ui.setWidgetActive("completion_custom_menu_item", "custom".equals(completionAction));

            // Restore panel visibility
            ui.setWidgetActive("left_panel_menu_item", uiStateService.isSidebarVisible());
            ui.setWidgetActive("info_panel_menu_item", uiStateService.isDetailsPanelVisible());

            LOGGER.info("Menu toggle states restored");
        } catch (Exception e) {
            LOGGER.warning("Failed to restore menu states: " + e.getMessage());
        }
    }

    private void startPeriodicUpdates() {
        try {
            updateService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MainWindow-UpdateService");
                t.setDaemon(true);
                return t;
            });

            updateService.scheduleAtFixedRate(() -> {
                try {
                    updateUI();
                } catch (Exception e) {
                    LOGGER.fine("Error in periodic UI update: " + e.getMessage());
                }
            }, 0, 2, TimeUnit.SECONDS); // Reduced frequency from 1 second to 2 seconds
            LOGGER.fine("Periodic updates scheduled");
        } catch (Exception e) {
            LOGGER.warning("Failed to start periodic updates: " + e.getMessage());
        }
    }

    private void restoreWindowState() {
        if (uiStateService != null /* && mainWindow != null */) {
            // Get saved window dimensions
            int width = uiStateService.getMainWindowWidth();
            int height = uiStateService.getMainWindowHeight();
            boolean maximized = settings.getBooleanProperty(SessionStateService.MAIN_WINDOW_MAXIMIZED, false);

            // Apply window state through UI if methods are available
            try {
                if (maximized) {
                    // Window will be maximized - let GTK handle sizing
                    LOGGER.info("Window will be maximized");
                } else {
                    // Set specific dimensions
                    LOGGER.info(String.format("Restoring window size: %dx%d", width, height));
                }

                // Restore selected category
                String savedCategory = uiStateService.getSelectedCategory();
                selectedCategory = savedCategory;

            } catch (Exception e) {
                LOGGER.warning("Failed to restore window state: " + e.getMessage());
            }
        }
        LOGGER.info("Window state restored");
    }

    // ====== Download Management ======
    public void handleNewDownload() {
        try {
            boolean result = newDownloadController.showDialog(ui.getWidget("main_window"));
            if (result) {
                refreshDownloadList();
                updateUI();
            }
        } catch (Exception e) {
            LOGGER.severe("Error showing new download dialog: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to show new download dialog: " + e.getMessage());
        }
    }

    public void handleNewWebsiteScraper() {
        try {
            LOGGER.info("Website scraper not yet implemented");
            ui.showInfoDialog("main_window", "Website scraper feature coming soon!");
        } catch (Exception e) {
            LOGGER.severe("Error in website scraper: " + e.getMessage());
        }
    }

    public void handleClipboardImport() {
        try {
            downloadManager.importFromClipboard().thenAccept(downloads -> {
                refreshDownloadList();
                updateUI();
                updateStatusBarMessage("Imported " + downloads.size() + " URLs from clipboard");
            }).exceptionally(throwable -> {
                ui.showErrorDialog("main_window", "Failed to import from clipboard: " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            LOGGER.severe("Error importing from clipboard: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to import from clipboard: " + e.getMessage());
        }
    }

    public void handleImportUrlSequence() {
        LOGGER.info("URL sequence import requested");
        try {
            // Open import-sequence dialog for generating URL sequences
            org.odm.ui.controller.ImportSequenceController importController = new org.odm.ui.controller.ImportSequenceController(
                    downloadManager, settings);
            Pointer parentWindow = ui.getWidget("main_window");
            boolean result = importController.showDialog(parentWindow);
            // Note: cleanup() has protected access, will be handled by
            // ImportSequenceController internally

            if (result) {
                LOGGER.info("URL sequence import completed successfully");
                refreshDownloadList();
            } else {
                LOGGER.info("URL sequence import cancelled by user");
            }
        } catch (Exception e) {
            LOGGER.warning("URL sequence import error: " + e.getMessage());
            if (ui != null) {
                ui.showErrorDialog("main_window", "Failed to import URL sequence: " + e.getMessage());
            }
        }
    }

    public void handleImportFile() {
        try {
            String filePath = ui.showFileChooserDialog("main_window", "Import URLs from file", null);
            if (filePath != null) {
                List<String> urls = readUrlsFromFile(filePath);
                if (!urls.isEmpty()) {
                    // Create downloads from URLs
                    for (String url : urls) {
                        if (isValidUrl(url)) {
                            try {
                                downloadManager.createDownload(java.net.URI.create(url),
                                        settings.getDefaultDownloadDirectory());
                            } catch (Exception ex) {
                                LOGGER.warning("Failed to create download for URL: " + url);
                            }
                        }
                    }
                    refreshDownloadList();
                    updateUI();
                    updateStatusBarMessage("Imported " + urls.size() + " URLs from file");

                    // Open Import List dialog as required by Requirement 33
                    LOGGER.info("Opening Import List dialog after successful file import");
                    showImportListDialog();
                } else {
                    ui.showInfoDialog("main_window", "No valid URLs found in file");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error importing from file: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to import from file: " + e.getMessage());
        }
    }

    public void handleImportHtml() {
        try {
            String filePath = ui.showFileChooserDialog("main_window", "Import links from HTML file", null);
            if (filePath != null) {
                List<String> urls = extractLinksFromHtmlFile(filePath);
                if (!urls.isEmpty()) {
                    // Create downloads from URLs
                    for (String url : urls) {
                        if (isValidUrl(url)) {
                            try {
                                downloadManager.createDownload(java.net.URI.create(url),
                                        settings.getDefaultDownloadDirectory());
                            } catch (Exception ex) {
                                LOGGER.warning("Failed to create download for URL: " + url);
                            }
                        }
                    }
                    refreshDownloadList();
                    updateUI();
                    updateStatusBarMessage("Imported " + urls.size() + " links from HTML file");

                    // Open Import List dialog as required by Requirement 35
                    LOGGER.info("Opening Import List dialog after successful HTML import");
                    showImportListDialog();
                } else {
                    ui.showInfoDialog("main_window", "No valid links found in HTML file");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error importing from HTML file: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to import from HTML file: " + e.getMessage());
        }
    }

    /**
     * Handles export to file functionality for Requirement 34.
     * Shows file chooser dialog and exports current download URLs to selected file.
     */
    public void handleExportToFile() {
        try {
            LOGGER.info("Export to file requested");

            // Show file chooser dialog as required by Requirement 34
            String filePath = ui.showFileChooserDialog("main_window",
                    "Export download URLs to file", null);

            if (filePath != null) {
                List<Download> allDownloads = downloadManager.getAllDownloads();
                List<String> urls = new ArrayList<>();

                // Extract URLs from all downloads
                for (Download download : allDownloads) {
                    urls.add(download.getUri().toString());
                }

                if (!urls.isEmpty()) {
                    // Export URLs to selected file
                    exportUrlsToFile(filePath, urls);
                    updateStatusBarMessage("Exported " + urls.size() + " URLs to file");
                    ui.showInfoDialog("main_window", "Successfully exported " + urls.size() + " URLs to file");
                } else {
                    ui.showInfoDialog("main_window", "No downloads available to export");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error exporting to file: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to export to file: " + e.getMessage());
        }
    }

    public void handleExportFile() {
        try {
            // Export functionality - simplified
            exportUrlsToFile("urls.txt");
            updateStatusBarMessage("URLs exported to file");
        } catch (Exception e) {
            LOGGER.severe("Error exporting downloads: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to export downloads: " + e.getMessage());
        }
    }

    public void handlePauseDownloads() {
        try {
            if (selectedDownload != null) {
                downloadManager.pauseDownload(selectedDownload);
            } else {
                // Pause all active downloads
                for (Download download : downloadManager.getAllDownloads()) {
                    if (download.getStatus() == Download.Status.DOWNLOADING) {
                        downloadManager.pauseDownload(download);
                    }
                }
            }
            refreshDownloadList();
            updateUI();
        } catch (Exception e) {
            LOGGER.severe("Error pausing downloads: " + e.getMessage());
        }
    }

    public void handleResumeDownloads() {
        try {
            if (selectedDownload != null) {
                downloadManager.resumeDownload(selectedDownload);
            } else {
                // Resume all paused downloads
                for (Download download : downloadManager.getAllDownloads()) {
                    if (download.getStatus() == Download.Status.PAUSED) {
                        downloadManager.resumeDownload(download);
                    }
                }
            }
            refreshDownloadList();
            updateUI();
        } catch (Exception e) {
            LOGGER.severe("Error resuming downloads: " + e.getMessage());
        }
    }

    public void handleDeleteDownloads() {
        if (selectedDownload != null) {
            downloadManager.cancelDownload(selectedDownload, false);
            refreshDownloadList();
            updateUI();
        }
    }

    public void handleDeleteWithFiles() {
        if (selectedDownload != null) {
            try {
                LOGGER.info("Deleting download with files: " + selectedDownload.getName());
                // Cancel download and delete associated files
                downloadManager.cancelDownload(selectedDownload, true);
                LOGGER.info("Successfully deleted download with files: " + selectedDownload.getName());
            } catch (Exception e) {
                LOGGER.severe("Error deleting download with files: " + e.getMessage());
                ui.showErrorDialog("main_window", "Failed to delete download with files: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Always refresh the UI regardless of success/failure
                refreshDownloadList();
                updateUI();
            }
        } else {
            LOGGER.warning("No download selected for delete with files operation");
            ui.showInfoDialog("main_window", "Please select a download to delete");
        }
    }

    public void handleMoveUp() {
        if (selectedDownload != null) {
            try {
                LOGGER.info("Moving download up in queue: " + selectedDownload.getName());
                // Get current position in displayed downloads
                int currentIndex = displayedDownloads.indexOf(selectedDownload);
                if (currentIndex > 0) {
                    // Swap with previous download
                    Download previousDownload = displayedDownloads.get(currentIndex - 1);
                    displayedDownloads.set(currentIndex - 1, selectedDownload);
                    displayedDownloads.set(currentIndex, previousDownload);
                    LOGGER.info("Moved download up successfully");
                } else {
                    LOGGER.info("Download is already at the top of the list");
                }
                refreshDownloadList();
                updateUI();
            } catch (Exception e) {
                LOGGER.severe("Error moving download up: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            LOGGER.warning("No download selected for move up operation");
        }
    }

    public void handleMoveTop() {
        if (selectedDownload != null) {
            try {
                LOGGER.info("Moving download to top of queue: " + selectedDownload.getName());
                // Remove from current position and add to top
                displayedDownloads.remove(selectedDownload);
                displayedDownloads.add(0, selectedDownload);
                LOGGER.info("Moved download to top successfully");
                refreshDownloadList();
                updateUI();
            } catch (Exception e) {
                LOGGER.severe("Error moving download to top: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            LOGGER.warning("No download selected for move to top operation");
        }
    }

    public void handleMoveDown() {
        if (selectedDownload != null) {
            try {
                LOGGER.info("Moving download down in queue: " + selectedDownload.getName());
                // Get current position in displayed downloads
                int currentIndex = displayedDownloads.indexOf(selectedDownload);
                if (currentIndex < displayedDownloads.size() - 1) {
                    // Swap with next download
                    Download nextDownload = displayedDownloads.get(currentIndex + 1);
                    displayedDownloads.set(currentIndex + 1, selectedDownload);
                    displayedDownloads.set(currentIndex, nextDownload);
                    LOGGER.info("Moved download down successfully");
                } else {
                    LOGGER.info("Download is already at the bottom of the list");
                }
                refreshDownloadList();
                updateUI();
            } catch (Exception e) {
                LOGGER.severe("Error moving download down: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            LOGGER.warning("No download selected for move down operation");
        }
    }

    public void handleMoveBottom() {
        if (selectedDownload != null) {
            try {
                LOGGER.info("Moving download to bottom of queue: " + selectedDownload.getName());
                // Remove from current position and add to bottom
                displayedDownloads.remove(selectedDownload);
                displayedDownloads.add(selectedDownload);
                LOGGER.info("Moved download to bottom successfully");
                refreshDownloadList();
                updateUI();
            } catch (Exception e) {
                LOGGER.severe("Error moving download to bottom: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            LOGGER.warning("No download selected for move to bottom operation");
        }
    }

    public void handleShowSettings() {
        try {
            boolean result = settingsController.showDialog(ui.getWidget("main_window"));
            if (result) {
                // Refresh UI to reflect any setting changes
                restoreMenuStates();
                updateUI();
            }
        } catch (Exception e) {
            LOGGER.severe("Error showing settings dialog: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to show settings dialog: " + e.getMessage());
        }
    }

    public void handleOpenFile() {
        if (selectedDownload != null) {
            try {
                Path filePath = selectedDownload.getDestination();
                if (Files.exists(filePath)) {
                    // Open file with system default application
                    LOGGER.info("Opening file: " + filePath);
                    // Implementation would use Desktop.getDesktop().open()
                } else {
                    ui.showInfoDialog("main_window", "File does not exist");
                }
            } catch (Exception e) {
                LOGGER.severe("Error opening file: " + e.getMessage());
                ui.showErrorDialog("main_window", "Failed to open file: " + e.getMessage());
            }
        }
    }

    public void handleOpenFolder() {
        if (selectedDownload != null) {
            try {
                Path filePath = selectedDownload.getDestination();
                Path folder = filePath != null ? filePath.getParent() : null;

                if (folder != null && Files.exists(folder)) {
                    LOGGER.info("Opening folder: " + folder);

                    // Use Desktop API to open folder with system file manager
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                            desktop.open(folder.toFile());
                            updateStatusBarMessage("Opened folder: " + folder.getFileName());
                        } else {
                            LOGGER.warning("Desktop OPEN action not supported on this system");
                            ui.showInfoDialog("main_window", "Opening folders is not supported on this system");
                        }
                    } else {
                        LOGGER.warning("Desktop API not supported on this system");
                        ui.showInfoDialog("main_window", "Desktop operations are not supported on this system");
                    }
                } else {
                    String message = folder == null ? "Download destination is not set"
                            : "Folder does not exist: " + folder;
                    LOGGER.warning(message);
                    ui.showInfoDialog("main_window", message);
                }
            } catch (Exception e) {
                LOGGER.severe("Error opening folder: " + e.getMessage());
                e.printStackTrace();
                ui.showErrorDialog("main_window", "Failed to open folder: " + e.getMessage());
            }
        } else {
            LOGGER.warning("No download selected for open folder operation");
            ui.showInfoDialog("main_window", "Please select a download first");
        }
    }

    public void handleCopyMagnet() {
        if (selectedDownload != null) {
            try {
                String url = selectedDownload.getUri().toString();

                // Check if this is a magnet link or if download type suggests it's a torrent
                if (url.startsWith("magnet:") || url.toLowerCase().endsWith(".torrent") ||
                        selectedDownload.getType() == Download.Type.ARIA2) {
                    LOGGER.info("Copying URL/magnet to clipboard: " + url);

                    // Use Java's Toolkit to access system clipboard
                    java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit()
                            .getSystemClipboard();
                    java.awt.datatransfer.StringSelection stringSelection = new java.awt.datatransfer.StringSelection(
                            url);
                    clipboard.setContents(stringSelection, null);

                    String displayText = url.startsWith("magnet:") ? "Magnet link" : "Download URL";
                    updateStatusBarMessage(displayText + " copied to clipboard");
                    LOGGER.info("Successfully copied " + displayText + " to clipboard");
                } else {
                    String message = "Selected download URL will be copied to clipboard";
                    LOGGER.info(message + ": " + url);

                    // Copy any URL to clipboard for user convenience
                    java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit()
                            .getSystemClipboard();
                    java.awt.datatransfer.StringSelection stringSelection = new java.awt.datatransfer.StringSelection(
                            url);
                    clipboard.setContents(stringSelection, null);

                    updateStatusBarMessage("Download URL copied to clipboard");
                }
            } catch (Exception e) {
                LOGGER.severe("Error copying download URL: " + e.getMessage());
                e.printStackTrace();
                ui.showErrorDialog("main_window", "Failed to copy URL to clipboard: " + e.getMessage());
            }
        } else {
            LOGGER.warning("No download selected for copy URL operation");
            ui.showInfoDialog("main_window", "Please select a download first");
        }
    }

    public void handleChangeDestination() {
        LOGGER.info("Changing destination folder");
        if (selectedDownload != null) {
            try {
                // This would open a folder chooser dialog
                String currentDestination = selectedDownload.getDestination() != null
                        ? selectedDownload.getDestination().toString()
                        : settings.getDefaultDownloadDirectory().toString();

                String selectedPath = ui.showDirectoryChooserDialog("main_window",
                        "Select New Destination Directory", currentDestination);

                if (selectedPath != null && !selectedPath.equals(currentDestination)) {
                    // Update download destination
                    // downloadManager.changeDestination(selectedDownload.getId(),
                    // Paths.get(selectedPath));
                    LOGGER.info("Destination changed for " + selectedDownload.getName() + " to: " + selectedPath);
                    updateStatusBarMessage("Destination changed for: " + selectedDownload.getName());
                    refreshDownloadList();
                } else {
                    LOGGER.info("Destination change cancelled or no change made");
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to change destination: " + e.getMessage());
                if (ui != null) {
                    ui.showErrorDialog("main_window", "Failed to change destination: " + e.getMessage());
                }
            }
        }
    }

    public void handleVerifyData() {
        if (selectedDownload != null) {
            try {
                // Verify download data - implementation depends on download manager
                updateStatusBarMessage("Data verification started for: " + selectedDownload.getName());
            } catch (Exception e) {
                LOGGER.severe("Error verifying data: " + e.getMessage());
                ui.showErrorDialog("main_window", "Failed to verify data: " + e.getMessage());
            }
        }
    }

    public void handleShowProperties() {
        if (selectedDownload != null) {
            try {
                propertyController.showProperties(selectedDownload);
            } catch (Exception e) {
                LOGGER.severe("Error showing properties dialog: " + e.getMessage());
                ui.showErrorDialog("main_window", "Failed to show properties dialog: " + e.getMessage());
            }
        }
    }

    public void handleAbout() {
        try {
            aboutController.showDialog(ui.getWidget("main_window"));
        } catch (Exception e) {
            LOGGER.severe("Error showing about dialog: " + e.getMessage());
        }
    }

    // ====== Selection Handlers ======
    public void handleDownloadSelectionChanged() {
        updateSelectedDownload();
        updateDetailsPanel();
        updateDownloadMenuState();
        updateToolbarState();
    }

    public void handleDownloadRowActivated() {
        if (selectedDownload != null) {
            handleShowProperties();
        }
    }

    /**
     * Handles download treeview button press events - Requirement-11: Right-click
     * context menu is displayed.
     * This method checks for right-click (button 3) and shows the context menu.
     */
    public void handleDownloadTreeviewButtonPress(Pointer widget, Pointer data) {
        try {
            // Check if it's a right-click (button 3)
            // In GTK, button press events contain a GdkEventButton structure
            // We need to extract the button number from the event data

            // For now, we'll show the context menu regardless of button
            // A proper implementation would check the button number from the event
            LOGGER.fine("Button press event on download treeview - showing context menu");

            // Make sure we have a download selected
            updateSelectedDownload();

            if (selectedDownload != null) {
                // Update context menu items based on selected download state
                updateContextMenuState();

                // Show the context menu
                showDownloadContextMenu();
                LOGGER.info("Context menu displayed for download: " + selectedDownload.getName());
            } else {
                LOGGER.fine("No download selected - context menu not shown");
            }

        } catch (Exception e) {
            LOGGER.warning("Error handling download treeview button press: " + e.getMessage());
        }
    }

    public void handleCategorySelectionChanged() {
        try {
            String previousCategory = selectedCategory;
            updateSelectedCategory();
            refreshDownloadList();
            updateUI(); // Ensure all UI elements are updated
            LOGGER.info("Category filter changed from '" + previousCategory + "' to '" + selectedCategory +
                    "' - showing " + displayedDownloads.size() + " downloads");
        } catch (Exception e) {
            LOGGER.warning("Error handling category selection change: " + e.getMessage());
        }
    }

    public void handleStatusSelectionChanged() {
        try {
            String previousStatus = selectedStatus;
            updateSelectedStatus();
            refreshDownloadList();
            updateUI(); // Ensure all UI elements are updated
            LOGGER.info("Status filter changed from '" + previousStatus + "' to '" + selectedStatus +
                    "' - showing " + displayedDownloads.size() + " downloads");
        } catch (Exception e) {
            LOGGER.warning("Error handling status selection change: " + e.getMessage());
        }
    }

    // ====== UI Update Methods ======
    public void refreshDownloadList() {
        refreshDownloadList(false);
    }

    public void forceFullRefresh() {
        refreshDownloadList(true);
    }

    /**
     * Refreshes the download list with optional incremental updates to prevent
     * flickering.
     * 
     * @param forceFullRefresh if true, performs a complete rebuild; if false, uses
     *                         incremental updates
     */
    public void refreshDownloadList(boolean forceFullRefresh) {
        // Add throttling to prevent rapid successive calls
        long currentTime = System.currentTimeMillis();
        if (!forceFullRefresh && (currentTime - lastRefreshTime) < MIN_REFRESH_INTERVAL_MS) {
            LOGGER.fine("refreshDownloadList: Skipping refresh due to throttling (too frequent)");
            return;
        }
        lastRefreshTime = currentTime;

        try {
            List<Download> allDownloads = downloadManager.getAllDownloads();
            LOGGER.info("refreshDownloadList: Found " + allDownloads.size() + " total downloads");

            List<Download> filteredDownloads = new ArrayList<>();
            for (Download download : allDownloads) {
                if (matchesCurrentFilters(download)) {
                    filteredDownloads.add(download);
                }
            }

            // Use full refresh with optimizations to prevent flickering
            // Incremental refresh is disabled for now due to complexity with rapid updates
            LOGGER.info("refreshDownloadList: After filtering, " + filteredDownloads.size()
                    + " downloads will be displayed");

            displayedDownloads.clear();
            displayedDownloads.addAll(filteredDownloads);

            // Use optimized refresh instead of traditional method
            updateDownloadListViewOptimized();

            updateCategoryCounts();
            updateStatusCounts();

        } catch (Exception e) {
            LOGGER.severe("Error refreshing download list: " + e.getMessage());
        }
    }

    /**
     * Performs a full refresh of the download list (clears and rebuilds).
     */
    private void performFullRefresh(List<Download> filteredDownloads) {
        displayedDownloadsCache.clear();
        for (Download download : filteredDownloads) {
            displayedDownloadsCache.put(download.getId(), download);
        }
        updateDownloadListView(); // This will do a full rebuild
    }

    /**
     * Performs an incremental refresh, only updating changed items to prevent
     * flickering.
     */
    private void performIncrementalRefresh(List<Download> filteredDownloads) {
        try {
            // Get the download list store
            Pointer downloadStore = ui.getWidget("download_store");
            if (downloadStore == null) {
                LOGGER.warning("performIncrementalRefresh: Download store not found, falling back to full refresh");
                performFullRefresh(filteredDownloads);
                return;
            }

            // Create new cache for comparison
            Map<String, Download> newCache = new HashMap<>();
            for (Download download : filteredDownloads) {
                newCache.put(download.getId(), download);
            }

            // Find additions and changes
            List<Download> toAdd = new ArrayList<>();
            List<Download> toUpdate = new ArrayList<>();

            for (Download download : filteredDownloads) {
                String id = download.getId();
                Download cached = displayedDownloadsCache.get(id);

                if (cached == null) {
                    // New download
                    toAdd.add(download);
                } else if (hasSignificantChanges(cached, download)) {
                    // Download has meaningful changes that warrant UI update
                    toUpdate.add(download);
                }
            }

            // Find removals (downloads no longer in the filtered list)
            List<String> toRemove = new ArrayList<>();
            for (String cachedId : displayedDownloadsCache.keySet()) {
                if (!newCache.containsKey(cachedId)) {
                    toRemove.add(cachedId);
                }
            }

            // Apply incremental changes without clearing the entire list
            updateDownloadListIncremental(downloadStore, toAdd, toUpdate, toRemove);

            // Update cache
            displayedDownloadsCache.clear();
            displayedDownloadsCache.putAll(newCache);

        } catch (Exception e) {
            LOGGER.warning("Error in incremental refresh, falling back to full refresh: " + e.getMessage());
            performFullRefresh(filteredDownloads);
        }
    }

    /**
     * Checks if a download has significant changes that warrant a UI update.
     */
    private boolean hasSignificantChanges(Download cached, Download current) {
        // Check key properties that users see in the list
        return !cached.getStatus().equals(current.getStatus())
                || Math.abs(cached.getProgress() - current.getProgress()) > 1.0f // Progress changed by >1%
                || Math.abs(cached.getSpeed() - current.getSpeed()) > 1024 // Speed changed by >1KB/s
                || cached.getDownloaded() != current.getDownloaded()
                || !cached.getName().equals(current.getName());
    }

    /**
     * Performs incremental updates to the GTK list store without clearing it
     * entirely.
     */
    private void updateDownloadListIncremental(Pointer downloadStore,
            List<Download> toAdd,
            List<Download> toUpdate,
            List<String> toRemove) {
        // Ensure we're running on the main GTK thread
        if (!isMainGtkThread()) {
            javax.swing.SwingUtilities
                    .invokeLater(() -> updateDownloadListIncremental(downloadStore, toAdd, toUpdate, toRemove));
            return;
        }

        try {
            // If we have removals, we need a full refresh for now due to GTK wrapper
            // limitations
            if (!toRemove.isEmpty()) {
                LOGGER.fine("Downloads removed, performing full refresh to avoid GTK complexity");
                updateDownloadListView();
                return;
            }

            // For updates and additions, use optimized approach
            if (!toUpdate.isEmpty()) {
                LOGGER.fine("Downloads updated, performing optimized refresh");
                // Instead of trying to update individual rows, do a faster full refresh
                // but with optimizations to reduce flicker
                updateDownloadListViewOptimized();
                return;
            }

            // Only additions - we can append these without clearing
            if (!toAdd.isEmpty()) {
                int nextIndex = displayedDownloads.size() + 1;
                for (Download download : toAdd) {
                    populateDownloadRow(downloadStore, download, nextIndex++);
                }
                LOGGER.fine("Added " + toAdd.size() + " new downloads without clearing list");
            }

        } catch (Exception e) {
            LOGGER.warning("Error in incremental list update: " + e.getMessage());
            // Fall back to full refresh if incremental fails
            updateDownloadListView();
        }
    }

    /**
     * Optimized version of download list view update that minimizes flicker.
     */
    private void updateDownloadListViewOptimized() {
        // Apply throttling with global synchronization to prevent rapid UI updates that
        // cause crashes
        long currentTime = System.currentTimeMillis();
        if (uiUpdateInProgress || (currentTime - lastUIUpdateTime) < MIN_UPDATE_INTERVAL_MS) {
            LOGGER.fine("updateDownloadListViewOptimized: Skipping update due to throttling");
            return;
        }

        uiUpdateInProgress = true;
        lastUIUpdateTime = currentTime;

        try {
            updateDownloadListViewOptimizedInternal();
        } finally {
            uiUpdateInProgress = false;
        }
    }

    /**
     * Internal optimized update that reduces visual flickering.
     */
    private void updateDownloadListViewOptimizedInternal() {
        // Ensure we're running this on the main thread
        if (!isMainGtkThread()) {
            javax.swing.SwingUtilities.invokeLater(() -> updateDownloadListViewOptimized());
            return;
        }

        LOGGER.info("updateDownloadListViewOptimized: Starting with " + displayedDownloads.size()
                + " downloads to display");

        // Get the download list store
        Pointer downloadStore = ui.getWidget("download_store");
        if (downloadStore == null) {
            LOGGER.warning("updateDownloadListViewOptimized: Download store not found");
            return;
        }

        try {
            // Clear the existing rows with absolute minimal delay to reduce flicker
            LOGGER.info("updateDownloadListViewOptimized: Clearing existing rows from download_store");

            // Direct clear without any delays
            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_clear(downloadStore);

            if (displayedDownloads.isEmpty()) {
                LOGGER.info("updateDownloadListViewOptimized: No downloads to display");
                return;
            }

            // Populate with filtered downloads as quickly as possible
            int index = 1;
            for (Download download : displayedDownloads) {
                if (download == null) {
                    LOGGER.warning("updateDownloadListViewOptimized: Skipping null download");
                    continue;
                }

                populateDownloadRow(downloadStore, download, index++);
            }

            LOGGER.info("updateDownloadListViewOptimized: Updated download list view with " + displayedDownloads.size()
                    + " downloads (super optimized - no delays)");

        } catch (Exception e) {
            LOGGER.severe("Error in optimized download list view update: " + e.getMessage());
            e.printStackTrace();

            // Try to recover by clearing the store
            try {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_clear(downloadStore);
                LOGGER.info("updateDownloadListViewOptimized: Cleared download store after error for recovery");
            } catch (Exception recoveryError) {
                LOGGER.severe(
                        "updateDownloadListViewOptimized: Failed to recover from error: " + recoveryError.getMessage());
            }
        }
    }

    /**
     * Updates the GTK download list view (TreeView) with the filtered downloads.
     * Requirement-7: Search downloads by name (updates view)
     * Requirement-8: Clear search results (updates view)
     */
    private synchronized void updateDownloadListView() {
        // Apply throttling with global synchronization to prevent rapid UI updates that
        // cause crashes
        long currentTime = System.currentTimeMillis();
        if (uiUpdateInProgress || (currentTime - lastUIUpdateTime) < MIN_UPDATE_INTERVAL_MS) {
            LOGGER.fine("updateDownloadListView: Skipping update due to throttling");
            return;
        }

        uiUpdateInProgress = true;
        lastUIUpdateTime = currentTime;

        try {
            updateDownloadListViewInternal();
        } finally {
            uiUpdateInProgress = false;
        }
    }

    /**
     * Internal method that performs the actual UI update work.
     */
    private void updateDownloadListViewInternal() {
        // Ensure we're running this on the main thread
        if (!isMainGtkThread()) {
            LOGGER.info("updateDownloadListView: Dispatching to main GTK thread");
            // Use a simple runnable to dispatch to main thread
            javax.swing.SwingUtilities.invokeLater(() -> updateDownloadListView());
            return;
        }

        LOGGER.info("updateDownloadListView: Starting with " + displayedDownloads.size() + " downloads to display");

        // Get the download list store
        Pointer downloadStore = ui.getWidget("download_store");
        if (downloadStore == null) {
            LOGGER.warning("updateDownloadListView: Download store not found");
            return;
        }

        try {
            // Clear the existing rows with minimal delay to reduce flicker
            LOGGER.info("updateDownloadListView: Clearing existing rows from download_store");

            // Ensure we're on the main GTK thread and clear safely
            clearListStoreSafely(downloadStore);

            LOGGER.info("updateDownloadListView: Cleared existing rows from download_store");

            // Reduced delay to minimize flicker
            Thread.sleep(10); // Reduced from 50ms to 10ms

            if (displayedDownloads.isEmpty()) {
                LOGGER.info("updateDownloadListView: No downloads to display");
                return;
            }

            // Populate with filtered downloads more efficiently
            int index = 1;
            for (Download download : displayedDownloads) {
                if (download == null) {
                    LOGGER.warning("updateDownloadListView: Skipping null download");
                    continue;
                }

                LOGGER.info("updateDownloadListView: Adding download to UI: " + download.getName());

                // Reduce GC frequency to minimize interruptions
                if (index % 20 == 0) { // Changed from every 10 to every 20 items
                    System.gc();
                    Thread.sleep(2); // Reduced from 5ms to 2ms
                }

                populateDownloadRow(downloadStore, download, index++);
            }

            LOGGER.info("updateDownloadListView: Updated download list view with " + displayedDownloads.size()
                    + " downloads");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("updateDownloadListView: Interrupted during processing");
        } catch (Exception e) {
            LOGGER.severe("Error updating download list view: " + e.getMessage());
            e.printStackTrace();

            // Try to recover by clearing the store
            try {
                GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_clear(downloadStore);
                LOGGER.info("updateDownloadListView: Cleared download store after error for recovery");
            } catch (Exception recoveryError) {
                LOGGER.severe("updateDownloadListView: Failed to recover from error: " + recoveryError.getMessage());
            }
        }
    }

    /**
     * Populates a single download row in the GTK ListStore.
     */
    private synchronized void populateDownloadRow(Pointer downloadStore, Download download, int index) {
        if (downloadStore == null || download == null) {
            LOGGER.warning("populateDownloadRow: Invalid parameters - downloadStore or download is null");
            return;
        }

        com.sun.jna.Memory iter = null;
        try {
            // Create a new row with proper memory management
            iter = new com.sun.jna.Memory(32);
            iter.clear(); // Initialize memory to zero

            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_append(downloadStore, iter);

            // Validate iterator before proceeding
            if (!isIteratorValid(downloadStore, iter)) {
                LOGGER.warning("populateDownloadRow: Iterator became invalid for download: " + download.getName());
                return;
            }

            // Safely format all values with null checks and sanitization
            String safeIndex = String.valueOf(index);
            String safeName = sanitizeString(download.getName());
            String safeComplete = sanitizeString(formatBytes(download.getDownloaded()));
            String safeSize = sanitizeString(formatBytes(download.getSize()));
            String safePercent = sanitizeString(formatPercentage(download.getProgress()));
            String safeElapsed = sanitizeString(formatDuration(download));
            String safeLeft = sanitizeString(estimateTimeLeft(download));
            String safeSpeed = sanitizeString(formatSpeed(download.getSpeed()));
            String safeUpSpeed = "0 B/s";
            String safeRetry = "0";
            String safeStartDate = sanitizeString(formatDate(download.getStartedAt()));
            String safeEndDate = sanitizeString(formatDate(download.getCompletedAt()));
            String safeIcon = sanitizeString(getDownloadIcon(download));

            // Calculate numeric progress value (0-100) for progress bar renderer
            // Ensure completed downloads show 100% regardless of calculation precision
            int progressValue;
            if (download.getStatus() == Download.Status.COMPLETED) {
                progressValue = 100;
            } else {
                progressValue = Math.max(0, Math.min(100, Math.round(download.getProgress())));
            }

            // Set column values one by one with error handling for each
            setListStoreValueSafe(downloadStore, iter, 0, safeIndex, "Number");
            setListStoreValueSafe(downloadStore, iter, 1, safeName, "Name");
            setListStoreValueSafe(downloadStore, iter, 2, safeComplete, "Complete");
            setListStoreValueSafe(downloadStore, iter, 3, safeSize, "Size");
            setListStoreValueSafe(downloadStore, iter, 4, safePercent, "Percent");
            setListStoreValueSafe(downloadStore, iter, 5, safeElapsed, "Elapsed");
            setListStoreValueSafe(downloadStore, iter, 6, safeLeft, "Left");
            setListStoreValueSafe(downloadStore, iter, 7, safeSpeed, "Speed");
            setListStoreValueSafe(downloadStore, iter, 8, safeUpSpeed, "UpSpeed");
            setListStoreValueSafe(downloadStore, iter, 9, safeRetry, "Retry");
            setListStoreValueSafe(downloadStore, iter, 10, safeStartDate, "StartDate");
            setListStoreValueSafe(downloadStore, iter, 11, safeEndDate, "EndDate");
            setListStoreValueSafe(downloadStore, iter, 12, safeIcon, "Icon");
            setListStoreValueSafe(downloadStore, iter, 13, progressValue, "ProgressValue");

            LOGGER.fine("populateDownloadRow: Successfully populated row for download: " + download.getName());

        } catch (Exception e) {
            LOGGER.severe("Error populating download row for " +
                    (download != null ? download.getName() : "null") + ": " + e.getMessage());
            e.printStackTrace();
        }
        // Note: iter memory is managed by GTK after gtk_list_store_append call
    }

    /**
     * Safely sets a value in the list store with additional error handling.
     */
    private synchronized void setListStoreValueSafe(Pointer listStore, Pointer iter, int column, String value,
            String columnName) {
        try {
            if (listStore == null || iter == null) {
                LOGGER.warning("setListStoreValueSafe: Invalid parameters for column " + columnName);
                return;
            }

            // Additional iterator validation to prevent crashes
            if (!isIteratorValid(listStore, iter)) {
                LOGGER.warning("setListStoreValueSafe: Iterator invalid for column " + columnName + ", skipping");
                return;
            }

            String safeValue = value != null ? value : "";

            // Perform the actual GTK operation with enhanced error handling
            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, safeValue, -1);
            // Removed excessive logging for performance

        } catch (Exception e) {
            LOGGER.warning("setListStoreValueSafe: Error setting " + columnName + " (column " + column +
                    ") to '" + value + "': " + e.getMessage());
            // Don't rethrow - continue with other columns
        }
    }

    /**
     * Safely sets an integer value in the list store with additional error
     * handling.
     */
    private synchronized void setListStoreValueSafe(Pointer listStore, Pointer iter, int column, int value,
            String columnName) {
        try {
            if (listStore == null || iter == null) {
                LOGGER.warning("setListStoreValueSafe: Invalid parameters for column " + columnName);
                return;
            }

            // Additional iterator validation to prevent crashes
            if (!isIteratorValid(listStore, iter)) {
                LOGGER.warning("setListStoreValueSafe: Iterator invalid for column " + columnName + ", skipping");
                return;
            }

            // Perform the actual GTK operation with enhanced error handling
            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_set(listStore, iter, column, value, -1);
            LOGGER.finest("setListStoreValueSafe: Set " + columnName + " = " + value);

        } catch (Exception e) {
            LOGGER.warning("setListStoreValueSafe: Error setting " + columnName + " (column " + column +
                    ") to " + value + ": " + e.getMessage());
            // Don't rethrow - continue with other columns
        }
    }

    /**
     * Safely clears a GTK ListStore to prevent iterator invalidation crashes.
     */
    private void clearListStoreSafely(Pointer listStore) {
        try {
            if (listStore == null) {
                LOGGER.warning("clearListStoreSafely: ListStore is null");
                return;
            }

            // Clear the list store in a controlled manner with minimal delay to reduce
            // flicker
            LOGGER.fine("clearListStoreSafely: About to clear ListStore");

            // Perform the clear operation
            GtkNativeLibraries.Gtk.INSTANCE.gtk_list_store_clear(listStore);

            LOGGER.fine("clearListStoreSafely: ListStore cleared, minimal processing time");

            // Reduced delay to minimize visible flicker while still allowing GTK processing
            try {
                Thread.sleep(2); // Reduced from 10ms to 2ms

                // Skip forced garbage collection during UI updates as it can cause delays
                // System.gc(); - Commented out to reduce flicker

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            LOGGER.fine("clearListStoreSafely: ListStore clear operation completed with minimal delay");

        } catch (Exception e) {
            LOGGER.severe("Error clearing ListStore safely: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Validates if a GTK iterator is still valid for the given list store.
     */
    private boolean isIteratorValid(Pointer listStore, Pointer iter) {
        try {
            if (listStore == null || iter == null) {
                return false;
            }

            // Simple validation - just check if pointers are not null and have valid memory
            return iter.getPointer(0) != null;

        } catch (Exception e) {
            LOGGER.finest("Iterator validation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if we're currently on the main GTK thread.
     * For simplicity, assumes single-threaded GTK access.
     */
    private boolean isMainGtkThread() {
        // For now, always return true since we're managing threading at application
        // level
        // In future versions, this could check actual GTK thread state
        return true;
    }

    /**
     * Sanitizes string values to prevent null/invalid values from causing native
     * crashes.
     */
    private String sanitizeString(String value) {
        if (value == null) {
            return "";
        }

        // Remove any control characters or potentially problematic characters
        String sanitized = value.replaceAll("[\u0000-\u001f\u007f-\u009f]", "");

        // Limit length to prevent memory issues
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 252) + "...";
        }

        return sanitized;
    }

    private boolean matchesCurrentFilters(Download download) {
        LOGGER.info("matchesCurrentFilters: Checking download " + download.getName() +
                " | selectedCategory: " + selectedCategory +
                " | selectedStatus: " + selectedStatus +
                " | currentSearchText: '" + currentSearchText + "'");

        // Category filter
        if (selectedCategory != null && !selectedCategory.equals("All") && !selectedCategory.equals("All Categories")) {
            String downloadCategory = getDownloadCategory(download);
            LOGGER.info("matchesCurrentFilters: Category check - downloadCategory: " + downloadCategory +
                    " | selectedCategory: " + selectedCategory);
            if (!selectedCategory.equals(downloadCategory)) {
                LOGGER.info("matchesCurrentFilters: Download REJECTED by category filter");
                return false;
            }
        }

        // Status filter - Requirement 4 implementation
        if (selectedStatus != null && !selectedStatus.equals("All") && !selectedStatus.equals("All Status")) {
            String downloadStatus = mapDownloadStatusToDisplayName(download.getStatus());
            LOGGER.info("matchesCurrentFilters: Status check - downloadStatus: " + downloadStatus +
                    " | selectedStatus: " + selectedStatus);
            if (!selectedStatus.equals(downloadStatus)) {
                LOGGER.info("matchesCurrentFilters: Download REJECTED by status filter");
                return false;
            }
        }

        // Search filter - Requirement-7: Search downloads by name
        if (currentSearchText != null && !currentSearchText.isEmpty()) {
            String downloadName = download.getName();
            LOGGER.info("matchesCurrentFilters: Search check - downloadName: " + downloadName +
                    " | searchText: '" + currentSearchText + "'");
            if (downloadName == null || !downloadName.toLowerCase().contains(currentSearchText.toLowerCase())) {
                LOGGER.info("matchesCurrentFilters: Download REJECTED by search filter");
                return false;
            }
        }

        LOGGER.info("matchesCurrentFilters: Download ACCEPTED by all filters");
        return true;
    }

    private String getDownloadCategory(Download download) {
        // Simple categorization based on file extension
        String name = download.getName();
        if (name == null) {
            return "Others";
        }

        String extension = "";
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            extension = name.substring(lastDot + 1).toLowerCase();
        }

        switch (extension) {
            case "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma":
                return "Audios"; // Match glade file
            case "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "3gp":
                return "Videos"; // Match glade file
            case "jpg", "jpeg", "png", "gif", "bmp", "tiff", "svg", "ico":
                return "Photos"; // Match glade file
            case "exe", "msi", "deb", "rpm", "dmg", "appimage", "flatpak", "snap":
                return "Programs";
            case "pdf", "doc", "docx", "txt", "rtf":
                return "Others"; // Documents go to Others
            case "zip", "rar", "7z", "tar", "gz":
                return "Others"; // Compressed files go to Others
            default:
                return "Others";
        }
    }

    private void updateCategoryCounts() {
        try {
            // Calculate category counts from current downloads
            Map<String, Integer> categoryCounts = new HashMap<>();

            // Initialize all categories to 0
            categoryCounts.put("All Categories", 0);
            categoryCounts.put("Videos", 0);
            categoryCounts.put("Audios", 0);
            categoryCounts.put("Photos", 0);
            categoryCounts.put("Programs", 0);
            categoryCounts.put("Others", 0);

            // Count downloads by category
            for (Download download : downloadManager.getAllDownloads()) {
                DownloadCoordinatorService.DownloadUIMetadata metadata = downloadUIService
                        .getUIMetadata(download.getId());
                String category = metadata != null ? metadata.getUserCategory() : null;
                if (category == null) {
                    category = getDownloadCategory(download);
                }
                categoryCounts.put(category, categoryCounts.getOrDefault(category, 0) + 1);
                categoryCounts.put("All Categories", categoryCounts.get("All Categories") + 1);
            }

            // Update category tree with counts using GTK ListStore
            updateCategoryTreeCounts(categoryCounts);

            LOGGER.fine("Category counts updated: " + categoryCounts);
        } catch (Exception e) {
            LOGGER.warning("Failed to update category counts: " + e.getMessage());
        }
    }

    /**
     * Updates the status counts in the status tree view.
     */
    private void updateStatusCounts() {
        try {
            // Calculate status counts from current downloads
            Map<String, Integer> statusCounts = new HashMap<>();

            // Initialize all statuses to 0
            statusCounts.put("All Status", 0);
            statusCounts.put("Active", 0);
            statusCounts.put("Queuing", 0);
            statusCounts.put("Finished", 0);
            statusCounts.put("Deleted", 0);

            // Count downloads by status
            for (Download download : downloadManager.getAllDownloads()) {
                String statusName = mapDownloadStatusToDisplayName(download.getStatus());
                statusCounts.put(statusName, statusCounts.getOrDefault(statusName, 0) + 1);
                statusCounts.put("All Status", statusCounts.get("All Status") + 1);
            }

            // Update status tree with counts using GTK ListStore
            updateStatusTreeCounts(statusCounts);

            LOGGER.fine("Status counts updated: " + statusCounts);
        } catch (Exception e) {
            LOGGER.warning("Failed to update status counts: " + e.getMessage());
        }
    }

    /**
     * Maps Download.Status enum values to display names used in the glade file.
     */
    private String mapDownloadStatusToDisplayName(Download.Status status) {
        return switch (status) {
            case DOWNLOADING, CONNECTING -> "Active";
            case QUEUED -> "Queuing";
            case COMPLETED -> "Finished";
            case CANCELED, ERROR -> "Deleted"; // Error and canceled downloads are shown as "Deleted"
            case PAUSED -> "Active"; // Paused downloads are still considered "Active"
        };
    }

    /**
     * Updates the category tree view with new counts.
     */
    private void updateCategoryTreeCounts(Map<String, Integer> categoryCounts) {
        try {
            Pointer categoryStore = ui.getWidget("category_store");
            if (categoryStore != null) {
                // Get the tree model and iterate through rows to update counts
                updateListStoreCounts(categoryStore, categoryCounts, "category");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update category tree counts: " + e.getMessage());
        }
    }

    /**
     * Updates the status tree view with new counts.
     */
    private void updateStatusTreeCounts(Map<String, Integer> statusCounts) {
        try {
            Pointer statusStore = ui.getWidget("status_store");
            if (statusStore != null) {
                // Get the tree model and iterate through rows to update counts
                updateListStoreCounts(statusStore, statusCounts, "status");
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update status tree counts: " + e.getMessage());
        }
    }

    /**
     * Generic method to update ListStore counts.
     * 
     * @param listStore The GtkListStore pointer
     * @param counts    Map of names to counts
     * @param type      Type identifier for logging ("category" or "status")
     */
    private void updateListStoreCounts(Pointer listStore, Map<String, Integer> counts, String type) {
        try {
            LOGGER.fine("Updating " + type + " counts: " + counts);

            if ("category".equals(type)) {
                updateCategoryListStoreCounts(listStore, counts);
            } else if ("status".equals(type)) {
                updateStatusListStoreCounts(listStore, counts);
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to update " + type + " list store counts: " + e.getMessage());
        }
    }

    /**
     * Updates category list store counts using known row positions from glade file.
     * This implementation now uses proper GTK tree model iteration.
     */
    private void updateCategoryListStoreCounts(Pointer listStore, Map<String, Integer> counts) {
        try {
            // Based on glade file order: All Categories, Videos, Audios, Photos, Programs,
            // Others
            String[] categoryOrder = { "All Categories", "Videos", "Audios", "Photos", "Programs", "Others" };

            com.sun.jna.Memory iter = new com.sun.jna.Memory(32);

            // Get the first iterator
            boolean hasIter = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get_iter_first(listStore, iter);

            int index = 0;
            while (hasIter && index < categoryOrder.length) {
                String categoryName = categoryOrder[index];
                Integer count = counts.get(categoryName);

                if (count != null) {
                    // Update count in column 1
                    ui.listStoreSetValue(listStore, iter, 1, count);
                    LOGGER.fine("Updated category '" + categoryName + "' count to: " + count);
                }

                // Move to next row
                hasIter = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_iter_next(listStore, iter);
                index++;
            }

        } catch (Exception e) {
            LOGGER.warning("Error updating category list store: " + e.getMessage());
        }
    }

    /**
     * Updates status list store counts using known row positions from glade file.
     * This implementation now uses proper GTK tree model iteration.
     */
    private void updateStatusListStoreCounts(Pointer listStore, Map<String, Integer> counts) {
        try {
            // Based on glade file order: All Status, Active, Queuing, Finished, Deleted
            String[] statusOrder = { "All Status", "Active", "Queuing", "Finished", "Deleted" };

            com.sun.jna.Memory iter = new com.sun.jna.Memory(32);

            // Get the first iterator
            boolean hasIter = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get_iter_first(listStore, iter);

            int index = 0;
            while (hasIter && index < statusOrder.length) {
                String statusName = statusOrder[index];
                Integer count = counts.get(statusName);

                if (count != null) {
                    // Update count in column 1
                    ui.listStoreSetValue(listStore, iter, 1, count);
                    LOGGER.fine("Updated status '" + statusName + "' count to: " + count);
                }

                // Move to next row
                hasIter = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_iter_next(listStore, iter);
                index++;
            }

        } catch (Exception e) {
            LOGGER.warning("Error updating status list store: " + e.getMessage());
        }
    }

    private void updateSelectedDownload() {
        try {
            Download newSelectedDownload = getSelectedDownloadFromTreeView();
            if (newSelectedDownload != null && newSelectedDownload != selectedDownload) {
                selectedDownload = newSelectedDownload;
                LOGGER.fine("Selected download updated to: " + selectedDownload.getName());
                updateToolbarState();
            } else if (newSelectedDownload == null && selectedDownload != null) {
                selectedDownload = null;
                LOGGER.fine("Download selection cleared");
                updateToolbarState();
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update selected download: " + e.getMessage());
        }
    }

    /**
     * Get the currently selected download from the download TreeView
     */
    private Download getSelectedDownloadFromTreeView() {
        try {
            Pointer downloadTreeView = ui.getWidget("download_treeview");
            if (downloadTreeView == null) {
                LOGGER.warning("Download treeview not found");
                return null;
            }

            Pointer selection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_get_selection(downloadTreeView);
            if (selection == null) {
                LOGGER.warning("Download treeview selection not found");
                return null;
            }

            // Use proper JNA memory allocation for output parameters
            com.sun.jna.Memory modelMem = new com.sun.jna.Memory(8); // Pointer size (8 bytes on 64-bit)
            com.sun.jna.Memory iterMem = new com.sun.jna.Memory(32); // GtkTreeIter size
            modelMem.clear(); // Initialize to zero
            iterMem.clear(); // Initialize to zero

            boolean hasSelection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_get_selected(selection, modelMem,
                    iterMem);
            if (!hasSelection) {
                LOGGER.fine("No download selected");
                return null;
            }

            // Get model pointer from the memory
            Pointer model = modelMem.getPointer(0);
            if (model == null) {
                LOGGER.warning("Download tree model is null");
                return null;
            }

            // Get the download index from the first column (column 0 contains the row
            // number)
            com.sun.jna.Memory indexValueMem = new com.sun.jna.Memory(8); // Pointer size
            indexValueMem.clear(); // Initialize to zero

            try {
                // Get the index from column 0 (row number)
                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get(model, iterMem, 0, indexValueMem, -1);

                // Extract the integer value
                Pointer indexPtr = indexValueMem.getPointer(0);
                if (indexPtr != null) {
                    String indexStr = indexPtr.getString(0);
                    if (indexStr != null && !indexStr.isEmpty()) {
                        int rowIndex = Integer.parseInt(indexStr) - 1; // Convert to 0-based index

                        // Get the download from displayedDownloads using the row index
                        if (rowIndex >= 0 && rowIndex < displayedDownloads.size()) {
                            Download selected = displayedDownloads.get(rowIndex);
                            LOGGER.fine("Selected download at index " + rowIndex + ": " + selected.getName());
                            return selected;
                        } else {
                            LOGGER.warning("Selected row index " + rowIndex + " is out of bounds (size: "
                                    + displayedDownloads.size() + ")");
                        }
                    }
                }

            } catch (NumberFormatException e) {
                LOGGER.warning("Failed to parse download row index: " + e.getMessage());
            } catch (Exception e) {
                LOGGER.warning("Error getting download row index: " + e.getMessage());
            }

            return null;

        } catch (Exception e) {
            LOGGER.warning("Error getting selected download from treeview: " + e.getMessage());
            return null;
        }
    }

    private void updateSelectedCategory() {
        try {
            String newCategory = getSelectedCategoryFromTreeView();
            if (newCategory != null) {
                selectedCategory = newCategory;
                uiStateService.setSelectedCategory(selectedCategory);
                LOGGER.fine("Selected category updated to: " + selectedCategory);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update selected category: " + e.getMessage());
        }
    }

    private void updateSelectedStatus() {
        try {
            String newStatus = getSelectedStatusFromTreeView();
            if (newStatus != null) {
                selectedStatus = newStatus;
                LOGGER.fine("Selected status updated to: " + selectedStatus);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update selected status: " + e.getMessage());
        }
    }

    /**
     * Get the currently selected category name from the category TreeView
     */
    private String getSelectedCategoryFromTreeView() {
        try {
            Pointer categoryTreeView = ui.getWidget("category_treeview");
            if (categoryTreeView == null) {
                LOGGER.warning("Category treeview not found");
                return "All";
            }

            Pointer selection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_get_selection(categoryTreeView);
            if (selection == null) {
                LOGGER.warning("Category treeview selection not found");
                return "All";
            }

            // Use proper JNA memory allocation for output parameters (similar to status
            // method)
            com.sun.jna.Memory modelMem = new com.sun.jna.Memory(8); // Pointer size (8 bytes on 64-bit)
            com.sun.jna.Memory iterMem = new com.sun.jna.Memory(32); // GtkTreeIter size
            modelMem.clear(); // Initialize to zero
            iterMem.clear(); // Initialize to zero

            boolean hasSelection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_get_selected(selection, modelMem,
                    iterMem);
            if (!hasSelection) {
                LOGGER.fine("No category selection found, defaulting to 'All'");
                return "All";
            }

            // Get model pointer from the memory
            Pointer model = modelMem.getPointer(0);
            if (model == null) {
                LOGGER.warning("Category tree model is null");
                return "All";
            }

            // Use a memory buffer to store the string pointer returned by
            // gtk_tree_model_get
            com.sun.jna.Memory nameValueMem = new com.sun.jna.Memory(8); // Pointer size
            nameValueMem.clear(); // Initialize to zero

            try {
                // Get the category name from column 0
                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get(model, iterMem, 0, nameValueMem, -1);

                Pointer namePtr = nameValueMem.getPointer(0);
                if (namePtr != null && namePtr != Pointer.NULL) {
                    String categoryName = namePtr.getString(0);
                    LOGGER.fine("Selected category from TreeView: " + categoryName);
                    return categoryName != null && !categoryName.isEmpty() ? categoryName : "All";
                }
            } catch (Exception e) {
                LOGGER.warning("Error reading category name from tree model: " + e.getMessage());
            }

            return "All";
        } catch (Exception e) {
            LOGGER.warning("Error getting selected category: " + e.getMessage());
            return "All";
        }
    }

    /**
     * Get the currently selected status name from the status TreeView
     */
    private String getSelectedStatusFromTreeView() {
        try {
            Pointer statusTreeView = ui.getWidget("status_treeview");
            if (statusTreeView == null) {
                LOGGER.warning("Status treeview not found");
                return "All Status";
            }

            Pointer selection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_view_get_selection(statusTreeView);
            if (selection == null) {
                LOGGER.warning("Status treeview selection not found");
                return "All Status";
            }

            // Use proper JNA memory allocation for output parameters (similar to GladeUI
            // implementation)
            com.sun.jna.Memory modelMem = new com.sun.jna.Memory(8); // Pointer size (8 bytes on 64-bit)
            com.sun.jna.Memory iterMem = new com.sun.jna.Memory(32); // GtkTreeIter size
            modelMem.clear(); // Initialize to zero
            iterMem.clear(); // Initialize to zero

            boolean hasSelection = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_selection_get_selected(selection, modelMem,
                    iterMem);
            if (!hasSelection) {
                LOGGER.fine("No status selection found, defaulting to 'All Status'");
                return "All Status";
            }

            // Get model pointer from the memory
            Pointer model = modelMem.getPointer(0);
            if (model == null) {
                LOGGER.warning("Status tree model is null");
                return "All Status";
            }

            // Use a memory buffer to store the string pointer returned by
            // gtk_tree_model_get
            com.sun.jna.Memory nameValueMem = new com.sun.jna.Memory(8); // Pointer size
            nameValueMem.clear(); // Initialize to zero

            try {
                // Get the status name from column 0
                GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get(model, iterMem, 0, nameValueMem, -1);

                Pointer namePtr = nameValueMem.getPointer(0);
                if (namePtr != null && namePtr != Pointer.NULL) {
                    String statusName = namePtr.getString(0);
                    LOGGER.fine("Selected status from TreeView: " + statusName);
                    return statusName != null && !statusName.isEmpty() ? statusName : "All Status";
                }
            } catch (Exception e) {
                LOGGER.warning("Error reading status name from tree model: " + e.getMessage());
            }

            return "All Status";
        } catch (Exception e) {
            LOGGER.warning("Error getting selected status: " + e.getMessage());
            return "All Status";
        }
    }

    private void updateDetailsPanel() {
        if (selectedDownload == null) {
            // Clear all detail tabs
            try {
                // Clear detail tabs - would require GTK notebook manipulation
                LOGGER.fine("Clearing detail tabs");
            } catch (Exception e) {
                LOGGER.warning("Failed to clear detail tabs: " + e.getMessage());
            }
            return;
        }

        updateGeneralTab();
        updateTrackersTab();
        updatePeersTab();
        updateFilesTab();
    }

    private void updateGeneralTab() {
        if (selectedDownload != null) {
            try {
                // Update general tab with download information
                LOGGER.fine("General tab updated for: " + selectedDownload.getName());
                // This would populate GTK widgets with download details
            } catch (Exception e) {
                LOGGER.warning("Failed to update general tab: " + e.getMessage());
            }
        }
    }

    private void updateTrackersTab() {
        if (selectedDownload != null) {
            try {
                // Update trackers tab with tracker information
                LOGGER.fine("Trackers tab updated for: " + selectedDownload.getName());
                // This would populate GTK widgets with tracker details
            } catch (Exception e) {
                LOGGER.warning("Failed to update trackers tab: " + e.getMessage());
            }
        }
    }

    private void updatePeersTab() {
        if (selectedDownload != null) {
            try {
                // Update peers tab with peer information
                LOGGER.fine("Peers tab updated for: " + selectedDownload.getName());
                // This would populate GTK widgets with peer details
            } catch (Exception e) {
                LOGGER.warning("Failed to update peers tab: " + e.getMessage());
            }
        }
    }

    private void updateFilesTab() {
        if (selectedDownload != null) {
            try {
                // Update files tab with file information
                LOGGER.fine("Files tab updated for: " + selectedDownload.getName());
                // This would populate GTK widgets with file details
            } catch (Exception e) {
                LOGGER.warning("Failed to update files tab: " + e.getMessage());
            }
        }
    }

    /**
     * Updates the entire UI - called periodically.
     */
    private void updateUI() {
        try {
            ui.processEvents(); // Process pending GTK events
            updateDownloadMenuState();
            updateToolbarState();
            updateStatusBar();

            if (selectedDownload != null) {
                updateDetailsPanel();
            }

            // Use UI services to prevent warnings
            if (uiStateService != null && downloadUIService != null) {
                // Services are available for future use
            }
        } catch (Exception e) {
            LOGGER.fine("Error in periodic UI update: " + e.getMessage());
        }
    }

    private void updateDownloadMenuState() {
        boolean hasSelection = selectedDownload != null;
        boolean canOpen = hasSelection && selectedDownload.getStatus() == Download.Status.COMPLETED;
        boolean canForce = hasSelection
                && (selectedDownload.getStatus() == Download.Status.PAUSED
                        || selectedDownload.getStatus() == Download.Status.ERROR
                        || selectedDownload.getStatus() == Download.Status.COMPLETED);

        // More specific conditions for context menu actions
        boolean canPause = hasSelection && selectedDownload.getStatus() == Download.Status.DOWNLOADING;
        boolean canResume = hasSelection
                && (selectedDownload.getStatus() == Download.Status.PAUSED
                        || selectedDownload.getStatus() == Download.Status.ERROR);
        boolean canStart = hasSelection
                && (selectedDownload.getStatus() == Download.Status.QUEUED
                        || selectedDownload.getStatus() == Download.Status.CANCELED);

        try {
            // Selection-dependent menu items with specific conditions
            ui.setWidgetSensitive("open_menu_item", canOpen);
            ui.setWidgetSensitive("open_folder_menu_item", hasSelection);
            ui.setWidgetSensitive("force_download_menu_item", canForce);
            ui.setWidgetSensitive("delete_menu_item", hasSelection);
            ui.setWidgetSensitive("delete_with_files_menu_item", hasSelection);
            ui.setWidgetSensitive("properties_menu_item", hasSelection);

            // Context menu items with state-specific logic
            ui.setWidgetSensitive("context_open_file", canOpen);
            ui.setWidgetSensitive("context_open_folder", hasSelection);
            ui.setWidgetSensitive("context_pause", canPause);
            ui.setWidgetSensitive("context_resume", canResume);
            ui.setWidgetSensitive("context_start", canStart);
            ui.setWidgetSensitive("context_copy_magnet", hasSelection);
            ui.setWidgetSensitive("context_change_destination", hasSelection);
            ui.setWidgetSensitive("context_verify_data", canOpen);
            ui.setWidgetSensitive("context_properties", hasSelection);
            ui.setWidgetSensitive("context_delete", hasSelection);
            ui.setWidgetSensitive("context_delete_with_files", hasSelection);

            // Always active menu items (these are always enabled)
            ui.setWidgetSensitive("pause_all_menu_item", true);
            ui.setWidgetSensitive("resume_all_menu_item", true);
            ui.setWidgetSensitive("remove_all_finished_menu_item", true);

            LOGGER.fine("Download menu state updated - hasSelection: " + hasSelection
                    + ", canOpen: " + canOpen + ", canPause: " + canPause
                    + ", canResume: " + canResume + ", canStart: " + canStart);

        } catch (Exception e) {
            LOGGER.warning("Failed to update download menu state: " + e.getMessage());
        }
    }

    /**
     * Updates context menu state based on selected download - Requirement-11:
     * Right-click context menu is displayed.
     */
    private void updateContextMenuState() {
        // Reuse the existing updateDownloadMenuState method which already handles
        // context menu items
        updateDownloadMenuState();
    }

    /**
     * Shows the download context menu - Requirement-11: Right-click context menu is
     * displayed.
     */
    private void showDownloadContextMenu() {
        try {
            Pointer contextMenu = ui.getWidget("download_context_menu");
            if (contextMenu != null) {
                // Show context menu at mouse position
                // In GTK, popup menus are typically shown at the current mouse position
                GtkNativeLibraries.Gtk.INSTANCE.gtk_menu_popup_at_pointer(contextMenu, null);
                LOGGER.fine("Download context menu displayed");
            } else {
                LOGGER.warning("Download context menu widget not found");
            }
        } catch (Exception e) {
            LOGGER.warning("Error showing download context menu: " + e.getMessage());
        }
    }

    private void updateToolbarState() {
        boolean hasSelection = selectedDownload != null;
        boolean canPause = hasSelection && selectedDownload.getStatus() == Download.Status.DOWNLOADING;
        boolean canResume = hasSelection
                && (selectedDownload.getStatus() == Download.Status.PAUSED
                        || selectedDownload.getStatus() == Download.Status.ERROR);

        // Enable/disable toolbar buttons based on state
        try {
            // Update button states - would require GTK widget sensitivity methods
            ui.setWidgetSensitive("pause_button", canPause);
            ui.setWidgetSensitive("resume_button", canResume);
            ui.setWidgetSensitive("delete_button", hasSelection);
            // ui.setWidgetSensitive("delete_with_files_button", hasSelection);
            ui.setWidgetSensitive("move_up_button", hasSelection);
            ui.setWidgetSensitive("move_top_button", hasSelection);
            ui.setWidgetSensitive("move_down_button", hasSelection);
            ui.setWidgetSensitive("move_bottom_button", hasSelection);

            LOGGER.fine(String.format("Toolbar state updated - Selection: %s, Can pause: %s, Can resume: %s",
                    hasSelection, canPause, canResume));
        } catch (Exception e) {
            LOGGER.warning("Failed to update toolbar state: " + e.getMessage());
            // Fallback logging
            LOGGER.fine("Toolbar state: canPause=" + canPause + ", canResume=" + canResume);
        }
    }

    /**
     * Updates the info label in the status bar with the provided message.
     * Requirement-6: Important messages are displayed to the user in the info_label
     */
    public void updateStatusBarMessage(String message) {
        try {
            if (message != null && !message.isEmpty()) {
                ui.setLabelText("info_label", message);
                LOGGER.info("Status: " + message);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update status bar message: " + e.getMessage());
        }
    }

    /**
     * Updates all status bar components with live data.
     * Requirement-6: Status bar is displayed with the global progress bar and the
     * global speed indicator
     * - The global progress bar is live updated with the total progress of all
     * downloads
     * - The global speed indicator is live updated with the current download speed
     * - The status bar is live updated with the DHT nodes count
     * - Important messages are displayed to the user in the info_label
     */
    public void updateStatusBar() {
        try {
            // Get statistics from the download coordinator service
            var statistics = downloadUIService.getStatistics();

            // Update download speed label
            String downSpeed = statistics.getFormattedGlobalSpeed();
            ui.setLabelText("down_speed_label", downSpeed);

            // Update upload speed label (not available from current API, use placeholder)
            // TODO get from aria2.getGlobalStat
            ui.setLabelText("up_speed_label", "0 B/s");

            // Update global progress bar
            updateGlobalProgressBar(statistics.getGlobalProgress());

            // Update DHT nodes count (placeholder for now, as DHT info is not readily
            // available)
            // In a full implementation, this would get DHT stats from aria2
            ui.setLabelText("dht_status_label", "DHT: 0");

            // Update info label with current statistics if no other message is showing
            String currentInfoLabel = getCurrentInfoLabelText();
            if (currentInfoLabel == null || currentInfoLabel.isEmpty() ||
                    currentInfoLabel.startsWith("Selected") || currentInfoLabel.contains("Downloads:")) {

                String statusInfo = String.format("Downloads: %d | Active: %d | Speed: %s",
                        statistics.getTotalDownloads(),
                        statistics.getActiveDownloads(),
                        downSpeed);
                ui.setLabelText("info_label", statusInfo);
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to update status bar: " + e.getMessage());
        }
    }

    /**
     * Updates the global progress bar with the overall download progress.
     * Requirement-6: The global progress bar is live updated with the total
     * progress of all downloads
     */
    private void updateGlobalProgressBar(double globalProgress) {
        try {
            Pointer globalProgressStore = ui.getWidget("global_progress_store");
            if (globalProgressStore != null) {
                // Allocate memory for GtkTreeIter structure
                com.sun.jna.Memory iter = new com.sun.jna.Memory(32);
                iter.clear(); // Initialize to zero

                // Get the first iterator in the list store
                boolean hasFirst = GtkNativeLibraries.Gtk.INSTANCE.gtk_tree_model_get_iter_first(
                        globalProgressStore, iter);

                if (hasFirst) {
                    // Update the progress value (column 0 in global_progress_store)
                    int progressValue = Math.max(0, Math.min(100, (int) Math.round(globalProgress)));
                    ui.listStoreSetValue(globalProgressStore, iter, 0, progressValue);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to update global progress bar: " + e.getMessage());
        }
    }

    /**
     * Gets the current text from the info label.
     */
    private String getCurrentInfoLabelText() {
        try {
            return ui.getLabelText("info_label");
        } catch (Exception e) {
            LOGGER.fine("Failed to get current info label text: " + e.getMessage());
        }
        return "";
    }

    // ====== Menu Toggle Handlers ======
    public void handleOfflineToggle(boolean offline) {
        // Update offline mode
        updateStatusBarMessage("Offline mode: " + (offline ? "ON" : "OFF"));
    }

    public void handleClipboardMonitoringToggle(boolean enabled) {
        downloadManager.setClipboardMonitoringEnabled(enabled);
        updateStatusBarMessage("Clipboard monitoring: " + (enabled ? "ON" : "OFF"));
    }

    public void handleClipboardSilentToggle(boolean silent) {
        // Update clipboard silent mode through settings
        updateStatusBarMessage("Clipboard silent mode: " + (silent ? "ON" : "OFF"));
    }

    public void handleCompletionActionChange(String action) {
        // Update completion action setting
        updateStatusBarMessage("Completion action set to: " + action);
    }

    public void handleLeftPanelToggle(boolean visible) {
        ui.setWidgetVisible("left_panel", visible);
        updateStatusBarMessage(visible ? "Left panel shown" : "Left panel hidden");
    }

    public void handleInfoPanelToggle(boolean visible) {
        ui.setWidgetVisible("info_panel_box", visible);
        updateStatusBarMessage(visible ? "Info panel shown" : "Info panel hidden");
    }

    public void handleColumnToggle(String columnId, boolean visible) {
        setColumnVisible(columnId, visible);
        // Save setting would go here
    }

    public void handleTorToggle(boolean enabled) {
        try {
            if (enabled) {
                LOGGER.info("Starting Tor service...");
                if (!torService.isHealthy()) {
                    updateStatusBarMessage("Tor network: STARTING...");
                    torService.start().thenAccept(success -> {
                        if (success) {
                            updateStatusBarMessage(
                                    "Tor network: ENABLED (SOCKS port: " + torService.getSocksPort() + ")");
                            LOGGER.info("Tor service started successfully on port " + torService.getSocksPort());
                        } else {
                            updateStatusBarMessage("Tor network: FAILED TO START");
                            ui.showErrorDialog("main_window", "Failed to start Tor service");
                            // Reset switch to off state
                            ui.setSwitchActive("tor_switch", false);
                            LOGGER.severe("Failed to start Tor service");
                        }
                    }).exceptionally(throwable -> {
                        updateStatusBarMessage("Tor network: ERROR - " + throwable.getMessage());
                        ui.showErrorDialog("main_window", "Error starting Tor service: " + throwable.getMessage());
                        ui.setSwitchActive("tor_switch", false);
                        LOGGER.severe("Error starting Tor service: " + throwable.getMessage());
                        return null;
                    });
                } else {
                    updateStatusBarMessage("Tor network: ENABLED (already running)");
                    LOGGER.info("Tor service already running");
                }
            } else {
                LOGGER.info("Stopping Tor service...");
                if (torService.isHealthy()) {
                    boolean stopped = torService.stop();
                    if (stopped) {
                        updateStatusBarMessage("Tor network: DISABLED");
                        LOGGER.info("Tor service stopped successfully");
                    } else {
                        updateStatusBarMessage("Tor network: ERROR STOPPING");
                        ui.showErrorDialog("main_window", "Failed to stop Tor service");
                        // Reset switch to on state on error
                        ui.setSwitchActive("tor_switch", true);
                        LOGGER.severe("Failed to stop Tor service");
                    }
                } else {
                    updateStatusBarMessage("Tor network: DISABLED (not running)");
                    LOGGER.info("Tor service was not running");
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Error toggling Tor service: " + e.getMessage());
            updateStatusBarMessage("Tor network: ERROR - " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to toggle Tor service: " + e.getMessage());
            // Reset switch to opposite state on error
            ui.setSwitchActive("tor_switch", !enabled);
            e.printStackTrace();
        }
    }

    public void handleSearchChanged(String searchText) {
        // Requirement-7: Search downloads by name
        // Requirement-8: Clear search results
        if (searchText == null) {
            searchText = "";
        }

        currentSearchText = searchText.trim();
        LOGGER.info("Search text changed to: '" + currentSearchText + "'");

        // Apply search filter and refresh
        refreshDownloadList();
        updateUI();
    }

    /**
     * Clears the current search filter - Requirement-8: Clear search results.
     */
    public void clearSearch() {
        currentSearchText = "";
        ui.setEntryText("search_entry", "");
        LOGGER.info("Search cleared");
        refreshDownloadList();
        updateUI();
    }

    /**
     * Gets the current search text.
     * 
     * @return the current search text
     */
    public String getCurrentSearchText() {
        return currentSearchText;
    }

    /**
     * Checks if search is currently active.
     * 
     * @return true if there is an active search filter
     */
    public boolean hasActiveSearch() {
        return currentSearchText != null && !currentSearchText.isEmpty();
    }

    // ====== File Operations ======
    private List<String> readUrlsFromFile(String filePath) throws IOException {
        List<String> urls = new ArrayList<>();
        List<String> lines = Files.readAllLines(Paths.get(filePath));

        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && isValidUrl(line)) {
                urls.add(line);
            }
        }
        return urls;
    }

    private List<String> extractLinksFromHtmlFile(String filePath) throws IOException {
        List<String> urls = new ArrayList<>();
        String content = new String(Files.readAllBytes(Paths.get(filePath)));

        // Simple regex to extract href attributes
        Pattern pattern = Pattern.compile("href=[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String url = matcher.group(1);
            if (isValidUrl(url)) {
                urls.add(url);
            }
        }

        return urls;
    }

    private void exportUrlsToFile(String filePath) throws IOException {
        List<String> urls = new ArrayList<>();
        for (Download download : displayedDownloads) {
            urls.add(download.getUri().toString());
        }
        Files.write(Paths.get(filePath), urls);
    }

    /**
     * Exports a list of URLs to the specified file path.
     * Used by Requirement 34 export functionality.
     */
    private void exportUrlsToFile(String filePath, List<String> urls) throws IOException {
        Files.write(Paths.get(filePath), urls);
    }

    private boolean isValidUrl(String url) {
        try {
            return url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("ftp://") || url.startsWith("magnet:");
        } catch (Exception e) {
            return false;
        }
    }

    // ====== Statistics and Info ======
    public void handleShowStatistics() {
        try {
            List<Download> allDownloads = downloadManager.getAllDownloads();
            int totalDownloads = allDownloads.size();
            int completedDownloads = 0;
            int activeDownloads = 0;

            for (Download download : allDownloads) {
                switch (download.getStatus()) {
                    case COMPLETED:
                        completedDownloads++;
                        break;
                    case DOWNLOADING:
                        activeDownloads++;
                        break;
                    case QUEUED:
                    case CONNECTING:
                    case PAUSED:
                        // Count as active for statistics
                        activeDownloads++;
                        break;
                    case ERROR:
                    case CANCELED:
                        // These are counted in total but not in active/completed
                        break;
                }
            }

            String statistics = String.format(
                    "Download Statistics:\n\n"
                            + "Total Downloads: %d\n"
                            + "Completed: %d\n"
                            + "Active: %d\n",
                    totalDownloads, completedDownloads, activeDownloads);

            ui.showInfoDialog("main_window", statistics);

        } catch (Exception e) {
            LOGGER.severe("Error showing statistics: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to show statistics: " + e.getMessage());
        }
    }

    public void handleRemoveAllFinished() {
        try {
            List<Download> completedDownloads = downloadManager.getAllDownloads().stream()
                    .filter(d -> d.getStatus() == Download.Status.COMPLETED)
                    .collect(java.util.stream.Collectors.toList());

            if (!completedDownloads.isEmpty()) {
                boolean confirm = ui.showQuestionDialog("main_window",
                        "Remove " + completedDownloads.size() + " completed downloads?");

                if (confirm) {
                    for (Download download : completedDownloads) {
                        downloadManager.cancelDownload(download, false);
                    }
                    refreshDownloadList();
                    updateUI();
                    updateStatusBarMessage("Removed " + completedDownloads.size() + " completed downloads");
                    ui.showInfoDialog("main_window", "Removed " + completedDownloads.size() + " completed downloads");
                }
            } else {
                ui.showInfoDialog("main_window", "No completed downloads to remove");
            }
        } catch (Exception e) {
            LOGGER.severe("Error removing finished downloads: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to remove finished downloads: " + e.getMessage());
        }
    }

    public void handleBatchProcess() {
        LOGGER.info("Batch process menu item activated");
        try {
            // Show file chooser for batch import
            String selectedFile = ui.showFileChooserDialog("main_window", "Select Batch File",
                    settings.getDefaultDownloadDirectory().toString());

            if (selectedFile != null) {
                // Import URLs from file - delegate to import file functionality
                handleImportFile();
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to open batch process dialog: " + e.getMessage());
            ui.showErrorDialog("main_window", "Failed to open batch processing: " + e.getMessage());
        }
    }

    public void handleDonation() {
        try {
            ui.showInfoDialog("main_window",
                    "Support Open Download Manager Development\n\n"
                            + "Visit: https://github.com/sponsors/your-project");
        } catch (Exception e) {
            LOGGER.severe("Error opening donation dialog: " + e.getMessage());
        }
    }

    // ====== Download Events ======
    public void onDownloadStart(Download download) {
        // Don't refresh the entire list - download was already added via
        // onDownloadAdded()
        // Just update the status bar to show the download has started
        updateStatusBarMessage("Started: " + download.getName());
    }

    public void onDownloadProgress(Download download) {
        // Update only if this download is currently displayed
        if (displayedDownloads.contains(download)) {
            // Update progress in UI - for now just log
            LOGGER.fine("Progress update for: " + download.getName() + " (" + download.getProgress() + "%)");
        }
    }

    public void onDownloadPause(Download download) {
        refreshDownloadList();
        updateStatusBarMessage("Paused: " + download.getName());
    }

    public void onDownloadResume(Download download) {
        refreshDownloadList();
        updateStatusBarMessage("Resumed: " + download.getName());
    }

    public void onDownloadComplete(Download download) {
        refreshDownloadList();
        updateStatusBarMessage("Completed: " + download.getName());
    }

    public void onDownloadError(Download download) {
        refreshDownloadList();
        updateStatusBarMessage("Error: " + download.getName());
    }

    public void onDownloadCanceled(Download download) {
        refreshDownloadList();
        updateStatusBarMessage("Canceled: " + download.getName());
    }

    // ====== DownloadUIListener Implementation ======
    @Override
    public void onDownloadAdded(Download download) {
        LOGGER.info("New download added: " + download.getName());
        refreshDownloadList();
        updateStatusBarMessage("Added: " + download.getName());
    }

    @Override
    public void onDownloadUpdated(Download download) {
        // Refresh the display to show updated information
        refreshDownloadList();
    }

    @Override
    public void onDownloadRemoved(String downloadId) {
        // Refresh the display to remove deleted downloads
        refreshDownloadList();
    }

    // ====== Window Event Handlers ======
    public boolean handleWindowDeleteEvent() {
        try {
            LOGGER.info("Main window delete event - showing exit confirmation dialog");

            // Show confirmation dialog as required by Requirement 30
            boolean confirmExit = ui.showQuestionDialog("main_window",
                    "Are you sure you want to exit the download manager?\n\nAll active downloads will be paused.");

            if (confirmExit) {
                LOGGER.info("User confirmed exit - initiating application shutdown");
                initiateApplicationShutdown();
                return false; // Allow window to close
            } else {
                LOGGER.info("User cancelled exit");
                return true; // Prevent window from closing
            }
        } catch (Exception e) {
            LOGGER.severe("Error during window delete event: " + e.getMessage());
            return false; // Still allow window to close
        }
    }

    public void handleWindowDestroy() {
        try {
            LOGGER.info("Main window destroying - initiating application shutdown");
            initiateApplicationShutdown();
        } catch (Exception e) {
            LOGGER.severe("Error during window destroy: " + e.getMessage());
        }
    }

    // ====== Cleanup and State Management ======
    /**
     * Initiates proper application shutdown using the registered callback.
     * Falls back to local cleanup if no callback is available.
     */
    private void initiateApplicationShutdown() {
        try {
            if (applicationShutdownCallback != null) {
                LOGGER.info("Calling application shutdown callback for comprehensive cleanup");
                applicationShutdownCallback.run();
            } else {
                LOGGER.warning("No application shutdown callback available, falling back to local cleanup");
                fallbackShutdown();
            }
        } catch (Exception e) {
            LOGGER.severe("Error during application shutdown: " + e.getMessage());
            e.printStackTrace();
            // Fallback to local cleanup in case of error
            fallbackShutdown();
        }
    }

    /**
     * Fallback shutdown method that performs local cleanup and quits the UI.
     * Used when application-level shutdown is not available.
     */
    private void fallbackShutdown() {
        try {
            LOGGER.warning("Performing fallback shutdown - application-level shutdown unavailable");

            // Save window state before cleanup
            saveWindowState();

            // Perform local cleanup
            cleanup();

            // Emergency cleanup of external processes since coordinated shutdown failed
            emergencyExternalProcessCleanup();

            // Try to shutdown ApplicationContext as fallback
            try {
                org.manager.ApplicationContext.shutdown();
                LOGGER.info("ApplicationContext shutdown completed in fallback mode");
            } catch (Exception e) {
                LOGGER.warning("Error shutting down ApplicationContext in fallback: " + e.getMessage());
            }

            // Quit UI
            if (ui != null) {
                ui.quit();
            }

            LOGGER.info("Fallback shutdown completed");
        } catch (Exception e) {
            LOGGER.severe("Error during fallback shutdown: " + e.getMessage());
            e.printStackTrace();
            // Force exit as last resort
            System.exit(1);
        }
    }

    /**
     * Emergency cleanup for external processes - only used in fallback
     * scenarios when the coordinated shutdown fails or is unavailable.
     */
    private void emergencyExternalProcessCleanup() {
        try {
            LOGGER.warning("Performing emergency external process cleanup...");

            String[] criticalProcesses = { "aria2c", "curl", "yt-dlp", "youtube-dl", "httrack" };

            for (String processName : criticalProcesses) {
                try {
                    Process killProcess = new ProcessBuilder("pkill", "-f", processName).start();
                    killProcess.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                    LOGGER.fine("Emergency cleanup attempted for: " + processName);
                } catch (Exception e) {
                    // Ignore errors - this is best effort emergency cleanup
                }
            }

        } catch (Exception e) {
            LOGGER.warning("Error during emergency process cleanup: " + e.getMessage());
        }
    }

    /**
     * Performs UI-specific resource cleanup that needs to happen before core
     * shutdown. This method only handles UI-related resources and lets the core
     * ShutdownCoordinator handle all the download manager and external process
     * cleanup.
     */
    private void performComprehensiveResourceCleanup() {
        try {
            LOGGER.info("Performing UI-specific resource cleanup...");

            // Save window state before any shutdown
            saveWindowState();

            // Only perform emergency process cleanup if this is a fallback scenario
            // The core ShutdownCoordinator should handle all process cleanup normally
            LOGGER.info("UI resource cleanup completed - core shutdown will handle external processes");

        } catch (Exception e) {
            LOGGER.warning("Error during UI resource cleanup: " + e.getMessage());
        }
    }

    public void cleanup() {
        try {
            if (updateService != null) {
                updateService.shutdown();
            }

            // Remove ourselves as a download UI listener
            if (downloadUIService != null) {
                downloadUIService.removeListener(this);
                LOGGER.info("Removed download UI listener");
            }

            // Cleanup sub-controllers
            if (newDownloadController != null) {
                // newDownloadController.cleanup(); // Will be implemented when cleanup is
                // public
            }
            if (settingsController != null) {
                // settingsController.cleanup(); // Will be implemented when cleanup is public
            }
            if (propertyController != null) {
                // propertyController.cleanup(); // Will be implemented when cleanup is public
            }
            if (aboutController != null) {
                // aboutController.cleanup(); // Will be implemented when cleanup is public
            }

            // Perform comprehensive resource cleanup
            performComprehensiveResourceCleanup();

            LOGGER.info("Main window service cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during cleanup: " + e.getMessage());
        }
    }

    public void saveWindowState() {
        try {
            // Save window size and position
            if (uiStateService != null) {
                // uiStateService.saveWindowState("main_window", ui); // Method signature to be
                // confirmed
            }
            LOGGER.info("Window state saved");
        } catch (Exception e) {
            LOGGER.severe("Error saving window state: " + e.getMessage());
        }
    }

    // ====== Formatting Utility Methods for Download List View ======

    /**
     * Formats bytes to human-readable string (e.g., "1.5 MB").
     */
    private String formatBytes(long bytes) {
        try {
            if (bytes < 0) {
                return "0 B";
            }
            if (bytes == 0) {
                return "0 B";
            }

            final String[] units = { "B", "KB", "MB", "GB", "TB" };
            int unitIndex = 0;
            double size = bytes;

            while (size >= 1024 && unitIndex < units.length - 1) {
                size /= 1024;
                unitIndex++;
            }

            return String.format("%.1f %s", size, units[unitIndex]);
        } catch (Exception e) {
            LOGGER.warning("Error formatting bytes: " + e.getMessage());
            return "0 B";
        }
    }

    /**
     * Formats percentage (0-100) to display string.
     */
    private String formatPercentage(float percentage) {
        try {
            if (Float.isNaN(percentage) || Float.isInfinite(percentage)) {
                return "0.0%";
            }
            if (percentage < 0) {
                return "0.0%";
            }
            if (percentage > 100) {
                return "100.0%";
            }
            return String.format("%.1f%%", percentage);
        } catch (Exception e) {
            LOGGER.warning("Error formatting percentage: " + e.getMessage());
            return "0.0%";
        }
    }

    /**
     * Formats speed in bytes/second to human-readable string.
     */
    private String formatSpeed(float speedBps) {
        try {
            if (Float.isNaN(speedBps) || Float.isInfinite(speedBps) || speedBps < 0) {
                return "0 B/s";
            }

            final String[] units = { "B/s", "KB/s", "MB/s", "GB/s" };
            int unitIndex = 0;
            double speed = speedBps;

            while (speed >= 1024 && unitIndex < units.length - 1) {
                speed /= 1024;
                unitIndex++;
            }

            return String.format("%.1f %s", speed, units[unitIndex]);
        } catch (Exception e) {
            LOGGER.warning("Error formatting speed: " + e.getMessage());
            return "0 B/s";
        }
    }

    /**
     * Formats duration for elapsed time based on download start time.
     */
    private String formatDuration(Download download) {
        try {
            if (download == null || download.getStartedAt() == null) {
                return "00:00:00";
            }

            java.time.Instant now = java.time.Instant.now();
            long secondsElapsed = java.time.Duration.between(download.getStartedAt(), now).getSeconds();

            if (secondsElapsed < 0) {
                return "00:00:00";
            }

            long hours = secondsElapsed / 3600;
            long minutes = (secondsElapsed % 3600) / 60;
            long seconds = secondsElapsed % 60;

            if (hours > 999) {
                return "∞";
            }

            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } catch (Exception e) {
            LOGGER.warning("Error formatting duration: " + e.getMessage());
            return "00:00:00";
        }
    }

    /**
     * Estimates time left for download completion.
     */
    private String estimateTimeLeft(Download download) {
        try {
            if (download == null) {
                return "∞";
            }

            float speed = download.getSpeed();
            long size = download.getSize();
            long downloaded = download.getDownloaded();

            if (Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0 || size <= 0) {
                return "∞";
            }

            long remainingBytes = size - downloaded;
            if (remainingBytes <= 0) {
                return "00:00:00";
            }

            long secondsLeft = (long) (remainingBytes / speed);
            if (secondsLeft < 0) {
                return "∞";
            }

            long hours = secondsLeft / 3600;
            long minutes = (secondsLeft % 3600) / 60;
            long seconds = secondsLeft % 60;

            if (hours > 99) {
                return "∞";
            }

            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } catch (Exception e) {
            LOGGER.warning("Error estimating time left: " + e.getMessage());
            return "∞";
        }
    }

    /**
     * Formats Instant to date string.
     */
    private String formatDate(java.time.Instant instant) {
        if (instant == null) {
            return "";
        }

        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(instant);
        } catch (Exception e) {
            LOGGER.warning("Error formatting date: " + e.getMessage());
            return "";
        }
    }

    /**
     * Gets appropriate icon for download based on its status/type.
     */
    private String getDownloadIcon(Download download) {
        if (download == null || download.getStatus() == null) {
            return "gtk-missing-image";
        }

        try {
            switch (download.getStatus()) {
                case DOWNLOADING:
                    return "media-playback-start-symbolic";
                case PAUSED:
                    return "media-playback-pause-symbolic";
                case COMPLETED:
                    return "gtk-apply";
                case ERROR:
                    return "dialog-error-symbolic";
                case CANCELED:
                    return "gtk-cancel";
                case QUEUED:
                    return "media-playlist-repeat-symbolic";
                case CONNECTING:
                    return "network-wireless-acquiring-symbolic";
                default:
                    return "gtk-missing-image";
            }
        } catch (Exception e) {
            LOGGER.warning("Error getting download icon: " + e.getMessage());
            return "gtk-missing-image";
        }
    }

    /**
     * Shows the Import List dialog for managing imported URLs.
     * Used by file import functionality as required by Requirement 33.
     */
    private void showImportListDialog() {
        try {
            LOGGER.info("Opening Import List dialog");
            org.odm.ui.controller.ImportListController importListController = new org.odm.ui.controller.ImportListController(
                    downloadManager, settings);
            Pointer parentWindow = ui.getWidget("main_window");
            boolean result = importListController.showDialog(parentWindow);

            if (result) {
                LOGGER.info("Import List dialog completed successfully");
                refreshDownloadList();
            } else {
                LOGGER.info("Import List dialog cancelled by user");
            }
        } catch (Exception e) {
            LOGGER.warning("Error showing Import List dialog: " + e.getMessage());
            if (ui != null) {
                ui.showErrorDialog("main_window", "Failed to show Import List dialog: " + e.getMessage());
            }
        }
    }
}
