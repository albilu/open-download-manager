package org.manager.download;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.schedule.ScheduleSettings;

class OfflineQueueAdmissionTest {
    @TempDir Path directory;

    static final class Handler extends AbstractDownloadHandler {
        final Map<String, Integer> starts = new ConcurrentHashMap<>();
        Handler() { super(null, null, null); }
        @Override public Download.Type getSupportedType() { return Download.Type.ARIA2; }
        @Override protected void doInitialize() { }
        @Override protected void doShutdown() { }
        @Override public CompletableFuture<String> startDownload(Download download) {
            starts.merge(download.getId(), 1, Integer::sum);
            return CompletableFuture.completedFuture("fixture-" + download.getId());
        }
        @Override public CompletableFuture<Void> pauseDownload(Download download) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> resumeDownload(Download download) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> changeSettings(Download download) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> cancelDownload(Download download, boolean files) {
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }
        void complete(Download download) { notifyDownloadComplete(download); }
    }

    @Test void onlineTransitionRespectsManualHoldsSchedulesAndCapacity() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            var manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            var handler = new Handler();
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.ARIA2, handler);
            manager.getGlobalSettings().setMaxConcurrentDownloads(1);
            var scheduler = new DownloadScheduler(manager);
            manager.setDownloadGate(scheduler::shouldDownloadBeActive);
            var offline = new OfflineModeController(manager, Runnable::run);
            try {
                offline.setOffline(true).join();
                Download held = manager.createDownload(URI.create("http://example.test/held"), directory);
                Download blocked = manager.createDownload(URI.create("http://example.test/blocked"), directory);
                blocked.setScheduleSettings(ScheduleSettings.neverActive().setRespectGlobalSchedule(false));
                Download first = manager.createDownload(URI.create("http://example.test/first"), directory);
                Download second = manager.createDownload(URI.create("http://example.test/second"), directory);
                manager.queueDownloadForManualStart(held).join();
                manager.queueDownload(blocked).join();
                manager.queueDownload(first).join();
                manager.queueDownload(second).join();
                assertTrue(handler.starts.isEmpty());
                offline.setOffline(false).join();
                await().atMost(Duration.ofSeconds(5)).until(() -> first.getStatus() == Download.Status.DOWNLOADING);
                assertEquals(Map.of(first.getId(), 1), handler.starts);
                handler.complete(first);
                await().atMost(Duration.ofSeconds(5)).until(() -> second.getStatus() == Download.Status.DOWNLOADING);
                assertEquals(Map.of(first.getId(), 1, second.getId(), 1), handler.starts);
                assertEquals(Download.Status.QUEUED, held.getStatus());
                assertEquals(Download.Status.QUEUED, blocked.getStatus());
            } finally {
                manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
                scheduler.shutdown().join();
                manager.setDownloadGate(null);
            }
        });
    }
}
