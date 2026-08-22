package org.ytdlp;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for URL validation and site detection for yt-dlp supported platforms.
 * This class helps determine if a URL is supported by yt-dlp and provides
 * information about the video platform.
 */
public class YtDlpUrlUtils {

    // Common video platforms supported by yt-dlp
    // Regex patterns for common video URL structures
    private static final Pattern YOUTUBE_VIDEO_PATTERN = Pattern.compile(
        "(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/|.*[?&]v=)|youtu\\.be\\/)([^\"&?\\/\\s]{11})"
    );

    private static final Pattern YOUTUBE_PLAYLIST_PATTERN = Pattern.compile(
        "youtube\\.com\\/.*[?&]list=([^&\\s]+)"
    );

    private static final Pattern VIMEO_PATTERN = Pattern.compile(
        "vimeo\\.com\\/(\\d+)"
    );

    private static final Pattern DAILYMOTION_PATTERN = Pattern.compile(
        "dailymotion\\.com\\/video\\/([^_\\s]+)"
    );

    private static final Pattern TWITCH_PATTERN = Pattern.compile(
        "twitch\\.tv\\/(?:videos\\/)?([^\\s\\/]+)"
    );

    /**
     * Platform types that can be detected from URLs.
     */
    public enum Platform {
        YOUTUBE,
        YOUTUBE_PLAYLIST,
        VIMEO,
        DAILYMOTION,
        TWITCH,
        TIKTOK,
        INSTAGRAM,
        FACEBOOK,
        TWITTER,
        REDDIT,
        SOUNDCLOUD,
        GENERIC,
        UNKNOWN
    }

    /**
     * Information about a detected URL.
     */
    public static class UrlInfo {
        private final String originalUrl;
        private final Platform platform;
        private final String videoId;
        private final boolean isPlaylist;
        private final boolean isLiveStream;
        private final String domain;

        public UrlInfo(String originalUrl, Platform platform, String videoId,
                      boolean isPlaylist, boolean isLiveStream, String domain) {
            this.originalUrl = originalUrl;
            this.platform = platform;
            this.videoId = videoId;
            this.isPlaylist = isPlaylist;
            this.isLiveStream = isLiveStream;
            this.domain = domain;
        }

        public String getOriginalUrl() { return originalUrl; }
        public Platform getPlatform() { return platform; }
        public String getVideoId() { return videoId; }
        public boolean isPlaylist() { return isPlaylist; }
        public boolean isLiveStream() { return isLiveStream; }
        public String getDomain() { return domain; }

        @Override
        public String toString() {
            return String.format("UrlInfo{platform=%s, videoId='%s', isPlaylist=%s, domain='%s'}",
                platform, videoId, isPlaylist, domain);
        }
    }

    /**
     * Checks whether the URL's host is a known media platform domain,
     * including subdomains (for example music.youtube.com).
     *
     * @param url the URL to inspect
     * @return true when the host is a known media platform
     */
    public static boolean isKnownMediaHost(String url) {
        return org.manager.download.MediaUrlDetector.isKnownMediaHost(url);
    }

    /**
     * Checks whether the URL points to a streaming media manifest or segment
     * (HLS playlist, DASH manifest, fragmented MP4) that requires yt-dlp.
     *
     * @param url the URL to inspect
     * @return true when the URL is a media manifest or segment
     */
    public static boolean isMediaManifestUrl(String url) {
        return org.manager.download.MediaUrlDetector.isMediaManifestUrl(url);
    }

    /**
     * Checks whether a URL should be routed to the yt-dlp handler: either a
     * streaming manifest (HLS/DASH/fragmented MP4) or a page hosted on a known
     * media platform. Direct media file URLs are deliberately excluded so they
     * keep using the multi-connection aria2 engine.
     *
     * @param url the URL to inspect
     * @return true when the URL should be handled by yt-dlp
     */
    public static boolean isMediaUrl(String url) {
        return org.manager.download.MediaUrlDetector.isMediaUrl(url);
    }

