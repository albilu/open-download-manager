package org.proxychains;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
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
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import utils.SocksHttpServer;

/**
 * Handler tests using real proxychains/aria2 processes through a private SOCKS5
 * fixture. Responses stay gated while lifecycle and active-count assertions run.
 */
@DisplayName("ProxychainsDownloadHandler Unit Tests")
class ProxychainsDownloadHandlerTest {

    private static final String TEST_URL = "http://downloads.odm.invalid/test-file.zip";
    private static final String PAYLOAD = "proxychains handler download test";
    private static final String TEST_DOWNLOAD_ID = "test-download-123";

    @Mock
    private Download mockDownload;

    @Mock
    private DownloadListener mockListener1;

    @Mock
    private DownloadListener mockListener2;

    @TempDir
    Path tempDir;

    private ProxychainsDownloadHandler handler;
    private SocksHttpServer proxy;
    private final CountDownLatch responseReady = new CountDownLatch(1);
    private ExecutorService executorService;
    private AutoCloseable mocks;

    @BeforeAll
    static void initializeApplicationContext() {
        ApplicationContext.initialize();
    }

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        executorService = Executors.newCachedThreadPool();
        proxy = new SocksHttpServer(false, PAYLOAD, responseReady);
        GlobalSettings globalSettings = new GlobalSettings()
                .setDefaultDownloadDirectory(tempDir)
                .setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:" + proxy.port());
        globalSettings.setHonorExternalAria2Configuration(false);

        // Setup mock download
        when(mockDownload.getId()).thenReturn(TEST_DOWNLOAD_ID);
        when(mockDownload.getUri()).thenReturn(URI.create(TEST_URL));
        when(mockDownload.getDestination()).thenReturn(tempDir);
        when(mockDownload.getName()).thenReturn("test-file.zip");
        when(mockDownload.getStatus()).thenReturn(Download.Status.QUEUED);
        when(mockDownload.getType()).thenReturn(Download.Type.PROXYCHAINS);

