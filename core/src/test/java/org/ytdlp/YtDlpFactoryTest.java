package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Comprehensive unit tests for YtDlpFactory class.
 * Tests factory methods, singleton behavior, settings creation, and resource
 * management.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("YtDlpFactory Unit Tests")
class YtDlpFactoryTest {

    @Mock
    private GlobalSettings mockGlobalSettings;

    @Mock
    private org.manager.tools.ToolManagerFactory mockToolManagerFactory;

    private YtDlpFactory factory;
    private Path tempDownloadDir;
    private AutoCloseable mockCloseable;

    @BeforeEach
    void setUp() throws Exception {
        mockCloseable = MockitoAnnotations.openMocks(this);

        // Create temporary directory for testing
        tempDownloadDir = Files.createTempDirectory("ytdlp-factory-test");

        // Configure mock global settings
        when(mockGlobalSettings.getYtDlpPath()).thenReturn("yt-dlp");
        when(mockGlobalSettings.isGlobalProxyEnabled()).thenReturn(false);
        when(mockGlobalSettings.getGlobalSpeedLimit()).thenReturn(0);
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(tempDownloadDir);
        when(mockGlobalSettings.getProperty(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(mockGlobalSettings.getIntProperty(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(mockGlobalSettings.getBooleanProperty(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Clear any existing singleton instance
        YtDlpFactory.clearInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.shutdown();
        }

        YtDlpFactory.clearInstance();

        // Clean up temporary directory
        if (tempDownloadDir != null && Files.exists(tempDownloadDir)) {
            Files.deleteIfExists(tempDownloadDir);
        }

        if (mockCloseable != null) {
            mockCloseable.close();
        }
    }

    @Test
    @DisplayName("Should create factory instance with global settings")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateInstanceWithSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        assertNotNull(factory);
        assertSame(mockGlobalSettings, factory.getGlobalSettings());
    }

    @Test
    @DisplayName("Should create factory instance with default settings")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateInstanceWithDefaults() {
        factory = YtDlpFactory.getInstance();

        assertNotNull(factory);
        assertNotNull(factory.getGlobalSettings());
    }

    @Test
    @DisplayName("Should maintain singleton behavior")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSingletonBehavior() {
        YtDlpFactory instance1 = YtDlpFactory.getInstance(mockGlobalSettings);
        YtDlpFactory instance2 = YtDlpFactory.getInstance(mockGlobalSettings);
        YtDlpFactory instance3 = YtDlpFactory.getInstance(); // Different method

        assertSame(instance1, instance2);
        assertSame(instance1, instance3);

        // Cleanup
        instance1.shutdown();
    }

    @Test
    @DisplayName("Should create YtDlpClient with embedded path")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateClientDefault() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpClient client = factory.createClient();

        assertNotNull(client);
        verify(mockGlobalSettings, times(0)).getYtDlpPath();
    }

    @Test
    @DisplayName("Should create YtDlpClient with custom path")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateClientCustomPath() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        String customPath = "/custom/path/to/yt-dlp";

        YtDlpClient client = factory.createClient(customPath);

        assertNotNull(client);
        // Should not call getYtDlpPath() when custom path is provided
        verify(mockGlobalSettings, never()).getYtDlpPath();
    }

    @Test
    @DisplayName("Created clients receive both external configuration policies")
    void testCreateClientConfigurationPolicies() {
        when(mockGlobalSettings.isHonorExternalYtDlpConfiguration()).thenReturn(true);
        when(mockGlobalSettings.isHonorExternalAria2Configuration()).thenReturn(true);
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpClient client = factory.createClient("yt-dlp");

        assertTrue(client.isHonoringExternalConfiguration());
        assertTrue(client.isHonoringExternalAria2Configuration());
    }

    @Test
    @DisplayName("Should fall back to system yt-dlp when no tool manager exists")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateClientNullPath() {
        when(mockToolManagerFactory.getYtDlpManager()).thenReturn(null);
        factory = YtDlpFactory.getInstance(mockGlobalSettings, mockToolManagerFactory);

        YtDlpClient client = factory.createClient();

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should fall back to system yt-dlp when the manager path is blank")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateClientEmptyPath() {
        org.ytdlp.YtDlpToolManager blankManager = org.mockito.Mockito.mock(org.ytdlp.YtDlpToolManager.class);
        when(blankManager.getToolPath()).thenReturn("");
        when(mockToolManagerFactory.getYtDlpManager()).thenReturn(blankManager);
        factory = YtDlpFactory.getInstance(mockGlobalSettings, mockToolManagerFactory);

        YtDlpClient client = factory.createClient();

        assertNotNull(client);
    }

    @Test
    @DisplayName("Should throw exception when factory is shut down")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateClientAfterShutdown() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        factory.shutdown();

        assertThrows(IllegalStateException.class, () -> factory.createClient());
        assertThrows(IllegalStateException.class, () -> factory.createClient("/custom/path"));
    }

    @Test
    @DisplayName("Should create default settings correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateDefaultSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createDefaultSettings();

        assertNotNull(settings);
        assertEquals("", settings.getFormat());
        assertFalse(settings.isEmbedThumbnail());
        assertFalse(settings.isEmbedMetadata());
        assertEquals(org.manager.download.DownloadSettingsFactory.DEFAULT_NETWORK_MAX_RETRIES,
                settings.getFragmentRetries());
        assertEquals(org.manager.download.DownloadSettingsFactory.DEFAULT_NETWORK_MAX_CONNECTIONS,
                settings.getMaxConnections());
        assertEquals(org.manager.download.DownloadSettingsFactory.DEFAULT_NETWORK_MAX_RETRIES,
                settings.getMaxRetries());
        assertTrue(settings.isGeoBypass());
        assertFalse(settings.isIgnoreErrors());
        assertFalse(settings.isUseAria2c());
        assertTrue(settings.isSkipUnavailableFragments());
    }

    @Test
    @DisplayName("Should apply global proxy settings to default settings")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDefaultSettingsWithProxy() {
        when(mockGlobalSettings.isGlobalProxyEnabled()).thenReturn(true);
        when(mockGlobalSettings.getGlobalProxyAddress()).thenReturn("http://proxy:8080");
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createDefaultSettings();

        assertNotNull(settings);
        // Note: The actual proxy handling would need to be implemented in YtDlpSettings
        verify(mockGlobalSettings).isGlobalProxyEnabled();
        verify(mockGlobalSettings).getGlobalProxyAddress();
    }

    @Test
    @DisplayName("Should apply the Network download limit to default settings")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDefaultSettingsWithSpeedLimit() {
        when(mockGlobalSettings.getIntProperty("network.downloadLimitKb", 0)).thenReturn(1000);
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createDefaultSettings();

        assertNotNull(settings);
        assertTrue(settings.isLimitRate());
        assertEquals(1000, settings.getRateLimit());
        verify(mockGlobalSettings).getIntProperty("network.downloadLimitKb", 0);
    }

    @Test
    @DisplayName("Should create audio settings correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateAudioSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createAudioSettings();

        assertNotNull(settings);
        assertTrue(settings.isExtractAudio());
        assertEquals("mp3", settings.getAudioFormat());
        assertEquals("192", settings.getAudioQuality());
        assertEquals("bestaudio/best", settings.getFormat());
    }

    @Test
    @DisplayName("Should create high quality video settings correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateHighQualityVideoSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createHighQualityVideoSettings();

        assertNotNull(settings);
        assertEquals("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best", settings.getFormat());
        assertTrue(settings.isEmbedThumbnail());
        assertTrue(settings.isEmbedMetadata());
        assertTrue(settings.isWriteSubtitles());
        assertNotNull(settings.getSubtitleLanguages());
        assertTrue(settings.getSubtitleLanguages().contains("en"));
    }

    @Test
    @DisplayName("Should create playlist settings correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreatePlaylistSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createPlaylistSettings();

        assertNotNull(settings);
        assertFalse(settings.isNoPlaylist());
        assertTrue(settings.isIgnoreErrors());
        assertEquals("best[height<=720]/best", settings.getFormat());
    }

    @Test
    @DisplayName("Should create Aria2c settings correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateAria2cSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createAria2cSettings();

        assertNotNull(settings);
        assertTrue(settings.isUseAria2c());
        assertEquals(16, settings.getAria2cConnections());
        assertEquals(16, settings.getAria2cSplitConnections());
        assertEquals("1M", settings.getAria2cMinSplitSize());
        assertTrue(settings.isAria2cContinue());
        assertEquals(60, settings.getAria2cTimeout());
        assertEquals(10, settings.getAria2cRetryWait());
        assertEquals(5, settings.getAria2cMaxTries());
    }

    @ParameterizedTest
    @CsvSource({
            "32, 16, 2M",
            "8, 4, 512K",
            "64, 32, 10M"
    })
    @DisplayName("Should create custom Aria2c settings correctly")
    void testCreateCustomAria2cSettings(int maxConnections, int splitConnections, String minSplitSize) {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpSettings settings = factory.createAria2cSettings(maxConnections, splitConnections, minSplitSize);

        assertNotNull(settings);
        assertTrue(settings.isUseAria2c());
        assertEquals(maxConnections, settings.getAria2cConnections());
        assertEquals(splitConnections, settings.getAria2cSplitConnections());
        assertEquals(minSplitSize, settings.getAria2cMinSplitSize());
    }

    @Test
    @DisplayName("Should create download task with all parameters")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateDownloadTaskComplete() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        String taskId = "test-task-123";
        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
        YtDlpSettings settings = factory.createDefaultSettings();
        Path outputPath = tempDownloadDir.resolve("downloads");

        YtDlpDownloadTask task = factory.createDownloadTask(taskId, url, settings, outputPath);

        assertNotNull(task);
        assertEquals(taskId, task.getTaskId());
        assertEquals(url, task.getUrl());
        assertSame(settings, task.getSettings());
        assertEquals(outputPath, task.getOutputPath());
        assertEquals(1, factory.getActiveTaskCount());
        assertSame(task, factory.getDownloadTask(taskId));
    }

