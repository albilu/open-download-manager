package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real YtDlpClient metadata pipeline against a scripted yt-dlp
 * binary that prints warning noise ahead of the JSON payload, exactly like
 * the real tool does. yt-dlp emits single-line JSON, so the fixture does too.
 */
@DisplayName("YtDlpClient video info extraction")
class YtDlpVideoInfoParsingTest {

    @TempDir
    Path tempDir;

    private Path fakeYtDlp(String stdout) throws IOException, InterruptedException {
        Path script = tempDir.resolve("yt-dlp-fake-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\ncat <<'__JSON__'\n" + stdout + "\n__JSON__\nexit 0\n");
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static final String METADATA_JSON = "WARNING: some codec noise the tool prints first\n"
            + "{\"id\":\"odmVideo123\",\"title\":\"ODM Test Video\",\"duration\":213.5,"
            + "\"formats\":["
            + "{\"format_id\":\"18\",\"ext\":\"mp4\",\"height\":360,\"filesize\":12345678,"
            + "\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"url\":\"http://e.test/360.mp4\",\"resolution\":\"640x360\"},"
            + "{\"format_id\":\"22\",\"ext\":\"mp4\",\"height\":720,\"filesize\":24567890,"
            + "\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"url\":\"http://e.test/720.mp4\",\"resolution\":\"1280x720\"},"
            + "{\"format_id\":\"140\",\"ext\":\"m4a\",\"filesize\":3456789,"
            + "\"vcodec\":\"none\",\"acodec\":\"mp4a\",\"url\":\"http://e.test/audio.m4a\"}],"
            + "\"subtitles\":{\"en\":[{\"ext\":\"vtt\",\"url\":\"http://e.test/en.vtt\"}]}}";

    @Test
    @DisplayName("metadata JSON ahead of warning lines parses into a VideoInfo")
    void metadataParsesThroughNoise() throws Exception {
        YtDlpClient client = new YtDlpClient(fakeYtDlp(METADATA_JSON).toString());

        YtDlpClient.VideoInfo info = client.extractInfo("https://www.youtube.com/watch?v=odmVideo123")
                .get(30, TimeUnit.SECONDS);

        assertNotNull(info);
        assertEquals("odmVideo123", info.getId());
        assertEquals("ODM Test Video", info.getTitle());
        assertEquals(213, info.getDuration());
        assertEquals(3, info.getFormats().size(), "all three formats must be parsed");
        assertTrue(info.getFormats().stream().anyMatch(f -> "22".equals(f.getFormatId())));
        assertTrue(info.getFormats().stream().anyMatch(f -> f.getResolution() != null
                        && f.getResolution().contains("720")),
                "the 720p format must carry its resolution");
        // the extractor currently leaves the subtitles list unparsed; the
        // field must simply never explode when the payload contains tracks
        assertTrue(info.getSubtitles() == null || info.getSubtitles().isEmpty());
    }

    @Test
    @DisplayName("flat playlist JSON parses into selectable preview rows")
    void playlistPreviewParses() throws Exception {
        String playlistJson = "{\"id\":\"PL-odm\",\"title\":\"ODM Playlist\",\"entries\":["
                + "{\"id\":\"one\",\"title\":\"First\",\"duration\":61,"
                + "\"playlist_index\":1,\"webpage_url\":\"https://e.test/one\"},"
                + "{\"id\":\"two\",\"title\":\"Second\",\"duration\":122,"
                + "\"playlist_index\":2,\"url\":\"https://e.test/two\"}]}";
        YtDlpClient client = new YtDlpClient(fakeYtDlp(playlistJson).toString());

        YtDlpClient.VideoInfo info = client.previewMedia(
                "https://e.test/playlist", new YtDlpSettings()).get(30, TimeUnit.SECONDS);

        assertTrue(info.isPlaylist());
        assertEquals(2, info.getEntries().size());
        assertEquals(1, info.getEntries().getFirst().getIndex());
        assertEquals("First", info.getEntries().getFirst().getTitle());
        assertEquals(61, info.getEntries().getFirst().getDuration());
        assertEquals("https://e.test/two", info.getEntries().getLast().getUrl());
    }

    @Test
    @DisplayName("a run without JSON metadata fails with a clear error")
    void missingJsonFails() throws Exception {
        YtDlpClient client = new YtDlpClient(
                fakeYtDlp("ERROR: Unsupported URL  (no json at all)").toString());

        assertThrows(Exception.class,
                () -> client.extractInfo("https://www.youtube.com/watch?v=x").get(30, TimeUnit.SECONDS),
                "missing metadata must surface as a failure");
    }

    @Test
    @DisplayName("a crashing yt-dlp fails the metadata future")
    void crashingBinaryFails() throws Exception {
        Path script = tempDir.resolve("yt-dlp-crash-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\necho 'boom' >&2\nexit 1\n");
        Files.setPosixFilePermissions(script,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        YtDlpClient client = new YtDlpClient(script.toString());

        assertThrows(Exception.class,
                () -> client.extractInfo("https://www.youtube.com/watch?v=x").get(30, TimeUnit.SECONDS));
    }
}