        handler = new ProxychainsDownloadHandler(globalSettings,
                new DownloadSettingsFactory(globalSettings), executorService,
                ApplicationContext.getToolManagerFactory());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (proxy != null) {
            proxy.close();
        }
        if (executorService != null) {
            executorService.shutdownNow();
            assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS), "Handler executor should stop");
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

        URI testUri = URI.create(TEST_URL);
        Download download = handler.download(testUri, tempDir);

        assertNotNull(download);
        assertEquals(testUri, download.getUri());
        assertEquals(tempDir, download.getDestination());
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        awaitTransfer(download);
        assertEquals(1, handler.getActiveDownloadCount());
        assertTrue(handler.isActive(download.getId()));
    }

    @Test
    @DisplayName("Should create downloads with options")
    void shouldCreateDownloadsWithOptions() {
        handler.initialize().join();
        URI testUri = URI.create(TEST_URL);
        Map<String, String> options = new HashMap<>();
        options.put("retry", "3");

        Download download = handler.download(testUri, tempDir, options);
        awaitTransfer(download);

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

        awaitTransfer(download);
        assertEquals(1, handler.getActiveDownloadCount());
        assertTrue(handler.isActive(download.getId()));
        handler.cancelDownload(download, false).join();
        assertEquals(Download.Status.CANCELED, download.getStatus());
        assertFalse(handler.isActive(download.getId()));
        assertEquals(0, handler.getActiveDownloadCount());
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
    void shouldStartDownloadWithProperSetup() throws Exception {
        String payload = "proxychains handler launch test";
        try (var proxy = new utils.SocksHttpServer(false, payload)) {
            handler.initialize().join();
            Download download = new Download(URI.create("http://launch.odm.invalid/test-file.zip"));
            download.setType(Download.Type.PROXYCHAINS);
            download.setDestination(tempDir);
            download.setProxyAddress("socks5h://127.0.0.1:" + proxy.port());
            download.setUseProxy(true);
            download.setStatus(Download.Status.QUEUED);

            String gid = handler.startDownload(download).get(10, TimeUnit.SECONDS);
            assertNotNull(gid);
            assertEquals(download.getId(), gid);
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                    .until(() -> download.getStatus() == Download.Status.COMPLETED);
            assertEquals(payload, Files.readString(tempDir.resolve("test-file.zip")));
            assertTrue(proxy.hosts.contains("launch.odm.invalid"));
            assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
        }
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
        handler.initialize().join();
        Download download = handler.download(URI.create(TEST_URL), tempDir);
        awaitTransfer(download);
        handler.pauseDownload(download).join();
        assertEquals(Download.Status.PAUSED, download.getStatus());
        int requestsBeforeResume = proxy.requestTargets.size();
        handler.resumeDownload(download).join();
        awaitTransfer(download);
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> proxy.requestTargets.size() > requestsBeforeResume);

        // Test with null download
        assertDoesNotThrow(() -> {
            handler.resumeDownload(null).join();
        });

        // A second resume while downloading must leave the active transfer intact.
        handler.resumeDownload(download).join();
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
        assertEquals(1, handler.getActiveDownloadCount());
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
        assertTrue(ProxychainsDownloadHandler.isProxychainsAvailable());
    }

    @Test
    @DisplayName("Should handle download lifecycle with state management")
    void shouldHandleDownloadLifecycleWithStateManagement() {
        assertDoesNotThrow(() -> handler.initialize().join());

        // Create a download
        URI testUri = URI.create(TEST_URL);
        Download download = handler.download(testUri, tempDir);

        awaitTransfer(download);
        assertEquals(1, handler.getActiveDownloadCount());
        assertTrue(handler.isActive(download.getId()));

        handler.pauseDownload(download).join();
        assertEquals(Download.Status.PAUSED, download.getStatus());
        assertFalse(handler.isActive(download.getId()));
        assertEquals(0, handler.getActiveDownloadCount());

        int requestsBeforeResume = proxy.requestTargets.size();
        handler.resumeDownload(download).join();
        awaitTransfer(download);
        Awaitility.await().atMost(10, TimeUnit.SECONDS)
                .until(() -> proxy.requestTargets.size() > requestsBeforeResume);
        assertEquals(1, handler.getActiveDownloadCount());

        handler.cancelDownload(download, true).join();
        assertEquals(Download.Status.CANCELED, download.getStatus());
        assertFalse(handler.isActive(download.getId()));
        assertEquals(0, handler.getActiveDownloadCount());
        assertFalse(Files.exists(tempDir.resolve(download.getName())));
    }

    @Test
    @DisplayName("Should handle multiple concurrent downloads")
    void shouldHandleMultipleConcurrentDownloads() throws Exception {
        assertDoesNotThrow(() -> handler.initialize().join());

        URI testUri1 = URI.create("http://first.odm.invalid/file1.zip");
        URI testUri2 = URI.create("http://second.odm.invalid/file2.zip");
        URI testUri3 = URI.create("http://third.odm.invalid/file3.zip");

        Download download1 = handler.download(testUri1, tempDir);
        Download download2 = handler.download(testUri2, tempDir);
        Download download3 = handler.download(testUri3, tempDir);

        awaitTransfer(download1);
        awaitTransfer(download2);
        awaitTransfer(download3);
        assertEquals(3, handler.getActiveDownloadCount());

        assertTrue(handler.isActive(download1.getId()));
        assertTrue(handler.isActive(download2.getId()));
        assertTrue(handler.isActive(download3.getId()));

        responseReady.countDown();
        for (Download download : List.of(download1, download2, download3)) {
            Awaitility.await().atMost(15, TimeUnit.SECONDS)
                    .until(() -> download.getStatus() == Download.Status.COMPLETED);
            assertEquals(PAYLOAD, Files.readString(tempDir.resolve(download.getName())));
        }
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 0);
        assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
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

            Download download = new Download(URI.create(TEST_URL));
            download.setType(Download.Type.PROXYCHAINS);
            download.setDestination(tempDir);
            handler.startDownload(download).join();

            handler.pauseDownload(download).join();
            handler.resumeDownload(download).join();
            handler.cancelDownload(download, false).join();

            handler.shutdown().join();
        });
    }

    private void awaitTransfer(Download download) {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(proxy.hosts.contains(download.getUri().getHost()),
                    "The transfer must reach its private SOCKS fixture");
            assertTrue(proxy.requestTargets.contains(download.getUri().getRawPath()),
                    "The fixture must receive this download's HTTP request before releasing responses");
            assertEquals(Download.Status.DOWNLOADING, download.getStatus());
            assertTrue(handler.isActive(download.getId()));
        });
    }

}
