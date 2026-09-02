package org.manager.download;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.manager.schedule.ScheduleSettings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a download task in the download manager.
 */
public class Download {

    public enum Status {
        CREATED,
        STARTING,
        DOWNLOADING,
        SEEDING,
        QUEUED,
        PAUSED,
        ERROR,
        COMPLETED,
        CONNECTING,
        CANCELED
    }

    /**
     * Source protocol/descriptor semantics, independent of the engine in
     * {@link Type}. For example, HTTP, SFTP, magnet, and torrent-file downloads
     * are all currently executed by the ARIA2 engine but retain distinct
     * protocol values for routing and presentation decisions.
     */
    public enum Protocol {
        HTTP,
        HTTPS,
        FTP,
        SFTP,
        TORRENT,
        MAGNET,
        METALINK;

        /**
         * Classifies a URI. Descriptor extensions take precedence over their
         * transport, so an HTTPS URL ending in {@code .torrent} is TORRENT,
         * not HTTPS.
         *
         * @param uri source URI, or null
         * @return the classified protocol, or null for an unsupported URI
         */
        public static Protocol fromUri(URI uri) {
            if (uri == null) {
                return null;
            }

            String scheme = uri.getScheme();
            if (scheme != null) {
                switch (scheme.toLowerCase(Locale.ROOT)) {
                    case "magnet" -> {
                        return MAGNET;
                    }
                    case "torrent" -> {
                        return TORRENT;
                    }
                    case "metalink" -> {
                        return METALINK;
                    }
                    default -> {
                        // Transport classification follows descriptor detection.
                    }
                }
            }

            Protocol descriptor = fromFileName(uri.getPath());
            if (descriptor != null) {
                return descriptor;
            }
            if (scheme == null) {
                return null;
            }
            return switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http" -> HTTP;
                case "https" -> HTTPS;
                case "ftp", "ftps" -> FTP;
                case "sftp" -> SFTP;
                default -> null;
            };
        }

        /** Classifies a local descriptor path; non-descriptors return null. */
        public static Protocol fromPath(Path path) {
            return path == null ? null : fromFileName(path.toString());
        }

