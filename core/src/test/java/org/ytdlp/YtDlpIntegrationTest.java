package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
 * Integration tests for YtDlp package focusing on component interactions
 * and real process management without full download workflows.
 * Tests the integration between settings, factory, client, and tasks
 * while using real yt-dlp binary for command validation.
 */
@DisplayName("YtDlp Integration Tests")
class YtDlpIntegrationTest {

    private static final String TEST_INFO_URL = "https://www.youtube.com/watch?v=nulrKPsBTi4";
    private static final int TEST_TIMEOUT_SECONDS = 60;

    @TempDir
    Path tempDir;

    private YtDlpClient client;
    private YtDlpFactory factory;
    private GlobalSettings globalSettings;
    private Path downloadDir;
    private YtDlpLocalMediaServer mediaServer;

    @BeforeAll
    static void checkYtDlpAvailability() {
        assumeTrue(isYtDlpAvailable(), "yt-dlp is not available on this system");
    }

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Setup real global settings
        globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        globalSettings.setYtDlpPath("yt-dlp");

        // Hermetic media source: local HTTP server exercising the real
        // yt-dlp generic extractor without platform access
        mediaServer = YtDlpLocalMediaServer.start();

        // Initialize components with real dependencies
        YtDlpFactory.clearInstance();
        factory = YtDlpFactory.getInstance(globalSettings);
        client = factory.createClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.shutdown();
        }
        if (factory != null) {
            factory.shutdown();
        }
        YtDlpFactory.clearInstance();
        if (mediaServer != null) {
            mediaServer.close();
        }
    }

    @Test
    @DisplayName("Should integrate factory and client creation")
    @EnabledIf("isYtDlpAvailable")
    void shouldIntegrateFactoryAndClientCreation() {
        assertNotNull(factory);
        assertNotNull(client);
        assertSame(globalSettings, factory.getGlobalSettings());
        assertTrue(client.isAvailable());
    }

    @Test
    @DisplayName("Should integrate settings with real command building")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldIntegrateSettingsWithRealCommandBuilding() throws Exception {
        // Create comprehensive settings - test that they can be created
        factory.createDefaultSettings()
                .setFormat("best[height<=480]/best")
                .setEmbedThumbnail(true)
                .setEmbedMetadata(true)
                .setWriteSubtitles(true)
                .addSubtitleLanguage("en")
                .setFragmentRetries(3)
                .setNoPlaylist(true);

        // Test command building by attempting info extraction. Command
        // building and JSON parsing are platform-independent: served locally
        CompletableFuture<YtDlpClient.VideoInfo> future = client
                .extractInfo(mediaServer.mediaUrl());
        YtDlpClient.VideoInfo info = future.get();

        assertNotNull(info);
        assertNotNull(info.getTitle());
        assertNotNull(info.getId());
    }

    @Test
    @DisplayName("Should integrate audio settings with real validation")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldIntegrateAudioSettingsWithRealValidation() throws Exception {
        // Requires the real platform (asserts a real duration); skip only
        // on the external bot block
        YtDlpBotBlock.assumeNotBotBlocked(TEST_INFO_URL);

        // Create audio settings - test that they can be created
        factory.createAudioSettings()
                .setAudioFormat("mp3")
                .setAudioQuality("128")
                .setEmbedMetadata(true);

        // Validate settings work with real yt-dlp by extracting info
        CompletableFuture<YtDlpClient.VideoInfo> future = client.extractInfo(TEST_INFO_URL);
        YtDlpClient.VideoInfo info = future.get();

        assertNotNull(info);
        // Audio settings should not prevent info extraction
        assertTrue(info.getDuration() > 0);
    }

    @Test
    @DisplayName("Should integrate high quality settings with format listing")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldIntegrateHighQualitySettingsWithFormatListing() throws Exception {
        // Create high quality settings - test that they can be created
        factory.createHighQualityVideoSettings()
                .setFormat("best[height<=720]/best");

        // Test settings integration by listing available formats. Format
        // listing is platform-independent: served locally
        CompletableFuture<List<YtDlpClient.VideoFormat>> future = client.listFormats(mediaServer.mediaUrl());
        List<YtDlpClient.VideoFormat> formats = future.get();

        assertNotNull(formats);
        assertFalse(formats.isEmpty());

        // Verify format data structure
        YtDlpClient.VideoFormat firstFormat = formats.get(0);
        assertNotNull(firstFormat.getFormatId());
        assertNotNull(firstFormat.getExt());
    }

    @Test
    @DisplayName("Should integrate aria2c settings with availability check")
    @EnabledIf("isYtDlpAvailable")
    void shouldIntegrateAria2cSettingsWithAvailabilityCheck() {
        boolean aria2cAvailable = client.isAria2cAvailable();

        if (aria2cAvailable) {
            YtDlpSettings aria2cSettings = factory.createAria2cSettings()
                    .setAria2cConnections(8)
                    .setAria2cSplitConnections(4)
                    .setAria2cMinSplitSize("1M");

            // Verify aria2c arguments are built correctly
            String aria2cArgs = aria2cSettings.buildAria2cArgs();
            assertNotNull(aria2cArgs);
            assertTrue(aria2cArgs.contains("-x 8"));
            assertTrue(aria2cArgs.contains("-s 4"));
            assertTrue(aria2cArgs.contains("-k 1M"));

            // Verify aria2c version can be retrieved
            String version = client.getAria2cVersion();
            if (version != null) {
                assertFalse(version.isEmpty());
            }
        } else {
            // If aria2c not available, settings should still work
            YtDlpSettings settings = factory.createDefaultSettings();
            assertNotNull(settings);
            assertFalse(settings.isUseAria2c());
        }
    }

    @Test
    @DisplayName("Should integrate download task with real client")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldIntegrateDownloadTaskWithRealClient() throws Exception {
        YtDlpSettings settings = new YtDlpSettings()
                .setFormat("worst") // Fast for testing
                .setNoPlaylist(true);

        YtDlpDownloadTask task = factory.createDownloadTask(
                "integration-test-task",
                TEST_INFO_URL,
                settings,
                downloadDir);

        // Verify initial state
        assertEquals("integration-test-task", task.getTaskId());
        assertEquals(TEST_INFO_URL, task.getUrl());
        assertEquals(YtDlpDownloadTask.Status.PENDING, task.getStatus());
        assertFalse(task.isActive());
        assertFalse(task.isDone());

        // Test task management without full download
        assertNotNull(task.getSettings());
        assertEquals(downloadDir, task.getOutputPath());
        assertNotNull(task.getCreatedAt());

        // Cleanup
        factory.removeDownloadTask("integration-test-task");
    }

    @Test
    @DisplayName("Should integrate factory task management")
    @EnabledIf("isYtDlpAvailable")
    void shouldIntegrateFactoryTaskManagement() {
        // Create multiple tasks
        YtDlpDownloadTask task1 = factory.createDownloadTask(
                "task-1", TEST_INFO_URL, factory.createDefaultSettings(), downloadDir);
        YtDlpDownloadTask task2 = factory.createDownloadTask(
                "task-2", TEST_INFO_URL, factory.createAudioSettings(), downloadDir);

        // Verify task retrieval
        assertSame(task1, factory.getDownloadTask("task-1"));
        assertSame(task2, factory.getDownloadTask("task-2"));

        // Test task removal
        factory.removeDownloadTask("task-1");
        assertNotNull(factory.getDownloadTask("task-2"));

        // Cleanup
        factory.removeDownloadTask("task-2");
    }

    @Test
    @DisplayName("Should integrate error handling across components")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldIntegrateErrorHandlingAcrossComponents() {
        String invalidUrl = "https://example.com/nonexistent-video-12345";

        // Test error propagation from client through factory
        YtDlpDownloadTask task = factory.createDownloadTask(
                "error-test-task",
                invalidUrl,
                factory.createDefaultSettings(),
                downloadDir);

        // Client should handle invalid URL properly
        CompletableFuture<YtDlpClient.VideoInfo> infoFuture = client.extractInfo(invalidUrl);

        assertThrows(Exception.class, () -> {
            infoFuture.get(10, TimeUnit.SECONDS);
        });

        // Task should still be manageable despite invalid URL
        assertEquals(YtDlpDownloadTask.Status.PENDING, task.getStatus());
        assertFalse(task.isDone());

        factory.removeDownloadTask("error-test-task");
    }

    @Test
    @DisplayName("Should integrate proxy settings with real command validation")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void shouldIntegrateProxySettingsWithRealCommandValidation() throws Exception {
        // Configure proxy settings (won't actually use proxy for test)
        globalSettings.setGlobalProxyEnabled(true);
        globalSettings.setGlobalProxyAddress("http://proxy.example.com:8080");

        YtDlpSettings proxySettings = factory.createDefaultSettings();
        if (proxySettings.getProxyAddress() != null) {
            // Test proxy integration if available
            assertNotNull(proxySettings.getProxyAddress());
        }

        // Info extraction should work against the hermetic media source
        // (the fake proxy is never applied to the extract-info command)
        CompletableFuture<YtDlpClient.VideoInfo> future = client.extractInfo(mediaServer.mediaUrl());
        YtDlpClient.VideoInfo info = future.get();

        assertNotNull(info);
    }

    @Test
    @DisplayName("Should integrate concurrent client operations")
    @EnabledIf("isYtDlpAvailable")
    @Timeout(value = TEST_TIMEOUT_SECONDS * 2, unit = TimeUnit.SECONDS)
    void shouldIntegrateConcurrentClientOperations() throws Exception {
        // Concurrent operations against the hermetic media source: id/title
        // consistency and format listing are platform-independent
        CompletableFuture<YtDlpClient.VideoInfo> future1 = client.extractInfo(mediaServer.mediaUrl());
        CompletableFuture<YtDlpClient.VideoInfo> future2 = client.extractInfo(mediaServer.mediaUrl());
        CompletableFuture<List<YtDlpClient.VideoFormat>> formatsFuture = client.listFormats(mediaServer.mediaUrl());

        // All should complete successfully
        YtDlpClient.VideoInfo info1 = future1.get();
        YtDlpClient.VideoInfo info2 = future2.get();
        List<YtDlpClient.VideoFormat> formats = formatsFuture.get();

        assertNotNull(info1);
        assertNotNull(info2);
        assertNotNull(formats);
        assertFalse(formats.isEmpty());

        // Info should be consistent
        assertEquals(info1.getId(), info2.getId());
        assertEquals(info1.getTitle(), info2.getTitle());
    }

    @Test
    @DisplayName("Should integrate settings copying and independence")
    @EnabledIf("isYtDlpAvailable")
    void shouldIntegrateSettingsCopyingAndIndependence() {
        YtDlpSettings original = factory.createHighQualityVideoSettings()
                .setFormat("best[height<=1080]")
                .setEmbedThumbnail(true)
                .setFragmentRetries(5);

        YtDlpSettings copy = (YtDlpSettings) original.copy();

        // Verify copy is independent
        assertNotSame(original, copy);
        assertEquals(original.getFormat(), copy.getFormat());
        assertEquals(original.isEmbedThumbnail(), copy.isEmbedThumbnail());
        assertEquals(original.getFragmentRetries(), copy.getFragmentRetries());

        // Modify copy and verify original unchanged
        copy.setFormat("worst").setEmbedThumbnail(false);

        assertNotEquals(original.getFormat(), copy.getFormat());
        assertNotEquals(original.isEmbedThumbnail(), copy.isEmbedThumbnail());
    }

    @Test
    @DisplayName("Should integrate resource cleanup")
    @EnabledIf("isYtDlpAvailable")
    void shouldIntegrateResourceCleanup() {
        // Create client and tasks
        YtDlpClient testClient = factory.createClient();
        YtDlpDownloadTask task = factory.createDownloadTask(
                "cleanup-test", TEST_INFO_URL, factory.createDefaultSettings(), downloadDir);

        assertTrue(testClient.isAvailable());
        assertNotNull(factory.getDownloadTask("cleanup-test"));

        // Test individual cleanup
        testClient.shutdown();
        factory.removeDownloadTask("cleanup-test");

        assertNull(factory.getDownloadTask("cleanup-test"));

        // Factory shutdown should handle remaining resources
        assertDoesNotThrow(() -> factory.shutdown());
    }

    // Helper methods

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
}
