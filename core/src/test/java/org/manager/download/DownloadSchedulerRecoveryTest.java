package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.manager.schedule.ScheduleSettings;

class DownloadSchedulerRecoveryTest {

    private static boolean await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @Test
    void admissionGateLazilyFindsScheduleLoadedAfterSchedulerConstruction() {
        DownloadManager manager = mock(DownloadManager.class);
        when(manager.getAllDownloads()).thenReturn(List.of());

        DownloadScheduler scheduler = new DownloadScheduler(manager);
        try {
            Download restored = new Download(URI.create("https://example.test/recovered.bin"));
            ScheduleSettings persisted = ScheduleSettings.neverActive()
                    .setRespectGlobalSchedule(false);
            restored.setScheduleSettings(persisted);
            when(manager.getDownload(restored.getId())).thenReturn(restored);

            assertFalse(scheduler.shouldDownloadBeActive(restored.getId()),
                    "recovery admission must honor a per-download schedule loaded later");
            assertEquals(persisted, scheduler.getDownloadSchedule(restored.getId()));
        } finally {
            scheduler.shutdown().join();
        }
    }

    @Test
    void failedPauseAndResumeRemainEligibleForRetryAndNotifyOnlyAfterSuccess() throws Exception {
        DownloadManager manager = mock(DownloadManager.class);
        Download download = new Download(URI.create("https://example.test/scheduled.bin"));
        download.setStatus(Download.Status.DOWNLOADING);
        ScheduleSettings blocked = ScheduleSettings.neverActive()
                .setRespectGlobalSchedule(false)
                .setPolicy(ScheduleSettings.SchedulePolicy.STRICT);
        download.setScheduleSettings(blocked);
        when(manager.getAllDownloads()).thenReturn(List.of(download));
        when(manager.getDownload(download.getId())).thenReturn(download);

        AtomicInteger pauseAttempts = new AtomicInteger();
        when(manager.pauseDownload(download)).thenAnswer(ignored -> {
            if (pauseAttempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("pause failed"));
            }
            download.setStatus(Download.Status.PAUSED);
            return CompletableFuture.completedFuture(null);
        });
        AtomicInteger resumeAttempts = new AtomicInteger();
        when(manager.resumeDownload(download)).thenAnswer(ignored -> {
            if (resumeAttempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("resume failed"));
            }
            download.setStatus(Download.Status.DOWNLOADING);
            return CompletableFuture.completedFuture(null);
        });

        AtomicInteger pausedEvents = new AtomicInteger();
        AtomicInteger resumedEvents = new AtomicInteger();
        DownloadScheduler scheduler = new DownloadScheduler(manager);
        scheduler.addListener(new DownloadScheduler.DownloadSchedulerListener() {
            @Override
            public void onDownloadPausedBySchedule(String downloadId, ScheduleSettings schedule) {
                pausedEvents.incrementAndGet();
            }

            @Override
            public void onDownloadResumedBySchedule(String downloadId, ScheduleSettings schedule) {
                resumedEvents.incrementAndGet();
            }
        });

        try {
            scheduler.start().join();
            assertTrue(await(() -> pauseAttempts.get() == 1));
            assertEquals(0, pausedEvents.get(), "failed pauses must not claim scheduler ownership");

            scheduler.checkDownloadScheduleNow(download.getId());
            assertTrue(await(() -> pauseAttempts.get() == 2
                    && download.getStatus() == Download.Status.PAUSED));
            assertEquals(1, pausedEvents.get());

            ScheduleSettings allowed = ScheduleSettings.alwaysActive()
                    .setRespectGlobalSchedule(false)
                    .setPolicy(ScheduleSettings.SchedulePolicy.STRICT);
            scheduler.setDownloadSchedule(download.getId(), allowed);
            assertTrue(await(() -> resumeAttempts.get() == 1));
            assertEquals(0, resumedEvents.get(),
                    "failed resumes must keep scheduler ownership for a later retry");

            scheduler.checkDownloadScheduleNow(download.getId());
            assertTrue(await(() -> resumeAttempts.get() == 2
                    && download.getStatus() == Download.Status.DOWNLOADING));
            assertEquals(1, resumedEvents.get());
        } finally {
            scheduler.shutdown().join();
        }
    }
}
