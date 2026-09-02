package org.manager.download;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;

import com.github.stefanbirkner.systemlambda.SystemLambda;

/**
 * Live settings application: while an aria2 download is running, changing
 * the global settings must propagate to the engine (global runtime options
 * and per-download options) without breaking the transfer.
 */
@DisplayName("Global settings apply to live downloads")
class ManagerLiveSettingsApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("changing global settings mid-flight keeps the download healthy")
    @Timeout(240)
    void settingsChangeMidFlight() throws Exception {
        Path xdgData = tempDir.resolve("xdg-data");
        Path xdgConfig = tempDir.resolve("xdg-config");
        Path downloadDir = Files.createDirectories(tempDir.resolve("downloads"));

        byte[] payload = new byte[512 * 1024];
        Arrays.fill(payload, (byte) 9);

        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    Thread.sleep(250);
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Length", String.valueOf(payload.length))
                            .setBody(new okio.Buffer().write(payload));
                }
            });

            SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdgData.toString())
                    .and("XDG_STATE_HOME", xdgData.toString())
                    .and("XDG_CONFIG_HOME", xdgConfig.toString())
                    .execute(() -> {
                        ApplicationContext.initialize();
                        DownloadManager manager = DownloadManagerFactory.createDefaultManager();
                        manager.initialize().get(60, TimeUnit.SECONDS);
                        try {
                            GlobalSettings initial = manager.getGlobalSettings();
                            initial.setDefaultDownloadDirectory(downloadDir);
                            initial.setGlobalSpeedLimit(0);
                            manager.setGlobalSettings(initial);

                            Download download = manager.createDownload(
                                    URI.create("http://127.0.0.1:" + server.getPort() + "/payload.bin"),
                                    downloadDir);
                            download.setName("payload.bin");

                            manager.startDownload(download).get(30, TimeUnit.SECONDS);

                            // wait until data is flowing, then change settings live
                            await().atMost(Duration.ofSeconds(90)).until(() ->
                                    download.getStatus() == Download.Status.DOWNLOADING
                                            || download.getStatus() == Download.Status.COMPLETED
                                            || download.getStatus() == Download.Status.ERROR);
                            assertTrue(download.getStatus() != Download.Status.ERROR,
                                    "the download must start: " + download.getErrorMessage());

                            GlobalSettings updated = manager.getGlobalSettings().copy();
                            updated.setGlobalSpeedLimit(1024);
                            updated.setMaxConcurrentDownloads(4);
                            manager.setGlobalSettings(updated);

                            // the slowed transfer must still finish after the change
                            await().atMost(Duration.ofSeconds(120)).until(() ->
                                    download.getStatus() == Download.Status.COMPLETED
                                            || download.getStatus() == Download.Status.ERROR);
                            assertEquals(Download.Status.COMPLETED, download.getStatus(),
                                    "a live settings change must not break the download");
                            Path output = downloadDir.resolve("payload.bin");
                            assertTrue(Files.exists(output));
                            assertEquals(payload.length, Files.size(output));
                            assertNotNull(manager.getGlobalSettings());
                        } finally {
                            manager.shutdown().get(60, TimeUnit.SECONDS);
                        }
                    });
            ApplicationContext.resetInstance();
        }
    }
}
