package org.ytdlp;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class MediaCandidatesTest {
    @ParameterizedTest
    @CsvSource({
        "clip.mp4,video/mp4", "clip.webm,video/webm", "clip.mov,video/quicktime",
        "clip.mkv,video/x-matroska", "clip.m4a,audio/mp4", "clip.mp3,audio/mpeg",
        "clip.flac,audio/flac", "stream,video/mp4", "playlist,application/vnd.apple.mpegurl",
        "stream.mpd,application/dash+xml", "clip.mp4,application/octet-stream",
        "master.m3u8,text/plain"
    })
    void acceptsCompleteMediaResponsesIncludingDirectAndExtensionlessFiles(String path, String type) {
        assertTrue(MediaCandidates.confidence("https://cdn.example/" + path + "?token=a%2Fb", 206, type, "fetch") >= 85);
    }

    @ParameterizedTest
    @CsvSource({
        "chunk-001.m4s,video/mp4", "init.mp4,video/mp4", "segment-000.mp4,video/mp4",
        "clip.ts,video/mp2t", "clip,video/iso.segment", "ads/preroll.mp4,video/mp4",
        "clip.mp4,text/html", "clip.mp4,application/json", "clip.mp4,image/jpeg",
        "clip.js,application/javascript"
    })
    void rejectsFragmentsAdsAndMisleadingExtensions(String path, String type) {
        assertEquals(0, MediaCandidates.confidence("https://cdn.example/" + path, 200, type, "fetch"));
    }

    @Test void rejectsFailedResponsesAndNonWebSources() {
        assertEquals(0, MediaCandidates.confidence("https://cdn.example/clip.mp4", 403, "video/mp4", "media"));
        assertEquals(0, MediaCandidates.confidence("blob:https://cdn.example/id", 200, "video/mp4", "media"));
        assertEquals(0, MediaCandidates.confidence("file:///tmp/clip.mp4", 200, "video/mp4", "media"));
    }

    @Test void masterManifestSupersedesItsVariantsAndInitializationFiles() {
        MediaCandidates candidates = new MediaCandidates();
        String master = "https://cdn.example/master.m3u8?token=a%2Fb";
        String child = "https://cdn.example/720/playlist.m3u8?token=a%2Fb";
        for (String url : new String[]{master, child}) {
            candidates.observe(url, 200, "application/vnd.apple.mpegurl", "fetch", 100,
                    Map.of("referer", "https://example.com/page", "origin", "https://example.com"));
        }
        candidates.observe("https://cdn.example/start.mp4", 200, "video/mp4", "fetch", 100, Map.of());
        candidates.manifest(master, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n720/playlist.m3u8?token=a%2Fb\n");
        candidates.manifest(child, "#EXTM3U\n#EXT-X-MAP:URI=\"../start.mp4\"\n#EXTINF:5\nclip.m4s\n");
        var result = candidates.results("https://example.com/page", "Browser UA", "jar", Set.of());
        assertEquals(1, result.size());
        assertEquals(master, result.getFirst().url());
        assertEquals("https://example.com", result.getFirst().context().origin());
        assertEquals("Browser UA", result.getFirst().context().userAgent());
    }

    @Test void repeatedRangesAreOneSourceAndActualPlayerSourceGetsPriority() {
        MediaCandidates candidates = new MediaCandidates();
        for (int i = 0; i < 3; i++) {
            candidates.observe("https://cdn.example/movie.mp4", 206, "video/mp4", "media", 900, Map.of());
        }
        candidates.observe("https://cdn.example/other.mp4", 200, "video/mp4", "media", 9999, Map.of());
        var result = candidates.results("https://example.com/page", "UA", "", Set.of("https://cdn.example/movie.mp4"));
        assertEquals(2, result.size());
        assertTrue(result.getFirst().url().endsWith("movie.mp4"));
    }
}