    /**
     * Validates if a URL is potentially supported by yt-dlp.
     *
     * @param url The URL to validate
     * @return true if the URL appears to be supported, false otherwise
     */
    public static boolean isSupported(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {
            URI uri = new URI(url);
            String host = uri.getHost();

            if (host == null) {
                return false;
            }

            // Check against known supported domains (superset probe via
            // the core detector: exact and subdomain matches)
            if (org.manager.download.MediaUrlDetector.isKnownMediaHost(url)) {
                return true;
            }

            // Check for common video URL patterns
            return containsVideoPattern(url);

        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Analyzes a URL and extracts information about it.
     *
     * @param url The URL to analyze
     * @return UrlInfo object containing details about the URL
     */
    public static UrlInfo analyzeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return new UrlInfo(url, Platform.UNKNOWN, null, false, false, null);
        }

        try {
            URI uri = new URI(url);
            String host = uri.getHost();

            if (host == null) {
                return new UrlInfo(url, Platform.UNKNOWN, null, false, false, null);
            }

            host = host.toLowerCase();
            Platform platform = detectPlatform(host, url);
            String videoId = extractVideoId(platform, url);
            boolean isPlaylist = detectPlaylist(platform, url);
            boolean isLiveStream = detectLiveStream(url);

            return new UrlInfo(url, platform, videoId, isPlaylist, isLiveStream, host);

        } catch (URISyntaxException e) {
            return new UrlInfo(url, Platform.UNKNOWN, null, false, false, null);
        }
    }

    /**
     * Checks if a URL is a valid web URL.
     *
     * @param url The URL to validate
     * @return true if the URL is valid, false otherwise
     */
    public static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {
            URL urlObj = new URL(url);
            String protocol = urlObj.getProtocol();
            return "http".equals(protocol) || "https".equals(protocol);
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Normalizes a URL by ensuring it has a proper protocol.
     *
     * @param url The URL to normalize
     * @return The normalized URL
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }

        url = url.trim();

