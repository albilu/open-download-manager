package org.manager.folder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Specialized folder monitor for handling Metalink files. This class integrates
 * with the DownloadManager to automatically add Metalink files to the download
 * queue when they are detected in monitored folders.
 */
public class MetaLinkFolderMonitor implements FolderMonitorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetaLinkFolderMonitor.class);

    private final DownloadManager downloadManager;
    private final FolderMonitorService folderMonitorService;
    private final Path defaultDownloadDirectory;

    /**
     * Creates a new MetaLinkFolderMonitor.
     *
     * @param downloadManager          The download manager to add Metalink files to
     * @param folderMonitorService     The folder monitor service
     * @param defaultDownloadDirectory Default directory for Metalink downloads
     */
    public MetaLinkFolderMonitor(DownloadManager downloadManager,
            FolderMonitorService folderMonitorService,
            Path defaultDownloadDirectory) {
        this.downloadManager = downloadManager;
        this.folderMonitorService = folderMonitorService;
        this.defaultDownloadDirectory = defaultDownloadDirectory;

        // Register this monitor as a listener
        folderMonitorService.addFolderMonitorListener(this);
    }

    /**
     * Starts monitoring a folder for Metalink files with default settings.
     *
     * @param folderPath The folder to monitor
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startMetaLinkMonitoring(Path folderPath) {
        FolderMonitorSettings settings = createDefaultMetaLinkSettings();
        return folderMonitorService.startMonitoring(folderPath, settings);
    }

    /**
     * Starts monitoring a folder for Metalink files with custom settings.
     *
     * @param folderPath The folder to monitor
     * @param settings   Custom monitoring settings
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startMetaLinkMonitoring(Path folderPath, FolderMonitorSettings settings) {
        // Ensure Metalink extensions are included
        if (!settings.getFileExtensions().contains(".metalink")
                && !settings.getFileExtensions().contains(".meta4")) {
            settings.addFileExtension(".metalink");
            settings.addFileExtension(".meta4");
        }
        return folderMonitorService.startMonitoring(folderPath, settings);
    }

    /**
     * Stops monitoring a folder for Metalink files.
     *
     * @param folderPath The folder to stop monitoring
     * @return A future that completes when monitoring stops
     */
    public CompletableFuture<Void> stopMetaLinkMonitoring(Path folderPath) {
        return folderMonitorService.stopMonitoring(folderPath);
    }

    /**
     * Creates default settings optimized for Metalink file monitoring.
     *
     * @return Default Metalink monitoring settings
     */
    public static FolderMonitorSettings createDefaultMetaLinkSettings() {
        return new FolderMonitorSettings()
                .setFileExtensions(java.util.Set.of())
                .addFileExtension(".metalink")
                .addFileExtension(".meta4")
                .setRecursive(false)
                .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_TRASH)
                .setProcessExistingFiles(true)
                .setDebounceDelay(java.time.Duration.ofSeconds(2))
                .setEnabled(true)
                .setMaxFilesPerBatch(5)
                .setCaseSensitive(false)
                .setMinFileSize(50L) // At least 50 bytes for a valid Metalink file
                .setMaxFileSize(5 * 1024 * 1024L); // Max 5MB for Metalink files
    }

    /**
     * Creates settings for moving processed Metalink files to a specific
     * directory.
     *
     * @param moveToDirectory The directory to move processed files to
     * @return Metalink monitoring settings with move action
     */
    public static FolderMonitorSettings createMetaLinkSettingsWithMove(Path moveToDirectory) {
        return createDefaultMetaLinkSettings()
                .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
                .setMoveToDirectory(moveToDirectory);
    }

    /**
     * Creates settings for deleting processed Metalink files.
     *
     * @return Metalink monitoring settings with delete action
     */
    public static FolderMonitorSettings createMetaLinkSettingsWithDelete() {
        return createDefaultMetaLinkSettings()
                .setFileAction(FolderMonitorSettings.FileAction.DELETE);
    }

    @Override
    public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        if (isMetaLinkFile(filePath)) {
            processMetaLinkFile(folderPath, filePath, settings);
        }
    }

    @Override
    public void onFileModified(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        // For Metalink files, we typically don't need to handle modifications
        // as they should be complete when first added
        if (isMetaLinkFile(filePath)) {
            LOGGER.debug("Metalink file modified (ignoring): " + filePath);
        }
    }

    @Override
    public void onFileProcessed(Path folderPath, Path filePath, FolderMonitorSettings.FileAction action,
            FolderMonitorSettings settings) {
        if (isMetaLinkFile(filePath)) {
            LOGGER.info("Metalink file processed with action " + action + ": " + filePath);
        }
    }

    @Override
    public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
        if (isMetaLinkFile(filePath)) {
            LOGGER.warn("Error processing Metalink file: " + filePath, error);
        }
    }

    @Override
    public void onMonitoringStarted(Path folderPath, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".metalink")
                || settings.getFileExtensions().contains(".meta4")) {
            LOGGER.info("Started Metalink monitoring for folder: " + folderPath);
        }
    }

    @Override
    public void onMonitoringStopped(Path folderPath, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".metalink")
                || settings.getFileExtensions().contains(".meta4")) {
            LOGGER.info("Stopped Metalink monitoring for folder: " + folderPath);
        }
    }

    @Override
    public void onMonitoringError(Path folderPath, Throwable error, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".metalink")
                || settings.getFileExtensions().contains(".meta4")) {
            LOGGER.error("Metalink monitoring error for folder: " + folderPath, error);
        }
    }

    /**
     * Processes a detected Metalink file by adding it to the download manager.
     *
     * @param folderPath The monitored folder path
     * @param filePath   The Metalink file path
     * @param settings   The monitoring settings
     */
    private void processMetaLinkFile(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        try {
            LOGGER.info("Processing Metalink file: " + filePath);

            // Validate Metalink file
            if (!isValidMetaLinkFile(filePath)) {
                LOGGER.warn("Invalid Metalink file detected: " + filePath);
                return;
            }

            // Determine download destination
            Path downloadDestination = determineDownloadDestination(folderPath, settings);

            // Create Metalink download using file URI
            java.net.URI metaLinkUri = filePath.toUri();
            Download metaLinkDownload = downloadManager.createMetaLinkDownload(metaLinkUri, downloadDestination);

            // Listener return is the folder service's acceptance boundary.
            // A failed queue future must propagate before the descriptor is
            // marked dispatched or the source is moved/deleted.
            downloadManager.queueDownloadFromBackgroundSource(metaLinkDownload).join();
            LOGGER.info("Successfully added Metalink to download queue: " + filePath);

        } catch (Exception e) {
            LOGGER.error("Error processing Metalink file: " + filePath, e);
            throw new RuntimeException("Failed to process Metalink file: " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks if a file is a Metalink file based on its extension.
     *
     * @param filePath The file path to check
     * @return true if the file is a Metalink file
     */
    private boolean isMetaLinkFile(Path filePath) {
        return Download.Protocol.fromPath(filePath) == Download.Protocol.METALINK;
    }

    /**
     * Validates that a Metalink file is properly formatted using XML parsing
     * and structure checking.
     *
     * @param filePath The Metalink file path
     * @return true if the file appears to be a valid Metalink file
     */
    private boolean isValidMetaLinkFile(Path filePath) {
        try {
            // Basic size check - Metalink files should be at least 50 bytes
            long fileSize = java.nio.file.Files.size(filePath);
            if (fileSize < 50) {
                return false;
            }

            // Read enough data to validate XML structure
            byte[] data = new byte[Math.min(16384, (int) fileSize)];
            int bytesRead;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(filePath.toFile())) {
                bytesRead = fis.read(data);
            }

            if (bytesRead < 20) {
                return false;
            }

            // Parse and validate XML structure
            return validateMetalinkXmlStructure(data, bytesRead);

        } catch (Exception e) {
            LOGGER.warn("Error validating Metalink file: " + filePath, e);
            return false;
        }
    }

    /**
     * Validates XML structure and checks for required Metalink elements.
     *
     * @param data   The metalink file data
     * @param length The length of valid data
     * @return true if the structure appears to be a valid Metalink file
     */
    private boolean validateMetalinkXmlStructure(byte[] data, int length) {
        try {
            String content = new String(data, 0, length, "UTF-8").toLowerCase();

            // Check for Metalink namespace and root element
            boolean hasMetalinkNamespace = content.contains("urn:ietf:params:xml:ns:metalink")
                    || content.contains("http://www.metalinker.org/");
            boolean hasMetalinkRoot = content.contains("<metalink");

            // Check for essential Metalink elements
            boolean hasFileElements = content.contains("<file") || content.contains("<files");
            boolean hasUrlElements = content.contains("<url") || content.contains("<resources");

            // Check for well-formed XML structure
            // An XML declaration is optional under the XML specification.
            boolean hasValidXmlStructure = content.contains("<")
                    && content.contains(">");

            // Additional validation for common Metalink attributes/elements
            boolean hasMetalinkFeatures = content.contains("name=")
                    || content.contains("size=")
                    || content.contains("hash")
                    || content.contains("verification");

            // A valid Metalink needs the root and basic XML structure.
            boolean isValidMetalink = hasValidXmlStructure && hasMetalinkRoot;

            // For higher confidence, check for namespace and file elements
            if (isValidMetalink && (hasMetalinkNamespace || hasFileElements || hasUrlElements || hasMetalinkFeatures)) {
                return true;
            }

            return isValidMetalink;

        } catch (Exception e) {
            LOGGER.debug("Error parsing Metalink XML structure", e);
            return false;
        }
    }

    /**
     * Determines the download destination directory for a Metalink.
     *
     * @param folderPath The monitored folder path
     * @param settings   The monitoring settings
     * @return The download destination directory
     */
    private Path determineDownloadDestination(Path folderPath, FolderMonitorSettings settings) {
        // Use the default download directory from the download manager's global
        // settings
        Path globalDownloadDir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();

        if (globalDownloadDir != null) {
            return globalDownloadDir;
        }

        // Fallback to the provided default directory
        if (defaultDownloadDirectory != null) {
            return defaultDownloadDirectory;
        }

        // Last resort: use a subdirectory of the monitored folder
        return folderPath.resolve("downloads");
    }

    /**
     * Convenience method to start monitoring a user's Downloads folder for
     * Metalink files.
     *
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startDefaultMetaLinkMonitoring() {
        return startMetaLinkMonitoring(org.manager.util.OdmPaths.downloadDirectory());
    }

    /**
     * Gets the underlying folder monitor service.
     *
     * @return The folder monitor service
     */
    public FolderMonitorService getFolderMonitorService() {
        return folderMonitorService;
    }

    /**
     * Gets the download manager used by this monitor.
     *
     * @return The download manager
     */
    public DownloadManager getDownloadManager() {
        return downloadManager;
    }

    /**
     * Shuts down the Metalink folder monitor.
     *
     * @return A future that completes when shutdown is finished
     */
    public CompletableFuture<Void> shutdown() {
        // Remove this listener from the folder monitor service
        folderMonitorService.removeFolderMonitorListener(this);

        // The folder monitor service itself should be shut down by its owner
        return CompletableFuture.completedFuture(null);
    }
}
