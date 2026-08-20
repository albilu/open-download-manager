package org.manager.download.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.aria2.Aria2Client;
import org.aria2.Aria2Client.Aria2RpcError;
import org.aria2.Aria2Client.Aria2RpcException;
import org.aria2.Aria2NotificationListener;
import org.aria2.Aria2Settings;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.aria2.Aria2ToolManager;

/**
 * Download handler for HTTP, FTP, BitTorrent, and Magnet link downloads using
 * aria2. This handler manages the communication with the aria2 RPC server and
 * provides progress tracking capabilities.
 *
 * <h3>Progress Tracking Mechanism</h3>
 * <p>
 * Unlike other clients that parse command-line output, the Aria2DownloadHandler
 * uses a <strong>polling-based approach</strong> because aria2 does NOT provide
 * built-in progress notifications via WebSocket. The progress tracking works as
 * follows:
 * </p>
 * <ul>
 * <li><strong>Polling Interval:</strong> Every 1000ms (1 second)</li>
 * <li><strong>RPC Method:</strong> Uses {@code aria2.tellStatus(gid, keys)} to
 * query specific status fields</li>
 * <li><strong>Status Fields:</strong> Extracts completedLength, totalLength,
 * downloadSpeed, and status</li>
 * <li><strong>Network Optimization:</strong> Requests only 4 essential fields
 * instead of all ~20 available fields</li>
 * <li><strong>Threading:</strong> Uses ScheduledExecutorService for concurrent
 * polling of multiple downloads</li>
 * <li><strong>Lifecycle:</strong> Polling starts when download begins and stops
 * when download completes/errors</li>
 * </ul>
 * <p>
 * <strong>Note:</strong> The {@code aria2.onDownloadProgress} in
 * Aria2NotificationListener is NOT a built-in aria2 notification - it's a
 * custom interface method that remains unused in favor of the polling mechanism
 * implemented in {@link #processProgressUpdate}.
 * </p>
 */
public class Aria2DownloadHandler extends AbstractDownloadHandler {

    private static final long PROGRESS_POLL_INTERVAL_MS = 1000;

    /**
     * Required keys for aria2.tellStatus to minimize data transfer. Only
     * requests the essential fields needed for progress tracking.
     *
     * <p>
     * <strong>Performance Benefit:</strong> aria2.tellStatus normally returns
     * ~20 fields including: bitfield, pieceLength, numPieces, connections,
     * errorCode, errorMessage, followedBy, following, belongsTo, dir, files,
     * bittorrent, verifiedLength, verifyIntegrityPending, etc. By requesting
     * only 4 fields, we reduce network overhead by ~80%.
     * </p>
     */
    private static final String[] REQUIRED_STATUS_KEYS = {
        "status", // Download status: active, waiting, paused, error, complete, removed
        "completedLength", // Bytes downloaded so far
        "totalLength", // Total file size in bytes
        "downloadSpeed", // Current download speed in bytes/second
        "uploadSpeed", // Current upload speed in bytes/second (BitTorrent)
        "connections", // Current connection count
        "numSeeders", // Connected seeder count (BitTorrent)
        "infoHash" // Torrent info hash (present for BitTorrent downloads)
    };

    private final Aria2Client aria2Client;
    private final Map<String, String> gidToIdMap; // aria2 GID -> download ID
    private final Map<String, ScheduledFuture<?>> pollTasks; // GID -> polling task
    private final Map<String, Download> activeDownloads; // download ID -> Download object
    private final ScheduledExecutorService progressPoller;
    private final AtomicBoolean isShuttingDown;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new Aria2DownloadHandler.
     *
     * @param globalSettings The global settings
     * @param settingsFactory The settings factory
     * @param executor The executor service for async operations
     * @param toolManagerFactory The tool manager factory for tool paths
     */
    public Aria2DownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        super(globalSettings, settingsFactory, executor);

        // Use ToolManagerFactory to get aria2 path
        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        String aria2Path = aria2Manager != null ? aria2Manager.getToolPath() : "aria2c";
        this.aria2Client = new Aria2Client(aria2Path);
        this.aria2Client.setUseWebSocket(true);
        this.gidToIdMap = new ConcurrentHashMap<>();
        this.activeDownloads = new ConcurrentHashMap<>();
        this.progressPoller = Executors.newScheduledThreadPool(1);
        this.pollTasks = new ConcurrentHashMap<>();
        this.isShuttingDown = new AtomicBoolean(false);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Download.Type getSupportedType() {
        // This handler primarily supports HTTP downloads
        // but also handles FTP, BitTorrent, and Magnet
        return Download.Type.ARIA2;
    }

    @Override
    public boolean canHandle(Download download) {
        if (download == null) {
            return false;
        }

        // Check if the download type is one we can handle
        Download.Type type = download.getType();
        // return type == Download.Type.HTTP
        // || type == Download.Type.FTP
        // || type == Download.Type.TORRENT
        // || type == Download.Type.MAGNET
        // || type == Download.Type.TOR;
        return type == Download.Type.ARIA2;
    }