        // Add protocol if missing
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        return url;
    }

    /**
     * Detects the platform from the hostname and URL.
     */
    private static Platform detectPlatform(String host, String url) {
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            if (YOUTUBE_PLAYLIST_PATTERN.matcher(url).find()) {
                return Platform.YOUTUBE_PLAYLIST;
            }
            return Platform.YOUTUBE;
        }

        if (host.contains("vimeo.com")) {
            return Platform.VIMEO;
        }

        if (host.contains("dailymotion.com")) {
            return Platform.DAILYMOTION;
        }

        if (host.contains("twitch.tv")) {
            return Platform.TWITCH;
        }

        if (host.contains("tiktok.com")) {
            return Platform.TIKTOK;
        }

        if (host.contains("instagram.com")) {
            return Platform.INSTAGRAM;
        }

        if (host.contains("facebook.com") || host.equals("fb.watch")) {
            return Platform.FACEBOOK;
        }

        if (host.contains("twitter.com") || host.equals("x.com")) {
            return Platform.TWITTER;
        }

        if (host.contains("reddit.com") || host.equals("v.redd.it")) {
            return Platform.REDDIT;
        }

        if (host.contains("soundcloud.com")) {
            return Platform.SOUNDCLOUD;
        }

        // Check if it's a supported domain but not specifically categorized
        if (org.manager.download.MediaUrlDetector.isKnownMediaHost(url)) {
            return Platform.GENERIC;
        }

        return Platform.UNKNOWN;
    }

    /**
     * Extracts video ID from URL based on platform.
     */
    private static String extractVideoId(Platform platform, String url) {
        return switch (platform) {
            case YOUTUBE, YOUTUBE_PLAYLIST -> {
                java.util.regex.Matcher youtubeMatcher = YOUTUBE_VIDEO_PATTERN.matcher(url);
                yield youtubeMatcher.find() ? youtubeMatcher.group(1) : null;
            }
            case VIMEO -> {
                java.util.regex.Matcher vimeoMatcher = VIMEO_PATTERN.matcher(url);
                yield vimeoMatcher.find() ? vimeoMatcher.group(1) : null;
            }
            case DAILYMOTION -> {
                java.util.regex.Matcher dailymotionMatcher = DAILYMOTION_PATTERN.matcher(url);
                yield dailymotionMatcher.find() ? dailymotionMatcher.group(1) : null;
            }
            case TWITCH -> {
                java.util.regex.Matcher twitchMatcher = TWITCH_PATTERN.matcher(url);
                yield twitchMatcher.find() ? twitchMatcher.group(1) : null;
            }
            default -> null;
        };
    }

    /**
     * Detects if the URL is a playlist.
     */
    private static boolean detectPlaylist(Platform platform, String url) {
        return switch (platform) {
            case YOUTUBE, YOUTUBE_PLAYLIST -> YOUTUBE_PLAYLIST_PATTERN.matcher(url).find();
            default -> url.toLowerCase().contains("playlist") ||
                       url.toLowerCase().contains("list=");
        };
    }

    /**
     * Detects if the URL might be a live stream.
     */
    private static boolean detectLiveStream(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("live") ||
               lowerUrl.contains("stream") ||
               lowerUrl.contains("twitch.tv") ||
               lowerUrl.contains("/live/");
    }

    /**
     * Checks if URL contains common video patterns.
     */
    private static boolean containsVideoPattern(String url) {
        String lowerUrl = url.toLowerCase();

        // Common video file extensions
        if (lowerUrl.matches(".*\\.(mp4|webm|mkv|avi|mov|wmv|flv|m4v)([?&#].*)?$")) {
            return true;
        }

        // Common video URL patterns
        return lowerUrl.contains("video") ||
               lowerUrl.contains("watch") ||
               lowerUrl.contains("player") ||
               lowerUrl.contains("embed") ||
               lowerUrl.contains("stream");
    }

    /**
     * Gets suggested yt-dlp format for a platform.
     *
     * @param platform The detected platform
     * @param quality Desired quality ("best", "worst", "720p", etc.)
     * @return Suggested format string for yt-dlp
     */
    public static String getSuggestedFormat(Platform platform, String quality) {
        if (quality == null) {
            quality = "best";
        }

        return switch (platform) {
            case YOUTUBE -> switch (quality.toLowerCase()) {
                case "audio" -> "bestaudio/best";
                case "720p" -> "bestvideo[height<=720]+bestaudio/best[height<=720]";
                case "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
                default -> "bestvideo+bestaudio/best";
            };
            case TWITCH -> "best"; // Twitch usually has good quality streams
            case SOUNDCLOUD -> "bestaudio/best"; // Audio platform
            case TIKTOK -> "best"; // Usually mobile-optimized videos
            default -> quality.equals("audio") ? "bestaudio/best" : "best";
        };
    }

    /**
     * Checks if a platform typically supports audio extraction.
     *
     * @param platform The platform to check
     * @return true if audio extraction is commonly used for this platform
     */
    public static boolean supportsAudioExtraction(Platform platform) {
        return switch (platform) {
            case YOUTUBE, SOUNDCLOUD, VIMEO, DAILYMOTION -> true;
            default -> false;
        };
    }

    /**
     * Checks if a platform typically supports subtitles.
     *
     * @param platform The platform to check
     * @return true if subtitles are commonly available for this platform
     */
    public static boolean supportsSubtitles(Platform platform) {
        return switch (platform) {
            case YOUTUBE, VIMEO -> true;
            default -> false;
        };
    }

    /**
     * Gets the display name for a platform.
     *
     * @param platform The platform
     * @return Human-readable platform name
     */
    public static String getPlatformDisplayName(Platform platform) {
        return switch (platform) {
            case YOUTUBE -> "YouTube";
            case YOUTUBE_PLAYLIST -> "YouTube Playlist";
            case VIMEO -> "Vimeo";
            case DAILYMOTION -> "Dailymotion";
            case TWITCH -> "Twitch";
            case TIKTOK -> "TikTok";
            case INSTAGRAM -> "Instagram";
            case FACEBOOK -> "Facebook";
            case TWITTER -> "Twitter/X";
            case REDDIT -> "Reddit";
            case SOUNDCLOUD -> "SoundCloud";
            case GENERIC -> "Generic Video Site";
            case UNKNOWN -> "Unknown";
            default -> platform.toString();
        };
    }
}
