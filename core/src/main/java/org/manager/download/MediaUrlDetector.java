package org.manager.download;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Core-owned routing classification for media URLs. The download domain
 * model needs to know whether a URI belongs to a media platform / streaming
 * manifest (routed to the yt-dlp engine) — but the model must not depend on
 * the yt-dlp integration package. This detector holds the single source of
 * truth; the yt-dlp layer delegates to it.
 */
public final class MediaUrlDetector {

    private static final Set<String> SUPPORTED_DOMAINS = new HashSet<>(Arrays.asList(
        // YouTube
        "youtube.com", "www.youtube.com", "youtu.be", "m.youtube.com",

        // Popular video platforms
        "vimeo.com", "www.vimeo.com",
        "dailymotion.com", "www.dailymotion.com",
        "twitch.tv", "www.twitch.tv", "clips.twitch.tv",
        "tiktok.com", "www.tiktok.com", "vm.tiktok.com",
        "instagram.com", "www.instagram.com",
        "facebook.com", "www.facebook.com", "fb.watch",
        "twitter.com", "www.twitter.com", "x.com",
        "reddit.com", "www.reddit.com", "v.redd.it",

        // Media platforms
        "soundcloud.com", "www.soundcloud.com",
        "bandcamp.com",
        "archive.org", "www.archive.org",
        "metacafe.com", "www.metacafe.com",
        "liveleak.com", "www.liveleak.com",

        // News and media
        "cnn.com", "www.cnn.com",
        "bbc.co.uk", "www.bbc.co.uk", "bbc.com", "www.bbc.com",
        "reuters.com", "www.reuters.com",
        "vice.com", "www.vice.com",

        // Streaming platforms
        "crunchyroll.com", "www.crunchyroll.com",
        "funimation.com", "www.funimation.com",
        "netflix.com", "www.netflix.com",

        // Educational
        "coursera.org", "www.coursera.org",
        "udemy.com", "www.udemy.com",
        "khanacademy.org", "www.khanacademy.org",

        // Adult content (commonly supported)
        "pornhub.com", "www.pornhub.com",
        "xvideos.com", "www.xvideos.com",
        "xhamster.com", "www.xhamster.com"
    ));

    // Streaming media manifests and segments (HLS playlists, DASH manifests,
    // fragmented MP4) that require yt-dlp instead of plain HTTP downloading.
    private static final Pattern MEDIA_MANIFEST_PATTERN = Pattern.compile(
        ".*\\.(m3u8|mpd|m4s)([?&#].*)?$"
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
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            for (String domain : SUPPORTED_DOMAINS) {
                if (host.equals(domain) || host.endsWith("." + domain)) {
                    return true;
                }
            }
            return false;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Checks whether the URL points to a streaming media manifest or segment
     * (HLS playlist, DASH manifest, fragmented MP4) that requires yt-dlp.
     *
     * @param url the URL to inspect
     * @return true when the URL is a media manifest or segment
     */
    public static boolean isMediaManifestUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            String path = new URI(url).getPath();
            return path != null && MEDIA_MANIFEST_PATTERN.matcher(path.toLowerCase()).matches();
        } catch (URISyntaxException e) {
            return false;
        }
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
        return isKnownMediaHost(url) || isMediaManifestUrl(url);
    }
}
