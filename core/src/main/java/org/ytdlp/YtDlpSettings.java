package org.ytdlp;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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

    private String format = "bestvideo+bestaudio/best";
    private boolean embedThumbnail = false;
    private boolean embedMetadata = true;
    private boolean embedSubs = false;
    private boolean writeAutoSubs = false;
    private boolean writeSubtitles = false;
    private List<String> subtitleLanguages = new ArrayList<>();
    private boolean extractAudio = false;
    private String audioFormat = "mp3";
    private String audioQuality = "192";
    private int fragmentRetries = 10;
    private boolean limitRate = false;
    private int rateLimit = 0; // KB/s, 0 means no limit
    private boolean skipUnavailableFragments = true;
    private boolean ignoreErrors = true;
    private boolean noPlaylist = false;
    private boolean playlistEnd = false;
    private int playlistItems = 0;
    private boolean geoBypass = true;
    private String cookieFile = null;
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
        this.subtitleLanguages = subtitleLanguages;
        return this;
    }

    /**
     * Adds a subtitle language.
     *
     * @param language The subtitle language to add
     * @return This settings object for chaining
     */
    public YtDlpSettings addSubtitleLanguage(String language) {
        this.subtitleLanguages.add(language);
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
        this.fragmentRetries = fragmentRetries;
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
        this.playlistItems = playlistItems;
        return this;
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

    /**
     * Sets the cookie file path.
     *
     * @param cookieFile The cookie file path
     * @return This settings object for chaining
     */
    public YtDlpSettings setCookieFile(String cookieFile) {
        this.cookieFile = cookieFile;
        return this;
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
        this.aria2cConnections = aria2cConnections;
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
        args.append(" --retry-wait=").append(aria2cRetryWait);
        args.append(" --max-tries=").append(aria2cMaxTries);

        if (aria2cUserAgent != null && !aria2cUserAgent.isEmpty()) {
            args.append(" --user-agent=\"").append(aria2cUserAgent).append("\"");
        }

        return args.toString();
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        map.put("ytdlp.format", format);

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

        map.put("ytdlp.fragment-retries", String.valueOf(fragmentRetries));

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

        if (playlistEnd && playlistItems > 0) {
            map.put("ytdlp.playlist-items", "1-" + playlistItems);
        }

        if (geoBypass) {
            map.put("ytdlp.geo-bypass", "true");
        }

        if (cookieFile != null) {
            map.put("ytdlp.cookies", cookieFile);
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
        copy.setConnections(this.getConnections());
        copy.setUseProxy(this.isUseProxy());
        copy.setProxyAddress(this.getProxyAddress());

        // Copy YtDlp-specific settings
        copy.format = this.format;
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
        copy.playlistEnd = this.playlistEnd;
        copy.playlistItems = this.playlistItems;
        copy.geoBypass = this.geoBypass;
        copy.cookieFile = this.cookieFile;
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
