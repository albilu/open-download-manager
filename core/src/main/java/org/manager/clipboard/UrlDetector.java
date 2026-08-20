package org.manager.clipboard;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for detecting and extracting URLs from text content. Supports
 * various URL formats including HTTP, HTTPS, FTP, magnet links, and torrent
 * files.
 */
public class UrlDetector {

    private static final Logger LOGGER = Logger.getLogger(UrlDetector.class.getName());

    // Comprehensive URL regex pattern that matches various protocols
    private static final String URL_REGEX = """
            (?i)\\b(?:
            (?:https?://)(?:[-\\w.])+(?:[:\\d]+)?(?:/(?:[\\w._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})*)*(?:\\?(?:[\\w._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})*)?(?:#(?:[\\w._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})*)?|
            (?:ftps?://|sftp://)(?:[-\\w:@.]+)?(?:[:\\d]+)?(?:/(?:[\\w._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})*)*|
            magnet:\\?xt=urn:[a-z0-9]+:[a-zA-Z0-9]{32,40}[&\\w\\d%+/=.]*|
            file://[^\\s]*\\.torrent
            )\\b"""
            .replaceAll("\\s+", "");

    private static final Pattern URL_PATTERN = Pattern.compile(URL_REGEX);

    // Pattern for detecting .torrent file extensions
    private static final Pattern TORRENT_PATTERN = Pattern.compile("(?i).*\\.torrent$");

    // Pattern for magnet links
    private static final Pattern MAGNET_PATTERN = Pattern.compile("(?i)^magnet:\\?xt=urn:");

    // Pattern for YouTube and video URLs
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile("""
            (?i)(?:https?://)?(?:www\\.)?
            (?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|youtube\\.com/v/|
            vimeo\\.com/|dailymotion\\.com/video/|twitch\\.tv/|facebook\\.com/.*videos/|
            instagram\\.com/p/|tiktok\\.com/|twitter\\.com/.*status/)
            [\\w-]+""".replaceAll("\\s+", ""));

