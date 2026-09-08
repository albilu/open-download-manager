package org.ytdlp;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.manager.ApplicationContext;
import org.manager.download.DownloadSettings;
import org.manager.tools.ToolManagerFactory;
import org.ytdlp.YtDlpToolManager;

/**
 * Settings specific to yt-dlp downloads. Provides configuration options for the
 * yt-dlp downloader used for downloading videos from YouTube and other
 * supported platforms.
 */
public class YtDlpSettings extends DownloadSettings {

    /** Browser profiles supported by yt-dlp's --cookies-from-browser option. */
    public enum BrowserCookieSource {
        NONE("none", "None"),
        BRAVE("brave", "Brave"),
        CHROME("chrome", "Google Chrome"),
        CHROMIUM("chromium", "Chromium"),
        EDGE("edge", "Microsoft Edge"),
        FIREFOX("firefox", "Firefox"),
        OPERA("opera", "Opera"),
        VIVALDI("vivaldi", "Vivaldi"),
        WHALE("whale", "Whale");

        private final String settingValue;
        private final String displayName;

        BrowserCookieSource(String settingValue, String displayName) {
            this.settingValue = settingValue;
            this.displayName = displayName;
        }

        public String settingValue() {
            return settingValue;
        }

        public String displayName() {
            return displayName;
        }

        public static BrowserCookieSource fromSetting(String value) {
            String normalized = value == null ? "" : value.trim();
            for (BrowserCookieSource source : values()) {
                if (source.settingValue.equalsIgnoreCase(normalized)
                        || source.name().equalsIgnoreCase(normalized)) {
                    return source;
                }
            }
            return NONE;
        }
    }

    /** Understandable, no-transcode output policies for new media downloads. */
    public enum ContainerProfile {
        AUTOMATIC("automatic", "Automatic / best compatible"),
        MP4_COMPATIBLE("mp4-compatible", "MP4 (prefer H.264/AAC)"),
        MKV("mkv", "MKV"),
        PRESERVE_NATIVE("preserve-native", "Preserve native formats");

        private final String settingValue;
        private final String displayName;

        ContainerProfile(String settingValue, String displayName) {
            this.settingValue = settingValue;
            this.displayName = displayName;
        }

        public String settingValue() {
            return settingValue;
        }

        public String displayName() {
            return displayName;
        }

        public static ContainerProfile fromSetting(String value) {
            String normalized = value == null ? "" : value.trim();
            for (ContainerProfile profile : values()) {
                if (profile.settingValue.equalsIgnoreCase(normalized)
                        || profile.name().equalsIgnoreCase(normalized)) {
                    return profile;
                }
            }
            return AUTOMATIC;
        }
    }

    /** SponsorBlock behavior is intentionally selected per download. */
    public enum SponsorBlockMode {
        OFF("off", "Off"),
        MARK("mark", "Mark segments as chapters"),
        REMOVE("remove", "Remove segments");

        private final String settingValue;
        private final String displayName;

        SponsorBlockMode(String settingValue, String displayName) {
            this.settingValue = settingValue;
            this.displayName = displayName;
        }

        public String settingValue() {
            return settingValue;
        }

        public String displayName() {
            return displayName;
        }

        public static SponsorBlockMode fromSetting(String value) {
            String normalized = value == null ? "" : value.trim();
            for (SponsorBlockMode mode : values()) {
                if (mode.settingValue.equalsIgnoreCase(normalized)
                        || mode.name().equalsIgnoreCase(normalized)) {
                    return mode;
                }
            }
            return OFF;
        }
    }

    private static final Set<String> SPONSOR_BLOCK_CATEGORIES = Set.of(
            "sponsor", "intro", "outro", "selfpromo", "preview", "filler",
            "interaction", "music_offtopic", "hook", "poi_highlight", "chapter",
            "all", "default");

    @Override
    public boolean supports(org.manager.download.ExternalToolSettings.Capability capability) {
        return switch (capability) {
            case CONNECTIONS, DOWNLOAD_LIMIT, MAX_RETRIES, RETRY_DELAY,
                    REFERER, USER_AGENT, COOKIE, PROXY, SOCKS_PROXY -> true;
            case UPLOAD_LIMIT -> false;
        };
    }

