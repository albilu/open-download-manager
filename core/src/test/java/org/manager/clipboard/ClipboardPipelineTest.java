package org.manager.clipboard;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Drives the real clipboard pipeline: an actual AWT ClipboardMonitorImpl
 * (running against Xvfb in CI), real UrlDetector classification and a real
 * ClipboardService, with only the DownloadManager mocked.
 */
@DisplayName("Clipboard monitor and service pipeline")
class ClipboardPipelineTest {

    private ClipboardMonitorImpl monitor;
    private DownloadManager downloadManager;
    private ClipboardService service;

    @BeforeEach
    void setUp() throws Exception {
        monitor = new ClipboardMonitorImpl();
        // the AWT toolkit needs a display; skip when there is none
        try {
            monitor.setClipboardContent("odm-pipeline-warmup");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "no AWT clipboard available: " + e.getMessage());
        }

        downloadManager = mock(DownloadManager.class);
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(Path.of(System.getProperty("java.io.tmpdir"), "odm-clipboard"));
        when(downloadManager.getGlobalSettings()).thenReturn(globalSettings);
        when(downloadManager.createDownload(any(URI.class), any(Path.class)))
                .thenAnswer(inv -> {
                    Download download = new Download(inv.getArgument(0));
                    return download;
                });

        service = new ClipboardService(downloadManager, monitor);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.cleanup();
        }
        if (monitor != null) {
            monitor.cleanup();
        }
    }

    @Test
    @DisplayName("monitor detects a pasted URL and notifies the service, which queues it silently")
    @org.junit.jupiter.api.Timeout(60)
    void pastedUrlBecomesQueuedDownload() throws Exception {
        ClipboardSettings settings = new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setSilentMode(true);
        service.updateSettings(settings);

        List<Download> created = new CopyOnWriteArrayList<>();
        when(downloadManager.createDownload(any(URI.class), any(Path.class))).thenAnswer(inv -> {
            Download download = new Download(inv.getArgument(0));
            created.add(download);
            return download;
        });

        service.startService().get(10, TimeUnit.SECONDS);
        assertTrue(service.isServiceEnabled());

        // paste + manual check; retry a few times to ride out the race with
        // the background poller over the shared AWT clipboard
        AtomicInteger monitorCallbacks = new AtomicInteger();
        monitor.addClipboardListener(new ClipboardListener() {
            @Override public void onUrlsDetected(java.util.List<URI> urls, String clipboardContent) {
                monitorCallbacks.incrementAndGet();
                System.out.println("DIAG monitor onUrlsDetected: " + urls);
            }
            @Override public void onClipboardChanged(String clipboardContent) {
                System.out.println("DIAG monitor onClipboardChanged: " + clipboardContent);
            }
            @Override public void onClipboardError(Exception error) {
                System.out.println("DIAG monitor onClipboardError: " + error);
            }
        });

        // paste + manual check; each retry uses a fresh URL because one stale
        // clipboard read poisons the change detection for a repeated value
        long deadline = System.currentTimeMillis() + 30_000;
        int attempt = 0;
        while (created.isEmpty() && System.currentTimeMillis() < deadline) {
            attempt++;
            String url = "https://example.test/pipeline-file-" + attempt + ".zip";
            monitor.setClipboardContent(url);
            monitor.checkClipboardNow().get(10, TimeUnit.SECONDS);
            Thread.sleep(200);
        }

        assertFalse(created.isEmpty(), "the pasted URL must produce a download");
        assertTrue(created.get(0).getUri().toString().startsWith("https://example.test/pipeline-file-"),
                "the created download must match the pasted URL");
    }

    @Test
    @DisplayName("monitor interval changes are honored")
    void monitoringInterval() {
        long original = monitor.getMonitoringInterval();
        assertTrue(original > 0);
        monitor.setMonitoringInterval(750);
        assertEquals(750, monitor.getMonitoringInterval());
        monitor.setMonitoringInterval(original);
    }

    @Test
    @DisplayName("URL classification helpers agree with the detector")
    void urlHelpers() {
        monitor.setClipboardContent("https://example.test/a.zip and some text");
        assertTrue(monitor.containsValidUrls("https://example.test/a.zip"));
        assertFalse(monitor.containsValidUrls("just some words"));
    }

    @Test
    @DisplayName("a service that was never started ignores detected URLs")
    @org.junit.jupiter.api.Timeout(60)
    void disabledServiceIgnoresUrls() {
        ClipboardSettings settings = new ClipboardSettings().setMonitoringEnabled(true);
        service.updateSettings(settings);
        // deliberately NOT calling startService()
        assertFalse(service.isServiceEnabled());

        AtomicInteger notified = new AtomicInteger();
        service.addServiceListener(new ClipboardServiceListener() {
            @Override public void onUrlsDetected(List<URI> urls, String content) {
                notified.incrementAndGet();
            }
            @Override public void onConfirmationRequired(List<URI> urls, String content) { }
        });

        service.onUrlsDetected(
                List.of(URI.create("https://example.test/ignored.zip")), "content");

        assertEquals(0, notified.get());
        verify(downloadManager, never()).createDownload(any(URI.class), any(Path.class));
    }

    @Test
    @DisplayName("the settings switch controls whether the monitor itself runs")
    @org.junit.jupiter.api.Timeout(60)
    void monitoringSwitchControlsMonitor() throws Exception {
        service.updateSettings(new ClipboardSettings().setMonitoringEnabled(false));
        service.startService().get(10, TimeUnit.SECONDS);
        assertTrue(service.isServiceEnabled());
        assertFalse(monitor.isMonitoring(),
                "monitoring disabled in settings must leave the monitor off");

        service.updateSettings(new ClipboardSettings().setMonitoringEnabled(true));
        await().atMost(Duration.ofSeconds(10)).until(monitor::isMonitoring);
    }

    @Test
    @DisplayName("silently queued URLs create downloads without auto-start notifications")
    @org.junit.jupiter.api.Timeout(60)
    void silentModeQueuesWithoutConfirmation() throws Exception {
        ClipboardSettings settings = new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setSilentMode(true)
                .setAutoDownloadDetectedUrls(false);
        service.updateSettings(settings);
        service.startService().get(10, TimeUnit.SECONDS);

        AtomicInteger confirmations = new AtomicInteger();
        service.addServiceListener(new ClipboardServiceListener() {
            @Override public void onUrlsDetected(List<URI> urls, String content) { }
            @Override public void onConfirmationRequired(List<URI> urls, String content) {
                confirmations.incrementAndGet();
            }
        });

        service.onUrlsDetected(
                List.of(URI.create("https://example.test/silent.zip")), "content");

        assertEquals(0, confirmations.get());
        verify(downloadManager).createDownload(any(URI.class), any(Path.class));
    }

    @Test
    @DisplayName("auto-download mode creates a download for every detected URL")
    @org.junit.jupiter.api.Timeout(60)
    void autoDownloadCreatesAll() throws Exception {
        ClipboardSettings settings = new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setAutoDownloadDetectedUrls(true);
        service.updateSettings(settings);
        service.startService().get(10, TimeUnit.SECONDS);

        service.onUrlsDetected(List.of(
                URI.create("https://example.test/one.zip"),
                URI.create("https://example.test/two.zip")), "content");

        verify(downloadManager, org.mockito.Mockito.times(2))
                .createDownload(any(URI.class), any(Path.class));
    }

    @Test
    @DisplayName("importFromClipboard extracts and creates downloads from pasted text")
    @org.junit.jupiter.api.Timeout(60)
    void manualImport() throws Exception {
        monitor.setClipboardContent("grab https://example.test/manual.bin please");
        List<Download> imported = service.importFromClipboard().get(15, TimeUnit.SECONDS);
        assertEquals(1, imported.size());
        assertEquals("https://example.test/manual.bin", imported.get(0).getUri().toString());
    }

    @Test
    @DisplayName("importFromClipboard with an empty clipboard imports nothing")
    @org.junit.jupiter.api.Timeout(60)
    void manualImportEmptyClipboard() throws Exception {
        monitor.setClipboardContent("");
        List<Download> imported = service.importFromClipboard().get(15, TimeUnit.SECONDS);
        assertNotNull(imported);
        assertTrue(imported.isEmpty());
    }

    @Test
    @DisplayName("errors are forwarded to service listeners")
    void errorForwarding() {
        List<Exception> errors = new CopyOnWriteArrayList<>();
        service.addServiceListener(new ClipboardServiceListener() {
            @Override public void onUrlsDetected(List<URI> urls, String content) { }
            @Override public void onClipboardError(Exception error) {
                errors.add(error);
            }
        });

        Exception failure = new IllegalStateException("clipboard exploded");
        service.onClipboardError(failure);

        assertEquals(1, errors.size());
        assertEquals(failure, errors.get(0));
    }

    @Test
    @DisplayName("statistics string is produced for reporting")
    void statisticsString() {
        String stats = service.getStatistics();
        assertNotNull(stats);
        assertFalse(stats.isBlank());
    }
}
