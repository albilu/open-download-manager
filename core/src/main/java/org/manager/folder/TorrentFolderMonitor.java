package org.manager.folder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Specialized folder monitor for handling .torrent files. This class integrates
 * with the DownloadManager to automatically add torrent files to the download
 * queue when they are detected in monitored folders.
 */
public class TorrentFolderMonitor implements FolderMonitorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(TorrentFolderMonitor.class);

    private final DownloadManager downloadManager;
    private final FolderMonitorService folderMonitorService;
    private final Path defaultDownloadDirectory;

    /**
     * Creates a new TorrentFolderMonitor.
     *
     * @param downloadManager          The download manager to add torrents to
     * @param folderMonitorService     The folder monitor service
     * @param defaultDownloadDirectory Default directory for torrent downloads
     */
    public TorrentFolderMonitor(DownloadManager downloadManager,
            FolderMonitorService folderMonitorService,
            Path defaultDownloadDirectory) {
        this.downloadManager = java.util.Objects.requireNonNull(downloadManager, "downloadManager");
        this.folderMonitorService = java.util.Objects.requireNonNull(folderMonitorService, "folderMonitorService");
        this.defaultDownloadDirectory = defaultDownloadDirectory; // null allowed; fallbacks in determineDownloadDestination

        // Register this monitor as a listener
        folderMonitorService.addFolderMonitorListener(this);
    }

    /**
     * Starts monitoring a folder for .torrent files with default settings.
     *
     * @param folderPath The folder to monitor
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startTorrentMonitoring(Path folderPath) {
        FolderMonitorSettings settings = createDefaultTorrentSettings();
        return folderMonitorService.startMonitoring(folderPath, settings);
    }

    /**
     * Starts monitoring a folder for .torrent files with custom settings.
     *
     * @param folderPath The folder to monitor
     * @param settings   Custom monitoring settings
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startTorrentMonitoring(Path folderPath, FolderMonitorSettings settings) {
        // Ensure torrent extension is included
        if (!settings.getFileExtensions().contains(".torrent")) {
            settings.addFileExtension(".torrent");
        }
        return folderMonitorService.startMonitoring(folderPath, settings);
    }

    /**
     * Stops monitoring a folder for torrent files.
     *
     * @param folderPath The folder to stop monitoring
     * @return A future that completes when monitoring stops
     */
    public CompletableFuture<Void> stopTorrentMonitoring(Path folderPath) {
        return folderMonitorService.stopMonitoring(folderPath);
    }

    /**
     * Creates default settings optimized for torrent file monitoring.
     *
     * @return Default torrent monitoring settings
     */
    public static FolderMonitorSettings createDefaultTorrentSettings() {
        return new FolderMonitorSettings()
                .addFileExtension(".torrent")
                .setRecursive(false)
                .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_TRASH)
                .setProcessExistingFiles(true)
                .setDebounceDelay(java.time.Duration.ofSeconds(2))
                .setEnabled(true)
                .setMaxFilesPerBatch(5)
                .setCaseSensitive(false)
                .setMinFileSize(100L) // At least 100 bytes for a valid torrent file
                .setMaxFileSize(10 * 1024 * 1024L); // Max 10MB for torrent files
    }

    /**
     * Creates settings for moving processed torrent files to a specific
     * directory.
     *
     * @param moveToDirectory The directory to move processed files to
     * @return Torrent monitoring settings with move action
     */
    public static FolderMonitorSettings createTorrentSettingsWithMove(Path moveToDirectory) {
        return createDefaultTorrentSettings()
                .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
                .setMoveToDirectory(moveToDirectory);
    }

    /**
     * Creates settings for deleting processed torrent files.
     *
     * @return Torrent monitoring settings with delete action
     */
    public static FolderMonitorSettings createTorrentSettingsWithDelete() {
        return createDefaultTorrentSettings()
                .setFileAction(FolderMonitorSettings.FileAction.DELETE);
    }

    @Override
    public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        if (isTorrentFile(filePath)) {
            processTorrentFile(folderPath, filePath, settings);
        }
    }

    @Override
    public void onFileModified(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        // For torrent files, we typically don't need to handle modifications
        // as they should be complete when first added
        if (isTorrentFile(filePath)) {
            LOGGER.debug("Torrent file modified (ignoring): " + filePath);
        }
    }

    @Override
    public void onFileProcessed(Path folderPath, Path filePath, FolderMonitorSettings.FileAction action,
            FolderMonitorSettings settings) {
        if (isTorrentFile(filePath)) {
            LOGGER.info("Torrent file processed with action " + action + ": " + filePath);
        }
    }

    @Override
    public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
        if (isTorrentFile(filePath)) {
            LOGGER.warn("Error processing torrent file: " + filePath, error);
        }
    }

    @Override
    public void onMonitoringStarted(Path folderPath, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".torrent")) {
            LOGGER.info("Started torrent monitoring for folder: " + folderPath);
        }
    }

    @Override
    public void onMonitoringStopped(Path folderPath, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".torrent")) {
            LOGGER.info("Stopped torrent monitoring for folder: " + folderPath);
        }
    }

    @Override
    public void onMonitoringError(Path folderPath, Throwable error, FolderMonitorSettings settings) {
        if (settings.getFileExtensions().contains(".torrent")) {
            LOGGER.error("Torrent monitoring error for folder: " + folderPath, error);
        }
    }

    /**
     * Processes a detected torrent file by adding it to the download manager.
     *
     * @param folderPath The monitored folder path
     * @param filePath   The torrent file path
     * @param settings   The monitoring settings
     */
    private void processTorrentFile(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        try {
            LOGGER.info("Processing torrent file: " + filePath);

            // Validate torrent file
            if (!isValidTorrentFile(filePath)) {
                LOGGER.warn("Invalid torrent file detected: " + filePath);
                return;
            }

            // Determine download destination
            Path downloadDestination = determineDownloadDestination(folderPath, settings);

            // Create torrent download
            Download torrentDownload = downloadManager.createTorrentDownload(filePath, downloadDestination);

            // Listener return is the folder service's acceptance boundary.
            // Wait for queue acceptance so a failed future prevents source
            // disposition and leaves the staged descriptor retryable.
            downloadManager.queueDownloadFromBackgroundSource(torrentDownload).join();
            LOGGER.info("Successfully added torrent to download queue: " + filePath);

        } catch (Exception e) {
            LOGGER.error("Error processing torrent file: " + filePath, e);
            throw new RuntimeException("Failed to process torrent file: " + filePath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks if a file is a torrent file based on its extension.
     *
     * @param filePath The file path to check
     * @return true if the file is a torrent file
     */
    private boolean isTorrentFile(Path filePath) {
        return Download.Protocol.fromPath(filePath) == Download.Protocol.TORRENT;
    }

    /**
     * Validates that a torrent file is properly formatted using bencode
     * structure checking.
     *
     * @param filePath The torrent file path
     * @return true if the file appears to be a valid torrent file
     */
    private boolean isValidTorrentFile(Path filePath) {
        try {
            // Basic size check - torrent files should be at least 100 bytes
            long fileSize = java.nio.file.Files.size(filePath);
            if (fileSize < 100) {
                return false;
            }

            // Read enough data to validate bencode structure
            byte[] data = new byte[Math.min(8192, (int) fileSize)];
            int bytesRead;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(filePath.toFile())) {
                bytesRead = fis.read(data);
            }

            if (bytesRead < 10) {
                return false;
            }

            // Check if file starts with 'd' (bencode dictionary start)
            if (data[0] != 'd') {
                return false;
            }

            // Parse basic bencode structure to validate torrent
            return validateBencodeStructure(data, bytesRead);

        } catch (Exception e) {
            LOGGER.warn("Error validating torrent file: " + filePath, e);
            return false;
        }
    }

    /**
     * Validates basic bencode structure and checks for required torrent fields.
     *
     * @param data   The torrent file data
     * @param length The length of valid data
     * @return true if the structure appears to be a valid torrent
     */
    private boolean validateBencodeStructure(byte[] data, int length) {
        try {
            String content = new String(data, 0, length, "ISO-8859-1");

            // Check for required torrent fields using improved detection
            boolean hasInfo = hasBencodeField(content, "info");

            // Check for basic bencode dictionary structure
            boolean hasValidStructure = content.startsWith("d")
                    && (content.contains("e") || length == data.length); // 'e' ends dictionary

            // Additional validation: check for common torrent fields
            boolean hasPieceLength = hasBencodeField(content, "piece length") || content.contains("piece lengthi");
            boolean hasPieces = hasBencodeField(content, "pieces");
            boolean hasName = hasBencodeField(content, "name");

            // Trackerless torrents are valid (for example when peers are found
            // through DHT), so announce fields cannot be mandatory.
            boolean isValidTorrent = hasInfo && hasValidStructure;

            // For more confidence, check if info dict contains expected fields
            if (isValidTorrent && (hasPieceLength || hasPieces || hasName)) {
                return true;
            }

            return isValidTorrent;

        } catch (Exception e) {
            LOGGER.debug("Error parsing bencode structure", e);
            return false;
        }
    }

    /**
     * Checks if a bencode field exists in the content by looking for the proper
     * length-prefixed format (e.g., "8:announce" for "announce").
     *
     * @param content The bencode content as string
     * @param field   The field name to look for
     * @return true if the field exists in proper bencode format
     */
    private boolean hasBencodeField(String content, String field) {
        String bencodeField = field.length() + ":" + field;
        return content.contains(bencodeField);
    }

    /**
     * Determines the download destination directory for a torrent.
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
     * torrents.
     *
     * @return A future that completes when monitoring starts
     */
    public CompletableFuture<Void> startDefaultTorrentMonitoring() {
        String userHome = System.getProperty("user.home");
        Path downloadsFolder = Paths.get(userHome, "Downloads");
        return startTorrentMonitoring(downloadsFolder);
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
     * Shuts down the torrent folder monitor.
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
