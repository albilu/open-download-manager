package org.odm.gtk4;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import static org.junit.jupiter.api.Assertions.*;

class DownloadSpeedHistoryTest {

    @Test
    void irregularUpdatesUseTimeWeightedAverageAndIncludeStalls() {
        var history = new DownloadSpeedHistory();
        Download download = download("a");
        history.record(download, 0, 0, 1000);
        history.record(download, 1000, 2000, 3000);
        history.record(download, 4000, 8000, 1000);
        assertEquals(2000, history.snapshot(download).averageBytesPerSecond(), 0.001);
        history.record(download, 5000, 8000, 0);
        history.record(download, 6000, 8000, 0);
        assertEquals(8500.0 / 6, history.snapshot(download).averageBytesPerSecond(), 0.001);
    }

    @Test
    void selectionDoesNotControlCollectionAndSnapshotsAreIsolated() {
        var history = new DownloadSpeedHistory();
        Download first = download("a");
        Download second = download("b");
        history.record(first, 0, 10, 200);
        var snapshot = history.snapshot(first);
        history.record(second, 0, 20, 800);
        history.record(first, 1000, 210, 200);
        assertEquals(200, history.snapshot(first).averageBytesPerSecond());
        assertEquals(800, history.snapshot(second).averageBytesPerSecond());
        assertEquals(1, snapshot.samples().size(), "render snapshots must not change beneath GTK");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.samples().clear());
        assertTrue(history.snapshot(null).samples().isEmpty());
    }

    @Test
    void longDownloadsKeepTheirBeginningAndAverageWithBoundedStorage() {
        var history = new DownloadSpeedHistory();
        Download download = download("long");
        for (int i = 0; i < 20_000; i++) {
            history.record(download, i * 1000L, i * 2500L, 2500);
        }
        var snapshot = history.snapshot(download);
        assertTrue(snapshot.samples().size() <= DownloadSpeedHistory.MAX_SAMPLES);
        assertEquals(0, snapshot.samples().getFirst().downloadedBytes());
        assertEquals(19_999 * 2500L, snapshot.samples().getLast().downloadedBytes());
        assertEquals(2500, snapshot.averageBytesPerSecond(), 0.001);
        for (int i = 1; i < snapshot.samples().size(); i++) {
            assertTrue(snapshot.samples().get(i).elapsedMillis()
                    > snapshot.samples().get(i - 1).elapsedMillis());
        }
    }

    @Test
    void restartingFromEarlierBytesClearsThePreviousAttempt() {
        var history = new DownloadSpeedHistory();
        Download download = download("retry");
        history.record(download, 1000, 5000, 5000);
        history.record(download, 2000, 9000, 4000);
        history.record(download, 3000, 100, 100);
        assertEquals(1, history.snapshot(download).samples().size());
        assertEquals(100, history.snapshot(download).averageBytesPerSecond());
    }

    @Test
    void idleStatesAndInvalidTelemetryDoNotAddBogusSamples() {
        var history = new DownloadSpeedHistory();
        Download download = download("idle");
        history.record(download, 0, 100, 500);
        for (Download.Status status : new Download.Status[]{Download.Status.PAUSED,
                Download.Status.SEEDING, Download.Status.COMPLETED}) {
            download.setStatus(status);
            history.record(download, 100, 0);
        }
        download.setStatus(Download.Status.DOWNLOADING);
        history.record(download, -1, 100);
        history.record(download, 100, Double.NaN);
        history.record(download, 100, Double.POSITIVE_INFINITY);
        assertEquals(1, history.snapshot(download).samples().size());
        assertEquals(500, history.snapshot(download).averageBytesPerSecond());
    }

    @Test
    void alreadyQueuedProgressSurvivesAFastCompletion() {
        var history = new DownloadSpeedHistory();
        Download download = download("fast");
        download.setStatus(Download.Status.COMPLETED);
        history.record(download, 1024, 1024);
        assertEquals(1024, history.snapshot(download).averageBytesPerSecond());
    }

    @Test
    void multipleUpdatesAtTheSameInstantDoNotInventElapsedTime() {
        var history = new DownloadSpeedHistory();
        Download download = download("burst");
        history.record(download, 1000, 100, 100);
        history.record(download, 1000, 200, 300);
        assertEquals(1, history.snapshot(download).samples().size());
        assertEquals(300, history.snapshot(download).averageBytesPerSecond());
        history.record(download, 2000, 500, 300);
        assertEquals(300, history.snapshot(download).averageBytesPerSecond());
    }

    private static Download download(String name) {
        return new Download(URI.create("https://example.com/" + name));
    }
}