    /** Empty means yt-dlp's automatic, protocol-aware best-quality choice. */
    private String format = "";
    private String outputTemplate;
    private boolean writeThumbnail = false;
    private boolean embedThumbnail = false;
    private boolean embedMetadata = false;
    private boolean embedSubs = false;
    private boolean writeAutoSubs = false;
    private boolean writeSubtitles = false;
    private List<String> subtitleLanguages = new ArrayList<>();
    private boolean extractAudio = false;
    private String audioFormat = "mp3";
    private String audioQuality = "192";
    /** Zero means yt-dlp's native retry policy. */
    private int fragmentRetries = 0;
    private boolean limitRate = false;
    private int rateLimit = 0; // KB/s, 0 means no limit
    private boolean skipUnavailableFragments = true;
    private boolean ignoreErrors = false;
    private boolean noPlaylist = false;
    private boolean useDownloadArchive;

    public boolean isUseDownloadArchive() { return useDownloadArchive; }

    public YtDlpSettings setUseDownloadArchive(boolean enabled) {
        useDownloadArchive = enabled;
        return this;
    }
    private boolean playlistEnd = false;
    private int playlistItems = 0;
    private String playlistItemSpec = null;
    private boolean geoBypass = true;
    private String cookieFile = null;
    private MediaRequestContext mediaRequestContext;
    private BrowserCookieSource browserCookieSource = BrowserCookieSource.NONE;
    private String browserCookieProfile = null;
    private ContainerProfile containerProfile = ContainerProfile.AUTOMATIC;
    private SponsorBlockMode sponsorBlockMode = SponsorBlockMode.OFF;
    private String sponsorBlockCategories = "default";
    private boolean verboseOutput = false;
    private boolean useAria2c = false;
    private String aria2cPath = ApplicationContext.getToolPath("aria2");
    private int aria2cConnections = 16;
    private int aria2cSplitConnections = 16;
    private String aria2cMinSplitSize = "1M";
    private String aria2cUserAgent = null;
    private boolean aria2cContinue = true;
    private int aria2cTimeout = 60;
    private int aria2cRetryWait = 10;
    private int aria2cMaxTries = 5;

    public String getOutputTemplate() {
        return outputTemplate;
    }

    public YtDlpSettings setOutputTemplate(String outputTemplate) {
        this.outputTemplate = outputTemplate == null || outputTemplate.isBlank()
                ? null : outputTemplate;
        return this;
    }

    /**
     * Gets the video format specification.
     *
     * @return The video format specification
     */
    public String getFormat() {
        return format;
    }

    /**
     * Sets the video format specification. See yt-dlp documentation for format
     * specification details.
     *
     * @param format The video format specification
     * @return This settings object for chaining
     */
    public YtDlpSettings setFormat(String format) {
        this.format = format;
        return this;
    }

    public boolean isWriteThumbnail() {
        return writeThumbnail;
    }

    public YtDlpSettings setWriteThumbnail(boolean writeThumbnail) {
        this.writeThumbnail = writeThumbnail;
        return this;
    }

    /**
     * Checks if thumbnail should be embedded in the video file.
     *
     * @return true if thumbnail should be embedded, false otherwise
     */
    public boolean isEmbedThumbnail() {
        return embedThumbnail;
    }

    /**
     * Sets whether to embed thumbnail in the video file.
     *
     * @param embedThumbnail true to embed thumbnail, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setEmbedThumbnail(boolean embedThumbnail) {
        this.embedThumbnail = embedThumbnail;
        return this;
    }

    /**
     * Checks if metadata should be embedded in the video file.
     *
     * @return true if metadata should be embedded, false otherwise
     */
    public boolean isEmbedMetadata() {
        return embedMetadata;
    }

    /**
     * Sets whether to embed metadata in the video file.
     *
     * @param embedMetadata true to embed metadata, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setEmbedMetadata(boolean embedMetadata) {
        this.embedMetadata = embedMetadata;
        return this;
    }

    /**
     * Checks if subtitles should be embedded in the video file.
     *
     * @return true if subtitles should be embedded, false otherwise
     */
    public boolean isEmbedSubs() {
        return embedSubs;
    }

    /**
     * Sets whether to embed subtitles in the video file.
     *
     * @param embedSubs true to embed subtitles, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setEmbedSubs(boolean embedSubs) {
        this.embedSubs = embedSubs;
        return this;
    }

    /**
     * Checks if auto-generated subtitles should be written.
     *
     * @return true if auto-generated subtitles should be written, false
     * otherwise
     */
    public boolean isWriteAutoSubs() {
        return writeAutoSubs;
    }

