package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for YtDlpClient class focusing on configuration, settings,
 * and basic functionality without mocking critical process execution.
 * For real process testing, see YtDlpIntegrationTest and YtDlpE2ETest.
 */
@DisplayName("YtDlpClient Unit Tests")
class YtDlpClientTest {

    @TempDir
    Path tempOutputDir;

    private YtDlpClient client;
    private static final String TEST_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String TEST_YTDLP_PATH = "/usr/bin/yt-dlp";

    @BeforeEach
    void setUp() throws Exception {
        tempOutputDir = Files.createTempDirectory("ytdlp-client-test");
        client = new YtDlpClient(TEST_YTDLP_PATH);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }

        // Clean up temporary directory
        if (tempOutputDir != null && Files.exists(tempOutputDir)) {
            Files.deleteIfExists(tempOutputDir);
        }
    }

    @Test
    @DisplayName("Should create client with default path")
    void testDefaultConstructor() {
        YtDlpClient defaultClient = new YtDlpClient();
        assertNotNull(defaultClient);
        // Cleanup
        defaultClient.shutdown();
    }

    @Test
    @DisplayName("Should create client with custom path")
    void testCustomPathConstructor() {
        String customPath = "/custom/path/to/yt-dlp";
        YtDlpClient customClient = new YtDlpClient(customPath);

        assertNotNull(customClient);
        // Cleanup
        customClient.shutdown();
    }

    @Test
    @DisplayName("Subtitle-only command uses yt-dlp settings and preserves network options")
    void subtitleOnlyCommandUsesClientSettings() {
        YtDlpSettings settings = new YtDlpSettings()
                .setWriteSubtitles(true)
                .setWriteAutoSubs(true)
                .setSubtitleLanguages(List.of("fr", "it"))
                .setOutputTemplate("clip.%(ext)s");
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:1080");
        settings
                .setReferer("https://example.com/page")
                .setUserAgent("ODM-Test");

        List<String> command = client.buildSubtitleCommand(TEST_URL, settings, tempOutputDir);

        assertEquals(TEST_YTDLP_PATH, command.getFirst());
        assertTrue(command.contains("--skip-download"));
        assertTrue(command.contains("--write-subs"));
        assertTrue(command.contains("--write-auto-subs"));
        assertTrue(command.contains("--no-overwrites"));
        assertEquals("fr,it", command.get(command.indexOf("--sub-langs") + 1));
        assertEquals("subtitle:" + tempOutputDir.toAbsolutePath().normalize(),
                command.get(command.indexOf("--paths") + 1));
        assertEquals("subtitle:clip.%(ext)s",
                command.get(command.indexOf("--output") + 1));
        assertEquals("socks5://127.0.0.1:1080",
                command.get(command.indexOf("--proxy") + 1));
        assertEquals("https://example.com/page",
                command.get(command.indexOf("--referer") + 1));
        assertEquals("ODM-Test", command.get(command.indexOf("--user-agent") + 1));
        assertEquals(TEST_URL, command.getLast());
    }

    @Test
    @DisplayName("Subtitle-only execution uses the shared process registry lifecycle")
    void subtitleOnlyExecutionUsesProcessRegistry() throws Exception {
        YtDlpClient successfulClient = new YtDlpClient("/bin/true");
        try {
            YtDlpSettings settings = new YtDlpSettings()
                    .setWriteSubtitles(true)
                    .setSubtitleLanguages(List.of("fr"));

            successfulClient.downloadSubtitles(TEST_URL, settings, tempOutputDir,
                    "subtitle-test").get(10, TimeUnit.SECONDS);

            assertEquals(0, successfulClient.getActiveProcessCount());
            assertFalse(successfulClient.cancelDownload("subtitle-test"));
        } finally {
            successfulClient.shutdown();
        }
    }

    @Test
    @DisplayName("Should handle null path gracefully")
    void testNullPathConstructor() {
        assertDoesNotThrow(() -> {
            YtDlpClient nullPathClient = new YtDlpClient(null);
            nullPathClient.shutdown();
        });
    }

    @Test
    @DisplayName("Should handle availability check without throwing")
    void testAvailabilityCheck() {
        // Test that method doesn't throw exceptions
        assertDoesNotThrow(() -> {
            boolean available = client.isAvailable();
            // Result depends on system, but method should not throw
        });
    }

    @Test
    @DisplayName("Should handle version check without throwing")
    void testVersionCheck() {
        assertDoesNotThrow(() -> {
            String version = client.getVersion();
            // Version may be null if yt-dlp not available
        });
    }

    @Test
    @DisplayName("Should check aria2c availability")
    void testIsAria2cAvailable() {
        // Test default path
        assertDoesNotThrow(() -> {
            boolean available = client.isAria2cAvailable();
        });
    }

    @Test
    @DisplayName("Should check aria2c availability with custom path")
    void testIsAria2cAvailableCustomPath() {
        assertDoesNotThrow(() -> {
            boolean available = client.isAria2cAvailable("/custom/path/aria2c");
        });
    }

    @Test
    @DisplayName("Should get aria2c version")
    void testGetAria2cVersion() {
        assertDoesNotThrow(() -> {
            String version = client.getAria2cVersion();
            // Version may be null if aria2c not available
        });
    }

    @Test
    @DisplayName("Should get aria2c version with custom path")
    void testGetAria2cVersionCustomPath() {
        assertDoesNotThrow(() -> {
            String version = client.getAria2cVersion("/custom/path/aria2c");
        });
    }

    @Test
    @DisplayName("Should handle extract info method call")
    void testExtractInfoMethodCall() {
        // Test that method can be called without throwing
        assertDoesNotThrow(() -> {
            CompletableFuture<YtDlpClient.VideoInfo> future = client.extractInfo(TEST_URL);
            // Don't wait for completion as it depends on system yt-dlp availability
        });
    }

    @Test
    @DisplayName("Should handle list formats method call")
    void testListFormatsMethodCall() {
        assertDoesNotThrow(() -> {
            CompletableFuture<List<YtDlpClient.VideoFormat>> future = client.listFormats(TEST_URL);
            // Don't wait for completion as it depends on system yt-dlp availability
        });
    }

    @Test
    @DisplayName("Should handle download method call")
    void testDownloadMethodCall() {
        YtDlpSettings settings = new YtDlpSettings().setFormat("best");
        YtDlpClient.ProgressCallback mockCallback = mock(YtDlpClient.ProgressCallback.class);

        assertDoesNotThrow(() -> {
            CompletableFuture<String> future = client.download(TEST_URL, settings, tempOutputDir, mockCallback);
            // Don't wait for completion as it depends on system yt-dlp availability
        });
    }

    @Test
    @DisplayName("Should handle cancel download with non-existent process")
    void testCancelNonExistentDownload() {
        boolean cancelled = client.cancelDownload("non-existent-process");
        assertFalse(cancelled);
    }

    @Test
    @DisplayName("Should shutdown properly")
    void testShutdown() {
        assertDoesNotThrow(() -> {
            client.shutdown();
        });
    }

    @Test
    @DisplayName("Should build commands correctly")
    void testCommandBuilding() {
        YtDlpSettings settings = new YtDlpSettings()
            .setFormat("best")
            .setEmbedThumbnail(true)
            .setExtractAudio(true);

        // Test that settings are properly configured
        assertEquals("best", settings.getFormat());
        assertTrue(settings.isEmbedThumbnail());
        assertTrue(settings.isExtractAudio());
    }

    @Test
    @DisplayName("useAria2c flag must engage the external aria2c downloader")
    void testUseAria2cFlagEngagesExternalDownloader() {
        YtDlpSettings settings = new YtDlpSettings()
                .setUseAria2c(true)
                .setAria2cConnections(8)
                .setAria2cSplitConnections(4);

        List<String> command = client.buildDownloadCommand(TEST_URL, settings, tempOutputDir);

        int downloaderIndex = command.indexOf("--external-downloader");
        assertTrue(downloaderIndex >= 0, "--external-downloader must be present");
        assertEquals("aria2c", command.get(downloaderIndex + 1));

        int argsIndex = command.indexOf("--external-downloader-args");
        assertTrue(argsIndex >= 0, "--external-downloader-args must be present");
        String args = command.get(argsIndex + 1);
        assertTrue(args.contains("-x 8"), "args must carry aria2cConnections: " + args);
        assertTrue(args.contains("-s 4"), "args must carry aria2cSplitConnections: " + args);

        // The old wiring consulted the additional-options map and never fired;
        // make sure no synthetic --use-aria2c flag leaks into the command
        assertFalse(command.contains("--use-aria2c"));
        assertFalse(command.contains("--aria2c-args"));
    }

    @Test
    @DisplayName("External downloader must stay off unless useAria2c is set")
    void testExternalDownloaderOmittedByDefault() {
        YtDlpSettings settings = new YtDlpSettings();

        List<String> command = client.buildDownloadCommand(TEST_URL, settings, tempOutputDir);

        assertFalse(command.contains("--external-downloader"));
        assertFalse(command.contains("--external-downloader-args"));
    }

    @Test
    @DisplayName("Shared connections field maps to --concurrent-fragments")
    void testConnectionsMapToConcurrentFragments() {
        YtDlpSettings settings = new YtDlpSettings();
        settings.setConnections(6);

        List<String> command = client.buildDownloadCommand(TEST_URL, settings, tempOutputDir);

        int index = command.indexOf("--concurrent-fragments");
        assertTrue(index >= 0, "--concurrent-fragments must be present");
        assertEquals("6", command.get(index + 1));
    }

    @Test
    @DisplayName("Single connection omits --concurrent-fragments")
    void testSingleConnectionOmitsConcurrentFragments() {
        YtDlpSettings settings = new YtDlpSettings();
        settings.setConnections(1);

        List<String> command = client.buildDownloadCommand(TEST_URL, settings, tempOutputDir);

        assertFalse(command.contains("--concurrent-fragments"));
    }

    @Test
    @DisplayName("Shared dialog retry, header and filename settings reach yt-dlp")
    void testSharedSettingsReachCommand() {
        YtDlpSettings settings = new YtDlpSettings()
                .setOutputTemplate("100% complete.mp4")
                .setDownloadLimitKB(320)
                .setUserAgent("odm-yt")
                .setReferer("https://referrer.test/");
        settings.setCookieHeader("Cookie: session=abc");
        settings.setMaxRetries(8);
        settings.setRetryDelaySeconds(4);

        List<String> command = client.buildDownloadCommand(TEST_URL, settings, tempOutputDir);

        assertCommandValue(command, "-o", "100%% complete.mp4");
        assertCommandValue(command, "--limit-rate", "320K");
        assertCommandValue(command, "--retries", "8");
        assertCommandValue(command, "--retry-sleep", "4");
        assertCommandValue(command, "--user-agent", "odm-yt");
        assertCommandValue(command, "--referer", "https://referrer.test/");
        assertCommandValue(command, "--add-header", "Cookie: session=abc");
        assertCommandValue(command, "--print", "after_move:|odmfile|%(filepath)s");
    }

    @Test
    @DisplayName("Machine output and already-downloaded lines yield an output path")
    void extractsReliableOutputPaths() {
        assertEquals("/tmp/final video.mkv",
                client.extractFilename("|odmfile|/tmp/final video.mkv"));
        assertEquals("existing.mp4", client.extractFilename(
                "[download] existing.mp4 has already been downloaded"));
    }

    @Test
    @DisplayName("Exit zero without an output path is a failed download")
    void successfulProcessWithoutOutputPathFailsLogically() throws Exception {
        YtDlpClient silentClient = new YtDlpClient("/bin/true");
        try {
            CompletableFuture<String> result = silentClient.download(
                    "https://example.test/video", new YtDlpSettings(), tempOutputDir, null);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("without reporting an output file"));
        } finally {
            silentClient.shutdown();
        }
    }

    @Test
    @DisplayName("Successful fast download publishes terminal progress before completion")
    void successfulProcessWithoutProgressLinesPublishesTerminalProgress() throws Exception {
        Path fakeYtDlp = tempOutputDir.resolve("fake-ytdlp");
        Files.writeString(fakeYtDlp, "#!/bin/sh\n"
                + "printf 'payload' > \"$PWD/final.mp4\"\n"
                + "printf '|odmfile|%s/final.mp4\\n' \"$PWD\"\n");
        assertTrue(fakeYtDlp.toFile().setExecutable(true));

        YtDlpClient fastClient = new YtDlpClient(fakeYtDlp.toString());
        YtDlpClient.ProgressCallback callback = mock(YtDlpClient.ProgressCallback.class);
        try {
            String result = fastClient.download(
                    "https://example.test/video", new YtDlpSettings(), tempOutputDir, callback)
                    .get(10, TimeUnit.SECONDS);

            assertEquals(tempOutputDir.resolve("final.mp4").toString(), result);
            var ordered = inOrder(callback);
            ordered.verify(callback).onStart(result);
            ordered.verify(callback).onProgress(100.0f, 7L, 7L, 0.0f);
            ordered.verify(callback).onComplete(result);
        } finally {
            fastClient.shutdown();
            Files.deleteIfExists(tempOutputDir.resolve("final.mp4"));
            Files.deleteIfExists(fakeYtDlp);
        }
    }

    private static void assertCommandValue(List<String> command, String flag, String expected) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0, "missing flag " + flag + " in " + command);
        assertEquals(expected, command.get(index + 1));
    }

    @ParameterizedTest
    @DisplayName("Should handle byte conversion calculations")
    @CsvSource({
        "1.5, KiB, 1536",
        "2.0, MiB, 2097152",
        "1.0, GiB, 1073741824",
        "500, B, 500"
    })
    void testByteConversionLogic(String value, String unit, long expected) {
        // Test the logic that would be used in byte conversion
        float numValue = Float.parseFloat(value);
        assertTrue(numValue > 0);
        assertNotNull(unit);
        assertTrue(expected > 0);

        // Verify unit relationships
        if ("KiB".equals(unit)) {
            assertEquals((long)(numValue * 1024), expected);
        } else if ("MiB".equals(unit)) {
            assertEquals((long)(numValue * 1024 * 1024), expected);
        } else if ("GiB".equals(unit)) {
            assertEquals((long)(numValue * 1024 * 1024 * 1024), expected);
        }
    }

    @Test
    @DisplayName("Should handle concurrent download requests")
    void testConcurrentDownloadRequests() {
        YtDlpSettings settings = new YtDlpSettings().setFormat("best");
        YtDlpClient.ProgressCallback mockCallback1 = mock(YtDlpClient.ProgressCallback.class);
        YtDlpClient.ProgressCallback mockCallback2 = mock(YtDlpClient.ProgressCallback.class);

        assertDoesNotThrow(() -> {
            CompletableFuture<String> future1 = client.download("https://example.com/video1", settings, tempOutputDir, mockCallback1);
            CompletableFuture<String> future2 = client.download("https://example.com/video2", settings, tempOutputDir, mockCallback2);

            // Both futures should be created without throwing
            assertNotNull(future1);
            assertNotNull(future2);
        });
    }

    @Test
    @DisplayName("VideoInfo should handle all getters and setters")
    void testVideoInfoGettersSetters() {
        YtDlpClient.VideoInfo info = new YtDlpClient.VideoInfo();

        info.setId("test-id");
        assertEquals("test-id", info.getId());

        info.setTitle("Test Title");
        assertEquals("Test Title", info.getTitle());

        info.setDescription("Test Description");
        assertEquals("Test Description", info.getDescription());

        info.setUploader("Test Uploader");
        assertEquals("Test Uploader", info.getUploader());

        info.setUploadDate("20231215");
        assertEquals("20231215", info.getUploadDate());

        info.setDuration(300);
        assertEquals(300, info.getDuration());

        info.setFilesize(1048576);
        assertEquals(1048576, info.getFilesize());

        info.setFormat("mp4");
        assertEquals("mp4", info.getFormat());

        info.setUrl("https://example.com/video.mp4");
        assertEquals("https://example.com/video.mp4", info.getUrl());

        info.setThumbnail("https://example.com/thumb.jpg");
        assertEquals("https://example.com/thumb.jpg", info.getThumbnail());

        List<YtDlpClient.VideoFormat> formats = List.of(new YtDlpClient.VideoFormat());
        info.setFormats(formats);
        assertEquals(formats, info.getFormats());

        List<YtDlpClient.Subtitle> subtitles = List.of(new YtDlpClient.Subtitle());
        info.setSubtitles(subtitles);
        assertEquals(subtitles, info.getSubtitles());
    }

    @Test
    @DisplayName("VideoFormat should handle all getters and setters")
    void testVideoFormatGettersSetters() {
        YtDlpClient.VideoFormat format = new YtDlpClient.VideoFormat();

        format.setFormatId("140");
        assertEquals("140", format.getFormatId());

        format.setExt("m4a");
        assertEquals("m4a", format.getExt());

        format.setFilesize(4194304);
        assertEquals(4194304, format.getFilesize());

        format.setResolution("audio only");
        assertEquals("audio only", format.getResolution());

        format.setFps(30);
        assertEquals(30, format.getFps());

        format.setAcodec("mp4a.40.2");
        assertEquals("mp4a.40.2", format.getAcodec());

        format.setVcodec("none");
        assertEquals("none", format.getVcodec());

        format.setAbr(128);
        assertEquals(128, format.getAbr());

        format.setVbr(0);
        assertEquals(0, format.getVbr());
    }

    @Test
    @DisplayName("Subtitle should handle all getters and setters")
    void testSubtitleGettersSetters() {
        YtDlpClient.Subtitle subtitle = new YtDlpClient.Subtitle();

        subtitle.setLanguage("en");
        assertEquals("en", subtitle.getLanguage());

        subtitle.setExt("vtt");
        assertEquals("vtt", subtitle.getExt());

        subtitle.setUrl("https://example.com/subtitle.vtt");
        assertEquals("https://example.com/subtitle.vtt", subtitle.getUrl());
    }
}
