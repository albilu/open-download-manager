package org.ytdlp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.manager.url.DownloadUrlPolicy;

/** Confidence is based on successful responses, not arbitrary strings found in HTML. */
final class MediaCandidates {
    private static final Set<String> DIRECT_EXTENSIONS = Set.of(
            "mp4", "m4v", "webm", "mkv", "mov", "avi", "ogv", "ogg",
            "mp3", "m4a", "flac", "wav", "opus");
    private static final Set<String> MANIFEST_TYPES = Set.of(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
            "audio/x-mpegurl", "application/dash+xml");
    private static final Pattern FRAGMENT = Pattern.compile(
            "(?i)(?:^|[/_.-])(?:init|segment|seg|chunk|fragment)(?:[0-9]*[_.-]|$)");
    private static final Pattern AD = Pattern.compile(
            "(?i)(?:^|[/_.-])(?:ads?|advert|advertisement|preroll|vast|vpaid)(?:[/_.-]|$)");
    private final Map<String, Observed> observed = new LinkedHashMap<>();
    private final Set<String> children = new HashSet<>();
    private final Set<String> masterManifests = new HashSet<>();

    record Observed(String url, int confidence, long size, Map<String, String> headers) { }

    static int confidence(String url, int status, String contentType, String resourceType) {
        if (status < 200 || status >= 300) { return 0; }
        final URI uri;
        try { uri = DownloadUrlPolicy.require(url).requireWeb().uri(); }
        catch (IllegalArgumentException invalid) { return 0; }
        String path = uri.getPath().toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (AD.matcher(uri.getHost() + path).find() || FRAGMENT.matcher(path).find()
                || path.endsWith(".m4s") || path.endsWith(".ts")
                || type.equals("video/mp2t") || type.equals("video/iso.segment")
                || type.equals("audio/iso.segment") || type.contains("html")
                || type.contains("json") || type.startsWith("image/")) { return 0; }
        if (MANIFEST_TYPES.contains(type)) { return 100; }
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) {
            return type.isEmpty() || type.startsWith("text/")
                    || type.equals("application/octet-stream") || type.contains("xml") ? 95 : 0;
        }
        String extension = path.substring(path.lastIndexOf('.') + 1);
        boolean mediaType = type.startsWith("video/") || type.startsWith("audio/")
                || type.equals("application/ogg") || type.equals("application/vnd.rn-realmedia");
        if (mediaType) { return 90; }
        // An extension plus a real media request or binary response is sufficient;
        // a script merely containing a .mp4 URL is not.
        return DIRECT_EXTENSIONS.contains(extension)
                && (type.equals("application/octet-stream") || "media".equals(resourceType)) ? 85 : 0;
    }

    void observe(String url, int status, String contentType, String resourceType,
            long size, Map<String, String> headers) {
        int confidence = confidence(url, status, contentType, resourceType);
        if (confidence == 0 || observed.size() >= 128 && !observed.containsKey(url)) { return; }
        observed.put(url, new Observed(url, confidence, size, Map.copyOf(headers)));
    }

    void manifest(String url, String body) {
        if (!body.stripLeading().startsWith("#EXTM3U")) { return; }
        boolean master = body.contains("#EXT-X-STREAM-INF:") || body.contains("#EXT-X-MEDIA:");
        if (master) { masterManifests.add(url); }
        URI base = URI.create(url);
        for (String line : body.lines().toList()) {
            String value = line.trim();
            if (!value.isEmpty() && !value.startsWith("#")) { child(base, value); }
            var matcher = Pattern.compile("URI=\"([^\"]+)\"").matcher(value);
            while (matcher.find()) { child(base, matcher.group(1)); }
        }
    }

    private void child(URI base, String value) {
        try { children.add(DownloadUrlPolicy.require(base.resolve(value)).requireWeb().uri().toString()); }
        catch (IllegalArgumentException ignored) { }
    }

    boolean isEmpty() { return observed.isEmpty(); }

    List<String> cookieUrls(String pageUrl) {
        var urls = new HashSet<>(observed.keySet());
        urls.addAll(children);
        urls.add(pageUrl);
        return List.copyOf(urls);
    }

    List<MediaCandidate> results(String pageUrl, String userAgent, String cookies,
            Set<String> playerSources) {
        var result = new ArrayList<MediaCandidate>();
        for (Observed item : observed.values()) {
            if (children.contains(item.url())) { continue; }
            int confidence = item.confidence() + (masterManifests.contains(item.url()) ? 20 : 0)
                    + (playerSources.contains(item.url()) ? 20 : 0);
            Map<String, String> headers = item.headers();
            var context = new MediaRequestContext(pageUrl,
                    headers.getOrDefault("referer", ""),
                    headers.getOrDefault("user-agent", userAgent),
                    headers.getOrDefault("origin", ""), cookies);
            result.add(new MediaCandidate(item.url(), confidence, item.size(), context));
        }
        result.sort(Comparator.comparingInt(MediaCandidate::confidence).reversed()
                .thenComparing(Comparator.comparingLong(MediaCandidate::size).reversed()));
        return result.stream().limit(8).toList();
    }
}