    /**
     * Sets whether to write auto-generated subtitles.
     *
     * @param writeAutoSubs true to write auto-generated subtitles, false
     * otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setWriteAutoSubs(boolean writeAutoSubs) {
        this.writeAutoSubs = writeAutoSubs;
        return this;
    }

    /**
     * Checks if subtitles should be written.
     *
     * @return true if subtitles should be written, false otherwise
     */
    public boolean isWriteSubtitles() {
        return writeSubtitles;
    }

    /**
     * Sets whether to write subtitles.
     *
     * @param writeSubtitles true to write subtitles, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setWriteSubtitles(boolean writeSubtitles) {
        this.writeSubtitles = writeSubtitles;
        return this;
    }

    /**
     * Gets the list of subtitle languages.
     *
     * @return The list of subtitle languages
     */
    public List<String> getSubtitleLanguages() {
        return subtitleLanguages;
    }

    /**
     * Sets the list of subtitle languages.
     *
     * @param subtitleLanguages The list of subtitle languages
     * @return This settings object for chaining
     */
    public YtDlpSettings setSubtitleLanguages(List<String> subtitleLanguages) {
        this.subtitleLanguages = subtitleLanguages == null
                ? new ArrayList<>() : new ArrayList<>(subtitleLanguages);
        return this;
    }

    /**
     * Adds a subtitle language.
     *
     * @param language The subtitle language to add
     * @return This settings object for chaining
     */
    public YtDlpSettings addSubtitleLanguage(String language) {
        if (language != null && !language.isBlank()
                && !this.subtitleLanguages.contains(language)) {
            this.subtitleLanguages.add(language);
        }
        return this;
    }

    /**
     * Checks if audio should be extracted.
     *
     * @return true if audio should be extracted, false otherwise
     */
    public boolean isExtractAudio() {
        return extractAudio;
    }

    /**
     * Sets whether to extract audio.
     *
     * @param extractAudio true to extract audio, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setExtractAudio(boolean extractAudio) {
        this.extractAudio = extractAudio;
        return this;
    }

    /**
     * Gets the audio format.
     *
     * @return The audio format
     */
    public String getAudioFormat() {
        return audioFormat;
    }

    /**
     * Sets the audio format. Valid values: "best", "aac", "flac", "mp3", "m4a",
     * "opus", "vorbis", "wav"
     *
     * @param audioFormat The audio format
     * @return This settings object for chaining
     */
    public YtDlpSettings setAudioFormat(String audioFormat) {
        this.audioFormat = audioFormat;
        return this;
    }

    /**
     * Gets the audio quality.
     *
     * @return The audio quality
     */
    public String getAudioQuality() {
        return audioQuality;
    }

    /**
     * Sets the audio quality. Value is a string, 0 (best) to 10 (worst) for VBR
     * or specific bitrate like "192"
     *
     * @param audioQuality The audio quality
     * @return This settings object for chaining
     */
    public YtDlpSettings setAudioQuality(String audioQuality) {
        this.audioQuality = audioQuality;
        return this;
    }

    /**
     * Gets the number of fragment retries.
     *
     * @return The number of fragment retries
     */
    public int getFragmentRetries() {
        return fragmentRetries;
    }

    /**
     * Sets the number of fragment retries.
     *
     * @param fragmentRetries The number of fragment retries
     * @return This settings object for chaining
     */
    public YtDlpSettings setFragmentRetries(int fragmentRetries) {
        this.fragmentRetries = Math.max(0, fragmentRetries);
        return this;
    }

    /**
     * Checks if rate limiting is enabled.
     *
     * @return true if rate limiting is enabled, false otherwise
     */
    public boolean isLimitRate() {
        return limitRate;
    }

    /**
     * Sets whether to enable rate limiting.
     *
     * @param limitRate true to enable rate limiting, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setLimitRate(boolean limitRate) {
        this.limitRate = limitRate;
        return this;
    }

    /**
     * Gets the rate limit in KB/s.
     *
     * @return The rate limit in KB/s
     */
    public int getRateLimit() {
        return rateLimit;
    }