    // Common download file extensions
    private static final Set<String> DOWNLOAD_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "exe", "msi", "dmg", "pkg", "deb", "rpm",
            "iso", "img", "bin",
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm",
            "mp3", "flac", "wav", "ogg", "aac",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "apk", "ipa",
            "torrent"));

    /**
     * Extracts all valid URLs from the given text.
     *
     * @param text The text to search for URLs
     * @return A list of valid URIs found in the text
     */
    public static List<URI> extractUrls(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<URI> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String urlString = matcher.group().trim();

            // Avoid duplicates
            if (seen.contains(urlString)) {
                continue;
            }
            seen.add(urlString);

            try {
                URI uri = normalizeUrl(urlString);
                if (uri != null && isValidDownloadUrl(uri)) {
                    urls.add(uri);
                    LOGGER.fine("Detected valid URL: " + uri);
                }
            } catch (Exception e) {
                LOGGER.fine("Invalid URL detected: " + urlString + " - " + e.getMessage());
            }
        }

        return urls;
    }

    /**
     * Checks if the given text contains any valid URLs.
     *
     * @param text The text to check
     * @return true if valid URLs are found, false otherwise
     */
    public static boolean containsUrls(String text) {
        return !extractUrls(text).isEmpty();
    }

    /**
     * Normalizes a URL string to a proper URI.
     *
     * @param urlString The URL string to normalize
     * @return A normalized URI, or null if invalid
     */
    private static URI normalizeUrl(String urlString) {
        try {
            // Handle magnet links directly
            if (MAGNET_PATTERN.matcher(urlString).find()) {
                return new URI(urlString);
            }

            // For HTTP/HTTPS URLs, ensure they're properly formed
            if (!urlString.startsWith("http://") && !urlString.startsWith("https://")
                    && !urlString.startsWith("ftp://") && !urlString.startsWith("file://")) {
                // Try adding https:// prefix for URLs that look like web URLs
                if (urlString.contains(".") && !urlString.contains(" ")) {
                    urlString = "https://" + urlString;
                }
            }

            // Validate the URL
            URL url = new URL(urlString);
            return url.toURI();
        } catch (MalformedURLException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * Checks if a URI represents a valid download URL.
     *
     * @param uri The URI to check
     * @return true if it's a valid download URL, false otherwise
     */
    private static boolean isValidDownloadUrl(URI uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }

        scheme = scheme.toLowerCase();

        // Accept common download protocols
        if (scheme.equals("http") || scheme.equals("https")
                || scheme.equals("ftp") || scheme.equals("ftps")
                || scheme.equals("sftp")
                || scheme.equals("magnet")
                || scheme.equals("file")) {

            // For magnet links, they're always valid for downloads
            if (scheme.equals("magnet")) {
                return true;
            }

            // For file:// URLs, check if it's a torrent file
            if (scheme.equals("file")) {
                return TORRENT_PATTERN.matcher(uri.getPath()).matches();
            }

            // SFTP references are always treated as downloads
            if (scheme.equals("sftp")) {
                return true;
            }

            // For HTTP/HTTPS/FTP, check various criteria
            return isLikelyDownloadableContent(uri);
        }

        return false;
    }

    /**
     * Determines if a URI is likely to point to downloadable content.
     *
     * @param uri The URI to check
     * @return true if it's likely downloadable content
     */
    private static boolean isLikelyDownloadableContent(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            path = "";
        }

        String query = uri.getQuery();
        String fullUrl = uri.toString().toLowerCase();

        // Check for direct file downloads by extension
        if (hasDownloadableExtension(path)) {
            return true;
        }

        // Check for video URLs (YouTube, Vimeo, etc.)
        if (VIDEO_URL_PATTERN.matcher(fullUrl).find()) {
            return true;
        }

        // Check for torrent files
        if (TORRENT_PATTERN.matcher(path).matches()) {
            return true;
        }

        // Check for common download URL patterns
        if (containsDownloadIndicators(fullUrl)) {
            return true;
        }

        // Check for URLs with download-related query parameters
        if (query != null && hasDownloadQueryParams(query)) {
            return true;
        }

        // If it's a direct link to a file (has extension), it's probably downloadable
        if (path.contains(".") && !path.endsWith("/")) {
            String extension = getFileExtension(path);
            if (extension != null && extension.length() <= 5) {
                return true;
            }
        }

        // For URLs without clear indicators, we'll be permissive
        // The user can decide whether to download or not
        return true;
    }

    /**
     * Checks if a path has a downloadable file extension.
     *
     * @param path The file path
     * @return true if it has a downloadable extension
     */
    private static boolean hasDownloadableExtension(String path) {
        String extension = getFileExtension(path);
        return extension != null && DOWNLOAD_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Extracts the file extension from a path.
     *
     * @param path The file path
     * @return The file extension without the dot, or null if none
     */
    private static String getFileExtension(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        int lastDot = path.lastIndexOf('.');
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));

        if (lastDot > lastSlash && lastDot < path.length() - 1) {
            return path.substring(lastDot + 1);
        }

        return null;
    }

    /**
     * Checks if the URL contains download-related indicators.
     *
     * @param url The URL to check
     * @return true if it contains download indicators
     */
    private static boolean containsDownloadIndicators(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("download")
                || lowerUrl.contains("get")
                || lowerUrl.contains("file")
                || lowerUrl.contains("attachment")
                || lowerUrl.contains("releases")
                || lowerUrl.contains("dist")
                || lowerUrl.contains("mirror")
                || lowerUrl.contains("cdn");
    }

    /**
     * Checks if query parameters indicate a download.
     *
     * @param query The query string
     * @return true if it has download-related parameters
     */
    private static boolean hasDownloadQueryParams(String query) {
        String lowerQuery = query.toLowerCase();
        return lowerQuery.contains("download")
                || lowerQuery.contains("attachment")
                || lowerQuery.contains("dl=")
                || lowerQuery.contains("file=")
                || lowerQuery.contains("export");
    }

    /**
     * Checks if a URI is a magnet link.
     *
     * @param uri The URI to check
     * @return true if it's a magnet link
     */
    public static boolean isMagnetLink(URI uri) {
        return uri != null && "magnet".equalsIgnoreCase(uri.getScheme());
    }

    /**
     * Checks if a URI points to a torrent file.
     *
     * @param uri The URI to check
     * @return true if it's a torrent file
     */
    public static boolean isTorrentFile(URI uri) {
        if (uri == null) {
            return false;
        }
        String path = uri.getPath();
        return path != null && TORRENT_PATTERN.matcher(path).matches();
    }

    /**
     * Checks if a URI is likely a video URL that can be downloaded with yt-dlp.
     *
     * @param uri The URI to check
     * @return true if it's likely a video URL
     */
    public static boolean isVideoUrl(URI uri) {
        if (uri == null) {
            return false;
        }
        return VIDEO_URL_PATTERN.matcher(uri.toString()).find();
    }
}
