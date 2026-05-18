package org.odm.ui.service;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.odm.ui.utils.DownloadUtils;
import org.odm.ui.utils.UIUtils;
import org.odm.ui.utils.URLGenerationUtils;

import com.sun.jna.Pointer;

/**
 * Service class for Import URL Sequence dialog business logic.
 * Handles URL generation, validation, and download creation.
 */
public class ImportSequenceService {

    private static final Logger LOGGER = Logger.getLogger(ImportSequenceService.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;
    private final GladeUI ui;
    private Consumer<Boolean> closeDialogCallback;

    // Generated URLs list
    private List<String> generatedUrls;

    // Current sequence settings
    private String currentUrlPattern = "";
    private boolean numSequenceEnabled = false;
    private boolean charSequenceEnabled = false;
    private int numStart = 1;
    private int numEnd = 10;
    private int numDigits = 3;
    private char charStart = 'a';
    private char charEnd = 'z';
    private Path selectedDestination;

    public ImportSequenceService(DownloadManager downloadManager, GlobalSettings settings,
            GladeUI ui) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        this.ui = ui;
        this.generatedUrls = new ArrayList<>();
    }

    public void setCloseDialogCallback(Consumer<Boolean> callback) {
        this.closeDialogCallback = callback;
    }

    // ====== Handler Methods ======
    public void handleCancel() {
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    public void handleValidate() {
        if (!generatedUrls.isEmpty()) {
            createDownloadsFromUrls();
            if (closeDialogCallback != null) {
                closeDialogCallback.accept(true);
            }
        }
    }

    public void handleNotebookPageSwitch() {
        // Load global settings when switching to Options tab
        loadGlobalSettingsToUI();
    }

    public void handleUriEntryChange(String urlPattern) {
        currentUrlPattern = urlPattern;
        generateAndUpdatePreview();
    }

    public void handleNumComboChange(int activeIndex) {
        numSequenceEnabled = (activeIndex == 1); // "Num" is at index 1
        generateAndUpdatePreview();
    }

    public void handleNumStartChange(int numStart) {
        this.numStart = numStart;
        generateAndUpdatePreview();
    }

    public void handleNumEndChange(int numEnd) {
        this.numEnd = numEnd;
        generateAndUpdatePreview();
    }

    public void handleNumCountChange(int numDigits) {
        this.numDigits = numDigits;
        generateAndUpdatePreview();
    }

    public void handleCharComboChange(int activeIndex) {
        charSequenceEnabled = (activeIndex == 1); // "Char" is at index 1
        generateAndUpdatePreview();
    }

    public void handleCharEntryChange(String text) {
        if (text != null && !text.isEmpty()) {
            charStart = text.charAt(0);
            generateAndUpdatePreview();
        }
    }

    public void handleCharVersEntryChange(String text) {
        if (text != null && !text.isEmpty()) {
            charEnd = text.charAt(0);
            generateAndUpdatePreview();
        }
    }

    // ====== Initialization Methods ======
    public void initializeService() {
        // Set default destination from global settings
        selectedDestination = settings.getDefaultDownloadDirectory();

        // Initialize UI with global settings
        loadGlobalSettingsToUI();

        // Update disk space display
        updateDiskSpaceDisplay();

        // Update button states
        updateButtonStates();

        LOGGER.info("Import sequence service initialized");
    }

    public void cleanup() {
        try {
            generatedUrls.clear();
            LOGGER.info("Import sequence service cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during service cleanup: " + e.getMessage());
        }
    }

    // ====== Core Implementation Methods ======
    /**
     * Generates URLs based on current pattern and sequence settings, then
     * updates the preview.
     */
    private void generateAndUpdatePreview() {
        generatedUrls.clear();

        if (currentUrlPattern.isEmpty()) {
            updatePreviewList();
            updateButtonStates();
            return;
        }

        try {
            if (numSequenceEnabled && charSequenceEnabled) {
                generatedUrls.addAll(URLGenerationUtils.generateCombinedSequence(
                        currentUrlPattern, numStart, numEnd, numDigits, charStart, charEnd));
            } else if (numSequenceEnabled) {
                generatedUrls.addAll(URLGenerationUtils.generateNumericSequence(
                        currentUrlPattern, numStart, numEnd, numDigits));
            } else if (charSequenceEnabled) {
                generatedUrls.addAll(URLGenerationUtils.generateCharacterSequence(
                        currentUrlPattern, charStart, charEnd));
            } else {
                if (URLGenerationUtils.isValidPattern(currentUrlPattern)) {
                    // Has placeholders but no sequence type selected
                    LOGGER.warning("URL has placeholders but no sequence type enabled");
                } else {
                    // No sequence placeholders and no sequence type selected,
                    // treat as a single URL
                    generatedUrls.add(currentUrlPattern);
                }
            }

            updatePreviewList();
            updateButtonStates();

        } catch (Exception e) {
            LOGGER.severe("Error generating URL sequence: " + e.getMessage());
        }
    }

    /**
     * Updates the preview list with generated URLs.
     */
    private void updatePreviewList() {
        try {
            Pointer listStore = ui.getTreeViewListStore("preview_treeview");
            if (listStore != null) {
                LOGGER.info("Found list store, clearing it");
                ui.listStoreClear(listStore);

                for (String url : generatedUrls) {
                    LOGGER.fine("Adding URL to list: " + url);
                    Pointer iter = ui.listStoreAppend(listStore);
                    if (iter != null) {
                        LOGGER.fine("Got valid iter, setting value");
                        ui.listStoreSetValue(listStore, iter, 0, url);
                    } else {
                        LOGGER.warning("Failed to get valid iter for URL: " + url);
                    }
                }
            } else {
                LOGGER.warning("List store not found for preview_treeview");
            }

            LOGGER.info("Updated preview list with " + generatedUrls.size() + " URLs");
        } catch (Exception e) {
            LOGGER.severe("Error updating preview list: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Updates button states based on current conditions.
     */
    private void updateButtonStates() {
        try {
            boolean hasUrls = !generatedUrls.isEmpty();
            UIUtils.updateButtonState(ui, "validate_button", hasUrls);

            LOGGER.info("Updated button states - validate button " + (hasUrls ? "enabled" : "disabled"));
        } catch (Exception e) {
            LOGGER.severe("Error updating button states: " + e.getMessage());
        }
    }

    /**
     * Updates the disk space display for the selected destination.
     */
    private void updateDiskSpaceDisplay() {
        UIUtils.updateDiskSpaceDisplay(ui, "disk_space_label", selectedDestination);
    }

    /**
     * Loads global settings into the UI components.
     */
    private void loadGlobalSettingsToUI() {
        UIUtils.loadDefaultSettingsToUI(ui);
    }

    /**
     * Creates downloads from the generated URLs.
     */
    private void createDownloadsFromUrls() {
        try {
            List<Download> downloads = new ArrayList<>();

            for (String urlString : generatedUrls) {
                URI uri = URI.create(urlString);
                Download download = downloadManager.createDownload(uri, selectedDestination);

                // Determine download type based on conditions
                Download.Type downloadType = determineDownloadType(uri);
                download.setType(downloadType);

                // Apply settings from UI
                applySettingsToDownload(download);

                downloads.add(download);
            }

            // Queue all downloads
            for (Download download : downloads) {
                CompletableFuture<Void> future = downloadManager.queueDownload(download);

                // Start automatically if checkbox is checked
                if (ui.getToggleButtonActive("start_automatically_check1")) {
                    future.thenCompose(v -> downloadManager.startDownload(download));
                }
            }

            LOGGER.info("Created " + downloads.size() + " downloads from URL sequence");

        } catch (Exception e) {
            LOGGER.severe("Error creating downloads from URLs: " + e.getMessage());
        }
    }

    /**
     * Determines the download type based on the feature file rules.
     */
    private Download.Type determineDownloadType(URI uri) {
        return DownloadUtils.determineDownloadType(uri, ui);
    }

    /**
     * Applies UI settings to a download.
     */
    private void applySettingsToDownload(Download download) {
        DownloadUtils.applySettingsToDownload(download, ui);
    }
}
