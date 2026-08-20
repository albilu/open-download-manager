package org.proxychains;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.ProxychainsDownloadHandler;
import org.mockito.Mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import org.tor.TorService;

/**
 * Unit tests for ProxychainsDownloadHandler class. Tests download management,
 * listener handling, options management, and lifecycle operations.
 */
@DisplayName("ProxychainsDownloadHandler Unit Tests")
class ProxychainsDownloadHandlerTest {

    private static final String TEST_URL = "https://example.com/test-file.zip";
    private static final String TEST_DOWNLOAD_ID = "test-download-123";
    private static final TorService torService = new TorService("tor");

    @Mock
    private GlobalSettings mockGlobalSettings;

    @Mock
    private DownloadSettingsFactory mockSettingsFactory;

    @Mock
    private Download mockDownload;

    @Mock
    private DownloadListener mockListener1;

    @Mock
    private DownloadListener mockListener2;

    @TempDir
    Path tempDir;

    private ProxychainsDownloadHandler handler;
    private ProxychainsClient client;
    private ExecutorService executorService;
    private AutoCloseable mocks;

    @BeforeAll
    static void startTorService() {
        assertTrue(torService.start().join());
    }

    @AfterAll
    static void stopTorService() {
        assertTrue(torService.stop());
    }

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        executorService = Executors.newCachedThreadPool();

        // Setup mock global settings
        when(mockGlobalSettings.getProxychainsPath()).thenReturn("proxychains4");
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        // Setup mock download
        when(mockDownload.getId()).thenReturn(TEST_DOWNLOAD_ID);
        when(mockDownload.getUri()).thenReturn(URI.create(TEST_URL));
        when(mockDownload.getDestination()).thenReturn(tempDir);
        when(mockDownload.getName()).thenReturn("test-file.zip");
        when(mockDownload.getStatus()).thenReturn(Download.Status.QUEUED);
        when(mockDownload.getType()).thenReturn(Download.Type.PROXYCHAINS);

        ApplicationContext.initialize();
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    @DisplayName("Should create handler with required dependencies")
    void shouldCreateHandlerWithRequiredDependencies() {
        assertNotNull(handler);
        assertEquals(Download.Type.PROXYCHAINS, handler.getSupportedType());
        assertEquals(0, handler.getActiveDownloadCount());
    }

    @Test
    @DisplayName("Should handle download type correctly")
    void shouldHandleDownloadTypeCorrectly() {
        assertTrue(handler.canHandle(mockDownload));

        // Test with null download
        assertFalse(handler.canHandle(null));

        // Test with different download type
        when(mockDownload.getType()).thenReturn(Download.Type.ARIA2);
        assertFalse(handler.canHandle(mockDownload));
    }

    @Test
    @DisplayName("Should add and remove download listeners")
    void shouldAddAndRemoveDownloadListeners() {
        // Add listeners
        handler.addDownloadListener(mockListener1);
        handler.addDownloadListener(mockListener2);

        // Add null listener (should be ignored)
        handler.addDownloadListener(null);

        // Remove listener
        handler.removeDownloadListener(mockListener1);

        // Remove null listener (should be ignored)
        handler.removeDownloadListener(null);
    }

    @Test
    @DisplayName("Should manage download options correctly")
    void shouldManageDownloadOptionsCorrectly() {
        Map<String, String> options = new HashMap<>();
        options.put("max-connections", "4");
        options.put("timeout", "30");

        // Set options
        handler.setDownloadOptions(TEST_DOWNLOAD_ID, options);

        // Get options
        Map<String, String> retrievedOptions = handler.getDownloadOptions(TEST_DOWNLOAD_ID);
        assertEquals(2, retrievedOptions.size());
        assertEquals("4", retrievedOptions.get("max-connections"));
        assertEquals("30", retrievedOptions.get("timeout"));

        // Test with non-existent download ID
        Map<String, String> emptyOptions = handler.getDownloadOptions("non-existent");
        assertTrue(emptyOptions.isEmpty());

        // Test with null parameters
        handler.setDownloadOptions(null, options);
        handler.setDownloadOptions(TEST_DOWNLOAD_ID, null);
    }

