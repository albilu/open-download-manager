package org.manager.download;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.manager.schedule.ScheduleSettings;

/**
 * Represents a download task in the download manager.
 */
public class Download {

    public enum Status {
        DOWNLOADING,
        QUEUED,
        PAUSED,
        ERROR,
        COMPLETED,
        CONNECTING,
        CANCELED
    }

    public enum Type {
        // HTTP,
        // FTP,
        // TORRENT,
        // MAGNET,
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
    private volatile boolean overrideOutputPath = true;
    private volatile URI uri;
    private volatile List<URI> mirrors;
    private volatile Path destination;
    private volatile Type type;
    private volatile Status status;
    private volatile long size; // total size in bytes
    private volatile long downloaded; // downloaded bytes
    private volatile float speed; // current speed in bytes/second
    private volatile float progress; // 0-100
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String errorMessage;
    private volatile DownloadSettings settings; // unified settings object
    private volatile ScheduleSettings scheduleSettings; // scheduling configuration

    /**
     * Creates a new Download instance with a random UUID.
     */
    public Download() {
        this.id = UUID.randomUUID().toString();
        this.mirrors = new ArrayList<>();
        this.status = Status.QUEUED;
        this.createdAt = Instant.now();

        // Settings will be initialized based on type when needed
    }

    /**
     * Creates a new Download instance with the specified URI.
     *
     * @param uri The URI to download from
     */
    public Download(URI uri) {
        this();
        this.uri = uri;

        // Set type based on URI. YouTube detection must come before the
        // generic http/https branch, otherwise YouTube URLs would always be
        // typed as ARIA2.
        if (uri.toString().contains("youtube.com") || uri.toString().contains("youtu.be")) {
            this.type = Type.YOUTUBE; // Use YouTube handler for YouTube URLs
        } else {
            String scheme = uri.getScheme().toLowerCase();
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("ftp")
                    || scheme.equals("magnet")) {
                this.type = Type.ARIA2;
            } else {
                // Default to ARIA2 for complex downloads
                this.type = Type.ARIA2;
            }
        }

        // Initialize settings based on type
        initSettings();

        // Try to get filename from URI path
        String path = uri.getPath();
        if (path != null && !path.isEmpty()) {
            String[] parts = path.split("/");
            if (parts.length > 0) {
                this.name = parts[parts.length - 1];
            }
        }

        if (this.name == null || this.name.isEmpty()) {
            this.name = "download_" + this.id.substring(0, 8);
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
        download.name = torrentPath.getFileName().toString();
        download.uri = torrentPath.toUri();
        // download.type = Type.TORRENT;
        download.type = Type.ARIA2;
        download.destination = destination;
        download.initSettings(); // Initialize settings based on type
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
        download.name = metaLinkPath.getFileName().toString();
        download.uri = metaLinkPath.toUri();
        download.type = Type.ARIA2;
        download.destination = destination;
        download.initSettings(); // Initialize settings based on type
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

            // Use the factory to create appropriate settings
            DownloadSettingsFactory factory = new DownloadSettingsFactory(new org.manager.GlobalSettings());
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

    public void setName(String name) {
        synchronized (lock) {
            this.name = name;
        }
    }

    public boolean isOverrideOutputPath() {
        return overrideOutputPath;
    }

    public void seOverrideOutputPath(boolean overrideOutputPath) {
        synchronized (lock) {
            this.overrideOutputPath = overrideOutputPath;
        }
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        synchronized (lock) {
            this.uri = uri;
        }
    }

    public List<URI> getMirrors() {
        synchronized (lock) {
            return new ArrayList<>(mirrors);
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
            this.status = status;

            // Update timestamps based on status changes
            if (status == Status.DOWNLOADING && startedAt == null) {
                this.startedAt = Instant.now();
            } else if (status == Status.COMPLETED && completedAt == null) {
                this.completedAt = Instant.now();
            }
        }
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

    public float getProgress() {
        return progress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
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
     * Gets all options as a map. For specific settings, prefer using
     * getSettings() and its specific getters.
     *
     * @return The options map
     */
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
     * Gets the schedule settings for this download.
     *
     * @return The schedule settings, or null if not set
     */
    public ScheduleSettings getScheduleSettings() {
        return scheduleSettings;
    }

    /**
     * Sets the schedule settings for this download.
     *
     * @param scheduleSettings The schedule settings to set
     * @return This download for method chaining
     */
    public Download setScheduleSettings(ScheduleSettings scheduleSettings) {
        synchronized (lock) {
            this.scheduleSettings = scheduleSettings;
        }
        return this;
    }

    /**
     * Checks if this download has schedule settings configured.
     *
     * @return true if schedule settings are configured, false otherwise
     */
    public boolean hasScheduleSettings() {
        return scheduleSettings != null;
    }

    /**
     * Checks if this download should be active based on its schedule settings.
     * If no schedule settings are configured, returns true (always active).
     *
     * @return true if the download should be active now, false otherwise
     */
    public boolean shouldBeActiveNow() {
        synchronized (lock) {
            if (scheduleSettings == null) {
                return true; // No schedule restrictions
            }
            return scheduleSettings.isActiveNow();
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
        String scheduleInfo = scheduleSettings != null ? ", scheduled=true" : "";
        return "Download{"
                + "id='" + id + '\''
                + ", name='" + name + '\''
                + ", status=" + status
                + ", progress=" + progress + "%"
                + scheduleInfo
                + '}';
    }
}
