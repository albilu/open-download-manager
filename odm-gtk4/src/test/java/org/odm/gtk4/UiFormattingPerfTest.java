package org.odm.gtk4;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.download.Download;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks per-row cell formatting: the human-size, rate, ETA, elapsed and
 * percentage calls that {@code DownloadListPresenter.refresh} executes for
 * every visible row on each progress tick. Hermetic: no display required.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("UI formatting performance")
class UiFormattingPerfTest {

    @Test
    @DisplayName("Human sizes and transfer rates")
    void sizesAndRates() {
        long[] values = {0, 512, 2048, 1_048_576, 157_286_400, 3_221_225_472L};
        int repeats = UiPerf.scale(20_000);
        long start = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            DownloadFormats.size(values[i % values.length]);
            DownloadFormats.rate(values[i % values.length]);
        }
        long elapsed = System.nanoTime() - start;

        assertNotNull(DownloadFormats.size(1536));
        UiPerf.report("UiFormat", "size-rate", (long) repeats * 2, elapsed, "");
        assertTrue(elapsed < 10_000_000_000L, "formatting took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Progress percentage strings and fills")
    void progressStrings() {
        double[] values = {0, 42.675, 99.995, 100, Double.NaN};
        int repeats = UiPerf.scale(20_000);
        long start = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            ProgressPresentation.percentage(values[i % values.length]);
            ProgressPresentation.wholePercentage(values[i % values.length]);
        }
        long elapsed = System.nanoTime() - start;

        UiPerf.report("UiFormat", "progress", (long) repeats * 2, elapsed, "");
        assertTrue(elapsed < 10_000_000_000L, "progress formatting took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Full per-row detail strings over 2000 downloads")
    void rowDetails() {
        List<Download> downloads = UiPerf.history(UiPerf.scale(2_000));
        downloads.forEach(d -> d.setActiveElapsedMillis(95_000));
        int repeats = 50;
        long start = System.nanoTime();
        for (int r = 0; r < repeats; r++) {
            for (Download download : downloads) {
                DownloadFormats.eta(download);
                DownloadFormats.elapsed(download);
                DownloadFormats.downloadedSize(download);
                DownloadFormats.remainingSize(download);
            }
        }
        long elapsed = System.nanoTime() - start;

        UiPerf.report("UiFormat", "row-details", (long) repeats * downloads.size() * 4, elapsed,
                "rows=" + downloads.size());
        assertTrue(elapsed < 30_000_000_000L, "row details took " + elapsed / 1_000_000 + "ms");
    }
}
