package org.manager.download;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Core-owned routing classification for media URLs. The download domain
 * model needs to know whether a URI belongs to a media platform / streaming
 * manifest (routed to the yt-dlp engine) — but the model must not depend on
 * the yt-dlp integration package. This detector holds the single source of
 * truth; input paths delegate their engine-routing decision to it.
 */
public final class MediaUrlDetector {

    private static final Set<String> MEDIA_DOMAINS = Set.of(
        // YouTube
        "youtube.com", "youtu.be",

        // Popular video platforms
        "vimeo.com",
        "dailymotion.com",
        "twitch.tv",
        "tiktok.com",
        "instagram.com",
        "facebook.com", "fb.watch",
        "twitter.com", "x.com",
        "reddit.com", "v.redd.it",

        // Media platforms
        "soundcloud.com",
        "bandcamp.com",
        "archive.org",
        "metacafe.com",
        "liveleak.com",

        // News and media
        "cnn.com",
        "bbc.co.uk", "bbc.com",
        "reuters.com",
        "vice.com",

        // Streaming platforms
        "crunchyroll.com",
        "funimation.com",
        "netflix.com",

        // Educational
        "coursera.org",
        "udemy.com",
        "khanacademy.org",

        // Adult content (commonly supported)
        "pornhub.com",
        "xvideos.com",
        "xhamster.com"
    );

    private static final Set<String> MEDIA_STREAM_EXTENSIONS = Set.of(
            "m3u8", "mpd", "m4s"
    );

    /**
     * Explicit files keep the ordinary aria2 route even when hosted by a
     * media/news domain. This prevents a broad host rule from sending an
     * Archive.org PDF, a BBC image, or a direct MP4 to yt-dlp. Manifest and
     * fragmented-stream extensions are checked first and are not listed here.
     */
    private static final Set<String> DIRECT_FILE_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "exe", "msi", "dmg", "pkg", "deb", "rpm",
            "iso", "img", "bin", "apk", "ipa",
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v",
            "mp3", "flac", "wav", "ogg", "aac", "m4a", "opus",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub",
            "jpg", "jpeg", "png", "gif", "webp", "svg",
            "srt", "vtt", "txt", "csv", "json", "xml",
            "torrent", "metalink", "meta4"
    );

    private MediaUrlDetector() {
    }

    /**
     * Checks whether the URL's host is a known media platform.
     *
     * @param url the URL to inspect
     * @return true when the host is a known media platform
     */
    public static boolean isKnownMediaHost(String url) {
        return isKnownMediaHost(parseWebUri(url));
    }

    /** URI overload used by the download and clipboard layers. */
    public static boolean isKnownMediaHost(URI uri) {
        if (!isWebUri(uri)) {
            return false;
        }
        String rawHost = uri.getHost().toLowerCase(Locale.ROOT);
        String host = rawHost.endsWith(".")
                ? rawHost.substring(0, rawHost.length() - 1)
                : rawHost;
        return MEDIA_DOMAINS.stream()
                .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    /**
     * Checks whether the URL points to a streaming media manifest or segment
     * (HLS playlist, DASH manifest, fragmented MP4) that requires yt-dlp.
     *
     * @param url the URL to inspect
     * @return true when the URL is a media manifest or segment
     */
    public static boolean isMediaManifestUrl(String url) {
        return isMediaManifestUrl(parseWebUri(url));
    }

    /** URI overload used when input has already passed central validation. */
    public static boolean isMediaManifestUrl(URI uri) {
        return isWebUri(uri) && MEDIA_STREAM_EXTENSIONS.contains(pathExtension(uri));
    }

    /**
     * Checks whether a URL should be routed to the yt-dlp engine: either a
     * streaming manifest (HLS/DASH/fragmented MP4) or a page hosted on a
     * known media platform. Direct media file URLs are deliberately excluded
     * so they keep using the multi-connection aria2 engine.
     *
     * @param url the URL to inspect
     * @return true when the URL should be handled by the media engine
     */
    public static boolean isMediaUrl(String url) {
        return isMediaUrl(parseWebUri(url));
    }

    /**
     * Canonical media-routing decision shared by every input path.
     * Streaming manifests/segments take the yt-dlp route, direct files take
     * aria2, and remaining pages on known media hosts take yt-dlp.
     */
    public static boolean isMediaUrl(URI uri) {
        if (!isWebUri(uri)) {
            return false;
        }
        String extension = pathExtension(uri);
        if (MEDIA_STREAM_EXTENSIONS.contains(extension)) {
            return true;
        }
        if (DIRECT_FILE_EXTENSIONS.contains(extension)) {
            return false;
        }
        return isKnownMediaHost(uri);
    }

    private static URI parseWebUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            return isWebUri(uri) ? uri : null;
        } catch (IllegalArgumentException invalidUri) {
            return null;
        }
    }

    private static boolean isWebUri(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null
                || uri.getHost().isBlank()) {
            return false;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
    }

    private static String pathExtension(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String segment = path.substring(slash + 1);
        int parameters = segment.indexOf(';');
        if (parameters >= 0) {
            segment = segment.substring(0, parameters);
        }
        int dot = segment.lastIndexOf('.');
        if (dot < 0 || dot == segment.length() - 1) {
            return "";
        }
        return segment.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
