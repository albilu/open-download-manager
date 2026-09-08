package org.manager.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.perf.PerfReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks speed-history telemetry: sustained recording through the
 * 512-sample compaction path, snapshot cost, and state round-trips.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Speed history performance")
class SpeedHistoryPerfTest {

    @Test
    @DisplayName("Record 20000 samples through compaction")
    void recordThroughCompaction() {
        DownloadSpeedHistory history = new DownloadSpeedHistory();
        int samples = 20_000;
        long start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            history.record(i * 1000L, i * 2500L, 2500);
        }
        long elapsed = System.nanoTime() - start;

        DownloadSpeedHistory.Snapshot snapshot = history.snapshot();
        assertTrue(snapshot.samples().size() <= DownloadSpeedHistory.MAX_SAMPLES);
        assertEquals(2500, snapshot.averageBytesPerSecond(), 0.001);
        PerfReporter.report("SpeedHistory", "record-20k", samples, elapsed,
                "stored=" + snapshot.samples().size() + " max=" + DownloadSpeedHistory.MAX_SAMPLES);
        assertTrue(elapsed < 10_000_000_000L, "record took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Snapshot and state round-trip throughput")
    void snapshotAndState() {
        DownloadSpeedHistory history = new DownloadSpeedHistory();
        for (int i = 0; i < 2000; i++) {
            history.record(i * 1000L, i * 5000L, 4000);
        }
        int repeats = 5_000;
        long snapStart = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            history.snapshot();
        }
        long snapElapsed = System.nanoTime() - snapStart;
        PerfReporter.report("SpeedHistory", "snapshot", repeats, snapElapsed, "");

        long stateStart = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            DownloadSpeedHistory.State state = history.state();
            new DownloadSpeedHistory().restore(state);
        }
        long stateElapsed = System.nanoTime() - stateStart;
        PerfReporter.report("SpeedHistory", "state-roundtrip", 500, stateElapsed, "");

        assertTrue(snapElapsed < 10_000_000_000L, "snapshots took " + snapElapsed / 1_000_000 + "ms");
        assertTrue(stateElapsed < 10_000_000_000L, "state round-trips took " + stateElapsed / 1_000_000 + "ms");
    }
}