        /** Classifies a descriptor file name or URI path. */
        public static Protocol fromFileName(String fileName) {
            if (fileName == null) {
                return null;
            }
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".torrent")) {
                return TORRENT;
            }
            if (lower.endsWith(".metalink") || lower.endsWith(".meta4")) {
                return METALINK;
            }
            return null;
        }

        public boolean supportsPeerDetails() {
            return this == TORRENT || this == MAGNET;
        }

        public boolean requiresAria2() {
            return supportsPeerDetails() || this == METALINK || this == SFTP;
        }

        public boolean isDirectTransfer() {
            return this == HTTP || this == HTTPS || this == FTP || this == SFTP;
        }
    }

    public enum Type {
        ARIA2,
        YOUTUBE,
        WEBSITE_SCRAPING,
        TOR,
        PROXYCHAINS,
        CURL
    }

    private final String id;
    private final Object lock = new Object(); // Synchronization lock
    private volatile String gid; // aria2 GID
    private volatile String name;
    /** Explicit filename entered by the user; null keeps engine-native naming. */
    private volatile String requestedFileName;
    /** Actual output artifacts reported by the selected download engine. */
    private final List<Path> outputPaths;
    private volatile boolean overrideOutputPath = true;
    private volatile URI uri;
    private volatile Protocol protocol;
    private volatile List<URI> mirrors;
    private volatile Path destination;
    private volatile Type type;
    private volatile Status status;
    private volatile long size; // total size in bytes
    private volatile long downloaded; // downloaded bytes
    private volatile float speed; // current speed in bytes/second
    private volatile float progress; // 0-100
    private volatile float uploadSpeed; // current upload speed in bytes/second (BitTorrent)
    private volatile int connections; // current connection count (aria2)
    private volatile int seeders; // connected seeder count (BitTorrent)
    private volatile String infoHash; // BitTorrent info hash, when applicable
    private volatile int queuePosition; // position in the download queue (lower = earlier)
    /** QUEUED but excluded from automatic admission until the user starts it. */
    private volatile boolean manualStartRequired;
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    /** Accumulated wall-clock time spent in an active transfer state. */
    private volatile long activeElapsedMillis;
    /** Start of the current active interval; runtime-only and never persisted. */
    private volatile Instant activeElapsedSince;
    private volatile String errorMessage;
    private volatile DownloadSettings settings; // unified settings object
    private volatile String checksumAlgorithm; // detected expected-hash algorithm (sha256, md5, ...)
    private volatile String expectedChecksum; // detected expected hash in hex
    private volatile ScheduleSettings scheduleSettings;
    private volatile long attemptGeneration; // per-start operation token, never persisted

    /**
     * Creates a new Download instance with a random UUID.
     */
    public Download() {
        this(UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * Creates a Download with an explicit id and creation time. Used by
     * Jackson to restore persisted downloads with their original identity.
     *
     * @param id        the download id
     * @param createdAt the creation timestamp
     */
    @JsonCreator
    public Download(@JsonProperty("id") String id, @JsonProperty("createdAt") Instant createdAt) {
        this.id = id;
        this.mirrors = new ArrayList<>();
        this.outputPaths = new ArrayList<>();
        this.status = Status.CREATED;
        this.createdAt = createdAt;

        // Settings will be initialized based on type when needed
    }

    /**
     * Creates a new Download instance with the specified URI.
     *
     * @param uri The URI to download from
     */
    public Download(URI uri) {
        this();
        setUri(uri);

        // Set type based on URI. Media detection must come before the generic
        // http/https branch: known media platforms and streaming manifests
        // (m3u8/DASH/fragmented MP4) are handled by the yt-dlp engine, while
        // everything else (including direct media file links, which benefit
        // from aria2 multi-connection) goes to aria2.
        if (MediaUrlDetector.isMediaUrl(uri)) {
            this.type = Type.YOUTUBE; // Use yt-dlp handler for media URLs
        } else {
            // Protocol describes the source; Type independently selects the
            // engine. aria2 owns all non-media protocols accepted by ODM.
            this.type = Type.ARIA2;
        }

        // Settings initialize lazily (getSettings) or via the manager's
        // injected factory (initSettings(factory)) — the model must not
        // eagerly reach into the application singleton for them

        // Try to get filename from URI path
        String path = uri.getPath();
        if (path != null && !path.isEmpty()) {
            String[] parts = path.split("/");
            if (parts.length > 0) {
                String candidate = parts[parts.length - 1];
                try {
                    candidate = java.net.URLDecoder.decode(candidate.replace("+", "%2B"),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (!candidate.isBlank()) {
                        setName(candidate);
                    }
                } catch (IllegalArgumentException invalidName) {
                    // A hostile/invalid URI segment must never bypass the
                    // same filename validation used by public setters.
                    this.name = null;
                }
            }
        }

        if (this.name == null || this.name.isEmpty()) {
            setName("download_" + this.id.substring(0, 8));
        }
    }

    /**
     * Creates a new Download instance for a torrent file.
     *
     * @param torrentPath Path to the torrent file
     * @param destination Destination directory
     * @return A new Download instance
     */
    public static Download fromTorrent(Path torrentPath, Path destination) {
        Download download = new Download();
        download.setName(torrentPath.getFileName().toString());
        download.setUri(torrentPath.toUri());
        download.setProtocol(Protocol.TORRENT);
        download.type = Type.ARIA2;
        download.destination = destination;
        // Settings initialize lazily via getSettings()
        return download;
    }

    /**
     * Creates a new Download instance for a Metalink file (.metalink/.meta4).
     * aria2 processes the Metalink itself: mirror selection and segmented
     * download are handled natively via the addMetalink RPC.
     *
     * @param metaLinkPath Path to the Metalink file
     * @param destination Destination directory
     * @return A new Download instance
     */
    public static Download fromMetaLink(Path metaLinkPath, Path destination) {
        Download download = new Download();
        download.setName(metaLinkPath.getFileName().toString());
        download.setUri(metaLinkPath.toUri());
        download.setProtocol(Protocol.METALINK);
        download.type = Type.ARIA2;
        download.destination = destination;
        // Settings initialize lazily via getSettings()
        return download;
    }

    /**
     * Initializes the settings object based on the download type.
     */
    private void initSettings() {
        synchronized (lock) {
            if (settings != null) {
                return; // Already initialized
            }

            // Use the application's live GlobalSettings so per-download
            // defaults (proxy, aria2.*, ytdlp.* properties) reflect the
            // persisted settings rather than a fresh default instance.
            org.manager.GlobalSettings globalSettings;
            try {
                globalSettings = org.manager.ApplicationContext.getGlobalSettings();
            } catch (IllegalStateException e) {
                // Application factory unavailable (unit tests); fall back to defaults
                globalSettings = new org.manager.GlobalSettings();
            }
            DownloadSettingsFactory factory = new DownloadSettingsFactory(globalSettings);
            settings = factory.createSettings(type);
        }
    }

    /**
     * Initializes the settings object with a provided factory. This allows for
     * dependency injection of the settings factory.
     */
    public void initSettings(DownloadSettingsFactory factory) {
        synchronized (lock) {
            if (settings != null) {
                return; // Already initialized
            }
            settings = factory.createSettings(type);
        }
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public String getGid() {
        return gid;
    }

    public void setGid(String gid) {
        synchronized (lock) {
            this.gid = gid;
        }
    }

    public String getName() {
        return name;
    }

    /**
     * Sets the download's display/output file name. The name becomes part of
     * resolved output paths in every engine, so it must be a plain file
     * name: path separators, {@code .} and {@code ..} would let a crafted
     * name redirect engine file operations outside the destination.
     *
     * @param name the file name, or null/empty for unset
     * @throws IllegalArgumentException when the name contains path
     *         separators or directory references
     */
    public void setName(String name) {
        if (name != null) {
            org.manager.util.PathSafety.requireSafeFileName(name);
        }
        synchronized (lock) {
            this.name = name;
        }
    }

    public String getRequestedFileName() {
        return requestedFileName;
    }

    /**
     * Stores an explicit output filename. Blank values restore the engine's
     * normal naming template; non-blank values are confined to one plain
     * filename.
     */
    public void setRequestedFileName(String requestedFileName) {
        String normalized = requestedFileName == null ? null : requestedFileName.strip();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null) {
            org.manager.util.PathSafety.requireSafeFileName(normalized);
        }
        synchronized (lock) {
            this.requestedFileName = normalized;
        }
    }

    /** Records a real artifact path reported by a download engine. */
    public void recordOutputPath(Path outputPath) {
        if (outputPath == null) {
            return;
        }
        synchronized (lock) {
            Path normalized = outputPath;
            if (!normalized.isAbsolute() && destination != null) {
                normalized = destination.resolve(normalized);
            }
            normalized = normalized.toAbsolutePath().normalize();
            if (!outputPaths.contains(normalized)) {
                outputPaths.add(normalized);
            }
        }
    }

    public List<Path> getOutputPaths() {
        synchronized (lock) {
            return List.copyOf(outputPaths);
        }
    }

    public void setOutputPaths(List<Path> paths) {
        synchronized (lock) {
            outputPaths.clear();
        }
        if (paths != null) {
            paths.forEach(this::recordOutputPath);
        }
    }

    /**
     * Returns the first engine-reported artifact, falling back to the safe
     * expected path for legacy/in-progress downloads.
     */
    @JsonIgnore
    public Path getPrimaryOutputPath() {
        synchronized (lock) {
            if (!outputPaths.isEmpty()) {
                return outputPaths.get(0);
            }
            if (destination == null || name == null || name.isBlank()) {
                return null;
            }
            org.manager.util.PathSafety.requireSafeFileName(name);
            return destination.resolve(name).toAbsolutePath().normalize();
        }
    }

    public boolean isOverrideOutputPath() {
        return overrideOutputPath;
    }

    public void setOverrideOutputPath(boolean overrideOutputPath) {
        synchronized (lock) {
            this.overrideOutputPath = overrideOutputPath;
        }
    }

    /** @deprecated use {@link #setOverrideOutputPath(boolean)}. */
    @Deprecated(forRemoval = false)
    public void seOverrideOutputPath(boolean overrideOutputPath) {
        setOverrideOutputPath(overrideOutputPath);
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        synchronized (lock) {
            this.uri = uri;
            this.protocol = deriveProtocol();
        }
    }

    public Protocol getProtocol() {
        return protocol;
    }

    /**
     * Overrides URI-derived classification when the caller has stronger
     * semantic knowledge, such as an opaque endpoint known to return a
     * torrent descriptor. Null restores URI-derived classification.
     */
    public void setProtocol(Protocol protocol) {
        synchronized (lock) {
            this.protocol = protocol != null ? protocol : deriveProtocol();
        }
    }

    private Protocol deriveProtocol() {
        Protocol fromUri = Protocol.fromUri(uri);
        if (infoHash != null && !infoHash.isBlank() && fromUri != Protocol.MAGNET) {
            return Protocol.TORRENT;
        }
        return fromUri;
    }

    public List<URI> getMirrors() {
        synchronized (lock) {
            return new ArrayList<>(mirrors);
        }
    }

    /**
     * Replaces the mirror list. Used by state deserialization and mirror
     * editing.
     *
     * @param mirrors the new mirror list, may be null for none
     */
    public void setMirrors(List<URI> mirrors) {
        synchronized (lock) {
            this.mirrors = mirrors != null ? new ArrayList<>(mirrors) : new ArrayList<>();
        }
    }

    public void addMirror(URI mirror) {
        synchronized (lock) {
            this.mirrors.add(mirror);
        }
    }

    public void removeMirror(URI mirror) {
        synchronized (lock) {
            this.mirrors.remove(mirror);
        }
    }

    public Path getDestination() {
        return destination;
    }

    public void setDestination(Path destination) {
        synchronized (lock) {
            this.destination = destination;
        }
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        synchronized (lock) {
            this.type = type;
        }
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        synchronized (lock) {
            applyStatusTransition(status, Instant.now());
        }
    }

    /**
     * Atomically changes status only when the current value is the expected
     * one. External-process workers use this to avoid overwriting a PAUSED or
     * CANCELED state after their child process exits or finishes launching.
     *
     * @return true when the transition was applied
     */
    public boolean compareAndSetStatus(Status expected, Status replacement) {
        synchronized (lock) {
            if (this.status != expected) {
                return false;
            }
            applyStatusTransition(replacement, Instant.now());
            return true;
        }
    }

    /** Applies one lifecycle transition and maintains active elapsed time. */
    private void applyStatusTransition(Status replacement, Instant now) {
        boolean wasActive = isElapsedActiveStatus(this.status);
        boolean willBeActive = isElapsedActiveStatus(replacement);
        if (wasActive && !willBeActive) {
            accumulateActiveInterval(now);
        } else if (!wasActive && willBeActive) {
            activeElapsedSince = now;
        }

        this.status = replacement;
        if ((replacement == Status.DOWNLOADING || replacement == Status.SEEDING)
                && startedAt == null) {
            this.startedAt = now;
        } else if (replacement == Status.COMPLETED && completedAt == null) {
            this.completedAt = now;
        }
    }

    private void accumulateActiveInterval(Instant now) {
        if (activeElapsedSince != null) {
            activeElapsedMillis += Math.max(0,
                    Duration.between(activeElapsedSince, now).toMillis());
            activeElapsedSince = null;
        }
    }

    private static boolean isElapsedActiveStatus(Status status) {
        return status == Status.STARTING
                || status == Status.CONNECTING
                || status == Status.DOWNLOADING
                || status == Status.SEEDING;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        synchronized (lock) {
            this.size = size;
            // Update progress when size changes
            updateProgress();
        }
    }

    public long getDownloaded() {
        return downloaded;
    }

    public void setDownloaded(long downloaded) {
        synchronized (lock) {
            this.downloaded = downloaded;
            // Update progress when downloaded bytes change
            updateProgress();
        }
    }

    private void updateProgress() {
        // This method is called from synchronized blocks, so no additional sync needed
        if (size > 0) {
            this.progress = (float) downloaded / size * 100;
        } else {
            this.progress = 0;
        }
    }

    /**
     * Gets the number of connections for this download.
     *
     * @return The number of connections
     */
    public int getConnections() {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            return settings.getConnections();
        }
    }

    /**
     * Sets the number of connections for this download.
     *
     * @param connections The number of connections
     * @return This download for method chaining
     */
    public Download setConnections(int connections) {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            settings.setConnections(connections);
            return this;
        }
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        synchronized (lock) {
            this.speed = speed;
        }
    }

    public float getUploadSpeed() {
        return uploadSpeed;
    }

    public void setUploadSpeed(float uploadSpeed) {
        synchronized (lock) {
            this.uploadSpeed = uploadSpeed;
        }
    }

    public int getConnectionCount() {
        return connections;
    }

    public void setConnectionCount(int connections) {
        synchronized (lock) {
            this.connections = connections;
        }
    }

    public int getSeeders() {
        return seeders;
    }

    public void setSeeders(int seeders) {
        synchronized (lock) {
            this.seeders = seeders;
        }
    }

    public String getInfoHash() {
        return infoHash;
    }

    public void setInfoHash(String infoHash) {
        synchronized (lock) {
            this.infoHash = infoHash;
            if (infoHash != null && !infoHash.isBlank() && protocol != Protocol.MAGNET) {
                // aria2 only reports infoHash for BitTorrent work. This also
                // classifies opaque HTTP endpoints once their real semantics
                // become known.
                this.protocol = Protocol.TORRENT;
            }
        }
    }

    public int getQueuePosition() {
        return queuePosition;
    }

    public void setQueuePosition(int queuePosition) {
        synchronized (lock) {
            this.queuePosition = queuePosition;
        }
    }

    public boolean isManualStartRequired() {
        return manualStartRequired;
    }

    public void setManualStartRequired(boolean manualStartRequired) {
        synchronized (lock) {
            this.manualStartRequired = manualStartRequired;
        }
    }

    /**
     * Token of the start operation this download currently belongs to.
     * The manager stamps a fresh token on every start submission so late
     * events of a superseded operation can be detected and dropped. Not
     * part of the persisted state: generations live only within one
     * manager run.
     *
     * @return the current attempt generation (0 when never started)
     */
    @JsonIgnore
    public long getAttemptGeneration() {
        return attemptGeneration;
    }

    public void setAttemptGeneration(long attemptGeneration) {
        synchronized (lock) {
            this.attemptGeneration = attemptGeneration;
        }
    }

    public float getProgress() {
        return progress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        synchronized (lock) {
            this.startedAt = startedAt;
        }
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        synchronized (lock) {
            this.completedAt = completedAt;
        }
    }

    /**
     * Returns active transfer time, including the current active interval.
     * Queued and paused wall-clock time is deliberately excluded.
     */
    @JsonProperty("activeElapsedMillis")
    public long getActiveElapsedMillis() {
        synchronized (lock) {
            if (activeElapsedSince == null) {
                return activeElapsedMillis;
            }
            return activeElapsedMillis + Math.max(0,
                    Duration.between(activeElapsedSince, Instant.now()).toMillis());
        }
    }

    @JsonProperty("activeElapsedMillis")
    public void setActiveElapsedMillis(long activeElapsedMillis) {
        synchronized (lock) {
            this.activeElapsedMillis = Math.max(0, activeElapsedMillis);
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        synchronized (lock) {
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Gets the algorithm of the detected expected checksum, when one was
     * auto-detected (for example from a sibling .sha256 file).
     *
     * @return the algorithm name (sha256, sha512, sha1, md5), or null
     */
    public String getChecksumAlgorithm() {
        return checksumAlgorithm;
    }

    public void setChecksumAlgorithm(String checksumAlgorithm) {
        synchronized (lock) {
            this.checksumAlgorithm = checksumAlgorithm;
        }
    }

    /**
     * Gets the expected checksum hex digest detected for this download.
     *
     * @return the expected checksum, or null
     */
    public String getExpectedChecksum() {
        return expectedChecksum;
    }

    public void setExpectedChecksum(String expectedChecksum) {
        synchronized (lock) {
            this.expectedChecksum = expectedChecksum;
        }
    }

    /** Per-download schedule persisted with the download state. */
    public ScheduleSettings getScheduleSettings() {
        synchronized (lock) {
            return scheduleSettings != null ? scheduleSettings.copy() : null;
        }
    }

    public void setScheduleSettings(ScheduleSettings scheduleSettings) {
        synchronized (lock) {
            this.scheduleSettings = scheduleSettings != null ? scheduleSettings.copy() : null;
        }
    }

    /**
     * Gets all options as a map. For specific settings, prefer using
     * getSettings() and its specific getters.
     *
     * @return The options map
     */
    @JsonIgnore
    public Map<String, String> getOptions() {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            return settings.toMap();
        }
    }

    /**
     * Sets an option value. For specific settings, prefer using getSettings()
     * and its specific setters.
     *
     * @param key The option key
     * @param value The option value
     * @return This download for method chaining
     */
    public Download setOption(String key, String value) {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }

            // Apply to settings based on known keys
            if (key.equals("connections")) {
                try {
                    settings.setConnections(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    // Ignore parsing errors
                }
            } else if (key.equals("use-proxy")) {
                settings.setUseProxy(Boolean.parseBoolean(value));
            } else if (key.equals("proxy-address")) {
                settings.setProxyAddress(value);
            } else {
                // Use generic option setting for unknown keys
                settings.setOption(key, value);
            }
            return this;
        }
    }

    /**
     * Gets the settings object for this download. If settings are not yet
     * initialized, they will be created based on the download type.
     *
     * This is the preferred way to configure download settings rather than
     * using the legacy getter/setter methods.
     *
     * @return The settings object
     */
    public DownloadSettings getSettings() {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            return settings;
        }
    }

    /**
     * Sets the settings object for this download.
     *
     * @param settings The settings object
     */
    public void setSettings(DownloadSettings settings) {
        synchronized (lock) {
            this.settings = settings;
        }
    }

    /**
     * Checks if proxy should be used for this download.
     *
     * @return true if proxy should be used, false otherwise
     */
    public boolean isUseProxy() {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            return settings.isUseProxy();
        }
    }

    /**
     * Sets whether to use a proxy for this download.
     *
     * @param useProxy true to use proxy, false otherwise
     * @return This download for method chaining
     */
    public Download setUseProxy(boolean useProxy) {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            settings.setUseProxy(useProxy);
            return this;
        }
    }

    /**
     * Gets the proxy address for this download.
     *
     * @return The proxy address
     */
    public String getProxyAddress() {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            return settings.getProxyAddress();
        }
    }

    /**
     * Sets the proxy address for this download.
     *
     * @param proxyAddress The proxy address
     * @return This download for method chaining
     */
    public Download setProxyAddress(String proxyAddress) {
        synchronized (lock) {
            if (settings == null) {
                initSettings();
            }
            settings.setProxyAddress(proxyAddress);
            return this;
        }
    }

    @Override
    public String toString() {
        return "Download{"
                + "id='" + id + '\''
                + ", name='" + name + '\''
                + ", status=" + status
                + ", progress=" + progress + "%"
                + '}';
    }
}