    @Test
    @DisplayName("Should create download task with minimal parameters")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateDownloadTaskMinimal() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        String taskId = "test-task-456";
        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        YtDlpDownloadTask task = factory.createDownloadTask(taskId, url);

        assertNotNull(task);
        assertEquals(taskId, task.getTaskId());
        assertEquals(url, task.getUrl());
        assertNotNull(task.getSettings());
        assertNotNull(task.getOutputPath());
        assertEquals(1, factory.getActiveTaskCount());
    }

    @Test
    @DisplayName("Should handle null settings in download task creation")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateDownloadTaskNullSettings() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        String taskId = "test-task-789";
        String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

        YtDlpDownloadTask task = factory.createDownloadTask(taskId, url, null, null);

        assertNotNull(task);
        assertNotNull(task.getSettings());
        assertNotNull(task.getOutputPath());
    }

    @Test
    @DisplayName("Should throw exception when creating task after shutdown")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCreateDownloadTaskAfterShutdown() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);
        factory.shutdown();

        assertThrows(IllegalStateException.class, () -> factory.createDownloadTask("task", "url", null, null));
        assertThrows(IllegalStateException.class, () -> factory.createDownloadTask("task", "url"));
    }

    @Test
    @DisplayName("Should manage download tasks correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDownloadTaskManagement() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        // Create multiple tasks
        YtDlpDownloadTask task1 = factory.createDownloadTask("task1", "url1");
        YtDlpDownloadTask task2 = factory.createDownloadTask("task2", "url2");
        YtDlpDownloadTask task3 = factory.createDownloadTask("task3", "url3");

        assertEquals(3, factory.getActiveTaskCount());
        assertSame(task1, factory.getDownloadTask("task1"));
        assertSame(task2, factory.getDownloadTask("task2"));
        assertSame(task3, factory.getDownloadTask("task3"));

        // Remove a task
        assertTrue(factory.removeDownloadTask("task2"));
        assertEquals(2, factory.getActiveTaskCount());
        assertNull(factory.getDownloadTask("task2"));

        // Try to remove non-existent task
        assertFalse(factory.removeDownloadTask("non-existent"));
        assertEquals(2, factory.getActiveTaskCount());
    }

    @Test
    @DisplayName("Should handle default output path correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDefaultOutputPath() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpDownloadTask task = factory.createDownloadTask("task", "url");
        Path outputPath = task.getOutputPath();

        assertNotNull(outputPath);
        assertTrue(outputPath.toString().contains("ytdlp"));
    }

    @Test
    @DisplayName("Should handle null default download directory")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testNullDefaultDownloadDirectory() {
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(null);
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpDownloadTask task = factory.createDownloadTask("task", "url");
        Path outputPath = task.getOutputPath();

        assertNotNull(outputPath);
        assertTrue(outputPath.toString().contains("Downloads"));
        assertTrue(outputPath.toString().contains("ytdlp"));
    }

    @Test
    @DisplayName("Should cancel all tasks correctly")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testCancelAllTasks() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        // Create multiple tasks
        factory.createDownloadTask("task1", "url1");
        factory.createDownloadTask("task2", "url2");
        factory.createDownloadTask("task3", "url3");

        assertEquals(3, factory.getActiveTaskCount());

        factory.cancelAllTasks();

        assertEquals(0, factory.getActiveTaskCount());
    }

    @Test
    @DisplayName("Should shutdown cleanly")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testShutdown() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        // Create some resources
        factory.createClient();
        factory.createDownloadTask("task", "url");

        assertDoesNotThrow(() -> factory.shutdown());

        // Should be able to call shutdown multiple times
        assertDoesNotThrow(() -> factory.shutdown());
    }

    @Test
    @DisplayName("Should clear singleton instance correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testClearInstance() {
        YtDlpFactory instance1 = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpFactory.clearInstance();

        YtDlpFactory instance2 = YtDlpFactory.getInstance(mockGlobalSettings);

        assertNotSame(instance1, instance2);
    }

    @Test
    @DisplayName("Should handle concurrent access correctly")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testConcurrentAccess() throws InterruptedException {
        final YtDlpFactory[] instances = new YtDlpFactory[10];
        final Thread[] threads = new Thread[10];

        // Create multiple threads trying to get instance simultaneously
        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                instances[index] = YtDlpFactory.getInstance(mockGlobalSettings);
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }

        // All instances should be the same
        for (int i = 1; i < instances.length; i++) {
            assertSame(instances[0], instances[i]);
        }

        // Cleanup
        instances[0].shutdown();
    }

    @ParameterizedTest
    @ValueSource(strings = { "task1", "task-with-dashes", "task_with_underscores", "task123" })
    @DisplayName("Should handle various task ID formats")
    void testVariousTaskIds(String taskId) {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        YtDlpDownloadTask task = factory.createDownloadTask(taskId, "https://example.com/video");

        assertNotNull(task);
        assertEquals(taskId, task.getTaskId());
        assertSame(task, factory.getDownloadTask(taskId));
    }

    @Test
    @DisplayName("Should handle settings inheritance correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSettingsInheritance() {
        when(mockGlobalSettings.isGlobalProxyEnabled()).thenReturn(true);
        when(mockGlobalSettings.getGlobalProxyAddress()).thenReturn("http://proxy:8080");
        when(mockGlobalSettings.getIntProperty("network.downloadLimitKb", 0)).thenReturn(2000);

        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        // All settings types should inherit the shared Network defaults.
        YtDlpSettings defaultSettings = factory.createDefaultSettings();
        YtDlpSettings audioSettings = factory.createAudioSettings();
        YtDlpSettings videoSettings = factory.createHighQualityVideoSettings();
        YtDlpSettings playlistSettings = factory.createPlaylistSettings();
        YtDlpSettings aria2cSettings = factory.createAria2cSettings();

        // All should inherit rate limiting
        assertTrue(defaultSettings.isLimitRate());
        assertTrue(audioSettings.isLimitRate());
        assertTrue(videoSettings.isLimitRate());
        assertTrue(playlistSettings.isLimitRate());
        assertTrue(aria2cSettings.isLimitRate());

        assertEquals(2000, defaultSettings.getRateLimit());
        assertEquals(2000, audioSettings.getRateLimit());
        assertEquals(2000, videoSettings.getRateLimit());
        assertEquals(2000, playlistSettings.getRateLimit());
        assertEquals(2000, aria2cSettings.getRateLimit());
    }

    @Test
    @DisplayName("Should track active tasks correctly under stress")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void testActiveTaskTracking() {
        factory = YtDlpFactory.getInstance(mockGlobalSettings);

        // Create many tasks
        for (int i = 0; i < 100; i++) {
            factory.createDownloadTask("task-" + i, "url-" + i);
        }

        assertEquals(100, factory.getActiveTaskCount());

        // Remove some tasks
        for (int i = 0; i < 50; i += 2) {
            assertTrue(factory.removeDownloadTask("task-" + i));
        }

        assertEquals(75, factory.getActiveTaskCount());

        // Cancel all remaining
        factory.cancelAllTasks();
        assertEquals(0, factory.getActiveTaskCount());
    }
}
