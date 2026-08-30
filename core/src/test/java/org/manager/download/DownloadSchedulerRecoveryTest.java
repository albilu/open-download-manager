package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.manager.schedule.ScheduleSettings;

class DownloadSchedulerRecoveryTest {

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
}
