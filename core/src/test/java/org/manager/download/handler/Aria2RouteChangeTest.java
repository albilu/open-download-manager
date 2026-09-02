package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.aria2.Aria2Client;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;

class Aria2RouteChangeTest {

    @Test
    void routeHandoffForceRemovesEveryGidWithoutCancelingTheDownload() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        RecordingAria2Client client = new RecordingAria2Client();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        Aria2DownloadHandler handler = new Aria2DownloadHandler(settings,
                new DownloadSettingsFactory(settings), executor, client, poller);
        handler.initialized = true;

        Download download = new Download(URI.create("https://example.com/file.iso"));
        download.setStatus(Download.Status.PAUSED);
        handler.registerTrackedDownload(download, List.of("gid-a", "gid-b"));

        try {
            handler.stopForRouteChange(download).get(10, TimeUnit.SECONDS);

            assertEquals(List.of("gid-a", "gid-b"), client.removedGids);
            assertEquals(Download.Status.PAUSED, download.getStatus());
            assertTrue(handler.getDownloadFiles(download).isEmpty(),
                    "the old aria2 task must no longer be tracked after handoff");
        } finally {
            executor.shutdownNow();
            poller.shutdownNow();
        }
    }

    @Test
    void routeHandoffRejectsPartialMultiGidRemoval() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        RecordingAria2Client client = new RecordingAria2Client();
        client.failingGid = "gid-b";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        Aria2DownloadHandler handler = new Aria2DownloadHandler(settings,
                new DownloadSettingsFactory(settings), executor, client, poller);
        handler.initialized = true;

        Download download = new Download(URI.create("https://example.com/file.iso"));
        download.setStatus(Download.Status.PAUSED);
        handler.registerTrackedDownload(download, List.of("gid-a", "gid-b"));

        try {
            assertThrows(ExecutionException.class,
                    () -> handler.stopForRouteChange(download).get(10, TimeUnit.SECONDS));
            assertEquals(List.of("gid-a", "gid-b"), client.removedGids,
                    "every GID must still be attempted");
        } finally {
            executor.shutdownNow();
            poller.shutdownNow();
        }
    }

    private static final class RecordingAria2Client extends Aria2Client {
        private final List<String> removedGids = new ArrayList<>();
        private String failingGid;

        private RecordingAria2Client() {
            super("aria2c");
        }

        @Override
        public String forceRemove(String gid) throws Aria2RpcException {
            removedGids.add(gid);
            if (gid.equals(failingGid)) {
                throw new Aria2RpcException(1, "simulated force-remove failure");
            }
            return gid;
        }
    }
}
