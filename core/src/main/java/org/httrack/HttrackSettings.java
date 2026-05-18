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

    private String url;
    private Path outputDirectory;
    private int depth = 5;//
    private boolean followExternalLinks = false;
    private boolean includeImages = true;
    private boolean includeVideos = true;
    private boolean includeAudio = true;
    private boolean includeDocuments = true;
    private boolean includeArchives = false;//
    private int maxRate = 0; // 0 means no limit, in KB/s
    private int connections = 8;
    private String userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private boolean useProxy = false;
    private String proxyAddress = null;
    private String proxyUsername = null;
    private String proxyPassword = null;
    private List<String> excludePatterns = new ArrayList<>();//
    private List<String> includePatterns = new ArrayList<>();//
    private boolean mirrorMode = true;

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
     * @param outputDirectory The output directory as a string
     * @return This settings object for chaining
     */
    public HttrackSettings setOutputDirectory(String outputDirectory) {
        this.outputDirectory = Paths.get(outputDirectory);
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
     * @param depth The maximum depth
     * @return This settings object for chaining
     */
    public HttrackSettings setDepth(int depth) {
        this.depth = depth;
        return this;
    }

    /**
     * Checks if external links should be followed.
     *
     * @return true if external links should be followed, false otherwise
     */
    public boolean isFollowExternalLinks() {
        return followExternalLinks;
    }

    /**
     * Sets whether external links should be followed.
     *
     * @param followExternalLinks true to follow external links, false otherwise
     * @return This settings object for chaining
     */
    public HttrackSettings setFollowExternalLinks(boolean followExternalLinks) {
        this.followExternalLinks = followExternalLinks;
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
     * Sets the maximum download rate in KB/s. Set to 0 for no limit.
     *
     * @param maxRate The maximum download rate in KB/s
     * @return This settings object for chaining
     */
    public HttrackSettings setMaxRate(int maxRate) {
        this.maxRate = maxRate;
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
     * @param connections The number of concurrent connections
     * @return This settings object for chaining
     */
    public HttrackSettings setConnections(int connections) {
        this.connections = connections;
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
     * Sets the user agent string.
     *
     * @param userAgent The user agent string
     * @return This settings object for chaining
     */
    public HttrackSettings setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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
        this.excludePatterns = excludePatterns;
        return this;
    }

    /**
     * Adds a pattern to exclude from the download.
     *
     * @param pattern The pattern to exclude
     * @return This settings object for chaining
     */
    public HttrackSettings addExcludePattern(String pattern) {
        this.excludePatterns.add(pattern);
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
        this.includePatterns = includePatterns;
        return this;
    }

    /**
     * Adds a pattern to include in the download.
     *
     * @param pattern The pattern to include
     * @return This settings object for chaining
     */
    public HttrackSettings addIncludePattern(String pattern) {
        this.includePatterns.add(pattern);
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
        copy.followExternalLinks = this.followExternalLinks;
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

        // Add the output directory
        args.add("-O");
        args.add(outputDirectory.toString());

        // Set the recursion depth
        args.add("-r" + depth);

        // Follow external links?
        if (followExternalLinks) {
            args.add("-*");
        }

        // File type filters
        StringBuilder filters = new StringBuilder();
        if (includeImages) {
            filters.append("+*.png,+*.jpg,+*.jpeg,+*.gif,+*.webp,+*.svg,");
        }
        if (includeVideos) {
            filters.append("+*.mp4,+*.webm,+*.avi,+*.mov,+*.mkv,");
        }
        if (includeAudio) {
            filters.append("+*.mp3,+*.ogg,+*.wav,+*.flac,");
        }
        if (includeDocuments) {
            filters.append("+*.pdf,+*.doc,+*.docx,+*.ppt,+*.pptx,+*.xls,+*.xlsx,+*.txt,");
        }
        if (includeArchives) {
            filters.append("+*.zip,+*.rar,+*.tar,+*.gz,");
        }

        if (filters.length() > 0) {
            args.add("-*." + filters.toString());
        }

        // Add exclude patterns
        for (String pattern : excludePatterns) {
            args.add("-" + pattern);
        }

        // Add include patterns
        for (String pattern : includePatterns) {
            args.add("+" + pattern);
        }

        // Speed limit
        if (maxRate > 0) {
            args.add("-max-rate=" + maxRate);
        }

        // Number of connections
        args.add("-c" + connections);

        // User agent
        if (userAgent != null && !userAgent.isEmpty()) {
            args.add("-F");
            args.add("\"" + userAgent + "\"");
        }

        // Proxy settings
        if (useProxy && proxyAddress != null && !proxyAddress.isEmpty()) {
            args.add("-P");
            args.add(proxyAddress);

            if (proxyUsername != null && !proxyUsername.isEmpty()) {
                args.add("%proxy-user:" + proxyUsername);

                if (proxyPassword != null && !proxyPassword.isEmpty()) {
                    args.add("%proxy-pass:" + proxyPassword);
                }
            }
        }
        return args;

    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        map.put("httrack.url", url != null ? url : "");
        map.put("httrack.output_directory", outputDirectory != null ? outputDirectory.toString() : "");
        map.put("httrack.depth", String.valueOf(depth));
        map.put("httrack.follow_external_links", String.valueOf(followExternalLinks));
        map.put("httrack.include_images", String.valueOf(includeImages));
        map.put("httrack.include_videos", String.valueOf(includeVideos));
        map.put("httrack.include_audio", String.valueOf(includeAudio));
        map.put("httrack.include_documents", String.valueOf(includeDocuments));
        map.put("httrack.include_archives", String.valueOf(includeArchives));
        map.put("httrack.max_rate", String.valueOf(maxRate));
        map.put("httrack.connections", String.valueOf(connections));
        map.put("httrack.user_agent", userAgent != null ? userAgent : "");
        map.put("httrack.mirror_mode", String.valueOf(mirrorMode));

        return map;
    }

    @Override
    public DownloadSettings copy() {
        HttrackSettings copy = new HttrackSettings();

        // Copy base DownloadSettings fields
        copy.setConnections(this.getConnections());
        copy.setUseProxy(this.isUseProxy());
        copy.setProxyAddress(this.getProxyAddress());

        // Copy HttrackSettings-specific fields
        copy.url = this.url;
        copy.outputDirectory = this.outputDirectory;
        copy.depth = this.depth;
        copy.followExternalLinks = this.followExternalLinks;
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

        return copy;
    }

}