    @Override
    public CompletableFuture<String> startDownload(Download download) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();

                // Set default destination if none provided
                setDefaultDestinationIfNeeded(download);

                // Override output
                overrideOutputPath(download);

                String gid = null;

                gid = switch (download.getType()) {
                    // case HTTP:
                    // case FTP:
                    // case TOR:
                    // gid = startHttpDownload(download);
                    // break;
                    // case TORRENT:
                    // gid = startTorrentDownload(download);
                    // break;
                    // case MAGNET:
                    // gid = startMagnetDownload(download);
                    // break;
                    case ARIA2 -> {
                        // For aria2 downloads, we can use the same method for HTTP/FTP
                        yield switch (download.getUri().getScheme()) {
                            // Descriptor files referenced over http(s) are fetched
                            // and added via addTorrent/addMetalink so mirrors and
                            // multi-file handling work like local files
                            case "http", "https" -> {
                                String path = download.getUri().getPath();
                                String lower = path != null ? path.toLowerCase() : "";
                                if (lower.endsWith(".torrent")) {
                                    yield startTorrentDownload(download);
                                }
                                if (lower.endsWith(".metalink") || lower.endsWith(".meta4")) {
                                    yield startMetaLinkDownload(download);
                                }
                                yield startHttpDownload(download);
                            }
                            case "ftp", "ftps" -> startHttpDownload(download);
                            // aria2 handles SFTP natively when built with
                            // libssh2 (Debian/Ubuntu builds are); credentials
                            // embedded in the URI userinfo are honored
                            case "sftp" -> startHttpDownload(download);
                            case "magnet" -> startMagnetDownload(download);
                            case "torrent" -> startTorrentDownload(download);
                            case "metalink" -> startMetaLinkDownload(download);
                            case "file" -> startLocalFileDownload(download);
                            default -> null;
                        };
                    }
                    default -> throw new IllegalArgumentException("Unsupported download type: " + download.getType());
                };

                if (gid != null) {
                    // Store the GID -> ID mapping
                    gidToIdMap.put(gid, download.getId());

                    // Store download reference for progress tracking
                    storeDownloadReference(download);

                    // Update download status and GID
                    download.setGid(gid);
                    download.setStatus(Download.Status.DOWNLOADING);

                    // Start progress polling
                    startProgressPolling(gid);

                    // Notify listeners
                    notifyDownloadStart(download);
                } else {
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage("Failed to start download with aria2");
                    notifyDownloadError(download, "Failed to start download with aria2");
                }

