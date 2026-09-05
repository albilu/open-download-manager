package org.manager.download;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.schedule.ScheduleSettings;

class PersistedPauseOwnershipTest {
    @TempDir Path directory;

    @Test
    void freshControllersResumePersistedAutomaticPausesAndPreserveManualPauses() throws Exception {
        Path database = directory.resolve("state.db");
        Path legacy = directory.resolve("state.json");
        List<Download> originals = java.util.Arrays.stream(Download.PauseReason.values()).map(reason -> {
            Download download = new Download(URI.create("http://resume.odm.invalid/" + reason + ".bin"));
            download.setStatus(Download.Status.PAUSED);
            download.setPauseReason(reason);
            download.setScheduleSettings(ScheduleSettings.alwaysActive().setRespectGlobalSchedule(false));
            return download;
        }).toList();
        try (SqliteDownloadStateStore beforeExit = new SqliteDownloadStateStore(database, legacy,
                DownloadManagerImpl.createStateObjectMapper())) {
            beforeExit.save(originals, Set.of());
        }
        try (SqliteDownloadStateStore afterRestart = new SqliteDownloadStateStore(database, legacy,
                DownloadManagerImpl.createStateObjectMapper())) {
            List<Download> restored = afterRestart.load().downloads();
            GlobalSettings global = new GlobalSettings() {
                @Override public boolean save() { return true; }
            };
            global.setProperty("ui.offline", "true");
            DownloadManager manager = (DownloadManager) Proxy.newProxyInstance(DownloadManager.class.getClassLoader(),
                    new Class<?>[] { DownloadManager.class }, (proxy, method, args) -> switch (method.getName()) {
                        case "getGlobalSettings" -> global;
                        case "getAllDownloads" -> restored;
                        case "getDownload" -> restored.stream().filter(d -> d.getId().equals(args[0])).findFirst().orElse(null);
                        case "resumeDownload" -> {
                            Download download = (Download) args[0];
                            download.setStatus(Download.Status.DOWNLOADING);
                            download.setPauseReason(null);
                            yield CompletableFuture.completedFuture(null);
                        }
                        case "saveState", "reconsiderQueuedDownloads" -> {
                            afterRestart.save(restored, Set.of());
                            yield CompletableFuture.completedFuture(null);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Download user = restored.stream().filter(d -> d.getPauseReason() == Download.PauseReason.USER).findFirst().orElseThrow();
            Download schedule = restored.stream().filter(d -> d.getPauseReason() == Download.PauseReason.SCHEDULE).findFirst().orElseThrow();
            Download offline = restored.stream().filter(d -> d.getPauseReason() == Download.PauseReason.OFFLINE).findFirst().orElseThrow();
            DownloadScheduler scheduler = new DownloadScheduler(manager);
            try {
                scheduler.checkDownloadScheduleNow(schedule.getId());
                assertEquals(Download.Status.PAUSED, schedule.getStatus(), "schedule must respect Offline Mode");
                new OfflineModeController(manager, Runnable::run).setOffline(false).join();
                assertEquals(Download.Status.DOWNLOADING, offline.getStatus());
                scheduler.start().join();
                await().atMost(Duration.ofSeconds(5)).until(() -> schedule.getStatus() == Download.Status.DOWNLOADING);
                assertEquals(Download.Status.PAUSED, user.getStatus());
                assertEquals(Download.PauseReason.USER, user.getPauseReason());
            } finally {
                scheduler.shutdown().join();
            }
        }
    }
}
