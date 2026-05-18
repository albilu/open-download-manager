package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

/**
 * End-to-end tests for YtDlp package using real yt-dlp binary.
 * These tests validate complete workflows with actual yt-dlp process execution,
 * avoiding mocks on critical components as per project requirements.
 */
@DisplayName("YtDlp End-to-End Tests")
class YtDlpE2ETest {

    private static final String TEST_VIDEO_URL = "https://www.youtube.com/watch?v=nulrKPsBTi4";
    private static final String TEST_AUDIO_URL = "https://www.youtube.com/watch?v=nulrKPsBTi4";
    // private static final String TEST_PLAYLIST_URL =
    // "https://archive.org/details/test_playlist";
    private static final int TEST_TIMEOUT_SECONDS = 120;

    @TempDir
    Path tempDir;

    private YtDlpClient client;
    private YtDlpFactory factory;
    private Path downloadDir;

    @BeforeAll
    static void checkYtDlpAvailability() {
        // Skip all tests if yt-dlp is not available
        assumeTrue(isYtDlpAvailable(), "yt-dlp is not available on this system");
    }

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Create real client (no mocks)
        client = new YtDlpClient();

        // Initialize factory with real global settings
        YtDlpFactory.clearInstance();
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        factory = YtDlpFactory.getInstance(globalSettings);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        if (factory != null) {
            factory.shutdown();
        }
        YtDlpFactory.clearInstance();
    }

    @Test
    @DisplayName("Should check yt-dlp availability with real binary")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldCheckYtDlpAvailability() {
        assertTrue(client.isAvailable());
    }

    @Test
    @DisplayName("Should get real yt-dlp version")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldGetRealYtDlpVersion() {
        String version = client.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        // Version should contain some numeric component
        assertTrue(version.matches(".*\\d+.*"));
    }

    @Test
    @DisplayName("Should check aria2c availability")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldCheckAria2cAvailability() {
        // Test both default path and custom path
        boolean defaultAvailable = client.isAria2cAvailable();
        boolean customAvailable = client.isAria2cAvailable("aria2c");

        // At least one should work or both should be false (if aria2c not installed)
        // This is environment dependent, so we just verify the method works
        assertNotNull(defaultAvailable);
        assertNotNull(customAvailable);
    }

    @Test
    @DisplayName("Should extract real video information")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldExtractRealVideoInformation() throws Exception {
        CompletableFuture<YtDlpClient.VideoInfo> future = client.extractInfo(TEST_VIDEO_URL);
        YtDlpClient.VideoInfo info = future.get();

        assertNotNull(info);
        assertNotNull(info.getId());
        assertNotNull(info.getTitle());
        assertFalse(info.getTitle().isEmpty());
        assertTrue(info.getDuration() > 0);
        assertNotNull(info.getFormat());
    }

    @Test
    @DisplayName("Should list real video formats")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldListRealVideoFormats() throws Exception {
        CompletableFuture<List<YtDlpClient.VideoFormat>> future = client.listFormats(TEST_VIDEO_URL);
        List<YtDlpClient.VideoFormat> formats = future.get();

        assertNotNull(formats);
        assertFalse(formats.isEmpty());

        // Verify format properties
        YtDlpClient.VideoFormat firstFormat = formats.get(0);
        assertNotNull(firstFormat.getFormatId());
        assertNotNull(firstFormat.getExt());
    }

    @Test
    @DisplayName("Should download small video with real yt-dlp")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldDownloadSmallVideoWithRealYtDlp() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("worst") // Use worst quality for faster testing
                .setNoPlaylist(true);

        TestProgressCallback callback = new TestProgressCallback();

        CompletableFuture<String> future = client.download(TEST_VIDEO_URL, settings, downloadDir, callback);
        String result = future.get();

        assertNotNull(result);
        assertTrue(callback.progressCalled.get());
        assertTrue(callback.completionCalled.get());
        assertFalse(callback.errorCalled.get());

        // Verify file was actually downloaded
        assertTrue(Files.list(downloadDir).findAny().isPresent());
    }

    @Test
    @DisplayName("Should extract audio with real yt-dlp")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldExtractAudioWithRealYtDlp() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setExtractAudio(true)
                .setAudioFormat("mp3")
                .setAudioQuality("96"); // Low quality for faster testing

        TestProgressCallback callback = new TestProgressCallback();

        CompletableFuture<String> future = client.download(TEST_AUDIO_URL, settings, downloadDir, callback);
        String result = future.get();

        assertNotNull(result);
        assertTrue(callback.completionCalled.get());

        // Verify audio file was created
        assertTrue(Files.list(downloadDir)
                .anyMatch(p -> p.toString().endsWith(".mp3")));
    }

    @Test
    @DisplayName("Should handle real download cancellation")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldHandleRealDownloadCancellation() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("best"); // Use best quality to ensure longer download

        TestProgressCallback callback = new TestProgressCallback();

        CompletableFuture<String> future = client.download(TEST_VIDEO_URL, settings, downloadDir, callback);

        // Wait for download to start
        Thread.sleep(3000);

        // Cancel the download - this should work with real process
        String processId = getActiveProcessId();
        if (processId != null) {
            boolean cancelled = client.cancelDownload(processId);
            assertTrue(cancelled);
        }

        // The future should complete with an exception or cancellation
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected - download was cancelled
            assertTrue(e.getCause() instanceof RuntimeException ||
                    e instanceof java.util.concurrent.CancellationException);
        }
    }

    @Test
    @DisplayName("Should handle invalid URL with real yt-dlp")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldHandleInvalidUrlWithRealYtDlp() {
        String invalidUrl = "https://example.com/nonexistent-video";

        CompletableFuture<YtDlpClient.VideoInfo> future = client.extractInfo(invalidUrl);

        assertThrows(Exception.class, () -> {
            future.get();
        });
    }

    @Test
    @DisplayName("Should work with YtDlpDownloadTask and real process")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldWorkWithDownloadTaskAndRealProcess() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("worst");

        YtDlpDownloadTask task = factory.createDownloadTask(
                "test-task-real",
                TEST_VIDEO_URL,
                settings,
                downloadDir);

        assertEquals(YtDlpDownloadTask.Status.PENDING, task.getStatus());

        CompletableFuture<String> future = task.start();

        // Wait for task to start
        Thread.sleep(1000);
        assertTrue(task.isActive());

        String result = future.get();
        assertNotNull(result);
        assertTrue(task.isDone());
        assertEquals(YtDlpDownloadTask.Status.COMPLETED, task.getStatus());

        factory.removeDownloadTask("test-task-real");
    }

    @Test
    @DisplayName("Should handle concurrent real downloads")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
    void shouldHandleConcurrentRealDownloads() throws Exception {
        // Create separate download directories to avoid filename conflicts
        // when downloading the same video concurrently
        Path downloadDir1 = downloadDir.resolve("download1");
        Path downloadDir2 = downloadDir.resolve("download2");
        Files.createDirectories(downloadDir1);
        Files.createDirectories(downloadDir2);

        YtDlpSettings settings1 = new YtDlpSettings()
                .setFormat("worst");

        YtDlpSettings settings2 = new YtDlpSettings()
                .setFormat("worst");

        TestProgressCallback callback1 = new TestProgressCallback();
        TestProgressCallback callback2 = new TestProgressCallback();

        CompletableFuture<String> future1 = client.download(TEST_VIDEO_URL, settings1, downloadDir1, callback1);
        CompletableFuture<String> future2 = client.download(TEST_AUDIO_URL, settings2, downloadDir2, callback2);

        String result1 = future1.get();
        String result2 = future2.get();

        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(callback1.completionCalled.get());
        assertTrue(callback2.completionCalled.get());

        // Verify files were downloaded in each directory
        assertTrue(Files.list(downloadDir1).findAny().isPresent(), "File should be downloaded in downloadDir1");
        assertTrue(Files.list(downloadDir2).findAny().isPresent(), "File should be downloaded in downloadDir2");
    }

    @Test
    @DisplayName("Should work with aria2c external downloader if available")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldWorkWithAria2cExternalDownloader() throws Exception {
        // Only run if aria2c is available
        assumeTrue(client.isAria2cAvailable(), "aria2c not available");

        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("worst")
                .setUseAria2c(true)
                .setAria2cConnections(4)
                .setAria2cSplitConnections(2);

        TestProgressCallback callback = new TestProgressCallback();

        CompletableFuture<String> future = client.download(TEST_VIDEO_URL, settings, downloadDir, callback);
        String result = future.get();

        assertNotNull(result);
        assertTrue(callback.completionCalled.get());

        // Verify file was downloaded
        assertTrue(Files.list(downloadDir).findAny().isPresent());
    }

    @Test
    @DisplayName("Should handle real yt-dlp error scenarios")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldHandleRealYtDlpErrorScenarios() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("nonexistent-format-12345");

        TestProgressCallback callback = new TestProgressCallback();

        CompletableFuture<String> future = client.download(TEST_VIDEO_URL, settings, downloadDir, callback);

        assertThrows(Exception.class, () -> {
            future.get();
        });

        assertTrue(callback.errorCalled.get());
    }

    // Helper methods and classes

    static boolean isYtDlpAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getActiveProcessId() {
        try {
            java.lang.reflect.Field activeProcessesField = YtDlpClient.class.getDeclaredField("activeProcesses");
            activeProcessesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Process> activeProcesses = (java.util.Map<String, Process>) activeProcessesField
                    .get(client);
            return activeProcesses.keySet().stream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static class TestProgressCallback implements YtDlpClient.ProgressCallback {
        private final AtomicBoolean progressCalled = new AtomicBoolean(false);
        private final AtomicBoolean completionCalled = new AtomicBoolean(false);
        private final AtomicBoolean errorCalled = new AtomicBoolean(false);
        private final AtomicReference<String> lastError = new AtomicReference<>();
        private final AtomicInteger progressCount = new AtomicInteger(0);

        @Override
        public void onStart(String filename) {
            // Implementation for start callback
        }

        @Override
        public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
            progressCalled.set(true);
            progressCount.incrementAndGet();
        }

        @Override
        public void onComplete(String filename) {
            completionCalled.set(true);
        }

        @Override
        public void onError(String error) {
            errorCalled.set(true);
            lastError.set(error);
        }
    }
}
