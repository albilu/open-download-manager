package org.manager.clipboard;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.manager.download.Download;
import org.manager.url.DownloadUrlPolicy;

/**
 * Compatibility facade for callers of the former clipboard-owned detector.
 * All extraction, normalization and validation live in {@link DownloadUrlPolicy}.
 */
@Deprecated(forRemoval = false)
public class UrlDetector {
    public static List<URI> extractUrls(String text) {
        return new ArrayList<>(DownloadUrlPolicy.extract(text).stream()
                .map(DownloadUrlPolicy.ValidatedSource::uri).toList());
    }

    public static boolean containsUrls(String text) {
        return DownloadUrlPolicy.containsUrls(text);
    }

    public static Optional<URI> normalizeAndValidate(String input) {
        return DownloadUrlPolicy.parse(input).map(DownloadUrlPolicy.ValidatedSource::uri);
    }

    public static URI requireValidDownloadUrl(String input) {
        return DownloadUrlPolicy.require(input).uri();
    }

    public static URI requireValidDownloadUri(URI uri) {
        return DownloadUrlPolicy.require(uri).uri();
    }

    public static boolean isValidDownloadUrl(URI uri) {
        return DownloadUrlPolicy.isValidDownloadUri(uri);
    }

    public static boolean isMagnetLink(URI uri) {
        return Download.Protocol.fromUri(uri) == Download.Protocol.MAGNET;
    }

    public static boolean isTorrentFile(URI uri) {
        return Download.Protocol.fromUri(uri) == Download.Protocol.TORRENT;
    }
}
