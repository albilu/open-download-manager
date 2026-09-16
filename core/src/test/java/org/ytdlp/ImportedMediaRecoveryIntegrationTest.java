package org.ytdlp;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManagerFactory;
import org.manager.download.DownloadManagerImpl;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Full manager/handler/browser/yt-dlp recovery against a local JavaScript-only media page. */
class ImportedMediaRecoveryIntegrationTest {
    @TempDir Path directory;

    @Test
    @Timeout(90)
    void recoveryKeepsItsQueueSlotAndRecordAndHonorsCookiesAndUniqueOutputNames() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
                    try (var fixture = new BrowserMediaProbeTest.Fixture()) {
                        var manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
                        var global = manager.getGlobalSettings();
                        global.setMaxConcurrentDownloads(1).setUniquifyOutputName(true);
                        global.setProperty("ytdlp.skipDownloaded", "false");
                        global.setProperty("ytdlp.useAria2c", "false");
                        global.setOverrideOutputPath(true);
                        Path output = Files.createDirectories(directory.resolve("output"));
                        Files.writeString(output.resolve("download.mp4"), "original file");
                        var enteredBrowser = new CountDownLatch(1);
                        var releaseBrowser = new CountDownLatch(1);
                        fixture.onBrowserPage = () -> {
                            enteredBrowser.countDown();
                            try {
                                if (!releaseBrowser.await(20, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test did not release browser navigation");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(interrupted);
                            }
                        };
                        Download imported = manager.createYoutubeDownload(fixture.page("/page"), output, Map.of());
                        imported.setRequestedFileName("download.mp4");
                        var settings = (YtDlpSettings) imported.getSettings();
                        settings.setUserAgent("ODM Browser Probe Test");
                        settings.setMaxRetries(0);
                        settings.setMediaProbeOnFailure(true);
                        String originalId = imported.getId();
                        var errors = new AtomicInteger();
                        manager.addDownloadListener(new DownloadListener() {
                            public void onDownloadStart(Download d) { }
                            public void onDownloadProgress(Download d, float p, long b, long t, float s) { }
                            public void onDownloadPause(Download d) { }
                            public void onDownloadResume(Download d) { }
                            public void onDownloadComplete(Download d) { }
                            public void onDownloadCanceled(Download d) { }
                            public void onDownloadError(Download d, String message) { errors.incrementAndGet(); }
                        });
                        try {
                            DownloadManagerFactory.getContainer()
                                    .getRequired(org.manager.download.handler.DownloadHandlerFactory.class)
                                    .initializeHandlers();
                            manager.queueDownload(imported).get(10, TimeUnit.SECONDS);
                            assertTrue(enteredBrowser.await(25, TimeUnit.SECONDS), "native failure must trigger Chromium");
                            Download next = manager.createDownload(fixture.page("/next.bin"), directory.resolve("next"));
                            manager.queueDownload(next).get(10, TimeUnit.SECONDS);
                            assertEquals(Download.Status.QUEUED, next.getStatus());
                            assertEquals(1, manager.getDownloadCountByStatus(Download.Status.DOWNLOADING));
                            assertEquals(0, errors.get(), "the recoverable failure must not terminate the record");
                            releaseBrowser.countDown();
                            await().atMost(Duration.ofSeconds(45)).until(() -> imported.getStatus() == Download.Status.COMPLETED
                                    || imported.getStatus() == Download.Status.ERROR);
                            assertEquals(Download.Status.COMPLETED, imported.getStatus(), imported.getErrorMessage());
                            assertSame(imported, manager.getDownload(originalId));
                            assertEquals(fixture.page("/media/movie.mp4?token=a%2Fb"), imported.getUri());
                            assertEquals(fixture.page("/page").toString(), settings.getMediaRequestContext().pageUrl());
                            assertFalse(settings.isMediaProbeOnFailure());
                            assertEquals("original file", Files.readString(output.resolve("download.mp4")));
                            try (var files = Files.list(output)) {
                                var created = files.filter(path -> !path.getFileName().toString().equals("download.mp4"))
                                        .filter(path -> path.toString().endsWith(".mp4")).toList();
                                assertEquals(1, created.size(), created.toString());
                                assertArrayEquals(fixture.media, Files.readAllBytes(created.getFirst()));
                            }
                            await().atMost(Duration.ofSeconds(15)).until(() -> next.getStatus() == Download.Status.COMPLETED);
                            assertEquals(0, errors.get());
                            assertEquals(0, manager.getDownloadCountByStatus(Download.Status.DOWNLOADING));
                            assertEquals(2, manager.getAllDownloads().size());
                        } finally {
                            releaseBrowser.countDown();
                            DownloadManagerFactory.shutdown();
                        }
                    }
                });
    }
}
