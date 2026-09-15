package org.manager.download;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadSpeedHistoryTest {

    @Test
    void irregularUpdatesUseTimeWeightedAverageAndIncludeStalls() {
        var history = new DownloadSpeedHistory();
        history.record(0, 0, 1000);
        history.record(1000, 2000, 3000);
        history.record(4000, 8000, 1000);
        assertEquals(2000, history.snapshot().averageBytesPerSecond(), 0.001);
        history.record(5000, 8000, 0);
        history.record(6000, 8000, 0);
        assertEquals(8500.0 / 6, history.snapshot().averageBytesPerSecond(), 0.001);
    }

    @Test
    void downloadsOwnTheirHistoryAndSnapshotsAreIsolated() {
        Download first = download("a");
        Download second = download("b");
        first.recordSpeedSample(10, 200);
        var snapshot = first.getSpeedHistory();
        second.recordSpeedSample(20, 800);
        first.recordSpeedSample(210, 200);
        assertEquals(200, first.getSpeedHistory().averageBytesPerSecond());
        assertEquals(800, second.getSpeedHistory().averageBytesPerSecond());
        assertEquals(1, snapshot.samples().size(), "render snapshots must not change beneath GTK");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.samples().clear());
        assertTrue(download("empty").getSpeedHistory().samples().isEmpty());
    }

    @Test
    void longDownloadsKeepTheirBeginningAndAverageWithBoundedStorage() {
        var history = new DownloadSpeedHistory();
        for (int i = 0; i < 20_000; i++) {
            history.record(i * 1000L, i * 2500L, 2500);
        }
        var snapshot = history.snapshot();
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
    void peakSurvivesCompactionAndRestorationEvenWhenTheSpikeIsSmoothedOut() {
        var history = new DownloadSpeedHistory();
        for (int i = 0; i < 2000; i++) {
            history.record(i * 1000L, i * 2500L, i == 123 ? 50_000 : 2500);
        }
        var snapshot = history.snapshot();
        assertTrue(snapshot.samples().stream().allMatch(sample -> sample.bytesPerSecond() < 50_000),
                "the original spike must have been smoothed out of the chart samples");
        assertEquals(50_000, snapshot.maxBytesPerSecond());
        var restored = new DownloadSpeedHistory();
        restored.restore(history.state());
        assertEquals(snapshot, restored.snapshot());
        restored.record(2_001_000, 5_000_000, 1000);
        assertEquals(50_000, restored.snapshot().maxBytesPerSecond());
        restored.record(2_002_000, 5_001_000, 60_000);
        assertEquals(60_000, restored.snapshot().maxBytesPerSecond());
    }

    @Test
    void restartingFromEarlierBytesOrElapsedTimeClearsThePreviousAttempt() {
        var history = new DownloadSpeedHistory();
        history.record(1000, 5000, 5000);
        history.record(2000, 9000, 4000);
        history.record(3000, 100, 100);
        assertEquals(1, history.snapshot().samples().size());
        assertEquals(100, history.snapshot().averageBytesPerSecond());
        assertEquals(100, history.snapshot().maxBytesPerSecond());
        history.record(0, 200, 200);
        assertEquals(1, history.snapshot().samples().size());
        assertEquals(200, history.snapshot().averageBytesPerSecond());
        assertEquals(200, history.snapshot().maxBytesPerSecond());
    }

    @Test
    void resettingTheByteCounterClearsHistoryBeforeTheNextProgressEvent() {
        Download download = download("reset");
        download.setDownloaded(5000);
        download.recordSpeedSample(5000, 5000);
        download.setDownloaded(0);
        assertTrue(download.getSpeedHistory().samples().isEmpty());
        assertEquals(0, download.getSpeedHistory().maxBytesPerSecond());
        assertNull(download.getSpeedHistoryState());
    }

    @Test
    void idleStatesAndInvalidTelemetryDoNotAddBogusSamples() {
        Download download = download("idle");
        download.recordSpeedSample(100, 500);
        for (Download.Status status : new Download.Status[]{Download.Status.PAUSED,
                Download.Status.SEEDING, Download.Status.COMPLETED}) {
            download.setStatus(status);
            download.recordSpeedSample(100, 0);
        }
        download.setStatus(Download.Status.DOWNLOADING);
        download.recordSpeedSample(-1, 100);
        download.recordSpeedSample(100, Double.NaN);
        download.recordSpeedSample(100, Double.POSITIVE_INFINITY);
        assertEquals(1, download.getSpeedHistory().samples().size());
        assertEquals(500, download.getSpeedHistory().averageBytesPerSecond());
    }

    @Test
    void finalProgressSurvivesAFastCompletion() {
        Download download = download("fast");
        download.setStatus(Download.Status.COMPLETED);
        download.recordSpeedSample(1024, 1024);
        assertEquals(1024, download.getSpeedHistory().averageBytesPerSecond());
    }

    @Test
    void multipleUpdatesAtTheSameInstantDoNotInventElapsedTime() {
        var history = new DownloadSpeedHistory();
        history.record(1000, 100, 100);
        history.record(1000, 200, 300);
        assertEquals(1, history.snapshot().samples().size());
        assertEquals(300, history.snapshot().averageBytesPerSecond());
        history.record(2000, 500, 300);
        assertEquals(300, history.snapshot().averageBytesPerSecond());
    }

    @Test
    void replacingASampleAtTheSameInstantKeepsTheHigherObservedSpeed() {
        var history = new DownloadSpeedHistory();
        history.record(1000, 100, 5000);
        history.record(1000, 200, 1000);
        assertEquals(1, history.snapshot().samples().size());
        assertEquals(1000, history.snapshot().samples().getFirst().bytesPerSecond());
        assertEquals(5000, history.snapshot().maxBytesPerSecond());
    }

    @Test
    void restoreKeepsOriginalAverageTotalsAfterCompactionAndStartsANewObservedInterval() {
        var original = new DownloadSpeedHistory();
        for (int i = 0; i < 2000; i++) {
            original.record(i * 1000L, i * 5000L, i % 7 * 1000);
        }
        var state = original.state();
        var restored = new DownloadSpeedHistory();
        restored.restore(state);
        assertEquals(original.snapshot(), restored.snapshot());

        // No speed can be inferred for the gap while ODM was not observing.
        restored.record(9_000_000, 10_000_000, 4000);
        assertEquals(state.speedMillis(), restored.state().speedMillis());
        assertEquals(state.durationMillis(), restored.state().durationMillis());
        restored.record(9_002_000, 10_010_000, 6000);
        assertEquals((state.speedMillis() + 10_000_000) / (state.durationMillis() + 2000),
                restored.snapshot().averageBytesPerSecond(), 0.0001);
    }

    @Test
    void resumingAfterPauseOrSeedingDoesNotIntegrateTheUnobservedGap() {
        for (Download.Status idle : new Download.Status[]{Download.Status.PAUSED, Download.Status.SEEDING}) {
            Download download = download(idle.name());
            download.setStatus(Download.Status.DOWNLOADING);
            download.recordSpeedSample(1000, 1000);
            download.setActiveElapsedMillis(1000);
            download.recordSpeedSample(2000, 1000);
            var before = download.getSpeedHistoryState();
            download.setStatus(idle);
            download.setActiveElapsedMillis(1_000_000);
            download.setStatus(Download.Status.DOWNLOADING);
            download.recordSpeedSample(3000, 5000);
            assertEquals(before.speedMillis(), download.getSpeedHistoryState().speedMillis());
            assertEquals(before.durationMillis(), download.getSpeedHistoryState().durationMillis());
        }
    }

    private static Download download(String name) {
        return new Download(URI.create("https://example.com/" + name));
    }
}
