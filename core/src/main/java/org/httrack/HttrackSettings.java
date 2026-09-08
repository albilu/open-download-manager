package org.httrack;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.manager.download.DownloadSettings;

/**
 * Settings for the httrack website crawler. These settings are used to
 * configure httrack for website downloads.
 */
public class HttrackSettings extends DownloadSettings {

    /** Deliberately bounded crawl scopes exposed by ODM. */
    public enum CrawlScope {
        SAME_DIRECTORY,
        SAME_HOST,
        SAME_DOMAIN,
        NEARBY_EXTERNAL_ASSETS,
        CUSTOM_EXTERNAL_DEPTH;

        public static CrawlScope fromProperty(String value) {
            if (value == null || value.isBlank()) {
                return SAME_HOST;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)
                        .replace('-', '_').replace(' ', '_'));
            } catch (IllegalArgumentException invalid) {
                return SAME_HOST;
            }
        }
    }

    /** How HTTrack should treat an existing mirror cache. */
    public enum RunMode {
        MIRROR,
        CONTINUE,
        UPDATE
    }

    @Override
    public boolean supports(org.manager.download.ExternalToolSettings.Capability capability) {
        return capability == org.manager.download.ExternalToolSettings.Capability.CONNECTIONS
                || capability == org.manager.download.ExternalToolSettings.Capability.DOWNLOAD_LIMIT
                || capability == org.manager.download.ExternalToolSettings.Capability.MAX_RETRIES
                || capability == org.manager.download.ExternalToolSettings.Capability.REFERER
                || capability == org.manager.download.ExternalToolSettings.Capability.USER_AGENT
                || capability == org.manager.download.ExternalToolSettings.Capability.COOKIE
                || capability == org.manager.download.ExternalToolSettings.Capability.PROXY
                || capability == org.manager.download.ExternalToolSettings.Capability.SOCKS_PROXY;
    }

    private String url;
    private Path outputDirectory;
    private int depth = 5;//
    private CrawlScope crawlScope = CrawlScope.SAME_HOST;
    private int externalDepth = 1;
    private boolean includeImages = true;
    private boolean includeVideos = true;
    private boolean includeAudio = true;
    private boolean includeDocuments = true;
    private boolean includeArchives = false;//
    private int maxRate = 0; // 0 means no limit, in KB/s
    // HTTrack caps sockets at 8 while its default security limits are enabled.
    public static final int MAX_CONNECTIONS = 8;

    @Override
    public int maxConnectionsLimit() {
        return MAX_CONNECTIONS;
    }

    private int connections = MAX_CONNECTIONS;
    /** Null means HTTrack's native identity and robots policy. */
    private String userAgent = null;
    private boolean useProxy = false;
    private String proxyAddress = null;
    private String proxyUsername = null;
    private String proxyPassword = null;
    private List<String> excludePatterns = new ArrayList<>();//
    private List<String> includePatterns = new ArrayList<>();//
    private boolean mirrorMode = true;
    private long maxTotalSizeBytes;
    private long maxNonHtmlFileSizeBytes;
    private long maxHtmlFileSizeBytes;
    /** -1 leaves HTTrack's native value alone; 0 explicitly means unlimited. */
    private int maxLinks = -1;
    private int maxDurationSeconds;
    /** -1 leaves HTTrack's native value alone; 0 explicitly disables the throttle. */
    private double connectionsPerSecond = -1;
    private int delayBetweenFilesSeconds;
    private List<String> additionalHttpHeaders = new ArrayList<>();
    private Path cookieFile;
    private RunMode runMode = RunMode.MIRROR;
    private boolean purgeOldFiles;

    /**
     * Creates new httrack settings with default values.
     */
    public HttrackSettings() {
    }

    /**
     * Creates new httrack settings with the specified URL and output directory.
     *
     * @param url The URL of the website to download
     * @param outputDirectory The directory to save the downloaded website
     */
    public HttrackSettings(String url, Path outputDirectory) {
        this.url = url;
        this.outputDirectory = outputDirectory;
    }

    /**
     * Gets the URL to download.
     *
     * @return The URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the URL to download.
     *
     * @param url The URL
     * @return This settings object for chaining
     */
    public HttrackSettings setUrl(String url) {
        this.url = url;
        return this;
    }

    /**
     * Gets the output directory where the website will be saved.
     *
     * @return The output directory
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Sets the output directory where the website will be saved.
     *
     * @param outputDirectory The output directory
     * @return This settings object for chaining
     */
    public HttrackSettings setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
        return this;
    }

    /**
     * Sets the output directory where the website will be saved.
     *
     * @param outputDirectory The output directory as a string; null clears
     *                        the directory (httrack then uses the working
     *                        directory)
     * @return This settings object for chaining
     */
    public HttrackSettings setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory != null ? Paths.get(outputDirectory) : null;
        return this;
    }

    /**
     * Gets the maximum depth to crawl.
     *
     * @return The maximum depth
     */
    public int getDepth() {
        return depth;
    }

    /**
     * Sets the maximum depth to crawl.
     *
     * @param depth The maximum depth (must be at least 1)
     * @return This settings object for chaining
     * @throws IllegalArgumentException if depth is zero or negative
     */
    public HttrackSettings setDepth(int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("Depth must be at least 1: " + depth);
        }
        this.depth = depth;
        return this;
    }

    /**
     * Checks if external links should be followed.
     *
     * @return true if external links should be followed, false otherwise
     */
    public boolean isFollowExternalLinks() {
        return crawlScope == CrawlScope.CUSTOM_EXTERNAL_DEPTH;
    }

    /**
     * Sets whether external links should be followed.
     *
     * @param followExternalLinks true to follow external links, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setFollowExternalLinks(boolean followExternalLinks) {
        this.crawlScope = followExternalLinks
                ? CrawlScope.CUSTOM_EXTERNAL_DEPTH : CrawlScope.SAME_HOST;
        return this;
    }

    public CrawlScope getCrawlScope() {
        return crawlScope;
    }

    public HttrackSettings setCrawlScope(CrawlScope crawlScope) {
        this.crawlScope = crawlScope == null ? CrawlScope.SAME_HOST : crawlScope;
        return this;
    }

    public int getExternalDepth() {
        return externalDepth;
    }

    public HttrackSettings setExternalDepth(int externalDepth) {
        if (externalDepth < 1 || externalDepth > 20) {
            throw new IllegalArgumentException(
                    "External depth must be between 1 and 20: " + externalDepth);
        }
        this.externalDepth = externalDepth;
        return this;
    }

    /**
     * Checks if images should be included in the download.
     *
     * @return true if images should be included, false otherwise
     */
    public boolean isIncludeImages() {
        return includeImages;
    }

    /**
     * Sets whether images should be included in the download.
     *
     * @param includeImages true to include images, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludeImages(boolean includeImages) {
        this.includeImages = includeImages;
        return this;
    }

    /**
     * Checks if videos should be included in the download.
     *
     * @return true if videos should be included, false otherwise
     */
    public boolean isIncludeVideos() {
        return includeVideos;
    }

    /**
     * Sets whether videos should be included in the download.
     *
     * @param includeVideos true to include videos, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludeVideos(boolean includeVideos) {
        this.includeVideos = includeVideos;
        return this;
    }

    /**
     * Checks if audio files should be included in the download.
     *
     * @return true if audio files should be included, false otherwise
     */
    public boolean isIncludeAudio() {
        return includeAudio;
    }

    /**
     * Sets whether audio files should be included in the download.
     *
     * @param includeAudio true to include audio files, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludeAudio(boolean includeAudio) {
        this.includeAudio = includeAudio;
        return this;
    }

    /**
     * Checks if documents should be included in the download.
     *
     * @return true if documents should be included, false otherwise
     */
    public boolean isIncludeDocuments() {
        return includeDocuments;
    }

    /**
     * Sets whether documents should be included in the download.
     *
     * @param includeDocuments true to include documents, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludeDocuments(boolean includeDocuments) {
        this.includeDocuments = includeDocuments;
        return this;
    }

    /**
     * Checks if archives should be included in the download.
     *
     * @return true if archives should be included, false otherwise
     */
    public boolean isIncludeArchives() {
        return includeArchives;
    }

    /**
     * Sets whether archives should be included in the download.
     *
     * @param includeArchives true to include archives, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludeArchives(boolean includeArchives) {
        this.includeArchives = includeArchives;
        return this;
    }

    /**
     * Gets the maximum download rate in KB/s. A value of 0 means no limit.
     *
     * @return The maximum download rate in KB/s
     */
    public int getMaxRate() {
        return maxRate;
    }

    /**
     * Sets the maximum download rate in KiB/s. Zero means unlimited.
     *
     * @param maxRate The maximum download rate in KB/s
     * @return This settings object for chaining
     * @throws IllegalArgumentException if maxRate is negative
     */
    public HttrackSettings setMaxRate(int maxRate) {
        if (maxRate < 0) {
            throw new IllegalArgumentException("Max rate cannot be negative (KiB/s): " + maxRate);
        }
        this.maxRate = maxRate;
        return this;
    }

    // ===== ExternalToolSettings bridge =====

    @Override
    public int getDownloadLimitKB() {
        return getMaxRate();
    }

    @Override
    public HttrackSettings setDownloadLimitKB(int kibPerSecond) {
        setMaxRate(Math.max(0, kibPerSecond));
        return this;
    }

    /**
     * Gets the number of concurrent connections.
     *
     * @return The number of concurrent connections
     */
    public int getConnections() {
        return connections;
    }

    /**
     * Sets the number of concurrent connections.
     *
     * @param connections Positive count, capped to HTTrack's active socket limit
     * @return This settings object for chaining
     * @throws IllegalArgumentException if connections is not positive
     */
    public HttrackSettings setConnections(int connections) {
        if (connections < 1) {
            throw new IllegalArgumentException("Connections must be positive: " + connections);
        }
        this.connections = Math.min(connections, maxConnectionsLimit());
        return this;
    }

    /**
     * Gets the user agent string.
     *
     * @return The user agent string
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Sets the user agent string. Null or empty resets to the default
     * browser-like user agent.
     *
     * @param userAgent The user agent string
     * @return This settings object for chaining
     */
    public HttrackSettings setUserAgent(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? null : userAgent.trim();
        return this;
    }

    /**
     * Checks if a proxy should be used.
     *
     * @return true if a proxy should be used, false otherwise
     */
    public boolean isUseProxy() {
        return useProxy;
    }

    /**
     * Sets whether a proxy should be used.
     *
     * @param useProxy true to use a proxy, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        return this;
    }

    /**
     * Gets the proxy address.
     *
     * @return The proxy address
     */
    public String getProxyAddress() {
        return proxyAddress;
    }

    /**
     * Sets the proxy address.
     *
     * @param proxyAddress The proxy address
     * @return This settings object for chaining
     */
    public HttrackSettings setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
        return this;
    }

    /**
     * Gets the proxy username.
     *
     * @return The proxy username
     */
    public String getProxyUsername() {
        return proxyUsername;
    }

    /**
     * Sets the proxy username.
     *
     * @param proxyUsername The proxy username
     * @return This settings object for chaining
     */
    public HttrackSettings setProxyUsername(String proxyUsername) {
        this.proxyUsername = proxyUsername;
        return this;
    }

    /**
     * Gets the proxy password.
     *
     * @return The proxy password
     */
    public String getProxyPassword() {
        return proxyPassword;
    }

    /**
     * Sets the proxy password.
     *
     * @param proxyPassword The proxy password
     * @return This settings object for chaining
     */
    public HttrackSettings setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
        return this;
    }

    /**
     * Gets the list of URL patterns to exclude from the download.
     *
     * @return The list of exclusion patterns
     */
    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    /**
     * Sets the list of URL patterns to exclude from the download.
     *
     * @param excludePatterns The list of exclusion patterns
     * @return This settings object for chaining
     */
    public HttrackSettings setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = excludePatterns != null ? new ArrayList<>(excludePatterns) : new ArrayList<>();
        return this;
    }

    /**
     * Adds a pattern to exclude from the download.
     *
     * @param pattern The pattern to exclude; null is ignored
     * @return This settings object for chaining
     */
    public HttrackSettings addExcludePattern(String pattern) {
        if (pattern != null) {
            this.excludePatterns.add(pattern);
        }
        return this;
    }

    /**
     * Gets the list of URL patterns to include in the download.
     *
     * @return The list of inclusion patterns
     */
    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    /**
     * Sets the list of URL patterns to include in the download.
     *
     * @param includePatterns The list of inclusion patterns
     * @return This settings object for chaining
     */
    public HttrackSettings setIncludePatterns(List<String> includePatterns) {
        this.includePatterns = includePatterns != null ? new ArrayList<>(includePatterns) : new ArrayList<>();
        return this;
    }

    /**
     * Adds a pattern to include in the download.
     *
     * @param pattern The pattern to include; null is ignored
     * @return This settings object for chaining
     */
    public HttrackSettings addIncludePattern(String pattern) {
        if (pattern != null) {
            this.includePatterns.add(pattern);
        }
        return this;
    }

    /**
     * Adds an additional command-line option.
     *
     * @param key The option key
     * @param value The option value (can be null or empty for flag-style
     * options)
     * @return This settings object for chaining
     */
    public HttrackSettings addAdditionalOption(String key, String value) {
        setOption(key, value);
        return this;
    }

    /**
     * Checks if mirror mode is enabled. In mirror mode, httrack will create a
     * mirror of the website.
     *
     * @return true if mirror mode is enabled, false otherwise
     */
    public boolean isMirrorMode() {
        return mirrorMode;
    }

    /**
     * Sets whether mirror mode is enabled. In mirror mode, httrack will create
     * a mirror of the website.
     *
     * @param mirrorMode true to enable mirror mode, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setMirrorMode(boolean mirrorMode) {
        this.mirrorMode = mirrorMode;
        return this;
    }

    public long getMaxTotalSizeBytes() {
        return maxTotalSizeBytes;
    }

    public HttrackSettings setMaxTotalSizeBytes(long bytes) {
        this.maxTotalSizeBytes = requireNonNegative(bytes, "Maximum total size");
        return this;
    }

    public long getMaxNonHtmlFileSizeBytes() {
        return maxNonHtmlFileSizeBytes;
    }

    public HttrackSettings setMaxNonHtmlFileSizeBytes(long bytes) {
        this.maxNonHtmlFileSizeBytes = requireNonNegative(bytes,
                "Maximum non-HTML file size");
        return this;
    }

    public long getMaxHtmlFileSizeBytes() {
        return maxHtmlFileSizeBytes;
    }

    public HttrackSettings setMaxHtmlFileSizeBytes(long bytes) {
        this.maxHtmlFileSizeBytes = requireNonNegative(bytes,
                "Maximum HTML file size");
        return this;
    }

    public int getMaxLinks() {
        return maxLinks;
    }

    /** 0 means unlimited; -1 leaves HTTrack's native value unchanged. */
    public HttrackSettings setMaxLinks(int maxLinks) {
        if (maxLinks < -1) {
            throw new IllegalArgumentException(
                    "Maximum link count must be -1 (engine default), 0, or positive: "
                            + maxLinks);
        }
        this.maxLinks = maxLinks;
        return this;
    }

    public int getMaxDurationSeconds() {
        return maxDurationSeconds;
    }

    public HttrackSettings setMaxDurationSeconds(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("Maximum crawl duration cannot be negative: " + seconds);
        }
        this.maxDurationSeconds = seconds;
        return this;
    }

    public double getConnectionsPerSecond() {
        return connectionsPerSecond;
    }

    /** 0 disables this throttle; -1 leaves HTTrack's native value unchanged. */
    public HttrackSettings setConnectionsPerSecond(double rate) {
        if (!Double.isFinite(rate) || rate < -1) {
            throw new IllegalArgumentException(
                    "Connections per second must be -1 (engine default), 0, or positive: "
                            + rate);
        }
        this.connectionsPerSecond = rate;
        return this;
    }

    public int getDelayBetweenFilesSeconds() {
        return delayBetweenFilesSeconds;
    }

    public HttrackSettings setDelayBetweenFilesSeconds(int seconds) {
        if (seconds < 0) {
            throw new IllegalArgumentException("Delay between files cannot be negative: " + seconds);
        }
        this.delayBetweenFilesSeconds = seconds;
        return this;
    }

    public List<String> getAdditionalHttpHeaders() {
        return List.copyOf(additionalHttpHeaders);
    }

    public HttrackSettings setAdditionalHttpHeaders(List<String> headers) {
        List<String> validated = new ArrayList<>();
        if (headers != null) {
            for (String header : headers) {
                String normalized = validatedHeader(header);
                if (normalized != null) {
                    validated.add(normalized);
                }
            }
        }
        this.additionalHttpHeaders = validated;
        return this;
    }

    public HttrackSettings addAdditionalHttpHeader(String header) {
        String normalized = validatedHeader(header);
        if (normalized != null) {
            additionalHttpHeaders.add(normalized);
        }
        return this;
    }

    public Path getCookieFile() {
        return cookieFile;
    }

    public HttrackSettings setCookieFile(Path cookieFile) {
        this.cookieFile = cookieFile;
        return this;
    }

    public HttrackSettings setCookieFile(String cookieFile) {
        this.cookieFile = cookieFile == null || cookieFile.isBlank()
                ? null : Paths.get(cookieFile.trim());
        return this;
    }

    public RunMode getRunMode() {
        return runMode;
    }

    public HttrackSettings setRunMode(RunMode runMode) {
        this.runMode = runMode == null ? RunMode.MIRROR : runMode;
        return this;
    }

    public boolean isPurgeOldFiles() {
        return purgeOldFiles;
    }

    public HttrackSettings setPurgeOldFiles(boolean purgeOldFiles) {
        this.purgeOldFiles = purgeOldFiles;
        return this;
    }

    private static long requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " cannot be negative: " + value);
        }
        return value;
    }

    private static String validatedHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String normalized = header.trim();
        if (normalized.indexOf('\0') >= 0 || normalized.indexOf('\r') >= 0
                || normalized.indexOf('\n') >= 0 || normalized.indexOf(':') <= 0) {
            throw new IllegalArgumentException(
                    "Each HTTrack HTTP header must be one Name: value line");
        }
        return normalized;
    }

    /**
     * Creates a typed copy of these httrack settings.
     *
     * @return A new HttrackSettings instance with the same settings
     */
    public HttrackSettings copySettings() {
        HttrackSettings copy = new HttrackSettings();

        // Copy base DownloadSettings fields using the parent method
        copyTo(copy);

        // Copy HttrackSettings-specific fields
        copy.url = this.url;
        copy.outputDirectory = this.outputDirectory;
        copy.depth = this.depth;
        copy.crawlScope = this.crawlScope;
        copy.externalDepth = this.externalDepth;
        copy.includeImages = this.includeImages;
        copy.includeVideos = this.includeVideos;
        copy.includeAudio = this.includeAudio;
        copy.includeDocuments = this.includeDocuments;
        copy.includeArchives = this.includeArchives;
        copy.maxRate = this.maxRate;
        copy.connections = this.connections;
        copy.userAgent = this.userAgent;
        copy.useProxy = this.useProxy;
        copy.proxyAddress = this.proxyAddress;
        copy.proxyUsername = this.proxyUsername;
        copy.proxyPassword = this.proxyPassword;
        copy.excludePatterns = new ArrayList<>(this.excludePatterns);
        copy.includePatterns = new ArrayList<>(this.includePatterns);
        copy.mirrorMode = this.mirrorMode;
        copy.maxTotalSizeBytes = this.maxTotalSizeBytes;
        copy.maxNonHtmlFileSizeBytes = this.maxNonHtmlFileSizeBytes;
        copy.maxHtmlFileSizeBytes = this.maxHtmlFileSizeBytes;
        copy.maxLinks = this.maxLinks;
        copy.maxDurationSeconds = this.maxDurationSeconds;
        copy.connectionsPerSecond = this.connectionsPerSecond;
        copy.delayBetweenFilesSeconds = this.delayBetweenFilesSeconds;
        copy.additionalHttpHeaders = new ArrayList<>(this.additionalHttpHeaders);
        copy.cookieFile = this.cookieFile;
        copy.runMode = this.runMode;
        copy.purgeOldFiles = this.purgeOldFiles;

        return copy;
    }

    /**
     * Builds the command line arguments for httrack based on these settings.
     *
     * @return An array of command line arguments
     */
    public List<String> buildCommandLine() {
        List<String> args = new ArrayList<>();

        // Add the URL
        args.add(url);

        // Add the output directory (optional; httrack uses its working
        // directory when omitted)
        if (outputDirectory != null) {
            args.add("-O");
            args.add(outputDirectory.toString());
        }

        // Set the recursion depth
        args.add("-r" + depth);

        switch (crawlScope) {
            case SAME_DIRECTORY -> args.add("-S");
            case SAME_HOST -> args.add("-a");
            case SAME_DOMAIN -> args.add("-d");
            case NEARBY_EXTERNAL_ASSETS -> {
                args.add("-a");
                args.add("-n");
            }
            case CUSTOM_EXTERNAL_DEPTH -> args.add("-%e" + externalDepth);
        }
        switch (runMode) {
            case MIRROR -> { }
            case CONTINUE -> args.add("--continue");
            case UPDATE -> {
                args.add("--update");
                // HTTrack purges old files during updates by default. ODM's
                // safe default is explicit preservation; removal requires a
                // separate user choice in the UI.
                args.add(purgeOldFiles ? "-X1" : "-X0");
            }
        }
        if (!mirrorMode) {
            args.add("-g");
        }

        // Native scope is authoritative. Positive extension filters broaden
        // HTTrack's crawl scope, so enabled types add no filter; only explicit
        // exclusions are emitted.
        if (!includeImages) {
            addTypeFilters(args, "-", "png", "jpg", "jpeg", "gif", "webp", "svg");
        }
        if (!includeVideos) {
            addTypeFilters(args, "-", "mp4", "webm", "avi", "mov", "mkv");
        }
        if (!includeAudio) {
            addTypeFilters(args, "-", "mp3", "ogg", "wav", "flac");
        }
        if (!includeDocuments) {
            addTypeFilters(args, "-", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "txt");
        }
        if (!includeArchives) {
            addTypeFilters(args, "-", "zip", "rar", "tar", "gz");
        }

        if (maxTotalSizeBytes > 0) {
            args.add("-M" + maxTotalSizeBytes);
        }
        if (maxNonHtmlFileSizeBytes > 0 || maxHtmlFileSizeBytes > 0) {
            args.add("-m" + maxNonHtmlFileSizeBytes + "," + maxHtmlFileSizeBytes);
        }
        if (maxDurationSeconds > 0) {
            args.add("-E" + maxDurationSeconds);
        }
        if (maxLinks >= 0) {
            args.add("-#L" + maxLinks);
        }

        // Add exclude patterns
        for (String pattern : excludePatterns) {
            String filter = safeFilterToken('-', pattern);
            if (filter != null) {
                args.add(filter);
            }
        }

        // Add include patterns
        for (String pattern : includePatterns) {
            String filter = safeFilterToken('+', pattern);
            if (filter != null) {
                args.add(filter);
            }
        }

        // ExternalToolSettings defines KiB/s; httrack -A expects bytes/s.
        if (maxRate > 0) {
            args.add("-A" + (maxRate * 1024L));
        }

        // Number of connections
        args.add("-c" + connections);

        if (connectionsPerSecond >= 0) {
            args.add("-%c" + compactDecimal(connectionsPerSecond));
        }
        if (delayBetweenFilesSeconds > 0) {
            args.add("-%G" + delayBetweenFilesSeconds);
        }

        if (getMaxRetries() > 0) {
            args.add("-R" + getMaxRetries());
        }
        if (getReferer() != null && !getReferer().isBlank()) {
            args.add("-%R");
            args.add(getReferer().trim());
        }
        if (getCookieHeader() != null && !getCookieHeader().isBlank()) {
            args.add("-%X");
            args.add(validatedHeader(getCookieHeader()));
        }
        for (String header : additionalHttpHeaders) {
            args.add("-%X");
            args.add(header);
        }
        if (cookieFile != null) {
            args.add("-%K");
            args.add(cookieFile.toString());
        }

        // User agent
        if (userAgent != null && !userAgent.isEmpty()) {
            args.add("-F");
            args.add(userAgent);
        }

        // Proxy settings (a missing address is skipped gracefully)
        if (useProxy && proxyAddress != null && !proxyAddress.isEmpty()) {
            args.add("-P");
            args.add(proxyWithCredentials());
        }

        // Additional user-specified options (flag-style or with a value)
        for (Map.Entry<String, String> entry : org.manager.tools.ToolOptionFilter
                .filter(org.manager.tools.ToolOptionFilter.Tool.HTTRACK,
                        getAdditionalOptions()).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            args.add("-" + key + (value == null ? "" : value));
        }
        return args;

    }

    /**
     * Encodes user/stored text as an unmistakable HTTrack scan-rule token.
     * A raw exclusion such as {@code Vcommand} previously became
     * {@code -Vcommand}, where {@code -V} is HTTrack's external-command
     * option. Prefixing a wildcard keeps the token in the filter grammar.
     */
    private static String safeFilterToken(char sign, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String normalized = pattern.trim();
        if (normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("HTTrack filters cannot contain control characters");
        }
        while (normalized.startsWith("+") || normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return null;
        }
        if (!normalized.startsWith("*")) {
            normalized = "*" + normalized;
        }
        return sign + normalized;
    }

    String proxyWithCredentials() {
        if (proxyAddress == null || proxyUsername == null || proxyUsername.isBlank() || proxyAddress.contains("@")) {
            return proxyAddress;
        }
        try {
            boolean hadScheme = proxyAddress.contains("://");
            java.net.URI parsed = java.net.URI.create(hadScheme
                    ? proxyAddress : "http://" + proxyAddress);
            String userInfo = proxyPassword == null || proxyPassword.isEmpty()
                    ? proxyUsername : proxyUsername + ':' + proxyPassword;
            String value = new java.net.URI(parsed.getScheme(), userInfo, parsed.getHost(),
                    parsed.getPort(), null, null, null).toASCIIString();
            return hadScheme ? value : value.substring("http://".length());
        } catch (Exception invalidProxy) {
            return proxyAddress;
        }
    }

    /**
     * Adds one httrack filter token per file extension, e.g. {@code +*.png}.
     *
     * @param args       the argument list to append to
     * @param sign       "+" to include, "-" to exclude
     * @param extensions file extensions without the dot
     */
    private static void addTypeFilters(List<String> args, String sign, String... extensions) {
        for (String extension : extensions) {
            args.add(sign + "*." + extension);
        }
    }

    private static String compactDecimal(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        map.put("httrack.url", url != null ? url : "");
        map.put("httrack.output_directory", outputDirectory != null ? outputDirectory.toString() : "");
        map.put("httrack.depth", String.valueOf(depth));
        map.put("httrack.crawl_scope", crawlScope.name());
        map.put("httrack.follow_external_links", String.valueOf(isFollowExternalLinks()));
        map.put("httrack.external_depth", String.valueOf(externalDepth));
        map.put("httrack.include_images", String.valueOf(includeImages));
        map.put("httrack.include_videos", String.valueOf(includeVideos));
        map.put("httrack.include_audio", String.valueOf(includeAudio));
        map.put("httrack.include_documents", String.valueOf(includeDocuments));
        map.put("httrack.include_archives", String.valueOf(includeArchives));
        map.put("httrack.max_rate", String.valueOf(maxRate));
        map.put("httrack.connections", String.valueOf(connections));
        map.put("httrack.user_agent", userAgent != null ? userAgent : "");
        map.put("httrack.mirror_mode", String.valueOf(mirrorMode));
        map.put("httrack.max_total_size_bytes", String.valueOf(maxTotalSizeBytes));
        map.put("httrack.max_non_html_file_size_bytes", String.valueOf(maxNonHtmlFileSizeBytes));
        map.put("httrack.max_html_file_size_bytes", String.valueOf(maxHtmlFileSizeBytes));
        map.put("httrack.max_links", String.valueOf(maxLinks));
        map.put("httrack.max_duration_seconds", String.valueOf(maxDurationSeconds));
        map.put("httrack.connections_per_second", String.valueOf(connectionsPerSecond));
        map.put("httrack.delay_between_files_seconds", String.valueOf(delayBetweenFilesSeconds));
        map.put("httrack.additional_http_headers", String.join("\n", additionalHttpHeaders));
        map.put("httrack.cookie_file", cookieFile != null ? cookieFile.toString() : "");
        map.put("httrack.run_mode", runMode.name());
        map.put("httrack.purge_old_files", String.valueOf(purgeOldFiles));

        return map;
    }

    @Override
    public HttrackSettings copy() {
        return copySettings();
    }

}