    @Test
    @DisplayName("Should create downloads with URI and destination")
    void shouldCreateDownloadsWithUriAndDestination() {
        assertDoesNotThrow(() -> handler.initialize().join());

        URI testUri = URI.create("http://example.com/test-file.zip");
        Download download = handler.download(testUri, tempDir);

        assertNotNull(download);
        assertEquals(testUri, download.getUri());
        assertEquals(tempDir, download.getDestination());
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 1);
        assertTrue(handler.isActive(download.getId()));
        // assertEquals(1, handler.getActiveDownloadCount());
    }

    @Test
    @DisplayName("Should create downloads with options")
    void shouldCreateDownloadsWithOptions() {
        URI testUri = URI.create(TEST_URL);
        Map<String, String> options = new HashMap<>();
        options.put("retry", "3");

        Download download = handler.download(testUri, tempDir, options);

        assertNotNull(download);
        assertEquals(testUri, download.getUri());
        assertEquals(tempDir, download.getDestination());
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        Map<String, String> retrievedOptions = handler.getDownloadOptions(download.getId());
        assertEquals("3", retrievedOptions.get("retry"));
    }

    @Test
    @DisplayName("Should track active downloads")
    void shouldTrackActiveDownloads() {
        assertDoesNotThrow(() -> handler.initialize().join());

        assertEquals(0, handler.getActiveDownloadCount());
        assertFalse(handler.isActive("non-existent"));

        // Create a download
        URI testUri = URI.create(TEST_URL);
        Download download = handler.download(testUri, tempDir);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 1);
        assertTrue(handler.isActive(download.getId()));
        // assertEquals(1, handler.getActiveDownloadCount());
    }

    @Test
    @DisplayName("Should initialize and shutdown properly")
    void shouldInitializeAndShutdownProperly() {
        assertDoesNotThrow(() -> {
            handler.initialize().join();
            handler.shutdown().join();
        });
    }

    @Test
    @DisplayName("Should start download with proper setup")
    void shouldStartDownloadWithProperSetup() {
        assertDoesNotThrow(() -> {
            handler.initialize().join();

            String gid = handler.startDownload(mockDownload).join();
            assertNotNull(gid);
            assertEquals(TEST_DOWNLOAD_ID, gid);
            assertTrue(handler.isActive(TEST_DOWNLOAD_ID));
        });
    }

    @Test
    @DisplayName("Should handle start download with null or invalid input")
    void shouldHandleStartDownloadWithNullOrInvalidInput() {
        assertDoesNotThrow(() -> handler.initialize().join());

        // Test with null download
        assertThrows(RuntimeException.class, () -> {
            handler.startDownload(null).join();
        });

        // Test with null URI
        when(mockDownload.getUri()).thenReturn(null);
        assertThrows(RuntimeException.class, () -> {
            handler.startDownload(mockDownload).join();
        });
    }

    @Test
    @DisplayName("Should pause download")
    void shouldPauseDownload() {
        assertDoesNotThrow(() -> {
            handler.initialize().join();
            handler.pauseDownload(mockDownload).join();
        });

        // Test with null download
        assertDoesNotThrow(() -> {
            handler.pauseDownload(null).join();
        });
    }

    @Test
    @DisplayName("Should resume download")
    void shouldResumeDownload() {
        when(mockDownload.getStatus()).thenReturn(Download.Status.PAUSED);

        assertDoesNotThrow(() -> {
            handler.initialize().join();
            handler.resumeDownload(mockDownload).join();
        });

        // Test with null download
        assertDoesNotThrow(() -> {
            handler.resumeDownload(null).join();
        });

        // Test with non-paused download. doReturn (not when()) because the
        // handler's executor thread concurrently invokes the void setStatus
        // on the same mock, which would corrupt when()'s stubbing state.
        doReturn(Download.Status.DOWNLOADING).when(mockDownload).getStatus();
        assertDoesNotThrow(() -> {
            handler.resumeDownload(mockDownload).join();
        });
    }

    @Test
    @DisplayName("Should cancel download")
    void shouldCancelDownload() {
        assertDoesNotThrow(() -> {
            handler.initialize().join();
            handler.cancelDownload(mockDownload, true).join();
        });

        // Test with null download
        assertDoesNotThrow(() -> {
            handler.cancelDownload(null, false).join();
        });
    }

    @Test
    @DisplayName("Should check proxychains availability")
    void shouldCheckProxychainsAvailability() {
        // This is a static method, so we can test it directly
        // The actual result depends on system configuration
        assertDoesNotThrow(() -> {
            ProxychainsDownloadHandler.isProxychainsAvailable();
        });
    }

    @Test
    @DisplayName("Should handle download lifecycle with state management")
    void shouldHandleDownloadLifecycleWithStateManagement() throws InterruptedException {
        assertDoesNotThrow(() -> handler.initialize().join());

        // Create a download
        URI testUri = URI.create(TEST_URL);
        Download download = handler.download(testUri, tempDir);

        // Verify initial state
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 1);
        // assertEquals(1, handler.getActiveDownloadCount());
        assertTrue(handler.isActive(download.getId()));

        // Test pause
        handler.pauseDownload(download);

        // Test resume (we'll test with the actual download object, not mock)
        handler.resumeDownload(download);

        // Test cancel
        handler.cancelDownload(download, true);
    }

    @Test
    @DisplayName("Should handle multiple concurrent downloads")
    void shouldHandleMultipleConcurrentDownloads() {
        assertDoesNotThrow(() -> handler.initialize().join());

        URI testUri1 = URI.create("https://example.com/file1.zip");
        URI testUri2 = URI.create("https://example.com/file2.zip");
        URI testUri3 = URI.create("https://example.com/file3.zip");

        Download download1 = handler.download(testUri1, tempDir);
        Download download2 = handler.download(testUri2, tempDir);
        Download download3 = handler.download(testUri3, tempDir);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 3);

        assertTrue(handler.isActive(download1.getId()));
        assertTrue(handler.isActive(download2.getId()));
        assertTrue(handler.isActive(download3.getId()));
    }

    @Test
    @DisplayName("Should handle download options persistence")
    void shouldHandleDownloadOptionsPersistence() {
        Map<String, String> options1 = new HashMap<>();
        options1.put("timeout", "60");
        options1.put("retries", "5");

        Map<String, String> options2 = new HashMap<>();
        options2.put("max-connections", "8");

        handler.setDownloadOptions("download1", options1);
        handler.setDownloadOptions("download2", options2);

        Map<String, String> retrieved1 = handler.getDownloadOptions("download1");
        Map<String, String> retrieved2 = handler.getDownloadOptions("download2");

        assertEquals(2, retrieved1.size());
        assertEquals("60", retrieved1.get("timeout"));
        assertEquals("5", retrieved1.get("retries"));

        assertEquals(1, retrieved2.size());
        assertEquals("8", retrieved2.get("max-connections"));

        // Test modification of returned map doesn't affect stored options
        retrieved1.put("new-option", "value");
        Map<String, String> retrievedAgain = handler.getDownloadOptions("download1");
        assertFalse(retrievedAgain.containsKey("new-option"));
    }

    @Test
    @Timeout(5)
    @DisplayName("Should handle operations within reasonable time")
    void shouldHandleOperationsWithinReasonableTime() {
        assertDoesNotThrow(() -> {
            handler.initialize().join();

            URI testUri = URI.create(TEST_URL);
            Download download = handler.download(testUri, tempDir);

            handler.pauseDownload(download).join();
            handler.resumeDownload(download).join();
            handler.cancelDownload(download, false).join();

            handler.shutdown().join();
        });
    }

}