    /**
     * Sets the rate limit in KB/s. Set to 0 for no limit.
     *
     * @param rateLimit The rate limit in KB/s
     * @return This settings object for chaining
     */
    public YtDlpSettings setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
        return this;
    }

    // ===== ExternalToolSettings bridge =====

    @Override
    public YtDlpSettings setConnections(int connections) {
        int normalized = Math.max(1, connections);
        super.setConnections(normalized);
        // Keep the optional external aria2c path aligned with the same
        // Network control used for yt-dlp concurrent fragments.
        setAria2cConnections(normalized);
        setAria2cSplitConnections(normalized);
        return this;
    }

    @Override
    public YtDlpSettings setMaxRetries(int maxRetries) {
        int normalized = Math.max(0, maxRetries);
        super.setMaxRetries(normalized);
        // ODM exposes one retry policy. Keep every yt-dlp transfer retry
        // class aligned when a caller edits the shared value.
        setFragmentRetries(normalized);
        setAria2cMaxTries(normalized);
        return this;
    }

    @Override
    public YtDlpSettings setRetryDelaySeconds(int seconds) {
        int normalized = Math.max(0, seconds);
        super.setRetryDelaySeconds(normalized);
        setAria2cRetryWait(normalized);
        return this;
    }

    @Override
    public int getDownloadLimitKB() {
        return isLimitRate() ? getRateLimit() : 0;
    }

    @Override
    public YtDlpSettings setDownloadLimitKB(int kibPerSecond) {
        if (kibPerSecond > 0) {
            setLimitRate(true);
            setRateLimit(kibPerSecond);
        } else {
            setLimitRate(false);
        }
        return this;
    }

    // "user-agent"/"referer" are allowlisted yt-dlp options, so storing
    // them under those keys flows them onto the command line directly
    @Override
    public String getUserAgent() {
        String value = getOption("user-agent");
        return value != null ? value : super.getUserAgent();
    }

    @Override
    public YtDlpSettings setUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isBlank()) {
            setOption("user-agent", userAgent.trim());
            setAria2cUserAgent(userAgent.trim());
        } else {
            clearOption("user-agent");
            setAria2cUserAgent(null);
        }
        return this;
    }

    @Override
    public String getReferer() {
        String value = getOption("referer");
        return value != null ? value : super.getReferer();
    }

    @Override
    public YtDlpSettings setReferer(String referer) {
        if (referer != null && !referer.isBlank()) {
            setOption("referer", referer.trim());
        } else {
            clearOption("referer");
        }
        return this;
    }

    /**
     * Checks if unavailable fragments should be skipped.
     *
     * @return true if unavailable fragments should be skipped, false otherwise
     */
    public boolean isSkipUnavailableFragments() {
        return skipUnavailableFragments;
    }

    /**
     * Sets whether to skip unavailable fragments.
     *
     * @param skipUnavailableFragments true to skip unavailable fragments, false
     * otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setSkipUnavailableFragments(boolean skipUnavailableFragments) {
        this.skipUnavailableFragments = skipUnavailableFragments;
        return this;
    }

    /**
     * Checks if errors should be ignored.
     *
     * @return true if errors should be ignored, false otherwise
     */
    public boolean isIgnoreErrors() {
        return ignoreErrors;
    }

    /**
     * Sets whether to ignore errors.
     *
     * @param ignoreErrors true to ignore errors, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setIgnoreErrors(boolean ignoreErrors) {
        this.ignoreErrors = ignoreErrors;
        return this;
    }

    /**
     * Checks if playlists should be ignored.
     *
     * @return true if playlists should be ignored, false otherwise
     */
    public boolean isNoPlaylist() {
        return noPlaylist;
    }

    /**
     * Sets whether to ignore playlists.
     *
     * @param noPlaylist true to ignore playlists, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setNoPlaylist(boolean noPlaylist) {
        this.noPlaylist = noPlaylist;
        return this;
    }

    /**
     * Checks if the playlist should be ended after a certain number of items.
     *
     * @return true if the playlist should be ended, false otherwise
     */
    public boolean isPlaylistEnd() {
        return playlistEnd;
    }

    /**
     * Sets whether to end the playlist after a certain number of items.
     *
     * @param playlistEnd true to end the playlist, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setPlaylistEnd(boolean playlistEnd) {
        this.playlistEnd = playlistEnd;
        return this;
    }

    /**
     * Gets the number of playlist items to download.
     *
     * @return The number of playlist items to download
     */
    public int getPlaylistItems() {
        return playlistItems;
    }

    /**
     * Sets the number of playlist items to download. Set to 0 for all items.
     *
     * @param playlistItems The number of playlist items to download
     * @return This settings object for chaining
     */
    public YtDlpSettings setPlaylistItems(int playlistItems) {
        this.playlistItems = Math.max(0, playlistItems);
        return this;
    }

    /**
     * Gets the native yt-dlp playlist item expression, such as {@code 1:10}
     * or {@code 1,3,8}. A blank value means every playlist entry.
     */
    public String getPlaylistItemSpec() {
        return playlistItemSpec;
    }

    public YtDlpSettings setPlaylistItemSpec(String playlistItemSpec) {
        String normalized = normalizePlaylistItemSpec(playlistItemSpec);
        this.playlistItemSpec = normalized.isEmpty() ? null : normalized;
        return this;
    }

    /** Retains the older first-N fields while exposing one effective value. */
    public String getEffectivePlaylistItemSpec() {
        if (playlistItemSpec != null && !playlistItemSpec.isBlank()) {
            return playlistItemSpec;
        }
        return playlistEnd && playlistItems > 0 ? "1:" + playlistItems : null;
    }

    public static String normalizePlaylistItemSpec(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", "");
        for (String part : normalized.split(",", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Playlist selection contains an empty item");
            }
            if (part.matches("-?[1-9]\\d*")) {
                continue;
            }
            if (!part.matches("(?:-?[1-9]\\d*)?:(?:-?[1-9]\\d*)?"
                    + "(?::-?[1-9]\\d*)?")) {
                throw new IllegalArgumentException(
                        "Playlist selection must use item numbers or ranges such as 1:10");
            }
            String[] range = part.split(":", -1);
            if (range[0].isEmpty() && range[1].isEmpty()) {
                throw new IllegalArgumentException(
                        "Playlist range must include a start or end item");
            }
        }
        return normalized;
    }

    /**
     * Checks if geo bypass is enabled.
     *
     * @return true if geo bypass is enabled, false otherwise
     */
    public boolean isGeoBypass() {
        return geoBypass;
    }

    /**
     * Sets whether to enable geo bypass.
     *
     * @param geoBypass true to enable geo bypass, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setGeoBypass(boolean geoBypass) {
        this.geoBypass = geoBypass;
        return this;
    }

    /**
     * Gets the cookie file path.
     *
     * @return The cookie file path
     */
    public String getCookieFile() {
        return cookieFile;
    }

    public MediaRequestContext getMediaRequestContext() {
        return mediaRequestContext;
    }

    public void setMediaRequestContext(MediaRequestContext context) {
        mediaRequestContext = context;
    }

    /**
     * Sets the cookie file path.
     *
     * @param cookieFile The cookie file path
     * @return This settings object for chaining
     */
    public YtDlpSettings setCookieFile(String cookieFile) {
        this.cookieFile = cookieFile == null || cookieFile.isBlank()
                ? null : cookieFile.trim();
        return this;
    }

    public BrowserCookieSource getBrowserCookieSource() {
        return browserCookieSource;
    }

    public YtDlpSettings setBrowserCookieSource(BrowserCookieSource browserCookieSource) {
        this.browserCookieSource = browserCookieSource == null
                ? BrowserCookieSource.NONE : browserCookieSource;
        return this;
    }

    public String getBrowserCookieProfile() {
        return browserCookieProfile;
    }

    public YtDlpSettings setBrowserCookieProfile(String browserCookieProfile) {
        this.browserCookieProfile = browserCookieProfile == null
                || browserCookieProfile.isBlank() ? null : browserCookieProfile.trim();
        return this;
    }

    /** Value accepted by yt-dlp's --cookies-from-browser option. */
    public String getBrowserCookieArgument() {
        if (browserCookieSource == BrowserCookieSource.NONE) {
            return null;
        }
        return browserCookieSource.settingValue()
                + (browserCookieProfile == null ? "" : ":" + browserCookieProfile);
    }

    public ContainerProfile getContainerProfile() {
        return containerProfile;
    }

    public YtDlpSettings setContainerProfile(ContainerProfile containerProfile) {
        this.containerProfile = containerProfile == null
                ? ContainerProfile.AUTOMATIC : containerProfile;
        return this;
    }

    public SponsorBlockMode getSponsorBlockMode() {
        return sponsorBlockMode;
    }

    public YtDlpSettings setSponsorBlockMode(SponsorBlockMode sponsorBlockMode) {
        SponsorBlockMode normalized = sponsorBlockMode == null
                ? SponsorBlockMode.OFF : sponsorBlockMode;
        validateSponsorBlockCategories(sponsorBlockCategories, normalized);
        this.sponsorBlockMode = normalized;
        return this;
    }

    public String getSponsorBlockCategories() {
        return sponsorBlockCategories;
    }

    public YtDlpSettings setSponsorBlockCategories(String categories) {
        String normalized = categories == null || categories.isBlank()
                ? "default" : categories.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        validateSponsorBlockCategories(normalized, sponsorBlockMode);
        this.sponsorBlockCategories = normalized;
        return this;
    }

    private static void validateSponsorBlockCategories(String normalized,
            SponsorBlockMode mode) {
        for (String category : normalized.split(",", -1)) {
            String name = category.startsWith("-") ? category.substring(1) : category;
            if (name.isBlank() || !SPONSOR_BLOCK_CATEGORIES.contains(name)) {
                throw new IllegalArgumentException("Unsupported SponsorBlock category: " + category);
            }
            if (mode == SponsorBlockMode.REMOVE && !category.startsWith("-")
                    && ("poi_highlight".equals(name) || "chapter".equals(name))) {
                throw new IllegalArgumentException(
                        "SponsorBlock cannot remove category: " + category);
            }
        }
    }

    /**
     * Checks if verbose output is enabled.
     *
     * @return true if verbose output is enabled, false otherwise
     */
    public boolean isVerboseOutput() {
        return verboseOutput;
    }

    /**
     * Sets whether to enable verbose output.
     *
     * @param verboseOutput true to enable verbose output, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setVerboseOutput(boolean verboseOutput) {
        this.verboseOutput = verboseOutput;
        return this;
    }

    /**
     * Checks if aria2c should be used as external downloader.
     *
     * @return true if aria2c should be used, false otherwise
     */
    public boolean isUseAria2c() {
        return useAria2c;
    }

    /**
     * Sets whether to use aria2c as external downloader.
     *
     * @param useAria2c true to use aria2c, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setUseAria2c(boolean useAria2c) {
        this.useAria2c = useAria2c;
        return this;
    }

    /**
     * Gets the aria2c executable path.
     *
     * @return The aria2c executable path
     */
    public String getAria2cPath() {
        return aria2cPath;
    }

    /**
     * Sets the aria2c executable path.
     *
     * @param aria2cPath The aria2c executable path
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cPath(String aria2cPath) {
        this.aria2cPath = aria2cPath;
        return this;
    }

    /**
     * Gets the number of connections aria2c should use.
     *
     * @return The number of aria2c connections
     */
    public int getAria2cConnections() {
        return aria2cConnections;
    }

    /**
     * Sets the number of connections aria2c should use.
     *
     * @param aria2cConnections The number of aria2c connections
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cConnections(int aria2cConnections) {
        // Native fragment concurrency has no fixed ceiling, but aria2's
        // per-server -x option still only accepts 1 through 16.
        this.aria2cConnections = Math.clamp(aria2cConnections, 1,
                org.aria2.Aria2Settings.MAX_CONNECTIONS);
        return this;
    }

    /**
     * Gets the number of split connections per server for aria2c.
     *
     * @return The number of split connections
     */
    public int getAria2cSplitConnections() {
        return aria2cSplitConnections;
    }

    /**
     * Sets the number of split connections per server for aria2c.
     *
     * @param aria2cSplitConnections The number of split connections
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cSplitConnections(int aria2cSplitConnections) {
        this.aria2cSplitConnections = aria2cSplitConnections;
        return this;
    }

    /**
     * Gets the minimum split size for aria2c.
     *
     * @return The minimum split size
     */
    public String getAria2cMinSplitSize() {
        return aria2cMinSplitSize;
    }

    /**
     * Sets the minimum split size for aria2c.
     *
     * @param aria2cMinSplitSize The minimum split size (e.g., "1M", "512K")
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cMinSplitSize(String aria2cMinSplitSize) {
        this.aria2cMinSplitSize = aria2cMinSplitSize;
        return this;
    }

    /**
     * Gets the user agent for aria2c.
     *
     * @return The aria2c user agent
     */
    public String getAria2cUserAgent() {
        return aria2cUserAgent;
    }

    /**
     * Sets the user agent for aria2c.
     *
     * @param aria2cUserAgent The aria2c user agent
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cUserAgent(String aria2cUserAgent) {
        this.aria2cUserAgent = aria2cUserAgent;
        return this;
    }

    /**
     * Checks if aria2c should continue partial downloads.
     *
     * @return true if aria2c should continue partial downloads, false otherwise
     */
    public boolean isAria2cContinue() {
        return aria2cContinue;
    }

    /**
     * Sets whether aria2c should continue partial downloads.
     *
     * @param aria2cContinue true to continue partial downloads, false otherwise
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cContinue(boolean aria2cContinue) {
        this.aria2cContinue = aria2cContinue;
        return this;
    }

    /**
     * Gets the timeout for aria2c connections.
     *
     * @return The timeout in seconds
     */
    public int getAria2cTimeout() {
        return aria2cTimeout;
    }

    /**
     * Sets the timeout for aria2c connections.
     *
     * @param aria2cTimeout The timeout in seconds
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cTimeout(int aria2cTimeout) {
        this.aria2cTimeout = aria2cTimeout;
        return this;
    }

    /**
     * Gets the retry wait time for aria2c.
     *
     * @return The retry wait time in seconds
     */
    public int getAria2cRetryWait() {
        return aria2cRetryWait;
    }

    /**
     * Sets the retry wait time for aria2c.
     *
     * @param aria2cRetryWait The retry wait time in seconds
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cRetryWait(int aria2cRetryWait) {
        this.aria2cRetryWait = aria2cRetryWait;
        return this;
    }

    /**
     * Gets the maximum number of tries for aria2c.
     *
     * @return The maximum number of tries
     */
    public int getAria2cMaxTries() {
        return aria2cMaxTries;
    }

    /**
     * Sets the maximum number of tries for aria2c.
     *
     * @param aria2cMaxTries The maximum number of tries
     * @return This settings object for chaining
     */
    public YtDlpSettings setAria2cMaxTries(int aria2cMaxTries) {
        this.aria2cMaxTries = aria2cMaxTries;
        return this;
    }

    /**
     * Builds the aria2c arguments string based on current settings.
     *
     * @return The aria2c arguments string
     */
    public String buildAria2cArgs() {
        StringBuilder args = new StringBuilder();

        args.append("-x ").append(aria2cConnections);
        args.append(" -s ").append(aria2cSplitConnections);
        args.append(" -k ").append(aria2cMinSplitSize);

        if (aria2cContinue) {
            args.append(" -c");
        }

        args.append(" --timeout=").append(aria2cTimeout);
        if (aria2cRetryWait > 0) {
            args.append(" --retry-wait=").append(aria2cRetryWait);
        }
        if (aria2cMaxTries > 0) {
            args.append(" --max-tries=").append(aria2cMaxTries);
        }
        int downloadLimitKb = getDownloadLimitKB();
        if (downloadLimitKb > 0) {
            args.append(" --max-download-limit=")
                    .append(downloadLimitKb).append('K');
        }

        if (aria2cUserAgent != null && !aria2cUserAgent.isEmpty()) {
            args.append(" --user-agent=\"").append(aria2cUserAgent).append("\"");
        }

        return args.toString();
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        if (format != null && !format.isBlank()) {
            map.put("ytdlp.format", format);
        }
        if (outputTemplate != null) {
            map.put("ytdlp.output-template", outputTemplate);
        }

        if (writeThumbnail) {
            map.put("ytdlp.write-thumbnail", "true");
        }

        if (embedThumbnail) {
            map.put("ytdlp.embed-thumbnail", "true");
        }

        if (embedMetadata) {
            map.put("ytdlp.embed-metadata", "true");
        }

        if (embedSubs) {
            map.put("ytdlp.embed-subs", "true");
        }

        if (writeAutoSubs) {
            map.put("ytdlp.write-auto-subs", "true");
        }

        if (writeSubtitles) {
            map.put("ytdlp.write-subs", "true");
        }

        if (!subtitleLanguages.isEmpty()) {
            map.put("ytdlp.sub-langs", String.join(",", subtitleLanguages));
        }

        if (extractAudio) {
            map.put("ytdlp.extract-audio", "true");
            map.put("ytdlp.audio-format", audioFormat);
            map.put("ytdlp.audio-quality", audioQuality);
        }

        if (fragmentRetries > 0) {
            map.put("ytdlp.fragment-retries", String.valueOf(fragmentRetries));
        }

        if (limitRate && rateLimit > 0) {
            map.put("ytdlp.limit-rate", rateLimit + "K");
        }

        if (skipUnavailableFragments) {
            map.put("ytdlp.skip-unavailable-fragments", "true");
        }

        if (ignoreErrors) {
            map.put("ytdlp.ignore-errors", "true");
        }

        if (noPlaylist) {
            map.put("ytdlp.no-playlist", "true");
        }

        String effectivePlaylistItems = getEffectivePlaylistItemSpec();
        if (effectivePlaylistItems != null) {
            map.put("ytdlp.playlist-items", effectivePlaylistItems);
        }

        if (geoBypass) {
            map.put("ytdlp.geo-bypass", "true");
        }

        if (cookieFile != null) {
            map.put("ytdlp.cookies", cookieFile);
        } else if (getBrowserCookieArgument() != null) {
            map.put("ytdlp.cookies-from-browser", getBrowserCookieArgument());
        }

        if (containerProfile != ContainerProfile.AUTOMATIC) {
            map.put("ytdlp.container-profile", containerProfile.settingValue());
        }

        if (sponsorBlockMode != SponsorBlockMode.OFF) {
            map.put("ytdlp.sponsorblock-" + sponsorBlockMode.settingValue(),
                    sponsorBlockCategories);
        }

        if (verboseOutput) {
            map.put("ytdlp.verbose", "true");
        }

        if (useAria2c) {
            map.put("use-aria2c", "true");
            map.put("aria2c-args", buildAria2cArgs());
        }

        return map;
    }

    @Override
    public DownloadSettings copy() {
        YtDlpSettings copy = new YtDlpSettings();

        // Copy base settings
        copyTo(copy);

        // Copy YtDlp-specific settings
        copy.format = this.format;
        copy.outputTemplate = this.outputTemplate;
        copy.writeThumbnail = this.writeThumbnail;
        copy.embedThumbnail = this.embedThumbnail;
        copy.embedMetadata = this.embedMetadata;
        copy.embedSubs = this.embedSubs;
        copy.writeAutoSubs = this.writeAutoSubs;
        copy.writeSubtitles = this.writeSubtitles;
        copy.subtitleLanguages = new ArrayList<>(this.subtitleLanguages);
        copy.extractAudio = this.extractAudio;
        copy.audioFormat = this.audioFormat;
        copy.audioQuality = this.audioQuality;
        copy.fragmentRetries = this.fragmentRetries;
        copy.limitRate = this.limitRate;
        copy.rateLimit = this.rateLimit;
        copy.skipUnavailableFragments = this.skipUnavailableFragments;
        copy.ignoreErrors = this.ignoreErrors;
        copy.noPlaylist = this.noPlaylist;
        copy.useDownloadArchive = this.useDownloadArchive;
        copy.playlistEnd = this.playlistEnd;
        copy.playlistItems = this.playlistItems;
        copy.playlistItemSpec = this.playlistItemSpec;
        copy.geoBypass = this.geoBypass;
        copy.cookieFile = this.cookieFile;
        copy.mediaRequestContext = this.mediaRequestContext;
        copy.browserCookieSource = this.browserCookieSource;
        copy.browserCookieProfile = this.browserCookieProfile;
        copy.containerProfile = this.containerProfile;
        copy.sponsorBlockMode = this.sponsorBlockMode;
        copy.sponsorBlockCategories = this.sponsorBlockCategories;
        copy.verboseOutput = this.verboseOutput;
        copy.useAria2c = this.useAria2c;
        copy.aria2cPath = this.aria2cPath;
        copy.aria2cConnections = this.aria2cConnections;
        copy.aria2cSplitConnections = this.aria2cSplitConnections;
        copy.aria2cMinSplitSize = this.aria2cMinSplitSize;
        copy.aria2cUserAgent = this.aria2cUserAgent;
        copy.aria2cContinue = this.aria2cContinue;
        copy.aria2cTimeout = this.aria2cTimeout;
        copy.aria2cRetryWait = this.aria2cRetryWait;
        copy.aria2cMaxTries = this.aria2cMaxTries;

        return copy;
    }
}
