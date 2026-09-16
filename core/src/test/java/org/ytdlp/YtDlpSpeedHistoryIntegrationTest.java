package org.ytdlp;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadManagerFactory;
import org.manager.download.DownloadManagerImpl;
import org.manager.download.handler.DownloadHandlerFactory;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises transfer sampling, ffmpeg finalization and persistence with real local media. */
class YtDlpSpeedHistoryIntegrationTest {

    @Test
    @Timeout(90)
    void smallerRemuxedOutputRetainsSpeedHistoryAndFinalSizeAcrossReloading(@TempDir Path directory)
            throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
                    try (var media = YtDlpLocalMediaServer.startHls(directory.resolve("hls"), 32 * 1024)) {
                        var manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
                        try {
                            var global = manager.getGlobalSettings();
                            global.setProperty("ytdlp.skipDownloaded", "false");
                            global.setProperty("ytdlp.useAria2c", "false");
                            global.setOdmAutoSaveEnabled(true);
                            global.setRetainCompletedAndCanceledHistory(true);
                            Path output = Files.createDirectories(directory.resolve("output"));
                            global.setDefaultDownloadDirectory(output);
                            Download download = manager.createYoutubeDownload(URI.create(media.hlsUrl()), output, Map.of());
                            ((YtDlpSettings) download.getSettings()).setUseAria2c(false)
                                    .setUseDownloadArchive(false).setFormat("best")
                                    .setOutputTemplate("final.%(ext)s").setEmbedMetadata(false);
                            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                                    .initializeHandlers();
                            manager.queueDownload(download).get(15, TimeUnit.SECONDS);
                            await().atMost(Duration.ofSeconds(45)).until(() ->
                                    download.getStatus() == Download.Status.COMPLETED
                                            || download.getStatus() == Download.Status.ERROR);
                            assertEquals(Download.Status.COMPLETED, download.getStatus(), download.getErrorMessage());
                            long finalSize = Files.size(download.getPrimaryOutputPath());
                            assertTrue(finalSize > 0);
                            assertEquals(finalSize, download.getSize());
                            assertEquals(finalSize, download.getDownloaded());
                            assertEquals(100, download.getProgress());
                            assertEquals(0, download.getSpeed());
                            var history = download.getSpeedHistory();
                            var state = download.getSpeedHistoryState();
                            assertTrue(history.samples().size() > 1, history.toString());
                            assertTrue(history.maxBytesPerSecond() > 0);
                            assertTrue(history.averageBytesPerSecond() > 0);
                            assertTrue(history.samples().getLast().downloadedBytes() > finalSize,
                                    "the HLS fixture must exercise a final file smaller than its transferred bytes");

                            manager.saveState().get(15, TimeUnit.SECONDS);
                            manager.loadState().get(15, TimeUnit.SECONDS);
                            Download restored = manager.getDownload(download.getId());
                            assertNotSame(download, restored, "the manager must reconstruct the persisted record");
                            assertEquals(Download.Status.COMPLETED, restored.getStatus());
                            assertEquals(finalSize, restored.getSize());
                            assertEquals(finalSize, restored.getDownloaded());
                            assertEquals(100, restored.getProgress());
                            assertEquals(history, restored.getSpeedHistory());
                            assertEquals(state, restored.getSpeedHistoryState());
                        } finally {
                            DownloadManagerFactory.shutdown();
                        }
                    }
                });
    }
}