                return gid;
            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage(e.getMessage());
                notifyDownloadError(download, e.getMessage());
                LOGGER.log(Level.SEVERE, "Failed to start download: " + download.getName(), e);
                throw new RuntimeException("Failed to start download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                if (download.getGid() != null) {
                    aria2Client.pause(download.getGid());
                    download.setStatus(Download.Status.PAUSED);
                    notifyDownloadPause(download);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to pause download: " + download.getName(), e);
                throw new RuntimeException("Failed to pause download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                if (download.getGid() != null) {
                    aria2Client.unpause(download.getGid());
                    download.setStatus(Download.Status.DOWNLOADING);
                    notifyDownloadResume(download);

                    // Restart progress polling
                    startProgressPolling(download.getGid());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to resume download: " + download.getName(), e);
                throw new RuntimeException("Failed to resume download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                if (download.getGid() != null) {
                    // Stop progress polling
                    stopProgressPolling(download.getGid());

                    // Cancel the download
                    if (deleteFiles) {
                        aria2Client.forceRemove(download.getGid());
                    } else {
                        aria2Client.remove(download.getGid());
                    }

                    // Clean up mappings
                    gidToIdMap.remove(download.getGid());
                    removeDownloadReference(download.getId());

                    download.setStatus(Download.Status.CANCELED);
                    notifyDownloadCanceled(download);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to cancel download: " + download.getName(), e);
                throw new RuntimeException("Failed to cancel download", e);
            }
        }, executor);
    }

    @Override
    protected void doInitialize() throws Exception {
        // Start aria2 RPC server
        List<String> extraArgs = new ArrayList<>();
        extraArgs.add("--max-concurrent-downloads=" + globalSettings.getMaxConcurrentDownloads());

        // Convert KB/s to B/s for aria2
        long speedLimitBytesPerSec = globalSettings.getGlobalSpeedLimit() * 1024L;
        if (speedLimitBytesPerSec > 0) {
            extraArgs.add("--max-overall-download-limit=" + speedLimitBytesPerSec);
        }

        // Apply proxy settings if enabled
        if (globalSettings.isGlobalProxyEnabled() && globalSettings.getGlobalProxyAddress() != null) {
            extraArgs.add("--all-proxy=" + globalSettings.getGlobalProxyAddress());
        }

        // Extra BitTorrent trackers (comma-separated announce URLs) and the
        // re-announce interval, when configured
        String trackerList = trackerListSetting();
        if (!trackerList.isBlank()) {
            extraArgs.add("--bt-tracker=" + trackerList);
        }
        int trackerInterval = globalSettings.getIntProperty("tracker.refreshInterval", 0);
        if (trackerInterval > 0) {
            extraArgs.add("--bt-tracker-interval=" + (trackerInterval * 60L));
        }

        // Configure session management using global settings
        try {
            // Use default session file paths in downloads directory
            java.nio.file.Path downloadsDir = globalSettings.getDefaultDownloadDirectory();
            java.nio.file.Path sessionPath = downloadsDir.resolve("aria2-session.txt");
            java.nio.file.Path inputPath = downloadsDir.resolve("aria2-input.txt");

            // Ensure downloads directory exists
            if (!java.nio.file.Files.exists(downloadsDir)) {
                java.nio.file.Files.createDirectories(downloadsDir);
            }

            // Configure aria2 with session support
            extraArgs.add("--save-session=" + sessionPath.toString());
            extraArgs.add("--save-session-interval=60");

            // Load existing session if file exists
            if (java.nio.file.Files.exists(sessionPath)) {
                extraArgs.add("--input-file=" + sessionPath.toString());
                LOGGER.info("Loading aria2 session from: " + sessionPath);
            }

            LOGGER.info("Configured aria2 session management with file: " + sessionPath);
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Could not configure aria2 session management", e);
        }

        // Start aria2 with RPC enabled
        aria2Client.startAria2cWithRpc(extraArgs);

        // Register notification listener
        aria2Client.addNotificationListener(new Aria2NotificationAdapter());

        // Connect WebSocket for notifications
        aria2Client.connectWebSocket();

        LOGGER.info("aria2 RPC server started successfully with session management");
    }

    /**
     * Re-applies the configured extra tracker list to every active
     * BitTorrent download via per-download {@code aria2.changeOption}.
     * Called by the scheduled tracker refresh job.
     *
     * @return the number of downloads the tracker list was applied to
     */
    public int refreshTrackers() {
        String trackerList = trackerListSetting();
        if (trackerList.isBlank() || aria2Client == null) {
            return 0;
        }
        int applied = 0;
        for (Download download : activeDownloads.values()) {
            String gid = download.getGid();
            if (gid == null) {
                continue;
            }
            try {
                aria2Client.changeOption(gid, Map.of("bt-tracker", trackerList));
                applied++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to refresh trackers for GID " + gid, e);
            }
        }
        if (applied > 0) {
            LOGGER.fine("Refreshed trackers on " + applied + " download(s)");
        }
        return applied;
    }

    /**
     * Reads the configured tracker list, normalizing whitespace/newline
     * separators to the comma form aria2 expects.
     */
    private String trackerListSetting() {
        String raw = globalSettings.getProperty("tracker.list", "");
        if (raw.isBlank()) {
            return "";
        }
        return String.join(",", java.util.Arrays.stream(raw.split("[\\s,]+"))
                .filter(s -> !s.isBlank())
                .toArray(String[]::new));
    }

    /**
     * Pushes the current global settings (overall speed limit, concurrency,
     * and proxy) to the running aria2 daemon and to every active download,
     * so changes made in the settings dialog or via the Tor toggle affect
     * running transfers without a restart. Downloads that carry their own
     * per-download proxy are left untouched.
     */
    public void applyGlobalRuntimeOptions() {
        if (aria2Client == null) {
            return;
        }

        // Daemon-wide options
        try {
            Map<String, Object> globalOptions = new HashMap<>();
            // Convert KB/s to B/s for aria2; "0" clears a previously set limit
            long speedLimitBytesPerSec = globalSettings.getGlobalSpeedLimit() * 1024L;
            globalOptions.put("max-overall-download-limit",
                    speedLimitBytesPerSec > 0 ? String.valueOf(speedLimitBytesPerSec) : "0");
            globalOptions.put("max-concurrent-downloads",
                    String.valueOf(globalSettings.getMaxConcurrentDownloads()));
            aria2Client.changeGlobalOption(globalOptions);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to apply global options to aria2", e);
        }

        // Per-download proxy: global proxy applies unless the download has
        // its own proxy configured. An empty value clears a previous proxy.
        String globalProxy = globalSettings.isGlobalProxyEnabled()
                && globalSettings.getGlobalProxyAddress() != null
                        ? globalSettings.getGlobalProxyAddress()
                        : "";
        for (Download download : activeDownloads.values()) {
            String gid = download.getGid();
            if (gid == null) {
                continue;
            }
            if (download.getSettings() instanceof Aria2Settings aria2Settings
                    && aria2Settings.isUseProxy()
                    && aria2Settings.getProxyAddress() != null) {
                continue; // per-download proxy wins
            }
            try {
                aria2Client.changeOption(gid, Map.of("all-proxy", globalProxy));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to update proxy option for GID " + gid, e);
            }
        }
    }

    /**
     * Saves the current aria2 session.
     */
    public void saveSession() throws Exception {
        if (aria2Client != null) {
            aria2Client.saveSession();
            LOGGER.info("Saved aria2 session");
        }
    }

    /**
     * Loads aria2 session from the specified file.
     */
    public void loadSession(java.nio.file.Path sessionFile) throws Exception {
        // Session loading is handled during aria2 startup via --input-file
        // This method is kept for compatibility
        LOGGER.info("Session loading is handled during aria2 startup");
    }

    @Override
    protected void doShutdown() throws Exception {
        // Mark as shutting down to prevent new tasks FIRST
        isShuttingDown.set(true);

        // Stop all progress polling
        stopAllProgressPolling();

        // Save session before disconnecting
        try {
            saveSession();
            LOGGER.info("Saved aria2 session before shutdown");
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Failed to save aria2 session during shutdown", e);
        }

        // Gracefully shutdown aria2 RPC server FIRST (this will close connections properly)
        try {
            aria2Client.shutdown(); // Graceful RPC shutdown
            Thread.sleep(2000); // Give it time to shutdown gracefully
            LOGGER.info("Aria2 RPC graceful shutdown completed");
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Graceful aria2 shutdown failed, forcing disconnect", e);
        }

        // Then disconnect WebSocket (should not trigger reconnection now)
        try {
            aria2Client.disconnectWebSocket();
            Thread.sleep(500); // Allow WebSocket to close cleanly
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Error disconnecting WebSocket", e);
        }

        // Finally force stop the process if still running
        try {
            aria2Client.stopAria2c();
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Error stopping aria2 process", e);
        }

        // Shutdown progress poller
        progressPoller.shutdown();
        if (!progressPoller.awaitTermination(5, TimeUnit.SECONDS)) {
            progressPoller.shutdownNow();
        }

        LOGGER.info("aria2 download handler shut down successfully");
    }

    /**
     * Starts polling for progress updates for a download.
     *
     * @param gid The aria2 GID of the download
     */
    private void startProgressPolling(String gid) {
        if (isShuttingDown.get()) {
            return;
        }

        ScheduledFuture<?> task = progressPoller.scheduleAtFixedRate(() -> {
            try {
                pollDownloadProgress(gid);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error polling download progress for GID " + gid, e);
            }
        }, 0, PROGRESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

        pollTasks.put(gid, task);
    }

    /**
     * Stops polling for progress updates for a download.
     *
     * @param gid The aria2 GID of the download
     */
    private void stopProgressPolling(String gid) {
        ScheduledFuture<?> task = pollTasks.remove(gid);
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Stops all progress polling tasks.
     */
    private void stopAllProgressPolling() {
        for (String gid : new ArrayList<>(pollTasks.keySet())) {
            stopProgressPolling(gid);
        }

        // Clear all download references
        activeDownloads.clear();
    }

    /**
     * Polls for download progress using aria2.tellStatus.
     *
     * @param gid The aria2 GID of the download
     */
    private void pollDownloadProgress(String gid) {
        try {
            // Skip if we're shutting down
            if (isShuttingDown.get()) {
                return;
            }

            // Get the download ID from GID
            String downloadId = gidToIdMap.get(gid);
            if (downloadId == null) {
                stopProgressPolling(gid);
                return;
            }

            // Get status from aria2 with only required fields for better performance
            // This reduces network overhead by requesting only essential progress data
            String statusJson = aria2Client.tellStatus(gid, REQUIRED_STATUS_KEYS);

            @SuppressWarnings("unchecked")
            Map<String, Object> status = objectMapper.readValue(statusJson, Map.class);

            if (status != null) {
                // Notify progress update
                processProgressUpdate(downloadId, gid, status);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error polling download progress: " + e.getMessage(), e);
        }
    }

    /**
     * Processes a progress update from aria2.
     *
     * @param downloadId The download ID
     * @param gid The aria2 GID
     * @param status The status information from aria2
     */
    private void processProgressUpdate(String downloadId, String gid, Map<String, Object> status) {
        try {
            // Get the download object
            Download download = getDownloadById(downloadId);
            if (download == null) {
                LOGGER.warning("Download not found for ID: " + downloadId);
                stopProgressPolling(gid);
                return;
            }

            // Extract aria2 status fields
            String downloadStatus = (String) status.get("status");
            String completedLengthStr = (String) status.get("completedLength");
            String totalLengthStr = (String) status.get("totalLength");
            String downloadSpeedStr = (String) status.get("downloadSpeed");

            // Parse numeric values
            long completedLength = Long.parseLong(completedLengthStr != null ? completedLengthStr : "0");
            long totalLength = Long.parseLong(totalLengthStr != null ? totalLengthStr : "0");
            float downloadSpeed = Float.parseFloat(downloadSpeedStr != null ? downloadSpeedStr : "0");

            // Extended detail fields (upload speed, connections, seeders, info hash)
            String uploadSpeedStr = (String) status.get("uploadSpeed");
            String connectionsStr = (String) status.get("connections");
            String numSeedersStr = (String) status.get("numSeeders");
            String infoHash = (String) status.get("infoHash");

            // Update download object
            download.setDownloaded(completedLength);
            download.setSize(totalLength);
            download.setSpeed(downloadSpeed);
            download.setUploadSpeed(Float.parseFloat(uploadSpeedStr != null ? uploadSpeedStr : "0"));
            download.setConnectionCount(connectionsStr != null ? Integer.parseInt(connectionsStr) : 0);
            download.setSeeders(numSeedersStr != null ? Integer.parseInt(numSeedersStr) : 0);
            if (infoHash != null && !infoHash.isBlank()) {
                download.setInfoHash(infoHash);
            }

            // Calculate progress percentage
            float progress = 0;
            if (totalLength > 0) {
                progress = (float) completedLength / totalLength * 100;
            }

            // Update download status based on aria2 status
            updateDownloadStatus(download, downloadStatus, gid);

            // Notify listeners about progress
            notifyDownloadProgress(download, progress, completedLength, totalLength, downloadSpeed);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing progress update for GID " + gid, e);
        }
    }

    /**
     * Updates the download status based on aria2 status and triggers
     * appropriate notifications.
     *
     * @param download The download object to update
     * @param aria2Status The status from aria2 ("active", "waiting", "paused",
     * "error", "complete", "removed")
     * @param gid The aria2 GID for cleanup operations
     */
    private void updateDownloadStatus(Download download, String aria2Status, String gid) {
        if (aria2Status == null) {
            return;
        }

        LOGGER.info("Aria2 status update - GID: " + gid + ", Download: " + download.getName()
                + ", Current status: " + download.getStatus() + ", Aria2 status: " + aria2Status);

        switch (aria2Status) {
            case "active":
                if (download.getStatus() != Download.Status.DOWNLOADING) {
                    LOGGER.info("Setting download status to DOWNLOADING for: " + download.getName());
                    download.setStatus(Download.Status.DOWNLOADING);
                }
                break;
            case "waiting":
                // CRITICAL FIX: Don't change status to QUEUED if download is already
                // DOWNLOADING
                // This prevents infinite loop where active downloads get reset to QUEUED
                if (download.getStatus() == Download.Status.DOWNLOADING) {
                    LOGGER.info("Ignoring 'waiting' status for active download: " + download.getName()
                            + " (keeping DOWNLOADING status)");
                } else if (download.getStatus() != Download.Status.QUEUED) {
                    LOGGER.info("Setting download status to QUEUED for: " + download.getName());
                    download.setStatus(Download.Status.QUEUED);
                }
                break;
            case "paused":
                if (download.getStatus() != Download.Status.PAUSED) {
                    download.setStatus(Download.Status.PAUSED);
                    notifyDownloadPause(download);
                }
                break;
            case "error":
                download.setStatus(Download.Status.ERROR);
                String errorMessage = "Aria2 download error for GID: " + gid;
                download.setErrorMessage(errorMessage);
                notifyDownloadError(download, errorMessage);
                stopProgressPolling(gid);
                // Clean up mappings
                String downloadId = gidToIdMap.remove(gid);
                if (downloadId != null) {
                    removeDownloadReference(downloadId);
                }
                break;
            case "complete":
                download.setStatus(Download.Status.COMPLETED);
                notifyDownloadComplete(download);
                stopProgressPolling(gid);
                // Clean up mappings
                String completedDownloadId = gidToIdMap.remove(gid);
                if (completedDownloadId != null) {
                    removeDownloadReference(completedDownloadId);
                }
                break;
            case "removed":
                download.setStatus(Download.Status.CANCELED);
                notifyDownloadCanceled(download);
                stopProgressPolling(gid);
                // Clean up mappings
                String canceledDownloadId = gidToIdMap.remove(gid);
                if (canceledDownloadId != null) {
                    removeDownloadReference(canceledDownloadId);
                }
                break;
            default:
                LOGGER.warning("Unknown aria2 status: " + aria2Status + " for GID: " + gid);
                break;
        }
    }

    /**
     * Gets a download by its ID from the active downloads map.
     *
     * @param downloadId The download ID
     * @return The download object or null if not found
     */
    private Download getDownloadById(String downloadId) {
        return activeDownloads.get(downloadId);
    }

    /**
     * Stores a download reference for progress tracking.
     *
     * @param download The download to store
     */
    private void storeDownloadReference(Download download) {
        activeDownloads.put(download.getId(), download);
    }

    /**
     * Removes a download reference from tracking.
     *
     * @param downloadId The download ID to remove
     */
    private void removeDownloadReference(String downloadId) {
        activeDownloads.remove(downloadId);
    }

    /**
     * Starts an HTTP or FTP download using aria2.
     *
     * @param download The download to start
     * @return The aria2 GID of the download
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private String startHttpDownload(Download download) throws IOException, Aria2RpcException {
        LOGGER.info("Starting HTTP download for: " + download.getUri());
        Map<String, Object> options = new HashMap<>();
        options.put("dir", download.getDestination().toString());

        // Get settings from download using pattern matching
        switch (download.getSettings()) {
            case Aria2Settings aria2Settings -> {
                // Use the toRpcOptions method to get properly formatted RPC options
                options.putAll(aria2Settings.toRpcOptions());
            }
            case null, default -> {
                // For non-Aria2Settings, get options from the settings map
                Map<String, String> settingsMap = download.getSettings().toMap();
                for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                    options.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Add mirrors if available: aria2 treats all URIs of a single addUri
        // call as mirrors of one download with automatic failover.
        List<String> uris = new ArrayList<>();
        uris.add(download.getUri().toString());
        for (URI mirror : download.getMirrors()) {
            uris.add(mirror.toString());
        }

        // Start download with aria2 as ONE multi-source task
        String[] uriArray = uris.toArray(new String[0]);
        LOGGER.info("Calling aria2.addUri for: " + uriArray[0] + " with " + uriArray.length
                + " URI(s) and options: " + options);
        String gid = aria2Client.addUriRpc(uriArray, options);
        LOGGER.info("aria2.addUri returned GID: " + gid);

        return gid;
    }

    /**
     * Starts a torrent download using aria2.
     *
     * @param download The download to start
     * @return The aria2 GID of the download
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private String startTorrentDownload(Download download) throws IOException, Aria2RpcException {
        URI torrentUri = download.getUri();

        // Handle different URI schemes for torrent files
        byte[] torrentData;
        String torrentSource;
        if ("file".equals(torrentUri.getScheme())) {
            // Local file path
            Path torrentFile = Paths.get(torrentUri);
            torrentData = readLocalFile(torrentFile, "Torrent");
            torrentSource = torrentFile.toString();
        } else if ("torrent".equals(torrentUri.getScheme())) {
            // Custom torrent: scheme - extract file path from URI
            Path torrentFile = Paths.get(torrentUri.getSchemeSpecificPart());
            torrentData = readLocalFile(torrentFile, "Torrent");
            torrentSource = torrentFile.toString();
        } else if ("http".equals(torrentUri.getScheme()) || "https".equals(torrentUri.getScheme())) {
            // Remote torrent file: fetch it into memory, then hand the bytes
            // to aria2's addTorrent
            torrentData = fetchRemoteBytes(torrentUri);
            torrentSource = torrentUri.toString();
        } else {
            throw new IOException("Unsupported torrent source URI: " + torrentUri);
        }

        // Prepare URI list for trackers/mirrors
        List<String> uris = new ArrayList<>();
        // Add any mirror URIs if available
        for (URI mirror : download.getMirrors()) {
            uris.add(mirror.toString());
        }

        // Get settings from download if available using pattern matching
        Map<String, Object> options = new HashMap<>();
        if (download.getSettings() instanceof Aria2Settings aria2Settings) {
            options.putAll(aria2Settings.toRpcOptions());
        } else if (download.getSettings() != null) {
            // For non-Aria2Settings, get options from the settings map
            Map<String, String> settingsMap = download.getSettings().toMap();
            for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                options.put(entry.getKey(), entry.getValue());
            }
        }

        // Use the existing addTorrent method from aria2Client
        // Parameters: byte[] torrent, List<String> uris, String dir, Map options
        String gid = aria2Client.addTorrent(torrentData, uris, download.getDestination().toString(), options);

        LOGGER.info("Started torrent download with GID: " + gid + " for source: " + torrentSource);
        return gid;
    }

    /**
     * Starts a magnet link download using aria2.
     *
     * @param download The download to start
     * @return The aria2 GID of the download
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private String startMagnetDownload(Download download) throws IOException, Aria2RpcException {
        Map<String, Object> options = new HashMap<>();
        options.put("dir", download.getDestination().toString());

        // Get settings from download using pattern matching
        switch (download.getSettings()) {
            case Aria2Settings aria2Settings -> {
                // Use the toRpcOptions method to get properly formatted RPC options
                options.putAll(aria2Settings.toRpcOptions());
            }
            case null, default -> {
                // For non-Aria2Settings, get options from the settings map
                Map<String, String> settingsMap = download.getSettings().toMap();
                for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                    options.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Start download with aria2
        String gid = aria2Client.addUriRpc(download.getUri().toString(), options);
        return gid;
    }

    /**
     * Routes a local file:// URI to the right aria2 entry point by extension:
     * .torrent goes to addTorrent, .metalink/.meta4 to addMetalink. Anything
     * else is not a downloadable local file and yields no GID.
     *
     * @param download The download to start
     * @return The aria2 GID, or null if the file type is unsupported
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private String startLocalFileDownload(Download download) throws IOException, Aria2RpcException {
        String path = download.getUri().getPath();
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".torrent")) {
            return startTorrentDownload(download);
        }
        if (lower.endsWith(".metalink") || lower.endsWith(".meta4")) {
            return startMetaLinkDownload(download);
        }
        LOGGER.warning("Unsupported local file type for aria2 download: " + path);
        return null;
    }

    /**
     * Starts a Metalink download using aria2's addMetalink RPC: the Metalink
     * XML is read from a local or remote (.metalink/.meta4) file and sent to
     * aria2, which handles mirror selection and segmented download itself.
     *
     * @param download The download to start (uri points to a local or remote
     *            .metalink/.meta4 file)
     * @return The aria2 GID of the download
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private String startMetaLinkDownload(Download download) throws IOException, Aria2RpcException {
        URI metaLinkUri = download.getUri();

        // Handle different URI schemes for metalink files
        byte[] metaLinkData;
        String metaLinkSource;
        if ("file".equals(metaLinkUri.getScheme())) {
            Path metaLinkFile = Paths.get(metaLinkUri);
            metaLinkData = readLocalFile(metaLinkFile, "Metalink");
            metaLinkSource = metaLinkFile.toString();
        } else if ("metalink".equals(metaLinkUri.getScheme())) {
            // Custom metalink: scheme - extract file path from URI
            Path metaLinkFile = Paths.get(metaLinkUri.getSchemeSpecificPart());
            metaLinkData = readLocalFile(metaLinkFile, "Metalink");
            metaLinkSource = metaLinkFile.toString();
        } else if ("http".equals(metaLinkUri.getScheme()) || "https".equals(metaLinkUri.getScheme())) {
            // Remote Metalink file: fetch it into memory and hand the bytes
            // to aria2's addMetalink
            metaLinkData = fetchRemoteBytes(metaLinkUri);
            metaLinkSource = metaLinkUri.toString();
        } else {
            throw new IOException("Unsupported Metalink source URI: " + metaLinkUri);
        }

        Map<String, Object> options = new HashMap<>();
        options.put("dir", download.getDestination().toString());

        // Get settings from download using pattern matching
        switch (download.getSettings()) {
            case Aria2Settings aria2Settings -> {
                // Use the toRpcOptions method to get properly formatted RPC options
                options.putAll(aria2Settings.toRpcOptions());
            }
            case null, default -> {
                // For non-Aria2Settings, get options from the settings map
                if (download.getSettings() != null) {
                    Map<String, String> settingsMap = download.getSettings().toMap();
                    for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                        options.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        String gid = aria2Client.addMetalink(metaLinkData, options);
        LOGGER.info("Started Metalink download with GID: " + gid + " for source: " + metaLinkSource);
        return gid;
    }

    /**
     * Reads a local descriptor file (.torrent/.metalink) after verifying it
     * exists and is readable.
     *
     * @param file the file to read
     * @param kind human-readable kind used in error messages
     * @return the file content
     * @throws IOException if the file is missing, unreadable, or reading fails
     */
    private static byte[] readLocalFile(Path file, String kind) throws IOException {
        if (!Files.exists(file) || !Files.isReadable(file)) {
            throw new IOException(kind + " file not found or not readable: " + file);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IOException("Failed to read " + kind + " file: " + file, e);
        }
    }

    /**
     * Upper bound for remote descriptor files (.torrent/.metalink) fetched
     * into memory. Real descriptors are a few KB; anything larger is treated
     * as a misconfigured URL pointing at the actual payload.
     */
    private static final long MAX_REMOTE_DESCRIPTOR_BYTES = 16L * 1024 * 1024;

    /**
     * Fetches a small remote descriptor (a .torrent or Metalink file) into
     * memory using the JDK built-in HTTP client, following redirects.
     *
     * @param uri the http/https URI to fetch
     * @return the file content
     * @throws IOException on connection failure, non-2xx response, empty body,
     *             or a body exceeding {@link #MAX_REMOTE_DESCRIPTOR_BYTES}
     */
    private static byte[] fetchRemoteBytes(URI uri) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + uri, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to fetch " + uri + ": HTTP " + response.statusCode());
        }
        try (InputStream in = response.body();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            byte[] data = out.toByteArray();
            if (data.length == 0) {
                throw new IOException("Remote descriptor is empty: " + uri);
            }
            if (data.length > MAX_REMOTE_DESCRIPTOR_BYTES) {
                throw new IOException("Remote descriptor exceeds " + MAX_REMOTE_DESCRIPTOR_BYTES
                        + " bytes (not a .torrent/.metalink file?): " + uri);
            }
            return data;
        }
    }

    /**
     * Gets full status information for debugging purposes. This method requests
     * all available fields from aria2.tellStatus.
     *
     * @param gid The aria2 GID of the download
     * @return Full status JSON or null if error occurs
     */
    private String getFullStatusForDebugging(String gid) {
        try {
            // Request all fields (no keys parameter)
            return aria2Client.tellStatus(gid);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get full status for debugging: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fetches the current peer list for a BitTorrent download (aria2.getPeers).
     *
     * @param download the download (must have a GID)
     * @return list of peer detail maps; empty when unavailable or not BT
     */
    public List<Map<String, Object>> getDownloadPeers(Download download) {
        String gid = download.getGid();
        if (gid == null) {
            return List.of();
        }
        try {
            return aria2Client.getPeers(gid);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get peers for gid " + gid, e);
            return List.of();
        }
    }

    /**
     * Fetches the file list of a download (aria2.getFiles).
     *
     * @param download the download (must have a GID)
     * @return list of file detail maps; empty when unavailable
     */
    public List<Map<String, Object>> getDownloadFiles(Download download) {
        String gid = download.getGid();
        if (gid == null) {
            return List.of();
        }
        try {
            return aria2Client.getFiles(gid);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get files for gid " + gid, e);
            return List.of();
        }
    }

    /**
     * Fetches the tracker announce tiers of a BitTorrent download (from
     * tellStatus.bittorrent.announceList).
     *
     * @param download the download (must have a GID)
     * @return list of tracker tiers, each a list of announce URLs; empty otherwise
     */
    public List<List<String>> getDownloadTrackers(Download download) {
        String gid = download.getGid();
        if (gid == null) {
            return List.of();
        }
        try {
            String json = aria2Client.tellStatus(gid, new String[]{"bittorrent"});
            @SuppressWarnings("unchecked")
            Map<String, Object> status = objectMapper.readValue(json, Map.class);
            Object bt = status.get("bittorrent");
            if (bt instanceof Map<?, ?> btMap) {
                Object announce = btMap.get("announceList");
                if (announce instanceof List<?> tiers) {
                    List<List<String>> trackers = new java.util.ArrayList<>();
                    for (Object tier : tiers) {
                        if (tier instanceof List<?> urls) {
                            trackers.add(urls.stream().map(String::valueOf).toList());
                        }
                    }
                    return trackers;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to get trackers for gid " + gid, e);
        }
        return List.of();
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                // Only apply when the download is active (has a gid); otherwise
                // the new settings stored on the Download apply on (re)start.
                String gid = download.getGid();
                if (gid == null) {
                    return;
                }

                Map<String, Object> options = new HashMap<>();
                switch (download.getSettings()) {
                    case Aria2Settings aria2Settings ->
                        options.putAll(aria2Settings.toRpcOptions());
                    case null, default -> {
                        if (download.getSettings() != null) {
                            Map<String, String> settingsMap = download.getSettings().toMap();
                            for (Map.Entry<String, String> entry : settingsMap.entrySet()) {
                                options.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }

                if (!options.isEmpty()) {
                    aria2Client.changeOption(gid, options);
                    LOGGER.fine("Changed aria2 options for gid " + gid + ": " + options.keySet());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to change settings for download: " + download.getName(), e);
                throw new RuntimeException("Failed to change aria2 settings", e);
            }
        }, executor);
    }

    /**
     * Adapter class to connect aria2 notifications to our download handler.
     */
    private class Aria2NotificationAdapter implements Aria2NotificationListener {

        @Override
        public void onDownloadStart(String gid) {
            String downloadId = gidToIdMap.get(gid);
            if (downloadId != null) {
                // This is handled by startDownload method
            }
        }

        @Override
        public void onDownloadPause(String gid) {
            String downloadId = gidToIdMap.get(gid);
            if (downloadId != null) {
                // This is handled by pauseDownload method
            }
        }

        @Override
        public void onDownloadStop(String gid) {
            String downloadId = gidToIdMap.get(gid);
            if (downloadId != null) {
                // Handle download stop event
            }
        }

        @Override
        public void onDownloadComplete(String gid) {
            String downloadId = gidToIdMap.get(gid);
            if (downloadId != null) {
                // Handle download complete event
                stopProgressPolling(gid);
            }
        }

        @Override
        public void onDownloadError(String gid, Aria2RpcError error) {
            String downloadId = gidToIdMap.get(gid);
            if (downloadId != null) {
                // Handle download error event
                stopProgressPolling(gid);
            }
        }

        @Override
        public void onBtDownloadComplete(String gid) {
            onDownloadComplete(gid);
        }

//        @Override
//        public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//            // This is not used - progress is handled by our polling mechanism
//        }
    }
}
