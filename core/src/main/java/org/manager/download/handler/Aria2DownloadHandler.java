package org.manager.download.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aria2.Aria2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.aria2.Aria2Client.Aria2RpcError;
import org.aria2.Aria2Client.Aria2RpcException;
import org.aria2.Aria2GlobalOptions;
import org.aria2.Aria2NotificationListener;
import org.aria2.Aria2Settings;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DescriptorFileInspector;
import org.manager.download.DownloadFileInfo;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.manager.util.DescriptorStaging;
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
 * <li><strong>Status Fields:</strong> Extracts progress, transfer, output,
 * relationship, and integrity-check activity fields</li>
 * <li><strong>Network Optimization:</strong> Requests only the compact field
 * set used by ODM instead of every available field</li>
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
     * Allows several regular progress polls to observe a short integrity
     * check. If aria2 finishes between polls, the operation is treated as
     * accepted after this grace period rather than leaving UI activity stuck.
     */
    private static final long RECHECK_OBSERVATION_GRACE_MS =
            PROGRESS_POLL_INTERVAL_MS * 3;

    /**
     * Required keys for aria2.tellStatus to minimize data transfer. Only
     * requests the essential fields needed for progress tracking.
     *
     * <p>
     * <strong>Performance Benefit:</strong> aria2.tellStatus normally returns
     * all available fields. Requesting this compact set avoids unrelated
     * payload while retaining progress, file discovery, and integrity-check
     * activity.
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
        "seeder", // True when the local BitTorrent task is only seeding
        "infoHash", // Torrent info hash (present for BitTorrent downloads)
        "files", // Authoritative output artifact paths
        "followedBy", // GIDs spawned by this one (BT metadata -> payload)
        "following", // GID this one was spawned by
        "belongsTo", // Parent GID (e.g. metadata download of a payload)
        "verifiedLength", // Bytes examined while aria2 is checking hashes
        "verifyIntegrityPending", // True while a requested hash check is queued
        "errorCode", // Numeric aria2 failure category for actionable diagnostics
        "errorMessage" // Engine-provided failure detail
    };

    private final Aria2Client aria2Client;
    private final Map<String, String> gidToIdMap; // aria2 GID -> download ID
    /** GIDs intentionally removed during an engine route handoff. */
    private final java.util.Set<String> routeChangeGids;
    /** GIDs already told to stop seeding because user seeding is disabled. */
    private final java.util.Set<String> seedingStopRequests;
    private final java.util.Set<String> pollTasks; // GIDs currently polled by the batch task
    /** The single shared batch-poll task covering every GID in pollTasks. */
    private volatile ScheduledFuture<?> batchPollTask;
    private final Map<String, Download> activeDownloads; // download ID -> Download object
    /**
     * Every aria2 GID belonging to a download (metalink files, magnet
     * metadata + followedBy children). A download is only complete when
     * this set drains.
     */
    private final Map<String, java.util.Set<String>> downloadGids;
    /** Every GID ever tracked for a download (including retired ones). */
    private final Map<String, java.util.Set<String>> downloadSeenGids;
    /** Last-known per-GID transfer values, aggregated into one ODM record. */
    private final Map<String, Map<String, GidTransferStats>> downloadProgress;
    /** Approximate integrity-check activity for the current request per download. */
    private final Map<String, RecheckObservation> recheckObservations;
    private final ScheduledExecutorService progressPoller;
    private final AtomicBoolean isShuttingDown;
    private final ObjectMapper objectMapper;

    private record GidTransferStats(long completed, long total,
            long downloadSpeed, long uploadSpeed, int connections, int seeders) {
    }

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
        this(globalSettings, settingsFactory, executor,
                createAria2Client(globalSettings, toolManagerFactory),
                Executors.newScheduledThreadPool(1));
    }

    /** Package-private injection seam for deterministic RPC-routing tests. */
    Aria2DownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            Aria2Client aria2Client,
            ScheduledExecutorService progressPoller) {
        super(globalSettings, settingsFactory, executor);
        this.aria2Client = Objects.requireNonNull(aria2Client, "aria2Client");
        this.gidToIdMap = new ConcurrentHashMap<>();
        this.routeChangeGids = ConcurrentHashMap.newKeySet();
        this.seedingStopRequests = ConcurrentHashMap.newKeySet();
        this.activeDownloads = new ConcurrentHashMap<>();
        this.downloadGids = new ConcurrentHashMap<>();
        this.downloadSeenGids = new ConcurrentHashMap<>();
        this.downloadProgress = new ConcurrentHashMap<>();
        this.recheckObservations = new ConcurrentHashMap<>();
        this.progressPoller = Objects.requireNonNull(progressPoller, "progressPoller");
        this.pollTasks = ConcurrentHashMap.newKeySet();
        this.isShuttingDown = new AtomicBoolean(false);
        this.objectMapper = new ObjectMapper();
    }

    private static Aria2Client createAria2Client(GlobalSettings globalSettings,
            ToolManagerFactory toolManagerFactory) {
        Aria2ToolManager aria2Manager = toolManagerFactory.getAria2Manager();
        String aria2Path = aria2Manager != null ? aria2Manager.getToolPath() : "aria2c";
        // A user-configured RPC secret (aria2.rpcSecret) is the ONLY way an
        // already-running external daemon gets adopted: the client probes
        // the endpoint with these credentials before starting a child.
        String configuredRpcSecret = globalSettings.getProperty("aria2.rpcSecret", null);
        if (configuredRpcSecret != null && configuredRpcSecret.isBlank()) {
            configuredRpcSecret = null;
        }
        int rpcPort = globalSettings.getAria2RpcPort();
        Aria2Client client = new Aria2Client(aria2Path,
                "http://localhost:" + rpcPort + "/jsonrpc", configuredRpcSecret);
        client.setHonorExternalConfiguration(
                globalSettings.isHonorExternalAria2Configuration());
        return client;
    }

    @Override
    public Download.Type getSupportedType() {
        // This handler primarily supports HTTP downloads
        // but also handles FTP, SFTP, BitTorrent, and Magnet
        return Download.Type.ARIA2;
    }

    /**
     * The handler's aria2 client. Same-process access for verification and
     * detail queries that bypass the handler's coarse API.
     *
     * @return the live client instance
     */
    public Aria2Client getAria2Client() {
        return aria2Client;
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

                List<String> gids = switch (download.getType()) {
                    case ARIA2 -> {
                        // Protocol captures descriptor semantics independently
                        // of transport: remote and local descriptor files take
                        // the same addTorrent/addMetalink route.
                        yield switch (download.getProtocol()) {
                            case HTTP, HTTPS, FTP, SFTP -> startUriDownload(download);
                            case MAGNET -> startMagnetDownload(download);
                            case TORRENT -> startTorrentDownload(download);
                            case METALINK -> startMetaLinkDownload(download);
                            case null -> {
                                String scheme = download.getUri().getScheme();
                                LOGGER.warn("Unsupported URI protocol for aria2 download: " + scheme);
                                yield null;
                            }
                        };
                    }
                    default -> throw new IllegalArgumentException("Unsupported download type: " + download.getType());
                };

                if (gids != null && !gids.isEmpty()) {
                    registerTrackedDownload(download, gids);

                    Download.Status previousStatus = download.getStatus();
                    download.setStatus(Download.Status.DOWNLOADING);
                    if (previousStatus != Download.Status.DOWNLOADING) {
                        notifyDownloadStatusChanged(download, previousStatus,
                                Download.Status.DOWNLOADING);
                    }

                    for (String gid : gids) {
                        startProgressPolling(gid);
                    }

                    // Notify listeners
                    notifyDownloadStart(download);
                } else {
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage("Failed to start download with aria2");
                    notifyDownloadError(download, "Failed to start download with aria2");
                }

                return gids != null && !gids.isEmpty() ? gids.get(0) : null;
            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage(e.getMessage());
                notifyDownloadError(download, e.getMessage());
                LOGGER.error("Failed to start download: " + download.getName(), e);
                throw new RuntimeException("Failed to start download", e);
            }
        }, executor);
    }

    /**
     * Registers every GID of a download for polling and mapping. The first
     * GID becomes the download's primary GID.
     *
     * @param download the download to track
     * @param gids every aria2 GID the download consists of
     */
    void registerTrackedDownload(Download download, List<String> gids) {
        java.util.Set<String> tracked = ConcurrentHashMap.newKeySet();
        tracked.addAll(gids);
        java.util.Set<String> seen = ConcurrentHashMap.newKeySet();
        seen.addAll(gids);
        for (String gid : gids) {
            gidToIdMap.put(gid, download.getId());
        }
        downloadGids.put(download.getId(), tracked);
        downloadSeenGids.put(download.getId(), seen);
        downloadProgress.put(download.getId(), new ConcurrentHashMap<>());
        download.setGid(gids.get(0));
        storeDownloadReference(download);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                List<String> gids = trackedGidsSnapshot(download);
                applyToEveryGid(gids, "pause", aria2Client::pause);
                download.setStatus(Download.Status.PAUSED);
                notifyDownloadPause(download);
            } catch (Exception e) {
                LOGGER.error("Failed to pause download: " + download.getName(), e);
                throw new RuntimeException("Failed to pause download", e);
            }
        }, executor);
    }

    /**
     * Removes aria2's live task while retaining partial files, without
     * publishing a canceled event. A normal pause is insufficient for a
     * route handoff because the paused GID would remain owned by aria2 while
     * proxychains starts a second writer for the same output.
     */
    @Override
    public CompletableFuture<Void> stopForRouteChange(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();
                List<String> gids = trackedGidsSnapshot(download);
                routeChangeGids.addAll(gids);
                for (String gid : gids) {
                    stopProgressPolling(gid);
                }
                try {
                    forceRemoveEveryGidForRouteChange(gids);
                    untrackEntireDownload(download.getId());
                } catch (Exception routeFailure) {
                    for (String gid : gids) {
                        if (gidToIdMap.containsKey(gid)) {
                            startProgressPolling(gid);
                        }
                    }
                    throw routeFailure;
                } finally {
                    routeChangeGids.removeAll(gids);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to stop aria2 download for route change: "
                        + download.getName(), e);
                throw new RuntimeException("Failed to stop aria2 download for route change", e);
            }
        }, executor);
    }

    /**
     * Route handoff is stricter than ordinary multi-GID controls: every old
     * task must be gone before another engine can write the same outputs.
     * Try all GIDs so one failure does not strand its siblings, but surface
     * any failure instead of accepting the partial success policy used by
     * pause and settings updates.
     */
    private void forceRemoveEveryGidForRouteChange(List<String> gids) throws Exception {
        Exception firstFailure = null;
        int failures = 0;
        for (String gid : gids) {
            try {
                aria2Client.forceRemove(gid);
            } catch (Exception e) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                LOGGER.warn("Failed to force-remove route-change GID " + gid, e);
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("Could not force-remove " + failures
                    + " of " + gids.size() + " aria2 task(s) for route change",
                    firstFailure);
        }
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                List<String> gids = trackedGidsSnapshot(download);
                applyToEveryGid(gids, "unpause", aria2Client::unpause);
                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadResume(download);

                // Restart progress polling for every still-tracked GID
                for (String gid : gids) {
                    startProgressPolling(gid);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to resume download: " + download.getName(), e);
                throw new RuntimeException("Failed to resume download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> changeDestination(Download download,
            Path previousDestination, Path newDestination) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();
                List<String> gids = liveTrackedGidsSnapshot(download);
                applyToEveryGid(gids, "change destination",
                        gid -> aria2Client.changeOption(gid,
                                Map.of("dir", newDestination.toString())));
            } catch (Exception e) {
                LOGGER.error("Failed to change aria2 destination for "
                        + download.getName(), e);
                throw new RuntimeException("Failed to change aria2 destination", e);
            }
        }, executor);
    }

    private static String unpauseLabel() {
        return "unpause";
    }

    /** Snapshot of GIDs that this handler still owns in its current daemon. */
    private List<String> liveTrackedGidsSnapshot(Download download) {
        if (download == null) {
            return List.of();
        }
        java.util.Set<String> tracked = downloadGids.get(download.getId());
        if (tracked == null || tracked.isEmpty()) {
            return List.of();
        }
        return tracked.stream()
                .filter(gid -> download.getId().equals(gidToIdMap.get(gid)))
                .sorted()
                .toList();
    }

    /**
     * Whether the context-menu data recheck can reach a live aria2 task with
     * authoritative integrity metadata. The engine type check deliberately
     * excludes proxychains-routed aria2 processes: those are separate CLI
     * children and are not owned by this handler's RPC daemon.
     */
    public static boolean canRecheckData(Download download) {
        if (download == null || download.getType() != Download.Type.ARIA2
                || download.getGid() == null || download.getGid().isBlank()
                || !(download.getSettings() instanceof Aria2Settings)) {
            return false;
        }
        boolean liveStatus = switch (download.getStatus()) {
            case QUEUED, STARTING, CONNECTING, DOWNLOADING, SEEDING, PAUSED -> true;
            default -> false;
        };
        if (!liveStatus) {
            return false;
        }
        return switch (download.getProtocol()) {
            case TORRENT, MAGNET, METALINK -> true;
            case HTTP, HTTPS, FTP -> checksumOptionFor(download) != null;
            case SFTP -> false;
            case null -> false;
        };
    }

    /** Returns a validated aria2 {@code TYPE=DIGEST} checksum option. */
    static String checksumOptionFor(Download download) {
        if (download == null) {
            return null;
        }
        if (download.getSettings() instanceof Aria2Settings aria2Settings) {
            String configured = normalizeChecksumOption(aria2Settings.getOption("checksum"));
            if (configured != null) {
                return configured;
            }
        }
        return normalizeChecksum(download.getChecksumAlgorithm(),
                download.getExpectedChecksum());
    }

    private static String normalizeChecksumOption(String option) {
        if (option == null) {
            return null;
        }
        int separator = option.indexOf('=');
        if (separator <= 0 || separator == option.length() - 1) {
            return null;
        }
        return normalizeChecksum(option.substring(0, separator),
                option.substring(separator + 1));
    }

    private static String normalizeChecksum(String algorithm, String digest) {
        if (algorithm == null || digest == null) {
            return null;
        }
        String key = algorithm.strip().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        String ariaName;
        int expectedLength;
        switch (key) {
            case "md5" -> {
                ariaName = "md5";
                expectedLength = 32;
            }
            case "sha1" -> {
                ariaName = "sha-1";
                expectedLength = 40;
            }
            case "sha224" -> {
                ariaName = "sha-224";
                expectedLength = 56;
            }
            case "sha256" -> {
                ariaName = "sha-256";
                expectedLength = 64;
            }
            case "sha384" -> {
                ariaName = "sha-384";
                expectedLength = 96;
            }
            case "sha512" -> {
                ariaName = "sha-512";
                expectedLength = 128;
            }
            case "adler32" -> {
                ariaName = "adler32";
                expectedLength = 8;
            }
            default -> {
                return null;
            }
        }
        String normalizedDigest = digest.strip().toLowerCase(Locale.ROOT);
        if (normalizedDigest.length() != expectedLength
                || !normalizedDigest.chars().allMatch(character ->
                    (character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
            return null;
        }
        return ariaName + "=" + normalizedDigest;
    }

    /**
     * A snapshot for control operations. The primary-GID fallback supports a
     * same-session task whose tracking maps were partially cleaned up; detail
     * and settings RPCs deliberately use only {@link #liveTrackedGidsSnapshot}
     * so persisted or retired GIDs are never queried.
     */
    private List<String> trackedGidsSnapshot(Download download) {
        List<String> live = liveTrackedGidsSnapshot(download);
        if (!live.isEmpty()) {
            return live;
        }
        return download != null && download.getGid() != null
                ? List.of(download.getGid()) : List.of();
    }

    /** Returns and publishes a live primary GID, replacing a retired parent. */
    private String primaryLiveGid(Download download) {
        List<String> live = liveTrackedGidsSnapshot(download);
        if (live.isEmpty()) {
            return null;
        }
        String primary = download.getGid();
        if (primary != null && live.contains(primary)) {
            return primary;
        }
        String replacement = live.getFirst();
        download.setGid(replacement);
        return replacement;
    }

    private boolean isLiveTrackedGid(Download download, String gid) {
        if (download == null || gid == null) {
            return false;
        }
        java.util.Set<String> tracked = downloadGids.get(download.getId());
        return tracked != null && tracked.contains(gid)
                && download.getId().equals(gidToIdMap.get(gid));
    }

    /** An aria2 GID-scoped RPC that may fail; used with {@link #applyToEveryGid}. */
    private interface GidOperation {

        void apply(String gid) throws Exception;
    }

    /**
     * Cancels every tracked GID with force-removal (deleteFiles=true path)
     * and deletes the payload and control files aria2 reported for them.
     * File paths are collected BEFORE removal (tellStatus files[].path);
     * deletion happens only after the removal succeeded, and the download
     * result entries are dropped last.
     */
    private void cancelAndDeleteFiles(Download download, List<String> gids) throws Exception {
        List<String> reportedPaths = new ArrayList<>();
        Exception firstFailure = null;
        int failures = 0;
        for (String gid : gids) {
            try {
                reportedPaths.addAll(collectReportedFilePaths(gid));
                aria2Client.forceRemove(gid);
            } catch (Aria2RpcException e) {
                if (e.getMessage() != null && e.getMessage().contains("not found")) {
                    // Already out of the daemon's queue (completed, errored,
                    // or removed moments ago): removal is implicitly done
                    // and the reported files stay deletion-authorized
                    continue;
                }
                failures++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                LOGGER.warn("Failed to force-remove GID " + gid, e);
            } catch (Exception e) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                LOGGER.warn("Failed to force-remove GID " + gid, e);
            }
        }
        if (!gids.isEmpty() && failures == gids.size()) {
            throw firstFailure;
        }
        deleteAria2Payloads(download, reportedPaths);
        for (String gid : gids) {
            try {
                aria2Client.removeDownloadResult(gid);
            } catch (Exception e) {
                LOGGER.warn("Failed to remove download result for GID " + gid, e);
            }
        }
        // aria2 flushes .aria2 control files asynchronously: a stale write
        // can land after the first sweep. Sweep again once every aria2
        // interaction for this download is done.
        deleteAria2Payloads(download, reportedPaths);
    }

    /**
     * The file paths aria2 reported for a download (tellStatus files[].path).
     * Empty when the status cannot be read.
     */
    private List<String> collectReportedFilePaths(String gid) {
        try {
            String json = aria2Client.tellStatus(gid, new String[] { "files" });
            @SuppressWarnings("unchecked")
            Map<String, Object> status = objectMapper.readValue(json, Map.class);
            List<String> paths = new ArrayList<>();
            if (status.get("files") instanceof List<?> files) {
                for (Object file : files) {
                    if (file instanceof Map<?, ?> fileMap && fileMap.get("path") instanceof String path) {
                        paths.add(path);
                    }
                }
            }
            return paths;
        } catch (Exception e) {
            LOGGER.warn("Failed to collect reported files for GID " + gid, e);
            return List.of();
        }
    }

    /**
     * Deletion eligibility for an aria2-reported file path. Only a path the
     * daemon itself reported may be deleted, and only when it cannot escape
     * the destination: relative candidates are resolved against the
     * normalized absolute destination and normalized again (collapsing
     * {@code ..} segments before the containment check), absolute
     * candidates must already sit beneath the destination.
     * {@link Path#startsWith} compares name elements, so a sibling like
     * {@code /dest-evil} never passes for {@code /dest}.
     *
     * @param normalizedDestination the normalized absolute destination dir
     * @param candidate             the path exactly as aria2 reported it
     * @return the normalized deletable path, or empty when rejected
     */
    static java.util.Optional<Path> eligibleAria2Path(Path normalizedDestination, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return java.util.Optional.empty();
        }
        Path candidatePath = Path.of(candidate.trim());
        Path normalized = candidatePath.isAbsolute()
                ? candidatePath.normalize()
                : normalizedDestination.resolve(candidatePath).normalize();
        if (!normalized.startsWith(normalizedDestination)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(normalized);
    }

    private static final Logger DELETE_LOGGER =
            LoggerFactory.getLogger(Aria2DownloadHandler.class);

    /**
     * Best-effort removal of a canceled aria2 download's payload and
     * control files. Deletion authority is exclusively the paths aria2
     * reported via tellStatus — display names are guesses, and guesses
     * must not delete files. Every candidate is validated by
     * {@link #eligibleAria2Path} and, at deletion time, by real-path
     * containment beneath the destination so a symlinked path whose real
     * location escapes the destination is refused. Each deletable path's
     * {@code .aria2} control-file sibling is removed with it. A missing or
     * rejected path produces a warning and no deletion.
     *
     * @param download      the canceled download
     * @param reportedPaths the file paths aria2 reported for the download
     */
    static void deleteAria2Payloads(Download download, List<String> reportedPaths) {
        Path destination = download.getDestination();
        if (destination == null) {
            DELETE_LOGGER.warn("Cannot delete aria2 payload for " + download.getId()
                    + ": destination unknown");
            return;
        }
        if (reportedPaths == null || reportedPaths.isEmpty()) {
            DELETE_LOGGER.warn("No aria2 file paths reported for " + download.getId()
                    + "; refusing deletion (display names are not deletion authority)");
            return;
        }
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path realDestination;
        try {
            realDestination = destination.toRealPath();
        } catch (IOException e) {
            realDestination = normalizedDestination;
        }
        for (String candidate : reportedPaths) {
            java.util.Optional<Path> eligible = eligibleAria2Path(normalizedDestination, candidate);
            if (eligible.isEmpty()) {
                DELETE_LOGGER.warn("Refusing to delete aria2 output outside the destination for "
                        + download.getId() + ": " + candidate);
                continue;
            }
            Path base = eligible.get();
            for (Path target : new Path[] { base, Path.of(base + ".aria2") }) {
                try {
                    if (!Files.exists(target)) {
                        continue;
                    }
                    Path real = target.toRealPath();
                    if (!real.startsWith(realDestination)) {
                        DELETE_LOGGER.warn("Refusing to delete aria2 output whose real path escapes "
                                + "the destination for " + download.getId() + ": " + target);
                        continue;
                    }
                    Files.deleteIfExists(real);
                } catch (Exception e) {
                    DELETE_LOGGER.warn("Could not delete aria2 output " + target + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Applies an operation to every tracked GID, best-effort per GID (one
     * finished GID of a multi-GID download must not abort the others). The
     * operation only fails when EVERY attempt failed.
     */
    private void applyToEveryGid(List<String> gids, String operation, GidOperation rpc) throws Exception {
        Exception firstFailure = null;
        int failures = 0;
        for (String gid : gids) {
            try {
                rpc.apply(gid);
            } catch (Exception e) {
                failures++;
                if (firstFailure == null) {
                    firstFailure = e;
                }
                LOGGER.warn("Failed to " + operation + " GID " + gid, e);
            }
        }
        if (!gids.isEmpty() && failures == gids.size()) {
            throw firstFailure;
        }
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                List<String> gids = trackedGidsSnapshot(download);
                if (!gids.isEmpty()) {
                    for (String gid : gids) {
                        stopProgressPolling(gid);
                    }

                    if (deleteFiles) {
                        cancelAndDeleteFiles(download, gids);
                    } else {
                        applyToEveryGid(gids, "remove", aria2Client::remove);
                    }

                    untrackEntireDownload(download.getId());

                    download.setStatus(Download.Status.CANCELED);
                    notifyDownloadCanceled(download);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to cancel download: " + download.getName(), e);
                throw new RuntimeException("Failed to cancel download", e);
            }
        }, executor);
    }

    @Override
    protected void doInitialize() throws Exception {
        // Start aria2 RPC server
        List<String> extraArgs = new ArrayList<>();
        extraArgs.add("--max-concurrent-downloads=" + globalSettings.getMaxConcurrentDownloads());

        // Honor a configured RPC port (ODM default 6801): the client's own RPC
        // endpoint is authoritative — the self-launched daemon derives its
        // --rpc-listen-port from it, and an external daemon must already
        // be listening there to be adopted
        int rpcPort = globalSettings.getAria2RpcPort();
        aria2Client.setRpcUrl("http://localhost:" + rpcPort + "/jsonrpc");
        if (rpcPort != GlobalSettings.DEFAULT_ARIA2_RPC_PORT) {
            LOGGER.info("aria2 RPC port overridden to " + rpcPort);
        }
        aria2Client.setHonorExternalConfiguration(
                globalSettings.isHonorExternalAria2Configuration());

        // aria2's --all-proxy accepts HTTP(S), but not SOCKS. SOCKS work is
        // routed per download through ProxychainsDownloadHandler.
        if (globalSettings.isGlobalProxyEnabled()
                && globalSettings.getGlobalProxyAddress() != null
                && !DownloadHandlerFactory.isSocksProxyAddress(
                        globalSettings.getGlobalProxyAddress())) {
            extraArgs.add("--all-proxy=" + globalSettings.getGlobalProxyAddress());
        }

        boolean strictProxyRouting = globalSettings.isGlobalProxyEnabled()
                && DownloadHandlerFactory.isSocksProxyAddress(
                        globalSettings.getGlobalProxyAddress());
        extraArgs.addAll(Aria2GlobalOptions.daemonLaunchArguments(
                globalSettings, strictProxyRouting));

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

        // Start aria2 with RPC enabled. A false return or an exception
        // means no usable daemon (e.g. the endpoint is occupied without
        // valid credentials) — fail initialization visibly instead of
        // letting later calls silently miss.
        if (!aria2Client.startAria2cWithRpc(extraArgs)) {
            throw new IOException("aria2 RPC daemon failed to start "
                    + "(aria2c did not become responsive within the startup timeout)");
        }

        // Startup discovery/readiness probes deliberately use HTTP. Enabling
        // WebSocket earlier makes the expected free-port probe try to open a
        // socket before aria2c exists, producing a misleading connection-
        // refused warning on every healthy launch.
        aria2Client.setUseWebSocket(true);

        // Register notification listener
        aria2Client.addNotificationListener(new Aria2NotificationAdapter());

        // Connect WebSocket for notifications
        aria2Client.connectWebSocket();

        LOGGER.info("aria2 RPC server started successfully");
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
            if (!supportsPeerDetails(download)) {
                continue;
            }
            List<String> gids = liveTrackedGidsSnapshot(download);
            try {
                applyToEveryGid(gids, "refresh trackers",
                        gid -> aria2Client.changeOption(gid, Map.of("bt-tracker", trackerList)));
                if (!gids.isEmpty()) {
                    applied++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to refresh trackers for download " + download.getId(), e);
            }
        }
        if (applied > 0) {
            LOGGER.debug("Refreshed trackers on " + applied + " download(s)");
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
     * Pushes the current global settings (concurrency and proxy) to the
     * running aria2 daemon and to every active download, so changes made in
     * the settings dialog or via the Tor toggle affect running transfers
     * without a restart. Transfer limits are per-record Network settings;
     * the legacy hidden global speed field must not override them.
     */
    public void applyGlobalRuntimeOptions() {
        if (aria2Client == null) {
            return;
        }

        // Daemon-wide options
        try {
            Map<String, Object> globalOptions = new HashMap<>();
            globalOptions.put("max-concurrent-downloads",
                    String.valueOf(globalSettings.getMaxConcurrentDownloads()));
            aria2Client.changeGlobalOption(globalOptions);
        } catch (Exception e) {
            LOGGER.warn("Failed to apply global options to aria2", e);
        }

        // Per-download proxy: global proxy applies unless the download has
        // its own proxy configured. An empty value clears a previous proxy.
        String globalProxy = globalSettings.isGlobalProxyEnabled()
                && globalSettings.getGlobalProxyAddress() != null
                && !DownloadHandlerFactory.isSocksProxyAddress(
                        globalSettings.getGlobalProxyAddress())
                        ? globalSettings.getGlobalProxyAddress()
                        : "";
        for (Download download : activeDownloads.values()) {
            List<String> gids = liveTrackedGidsSnapshot(download);
            if (gids.isEmpty()) {
                continue;
            }
            if (download.getSettings() instanceof Aria2Settings aria2Settings
                    && aria2Settings.isUseProxy()
                    && aria2Settings.getProxyAddress() != null) {
                continue; // per-download proxy wins
            }
            try {
                applyToEveryGid(gids, "update proxy option",
                        gid -> aria2Client.changeOption(gid, Map.of("all-proxy", globalProxy)));
            } catch (Exception e) {
                LOGGER.warn("Failed to update proxy option for download " + download.getId(), e);
            }
        }
    }

    @Override
    protected void doShutdown() throws Exception {
        // Mark as shutting down to prevent new tasks FIRST
        isShuttingDown.set(true);

        // Stop all progress polling
        stopAllProgressPolling();

        // Shutdown is ownership-driven (Aria2Client.DaemonOwnership):
        // - ODM_STARTED: stopAria2c sends the authenticated shutdown RPC
        //   and reaps the child process.
        // - EXTERNAL_AUTHENTICATED: stopAria2c only closes ODM's transports.
        //   An aria2.shutdown RPC is never sent to a daemon ODM did not
        //   start, so an adopted user daemon survives ODM.
        // The unconditional daemon shutdown that used to happen here killed
        // adopted external daemons.

        // Then disconnect WebSocket (should not trigger reconnection now)
        try {
            aria2Client.disconnectWebSocket();
            LOGGER.info("Aria2 WebSocket disconnected");
        } catch (Exception e) {
            LOGGER.warn("Error disconnecting WebSocket", e);
        }

        // Finally stop the daemon attachment: polls daemon state with a
        // bounded wait (no fixed sleeps) and escalates to destroy on
        // timeout for ODM-owned children
        boolean daemonStopped = false;
        Exception daemonStopFailure = null;
        try {
            daemonStopped = aria2Client.stopAria2c();
        } catch (Exception e) {
            daemonStopFailure = e;
            LOGGER.warn("Error stopping aria2 process", e);
        }

        // Shutdown progress poller
        progressPoller.shutdown();
        if (!progressPoller.awaitTermination(5, TimeUnit.SECONDS)) {
            progressPoller.shutdownNow();
        }

        if (!daemonStopped) {
            if (daemonStopFailure != null) {
                throw daemonStopFailure;
            }
            throw new IOException("ODM-owned aria2 RPC daemon is still running after shutdown escalation");
        }

        LOGGER.info("aria2 download handler shut down successfully");
    }

    /**
     * Starts polling for progress updates for a download. All GIDs share a
     * single poller tick that batches every active download into ONE
     * {@code system.multicall} round trip — per-download scheduling costs N
     * sequential RPCs per second and one hung response stalls them all.
     *
     * @param gid The aria2 GID of the download
     */
    private void startProgressPolling(String gid) {
        if (isShuttingDown.get()) {
            return;
        }

        pollTasks.add(gid);
        ensureBatchPollingScheduled();
    }

    /** Schedules the shared batch poll task once, for however many GIDs are active. */
    private synchronized void ensureBatchPollingScheduled() {
        if (batchPollTask == null && !pollTasks.isEmpty() && !isShuttingDown.get()) {
            batchPollTask = progressPoller.scheduleAtFixedRate(() -> {
                try {
                    pollAllDownloadsProgress();
                } catch (Exception e) {
                    LOGGER.warn("Error polling download progress", e);
                }
            }, 0, PROGRESS_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops polling for progress updates for a download.
     *
     * @param gid The aria2 GID of the download
     */
    private void stopProgressPolling(String gid) {
        pollTasks.remove(gid);
    }

    /** Cancels the shared batch task when the last GID goes away. */
    private synchronized void maybeStopBatchPolling() {
        if (batchPollTask != null && pollTasks.isEmpty()) {
            batchPollTask.cancel(false);
            batchPollTask = null;
        }
    }

    /**
     * Stops all progress polling tasks.
     */
    private void stopAllProgressPolling() {
        synchronized (this) {
            if (batchPollTask != null) {
                batchPollTask.cancel(false);
                batchPollTask = null;
            }
        }
        pollTasks.clear();
        new ArrayList<>(recheckObservations.values())
                .forEach(RecheckObservation::finish);

        // Clear all download references
        activeDownloads.clear();
    }

    /**
     * One poll tick: batches every active GID into a single
     * {@code system.multicall} round trip, falling back to per-GID
     * tellStatus if the batch request fails (so one bad GID cannot stop
     * every download's updates).
     */
    private void pollAllDownloadsProgress() {
        if (isShuttingDown.get() || pollTasks.isEmpty()) {
            maybeStopBatchPolling();
            return;
        }

        List<String> gids = new ArrayList<>(pollTasks);
        try {
            List<Map<String, Object>> calls = aria2Client.tellStatusMulticallCalls(
                    gids, REQUIRED_STATUS_KEYS);
            List<List<Object>> results = aria2Client.systemMulticall(calls);

            if (results == null || results.size() != gids.size()) {
                throw new IOException("Unexpected multicall result shape: "
                        + (results == null ? "null" : results.size()));
            }
            for (int i = 0; i < gids.size(); i++) {
                dispatchPollResult(gids.get(i), results.get(i));
            }
        } catch (Exception batchError) {
            LOGGER.warn(
                    "Multicall progress poll failed; falling back to per-download polling", batchError);
            for (String gid : gids) {
                pollDownloadProgress(gid);
            }
        } finally {
            maybeStopBatchPolling();
        }
    }

    /**
     * Unwraps one multicall entry ({@code [[status]]} or
     * {@code [{"error":...}]}) and routes it through the regular progress
     * pipeline.
     */
    private void dispatchPollResult(String gid, List<Object> multicallEntry) {
        try {
            if (multicallEntry == null || multicallEntry.isEmpty()) {
                return;
            }
            Object status = multicallEntry.get(0);
            if (status instanceof Map<?, ?> statusMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) status;
                String downloadId = gidToIdMap.get(gid);
                if (downloadId != null && isPollableGid(gid)
                        && downloadId.equals(gidToIdMap.get(gid))) {
                    processProgressUpdate(downloadId, gid, typed);
                }
            }
            // An error entry or missing GID mapping is silently skipped; the
            // per-GID fallback in pollDownloadProgress handles persistent failures
        } catch (Exception e) {
            LOGGER.warn("Error processing multicall result for GID " + gid, e);
        }
    }

    /**
     * Polls for download progress using aria2.tellStatus.
     *
     * @param gid The aria2 GID of the download
     */
    private void pollDownloadProgress(String gid) {
        try {
            if (!isPollableGid(gid)) {
                stopProgressPolling(gid);
                return;
            }

            String downloadId = gidToIdMap.get(gid);
            if (downloadId == null || !isPollableGid(gid)) {
                return;
            }

            // Get status from aria2 with only required fields for better performance
            // This reduces network overhead by requesting only essential progress data
            String statusJson = aria2Client.tellStatus(gid, REQUIRED_STATUS_KEYS);

            @SuppressWarnings("unchecked")
            Map<String, Object> status = objectMapper.readValue(statusJson, Map.class);

            if (status != null && isPollableGid(gid)
                    && downloadId.equals(gidToIdMap.get(gid))) {
                // Notify progress update
                processProgressUpdate(downloadId, gid, status);
            }
        } catch (Exception e) {
            if (isGidNotFoundResponse(e) && !isPollableGid(gid)) {
                LOGGER.debug("Ignoring progress response for retired GID " + gid);
            } else {
                LOGGER.warn("Error polling download progress: " + e.getMessage(), e);
            }
        }
    }

    /** A GID is pollable only while both its schedule and ownership are live. */
    private boolean isPollableGid(String gid) {
        return gid != null && !isShuttingDown.get()
                && pollTasks.contains(gid) && gidToIdMap.containsKey(gid);
    }

    /**
     * Processes a progress update from aria2.
     *
     * @param downloadId The download ID
     * @param gid The aria2 GID
     * @param status The status information from aria2
     */
    void processProgressUpdate(String downloadId, String gid, Map<String, Object> status) {
        try {
            // Get the download object
            Download download = getDownloadById(downloadId);
            if (download == null) {
                LOGGER.warn("Download not found for ID: " + downloadId);
                finishRecheckObservation(downloadId);
                stopProgressPolling(gid);
                return;
            }

            observeRecheckActivity(downloadId, gid, status);

            // Related GIDs (followedBy/following/belongTo) may surface at any
            // time: a finished magnet metadata download spawns its payload
            // GID. Track every discovery before evaluating completion.
            discoverRelatedGids(downloadId, status);

            // Extract aria2 status fields
            String downloadStatus = (String) status.get("status");
            long completedLength = statusLong(status.get("completedLength"));
            long totalLength = statusLong(status.get("totalLength"));
            long downloadSpeed = statusLong(status.get("downloadSpeed"));
            long uploadSpeed = statusLong(status.get("uploadSpeed"));
            int connections = statusInt(status.get("connections"));
            int numSeeders = statusInt(status.get("numSeeders"));

            // Extended detail fields (seeding state and info hash)
            String infoHash = (String) status.get("infoHash");
            boolean seeder = Boolean.parseBoolean(String.valueOf(
                    status.getOrDefault("seeder", false)));
            String errorCode = statusText(status.get("errorCode"));
            String errorMessage = statusText(status.get("errorMessage"));

            // Record this GID's complete transfer snapshot, then aggregate
            // every tracked GID. Previously only completed/total were summed,
            // while whichever related GID happened to be processed last
            // overwrote upload speed (often with zero from magnet metadata).
            Map<String, GidTransferStats> perGid = downloadProgress.get(downloadId);
            if (perGid != null) {
                perGid.put(gid, new GidTransferStats(completedLength, totalLength,
                        downloadSpeed, uploadSpeed, connections, numSeeders));
            }
            long aggregatedCompleted = 0;
            long aggregatedTotal = 0;
            long aggregatedDownloadSpeed = 0;
            long aggregatedUploadSpeed = 0;
            int aggregatedConnections = 0;
            int aggregatedSeeders = 0;
            for (GidTransferStats progress : perGid != null
                    ? perGid.values() : List.<GidTransferStats>of()) {
                aggregatedCompleted += progress.completed();
                aggregatedTotal += progress.total();
                aggregatedDownloadSpeed += progress.downloadSpeed();
                aggregatedUploadSpeed += progress.uploadSpeed();
                aggregatedConnections += progress.connections();
                aggregatedSeeders += progress.seeders();
            }

            // Update download object
            download.setDownloaded(aggregatedCompleted);
            download.setSize(aggregatedTotal);
            download.setSpeed((float) aggregatedDownloadSpeed);
            download.setUploadSpeed((float) aggregatedUploadSpeed);
            download.setConnectionCount(aggregatedConnections);
            download.setSeeders(aggregatedSeeders);
            if (infoHash != null && !infoHash.isBlank()) {
                download.setInfoHash(infoHash);
            }
            recordReportedOutputPaths(download, status);
            stopDisabledSeeding(download, gid, downloadStatus, seeder);

            // Calculate progress percentage
            float progress = 0;
            if (aggregatedTotal > 0) {
                progress = (float) aggregatedCompleted / aggregatedTotal * 100;
            }

            // Update download status based on aria2 status
            updateDownloadStatus(download, downloadStatus, gid, seeder,
                    errorCode, errorMessage);

            // Notify listeners about progress
            notifyDownloadProgress(download, progress, aggregatedCompleted, aggregatedTotal,
                    (float) aggregatedDownloadSpeed);

        } catch (Exception e) {
            LOGGER.warn("Error processing progress update for GID " + gid, e);
        }
    }

    private static long statusLong(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.toString()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String statusText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static int statusInt(Object value) {
        return (int) Math.min(Integer.MAX_VALUE, statusLong(value));
    }

    /**
     * aria2 keeps a fully downloaded torrent active while it seeds toward its
     * default share ratio. When this download's seeding policy is disabled,
     * explicitly set a zero seed time on an already-running task; aria2 then emits its
     * normal complete transition and the manager releases the slot cleanly.
     */
    private void stopDisabledSeeding(Download download, String gid,
            String aria2Status, boolean seeder) {
        if (!"active".equals(aria2Status) || !seeder
                || !seedingDisabled(download)
                || !seedingStopRequests.add(gid)) {
            return;
        }
        try {
            aria2Client.changeOption(gid, Map.of("seed-time", "0"));
            LOGGER.info("Stopping disabled BitTorrent seeding for download "
                    + download.getId());
        } catch (Exception e) {
            seedingStopRequests.remove(gid);
            LOGGER.warn("Could not stop BitTorrent seeding for GID " + gid, e);
        }
    }

    private boolean seedingDisabled(Download download) {
        if (download.getSettings() instanceof Aria2Settings settings
                && settings.getOption("seed-time") != null) {
            return settings.isSeedingDisabled();
        }
        // Rehydrated records created before per-download policy snapshots
        // fall back to the current global preference.
        return Aria2GlobalOptions.seedingPolicy(globalSettings)
                == Aria2GlobalOptions.SeedingPolicy.DISABLED;
    }

    private void recordReportedOutputPaths(Download download, Map<String, Object> status) {
        if (!(status.get("files") instanceof List<?> files)) {
            return;
        }
        boolean followedByPayload = status.get("followedBy") instanceof List<?> followedBy
                && !followedBy.isEmpty();
        for (Object file : files) {
            if (!(file instanceof Map<?, ?> fileMap)
                    || !(fileMap.get("path") instanceof String path)
                    || path.isBlank()) {
                continue;
            }
            try {
                Path artifact = Path.of(path);
                boolean magnetMetadataPlaceholder =
                        download.getProtocol() == Download.Protocol.MAGNET
                        && artifact.getFileName() != null
                        && artifact.getFileName().toString().startsWith("[METADATA]");
                if (followedByPayload || magnetMetadataPlaceholder) {
                    // Magnet metadata is an internal aria2 hand-off artifact,
                    // not a user download. It can have been observed on an
                    // earlier poll before followedBy appeared, so discard it
                    // as well as refusing to add it now.
                    download.removeOutputPath(artifact);
                    continue;
                }
                download.recordOutputPath(artifact);
                if (download.getRequestedFileName() == null && files.size() == 1
                        && artifact.getFileName() != null) {
                    download.setName(artifact.getFileName().toString());
                }
            } catch (IllegalArgumentException invalidPath) {
                LOGGER.warn("Ignoring invalid aria2 output path", invalidPath);
            }
        }
    }

    /**
     * The tracked GIDs of a download. Test/inspection accessor for
     * multi-GID ownership.
     */
    java.util.Set<String> trackedGidsFor(String downloadId) {
        java.util.Set<String> tracked = downloadGids.get(downloadId);
        return tracked != null ? java.util.Set.copyOf(tracked) : java.util.Set.of();
    }

    /**
     * Registers GIDs discovered in a tellStatus result (followedBy array,
     * following/belongTo strings) as part of the given download so they are
     * polled and completion-gated too.
     */
    private void discoverRelatedGids(String downloadId, Map<String, Object> status) {
        java.util.Set<String> tracked = downloadGids.get(downloadId);
        java.util.Set<String> seen = downloadSeenGids.get(downloadId);
        if (tracked == null || seen == null) {
            return;
        }
        List<String> related = new ArrayList<>();
        if (status.get("followedBy") instanceof List<?> followed) {
            for (Object gid : followed) {
                related.add(String.valueOf(gid));
            }
        }
        for (String key : List.of("following", "belongsTo")) {
            Object value = status.get(key);
            if (value != null) {
                related.add(String.valueOf(value));
            }
        }
        for (String gid : related) {
            if (gid == null || gid.isBlank() || gid.equals("null")) {
                continue;
            }
            // First time EVER seen: track and poll it. GIDs already seen
            // (still tracked, or retired after completing) must not be
            // re-registered — a retired parent re-listed by its child's
            // following/belongsTo reference would never drain the set.
            if (seen.add(gid)) {
                tracked.add(gid);
                gidToIdMap.put(gid, downloadId);
                LOGGER.info("Tracking related aria2 GID " + gid + " for download " + downloadId);
                startProgressPolling(gid);
            }
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
    private void updateDownloadStatus(Download download, String aria2Status, String gid,
            boolean seeder, String errorCode, String aria2ErrorMessage) {
        if (aria2Status == null) {
            return;
        }

        // Per-tick diagnostics (1 Hz per download): FINE so a busy queue
        // does not generate a log line per second per download
        LOGGER.debug("Aria2 status update - GID: " + gid + ", Download: " + download.getName()
                + ", Current status: " + download.getStatus() + ", Aria2 status: " + aria2Status);

        switch (aria2Status) {
            case "active":
                Download.Status activeStatus = seeder
                        ? Download.Status.SEEDING : Download.Status.DOWNLOADING;
                if (download.getStatus() != activeStatus) {
                    Download.Status previousStatus = download.getStatus();
                    LOGGER.info("Setting download status to " + activeStatus
                            + " for: " + download.getName());
                    download.setStatus(activeStatus);
                    notifyDownloadStatusChanged(download, previousStatus, activeStatus);
                }
                break;
            case "waiting":
                // CRITICAL FIX: Don't change status to QUEUED if download is already
                // DOWNLOADING
                // This prevents infinite loop where active downloads get reset to QUEUED
                if (download.getStatus() == Download.Status.DOWNLOADING
                        || download.getStatus() == Download.Status.SEEDING) {
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
                String errorMessage = aria2FailureMessage(
                        gid, errorCode, aria2ErrorMessage);
                download.setErrorMessage(errorMessage);
                notifyDownloadError(download, errorMessage);
                untrackEntireDownload(download.getId());
                break;
            case "complete":
                // The download completes only when EVERY tracked GID is
                // complete: a finished magnet metadata GID (followedBy) or a
                // finished metalink file must not complete the whole download
                if (retireTrackedGid(download.getId(), gid)) {
                    download.setStatus(Download.Status.COMPLETED);
                    notifyDownloadComplete(download);
                    removeDownloadReference(download.getId());
                }
                break;
            case "removed":
                download.setStatus(Download.Status.CANCELED);
                notifyDownloadCanceled(download);
                untrackEntireDownload(download.getId());
                break;
            default:
                LOGGER.warn("Unknown aria2 status: " + aria2Status + " for GID: " + gid);
                break;
        }
    }

    static String aria2FailureMessage(String gid, String errorCode,
            String aria2ErrorMessage) {
        String detail = statusText(aria2ErrorMessage);
        String code = statusText(errorCode);
        if (code != null && detail != null) {
            return "Aria2 error " + code + ": " + detail;
        }
        if (detail != null) {
            return "Aria2 error: " + detail;
        }
        if (code != null) {
            return "Aria2 error " + code + " for GID: " + gid;
        }
        return "Aria2 download error for GID: " + gid;
    }

    /**
     * Retires one completed GID of a download. Returns true when the last
     * tracked GID retired (the whole download is complete); sibling GIDs
     * that are still running keep the download in progress.
     */
    private boolean retireTrackedGid(String downloadId, String gid) {
        java.util.Set<String> tracked = downloadGids.get(downloadId);
        if (tracked == null) {
            return true;
        }
        tracked.remove(gid);
        seedingStopRequests.remove(gid);
        stopProgressPolling(gid);
        gidToIdMap.remove(gid);
        Map<String, GidTransferStats> progress = downloadProgress.get(downloadId);
        if (progress != null) {
            // A retired child still contributes its completed/total bytes to
            // the record aggregate. Only its live activity must disappear;
            // dropping the whole snapshot makes multi-GID downloads shrink
            // as each file completes.
            progress.computeIfPresent(gid, (ignored, retired) ->
                    new GidTransferStats(retired.completed(), retired.total(),
                            0, 0, 0, 0));
        }
        if (tracked.isEmpty()) {
            downloadGids.remove(downloadId);
            downloadSeenGids.remove(downloadId);
            downloadProgress.remove(downloadId);
            return true;
        }
        Download download = activeDownloads.get(downloadId);
        if (download != null && Objects.equals(download.getGid(), gid)) {
            // A magnet metadata GID (or the first file of a Metalink) can
            // retire while child/file GIDs remain. Keep Download.gid aligned
            // with a live task for presentation and any legacy consumers.
            primaryLiveGid(download);
        }
        LOGGER.info("GID " + gid + " complete; " + tracked.size() + " tracked GID(s) remain for download "
                + downloadId);
        return false;
    }

    /**
     * Drops every tracked GID of a download from polling and mapping, and
     * releases the download reference. Used for terminal states that end
     * the whole download (error, removed, cancel).
     */
    private void untrackEntireDownload(String downloadId) {
        finishRecheckObservation(downloadId);
        java.util.Set<String> tracked = downloadGids.remove(downloadId);
        if (tracked != null) {
            for (String gid : tracked) {
                stopProgressPolling(gid);
                gidToIdMap.remove(gid);
                seedingStopRequests.remove(gid);
            }
        }
        downloadSeenGids.remove(downloadId);
        downloadProgress.remove(downloadId);
        removeDownloadReference(downloadId);
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
     * Starts an HTTP, HTTPS, FTP, or SFTP URI using aria2's addUri RPC.
     *
     * @param download The download to start
     * @return the aria2 GIDs of the download (single element)
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private List<String> startUriDownload(Download download) throws IOException, Aria2RpcException {
        LOGGER.info("Starting " + download.getProtocol() + " download " + download.getId());
        Map<String, Object> options = new HashMap<>();
        options.put("dir", download.getDestination().toString());
        if (download.getRequestedFileName() != null) {
            options.put("out", download.getRequestedFileName());
        }

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

        allowRenameWhenExistingOutputCannotBeResumed(download, options);

        // aria2 cannot validate an ordinary HTTP(S)/FTP file from
        // check-integrity alone; it also needs the authoritative digest.
        String checksum = checksumOptionFor(download);
        if (checksum != null) {
            options.put("checksum", checksum);
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
        LOGGER.info("Calling aria2.addUri for download " + download.getId()
                + " with " + uriArray.length + " source URI(s) and " + options.size() + " option(s)");
        String gid = aria2Client.addUriRpc(uriArray, options);
        LOGGER.info("aria2.addUri returned GID: " + gid);

        return List.of(gid);
    }

    /**
     * aria2 gives {@code --continue} precedence over auto file renaming. If an
     * existing target is not writable, resume mode consequently fails with
     * error 15 instead of applying the requested auto-renaming policy. Resume
     * is impossible in that situation anyway, so disable it for this one RPC
     * request and let aria2 choose its normal {@code name.1.ext} path.
     */
    void allowRenameWhenExistingOutputCannotBeResumed(Download download,
            Map<String, Object> options) {
        if (download == null || options == null
                || !Boolean.parseBoolean(String.valueOf(options.get("continue")))
                || !Boolean.parseBoolean(String.valueOf(options.get("auto-file-renaming")))
                || Boolean.parseBoolean(String.valueOf(options.get("allow-overwrite")))) {
            return;
        }
        Path destination = download.getDestination();
        String outputName = download.getRequestedFileName() != null
                ? download.getRequestedFileName() : download.getName();
        if (destination == null || outputName == null || outputName.isBlank()) {
            return;
        }
        org.manager.util.PathSafety.requireSafeFileName(outputName);
        Path output = destination.resolve(outputName);
        if (Files.exists(output) && !Files.isWritable(output)) {
            options.put("continue", "false");
            LOGGER.info("Existing output is not writable; allowing aria2 to auto-rename: "
                    + output);
        }
    }

    /**
     * Starts a torrent download using aria2.
     *
     * @param download The download to start
     * @return the aria2 GIDs of the download (single element)
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private List<String> startTorrentDownload(Download download) throws IOException, Aria2RpcException {
        URI torrentUri = download.getUri();

        // Handle different URI schemes for torrent files
        byte[] torrentData;
        String torrentSource;
        // Local descriptor whose bytes are consumed by addTorrent; when ODM
        // staged it (folder monitoring), successful ingestion owns its removal
        Path localTorrentFile = null;
        if ("file".equals(torrentUri.getScheme())) {
            // Local file path
            Path torrentFile = Paths.get(torrentUri);
            torrentData = readLocalFile(torrentFile, "Torrent");
            torrentSource = torrentFile.toString();
            localTorrentFile = torrentFile;
        } else if ("torrent".equals(torrentUri.getScheme())) {
            // Custom torrent: scheme - extract file path from URI
            Path torrentFile = Paths.get(torrentUri.getSchemeSpecificPart());
            torrentData = readLocalFile(torrentFile, "Torrent");
            torrentSource = torrentFile.toString();
            localTorrentFile = torrentFile;
        } else if ("http".equals(torrentUri.getScheme()) || "https".equals(torrentUri.getScheme())) {
            // Remote torrent file: fetch it into memory, then hand the bytes
            // to aria2's addTorrent
            torrentData = fetchRemoteBytes(torrentUri, download);
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

        // Successful ingestion consumes the descriptor: delete it only when
        // ODM owns it (staged by folder monitoring or the selected-descriptor
        // Trash flow); non-staged source files remain user-owned
        if (localTorrentFile != null) {
            DescriptorStaging.deleteIfStaged(localTorrentFile);
        }

        LOGGER.info("Started torrent download with GID: " + gid);
        return List.of(gid);
    }

    /**
     * Starts a magnet link download using aria2.
     *
     * @param download The download to start
     * @return the aria2 GIDs of the download (the metadata GID; the payload
     *         GID is discovered later via tellStatus followedBy)
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private List<String> startMagnetDownload(Download download) throws IOException, Aria2RpcException {
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
        return List.of(gid);
    }

    /**
     * Starts a Metalink download using aria2's addMetalink RPC: the Metalink
     * XML is read from a local or remote (.metalink/.meta4) file and sent to
     * aria2, which handles mirror selection and segmented download itself.
     *
     * @param download The download to start (uri points to a local or remote
     *            .metalink/.meta4 file)
     * @return the aria2 GIDs, one per file in the metalink
     * @throws IOException if an I/O error occurs
     * @throws Aria2RpcException if an error occurs in the aria2 RPC call
     */
    private List<String> startMetaLinkDownload(Download download) throws IOException, Aria2RpcException {
        URI metaLinkUri = download.getUri();

        // Handle different URI schemes for metalink files
        byte[] metaLinkData;
        String metaLinkSource;
        // Local descriptor whose bytes are consumed by addMetalink; when ODM
        // staged it (folder monitoring), successful ingestion owns its removal
        Path localMetaLinkFile = null;
        if ("file".equals(metaLinkUri.getScheme())) {
            Path metaLinkFile = Paths.get(metaLinkUri);
            metaLinkData = readLocalFile(metaLinkFile, "Metalink");
            metaLinkSource = metaLinkFile.toString();
            localMetaLinkFile = metaLinkFile;
        } else if ("metalink".equals(metaLinkUri.getScheme())) {
            // Custom metalink: scheme - extract file path from URI
            Path metaLinkFile = Paths.get(metaLinkUri.getSchemeSpecificPart());
            metaLinkData = readLocalFile(metaLinkFile, "Metalink");
            metaLinkSource = metaLinkFile.toString();
            localMetaLinkFile = metaLinkFile;
        } else if ("http".equals(metaLinkUri.getScheme()) || "https".equals(metaLinkUri.getScheme())) {
            // Remote Metalink file: fetch it into memory and hand the bytes
            // to aria2's addMetalink
            metaLinkData = fetchRemoteBytes(metaLinkUri, download);
            metaLinkSource = "remote Metalink descriptor";
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

        List<String> gids = aria2Client.addMetalinkAll(metaLinkData, options);

        // Successful ingestion consumes the descriptor: delete it only when
        // ODM owns it (staged by folder monitoring or the selected-descriptor
        // Trash flow); non-staged source files remain user-owned
        if (localMetaLinkFile != null) {
            DescriptorStaging.deleteIfStaged(localMetaLinkFile);
        }

        LOGGER.info("Started Metalink download with " + gids.size() + " GID(s)");
        return gids;
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
            if (Files.size(file) > MAX_REMOTE_DESCRIPTOR_BYTES) {
                throw new IOException(kind + " descriptor exceeds the 16 MiB limit");
            }
            try (InputStream input = Files.newInputStream(file)) {
                byte[] data = input.readNBytes((int) MAX_REMOTE_DESCRIPTOR_BYTES + 1);
                if (data.length > MAX_REMOTE_DESCRIPTOR_BYTES) {
                    throw new IOException(kind + " descriptor exceeds the 16 MiB limit");
                }
                return data;
            }
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
    private byte[] fetchRemoteBytes(URI uri, Download download) throws IOException {
        String proxy = null;
        if (download.getSettings() != null && download.getSettings().isUseProxy()) {
            proxy = download.getSettings().getProxyAddress();
        } else if (globalSettings.isGlobalProxyEnabled()) {
            proxy = globalSettings.getGlobalProxyAddress();
        }
        byte[] data = org.manager.tools.BoundedHttpFetcher.fetch(uri,
                MAX_REMOTE_DESCRIPTOR_BYTES, Duration.ofSeconds(30), Duration.ofSeconds(60), proxy);
        if (data.length == 0) {
            throw new IOException("Remote descriptor is empty");
        }
        return data;
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
            LOGGER.warn("Failed to get full status for debugging: " + e.getMessage(), e);
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
        if (!supportsPeerDetails(download)) {
            return List.of();
        }
        String gid = primaryLiveGid(download);
        if (gid == null) {
            return List.of();
        }
        try {
            return aria2Client.getPeers(gid);
        } catch (Aria2RpcException e) {
            if (isNoPeerDataResponse(e)) {
                // A valid torrent can temporarily have no peer data while
                // metadata resolves or while it is stopped.
                LOGGER.debug("No peer data available for gid " + gid + ": " + e.getMessage());
            } else if (isGidNotFoundResponse(e) && !isLiveTrackedGid(download, gid)) {
                LOGGER.debug("Ignoring peer query for retired GID " + gid);
            } else {
                LOGGER.warn("Failed to get peers for gid " + gid, e);
            }
            return List.of();
        } catch (Exception e) {
            LOGGER.warn("Failed to get peers for gid " + gid, e);
            return List.of();
        }
    }

    /**
     * Whether aria2 can meaningfully answer {@code aria2.getPeers} for this
     * download. The consolidated ARIA2 type also represents ordinary HTTP and
     * FTP downloads, for which getPeers always returns an RPC error.
     */
    static boolean supportsPeerDetails(Download download) {
        Download.Protocol protocol = download != null ? download.getProtocol() : null;
        return protocol != null && protocol.supportsPeerDetails();
    }

    static boolean isNoPeerDataResponse(Aria2RpcException error) {
        return error != null && error.getMessage() != null
                && error.getMessage().toLowerCase(java.util.Locale.ROOT)
                        .contains("no peer data is available");
    }

    static boolean isGidNotFoundResponse(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return false;
        }
        String message = error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("gid") && message.contains("not found");
    }

    /**
     * Fetches the file list of a download (aria2.getFiles).
     *
     * @param download the download (must have a GID)
     * @return list of file detail maps; empty when unavailable
     */
    public List<Map<String, Object>> getDownloadFiles(Download download) {
        List<String> gids = liveTrackedGidsSnapshot(download);
        if (gids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (String gid : gids) {
            try {
                files.addAll(aria2Client.getFiles(gid));
            } catch (Exception e) {
                if (!(isGidNotFoundResponse(e) && !isLiveTrackedGid(download, gid))) {
                    LOGGER.warn("Failed to get files for gid " + gid, e);
                }
            }
        }
        return files;
    }

    /**
     * Resolves descriptor contents without registering an ODM download. Local
     * and remote descriptors are parsed directly. A magnet has no file list
     * until its metadata arrives, so aria2 downloads metadata only into an
     * owned temporary directory; the temporary RPC result and files are
     * removed in all outcomes.
     */
    public CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(URI source) {
        return previewDownloadFiles(source, null);
    }

    public CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(
            URI source, String proxyAddress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String effectiveProxy = proxyAddress != null && !proxyAddress.isBlank()
                        ? proxyAddress
                        : globalSettings.isGlobalProxyEnabled()
                                ? globalSettings.getGlobalProxyAddress() : null;
                Download.Protocol protocol = Download.Protocol.fromUri(source);
                return switch (protocol) {
                    case TORRENT, METALINK -> DescriptorFileInspector.inspect(
                            readPreviewDescriptor(source, effectiveProxy), protocol);
                    case MAGNET -> {
                        if (DownloadHandlerFactory.isSocksProxyAddress(effectiveProxy)) {
                            throw new IOException("Magnet metadata preview cannot use the selected "
                                    + "SOCKS/Tor route without starting its proxychains transfer");
                        }
                        ensureInitialized();
                        yield previewMagnetFiles(source, effectiveProxy);
                    }
                    default -> List.of();
                };
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(
                        "Could not inspect download files: " + e.getMessage(), e);
            }
        }, executor);
    }

    private byte[] readPreviewDescriptor(URI source, String proxyAddress) throws IOException {
        String scheme = source.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            return readLocalFile(Paths.get(source), "Descriptor");
        }
        if ("torrent".equalsIgnoreCase(scheme) || "metalink".equalsIgnoreCase(scheme)) {
            return readLocalFile(Paths.get(source.getSchemeSpecificPart()), "Descriptor");
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return org.manager.tools.BoundedHttpFetcher.fetch(source,
                    DescriptorFileInspector.MAX_DESCRIPTOR_BYTES,
                    Duration.ofSeconds(30), Duration.ofSeconds(60), proxyAddress);
        }
        throw new IOException("Unsupported descriptor source URI: " + source);
    }

    private List<DownloadFileInfo> previewMagnetFiles(URI source, String proxyAddress)
            throws Exception {
        Path previewDirectory = Files.createTempDirectory("odm-magnet-preview-");
        String gid = null;
        try {
            Map<String, Object> options = new HashMap<>();
            options.put("dir", previewDirectory.toString());
            options.put("bt-metadata-only", "true");
            options.put("bt-save-metadata", "true");
            options.put("file-allocation", "none");
            options.put("seed-time", "0");
            if (proxyAddress != null && !proxyAddress.isBlank()) {
                options.put("all-proxy", proxyAddress);
            }
            gid = aria2Client.addUriRpc(source.toString(), options);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                Path descriptor = firstTorrentDescriptor(previewDirectory);
                if (descriptor != null) {
                    return DescriptorFileInspector.inspect(
                            readLocalFile(descriptor, "Magnet metadata"),
                            Download.Protocol.TORRENT);
                }

                String statusJson = aria2Client.tellStatus(gid,
                        new String[]{"status", "errorCode", "errorMessage"});
                @SuppressWarnings("unchecked")
                Map<String, Object> status = objectMapper.readValue(statusJson, Map.class);
                if ("error".equals(status.get("status"))) {
                    throw new IOException(String.valueOf(status.getOrDefault("errorMessage",
                            "aria2 could not retrieve magnet metadata")));
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Magnet metadata preview was interrupted");
                }
                Thread.sleep(250);
            }
            throw new IOException("Timed out waiting for magnet metadata");
        } finally {
            cleanupPreviewResult(gid);
            deletePreviewDirectory(previewDirectory);
        }
    }

    private static Path firstTorrentDescriptor(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".torrent"))
                    .findFirst().orElse(null);
        }
    }

    private void cleanupPreviewResult(String gid) {
        if (gid == null) {
            return;
        }
        try {
            aria2Client.forceRemove(gid);
        } catch (Exception e) {
            LOGGER.debug("Metadata preview GID was already stopped: " + gid, e);
        }
        try {
            aria2Client.removeDownloadResult(gid);
        } catch (Exception e) {
            LOGGER.debug("Could not remove metadata preview result: " + gid, e);
        }
    }

    private void deletePreviewDirectory(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not remove magnet metadata preview directory " + directory, e);
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
        if (!supportsPeerDetails(download)) {
            return List.of();
        }
        String gid = primaryLiveGid(download);
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
            if (!(isGidNotFoundResponse(e) && !isLiveTrackedGid(download, gid))) {
                LOGGER.warn("Failed to get trackers for gid " + gid, e);
            }
        }
        return List.of();
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                ensureInitialized();

                // Only apply to GIDs this handler still owns. Persisted and
                // retired metadata GIDs are session-scoped and must not receive
                // live RPC calls; their settings apply on the next start.
                List<String> gids = liveTrackedGidsSnapshot(download);
                if (gids.isEmpty()) {
                    return;
                }

                Map<String, Object> options = new HashMap<>();
                switch (download.getSettings()) {
                    case Aria2Settings aria2Settings -> {
                        String proxy = aria2Settings.isUseProxy()
                                ? aria2Settings.getProxyAddress()
                                : globalSettings.isGlobalProxyEnabled()
                                        ? globalSettings.getGlobalProxyAddress() : null;
                        options.putAll(liveRpcOptions(aria2Settings, proxy));
                    }
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
                    applyToEveryGid(gids, "change options",
                            gid -> aria2Client.changeOption(gid, options));
                    LOGGER.debug("Changed aria2 options for GIDs " + gids + ": " + options.keySet());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to change settings for download: " + download.getName(), e);
                throw new RuntimeException("Failed to change aria2 settings", e);
            }
        }, executor);
    }

    /**
     * Builds a complete live-update map. aria2.changeOption retains omitted
     * values, so zero/blank controls must be sent explicitly to clear a
     * previous per-GID limit, header, or proxy.
     */
    static Map<String, Object> liveRpcOptions(Aria2Settings settings,
            String effectiveProxy) {
        if (org.manager.download.handler.DownloadHandlerFactory
                .isSocksProxyAddress(effectiveProxy)) {
            throw new IllegalStateException(
                    "SOCKS settings require a proxychains route handoff");
        }
        Map<String, Object> options = new HashMap<>(settings.toRpcOptions());
        options.put("max-download-limit", settings.getDownloadLimitKB() > 0
                ? String.valueOf(settings.getDownloadLimitKB() * 1024L) : "0");
        options.put("max-upload-limit", settings.getUploadLimitKB() > 0
                ? String.valueOf(settings.getUploadLimitKB() * 1024L) : "0");
        options.put("referer", blankIfNull(settings.getReferer()));
        options.put("user-agent", blankIfNull(settings.getUserAgent()));
        options.put("header", blankIfNull(settings.getCookieHeader()));
        options.put("all-proxy", blankIfNull(effectiveProxy));
        return options;
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * Requests aria2's one-shot data recheck on every GID owned by this
     * selected download.
     * Unlike changeSettings, the temporary aria2 options are deliberately not
     * persisted: a context-menu recheck must either reach a live task or fail.
     * The manager separately persists the request outcome for the Actions tab.
     */
    @Override
    public CompletableFuture<Void> recheckData(Download download) {
        return CompletableFuture.supplyAsync(() -> {
            RecheckObservation observation = null;
            try {
                ensureInitialized();
                if (!canRecheckData(download)) {
                    throw new UnsupportedOperationException(
                            "Data recheck is unavailable for this download");
                }
                List<String> gids = liveTrackedGidsSnapshot(download);
                if (gids.isEmpty()) {
                    throw new IllegalStateException(
                            "The aria2 task is no longer active; no data was rechecked");
                }
                observation = beginRecheckObservation(download.getId(), gids);
                Map<String, Object> options = new HashMap<>();
                options.put("check-integrity", "true");
                if (download.getProtocol() == Download.Protocol.HTTP
                        || download.getProtocol() == Download.Protocol.HTTPS
                        || download.getProtocol() == Download.Protocol.FTP) {
                    String checksum = checksumOptionFor(download);
                    if (checksum == null) {
                        throw new IllegalStateException(
                                "HTTP/FTP data recheck requires a valid expected checksum");
                    }
                    options.put("checksum", checksum);
                }
                Map<String, Object> recheckOptions = Map.copyOf(options);
                applyToEveryGid(gids, "recheck data",
                        gid -> aria2Client.changeOption(gid, recheckOptions));
                LOGGER.info("Requested data recheck for " + download.getName()
                        + " on GIDs " + gids);
                RecheckObservation activeObservation = observation;
                try {
                    activeObservation.armGrace(progressPoller.schedule(
                            activeObservation::finishUnobserved,
                            RECHECK_OBSERVATION_GRACE_MS,
                            TimeUnit.MILLISECONDS));
                } catch (RuntimeException monitoringFailure) {
                    LOGGER.warn("Could not monitor aria2 integrity-check activity for "
                            + download.getName(), monitoringFailure);
                    activeObservation.finish();
                }
                return activeObservation.completion();
            } catch (Exception e) {
                if (observation != null) {
                    observation.finish();
                }
                LOGGER.error("Failed to request data recheck for: "
                        + (download == null ? "<null>" : download.getName()), e);
                throw new RuntimeException("Failed to request data recheck", e);
            }
        }, executor).thenCompose(completion -> completion);
    }

    private RecheckObservation beginRecheckObservation(String downloadId,
            List<String> gids) {
        RecheckObservation observation = new RecheckObservation(gids);
        RecheckObservation existing = recheckObservations.putIfAbsent(
                downloadId, observation);
        if (existing != null) {
            throw new IllegalStateException(
                    "A data recheck is already running for this download");
        }
        observation.completion().whenComplete((ignored, failure) -> {
            recheckObservations.remove(downloadId, observation);
            observation.cancelGrace();
        });
        return observation;
    }

    private void observeRecheckActivity(String downloadId, String gid,
            Map<String, Object> status) {
        RecheckObservation observation = recheckObservations.get(downloadId);
        if (observation != null) {
            observation.observe(gid, status);
        }
    }

    private void finishRecheckObservation(String downloadId) {
        RecheckObservation observation = recheckObservations.get(downloadId);
        if (observation != null) {
            observation.finish();
        }
    }

    /**
     * Tracks the integrity fields already returned by the regular progress
     * poll. This intentionally provides activity feedback, not a clean/corrupt
     * verdict: aria2 does not expose that verdict through these status fields.
     */
    private static final class RecheckObservation {

        private final java.util.Set<String> pendingGids;
        private final java.util.Set<String> observedGids = new java.util.HashSet<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private ScheduledFuture<?> graceTask;

        RecheckObservation(List<String> gids) {
            this.pendingGids = new java.util.HashSet<>(gids);
        }

        synchronized CompletableFuture<Void> completion() {
            return completion;
        }

        synchronized void observe(String gid, Map<String, Object> status) {
            if (!pendingGids.contains(gid)) {
                return;
            }
            String aria2Status = String.valueOf(status.get("status"));
            if ("complete".equals(aria2Status)
                    || "error".equals(aria2Status)
                    || "removed".equals(aria2Status)) {
                pendingGids.remove(gid);
            } else if (isIntegrityActivity(status)) {
                observedGids.add(gid);
            } else if (observedGids.contains(gid)) {
                pendingGids.remove(gid);
            }
            completeIfFinished();
        }

        private static boolean isIntegrityActivity(Map<String, Object> status) {
            return status.containsKey("verifiedLength")
                    || Boolean.parseBoolean(String.valueOf(
                            status.get("verifyIntegrityPending")));
        }

        /** Completes GIDs whose entire short check fell between poll ticks. */
        synchronized void finishUnobserved() {
            pendingGids.removeIf(gid -> !observedGids.contains(gid));
            completeIfFinished();
        }

        synchronized void finish() {
            pendingGids.clear();
            completeIfFinished();
        }

        synchronized void armGrace(ScheduledFuture<?> task) {
            if (completion.isDone()) {
                task.cancel(false);
            } else {
                graceTask = task;
            }
        }

        synchronized void cancelGrace() {
            if (graceTask != null) {
                graceTask.cancel(false);
                graceTask = null;
            }
        }

        private void completeIfFinished() {
            if (pendingGids.isEmpty()) {
                completion.complete(null);
            }
        }
    }

    /**
     * Turns a daemon push into an immediate status poll, so terminal and pause
     * transitions surface without waiting for the next batch tick. Both the
     * scheduling boundary and the runnable re-check ownership: cancellation
     * removes the GID from {@code pollTasks} before aria2 emits its final push.
     */
    void requestImmediatePoll(String gid) {
        if (!isPollableGid(gid)) {
            return;
        }
        try {
            progressPoller.execute(() -> {
                if (!isPollableGid(gid)) {
                    return;
                }
                pollDownloadProgress(gid);
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            // Poller already stopped; the batch tick is gone with it.
        }
    }

    /**
     * Adapter class to connect aria2 notifications to our download handler.
     */
    private class Aria2NotificationAdapter implements Aria2NotificationListener {

        @Override
        public void onDownloadStart(String gid) {
            // start transitions are driven by startDownload's own RPC reply
        }

        @Override
        public void onDownloadPause(String gid) {
            if (!routeChangeGids.contains(gid) && gidToIdMap.get(gid) != null) {
                requestImmediatePoll(gid);
            }
        }

        @Override
        public void onDownloadStop(String gid) {
            if (!routeChangeGids.contains(gid) && gidToIdMap.get(gid) != null) {
                requestImmediatePoll(gid);
            }
        }

        @Override
        public void onDownloadComplete(String gid) {
            if (!routeChangeGids.contains(gid) && gidToIdMap.get(gid) != null) {
                requestImmediatePoll(gid);
            }
        }

        @Override
        public void onDownloadError(String gid, Aria2RpcError error) {
            if (!routeChangeGids.contains(gid) && gidToIdMap.get(gid) != null) {
                requestImmediatePoll(gid);
            }
        }

        @Override
        public void onBtDownloadComplete(String gid) {
            onDownloadComplete(gid);
        }

    }
}
