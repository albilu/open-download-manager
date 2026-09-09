package org.manager.download;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.perf.PerfReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks core domain-model throughput: download construction plus
 * protocol classification, the per-item cost of imports and history rebuilds.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Download model performance")
class DownloadModelPerfTest {

    @Test
    @DisplayName("Create and classify 10000 downloads")
    void createAndClassify() {
        int count = PerfReporter.scale(10_000);
        // Warm up URI parsing and the classification switch.
        for (int i = 0; i < 500; i++) {
            new Download(URI.create("https://example.com/warm-" + i + ".zip"));
        }
        long start = System.nanoTime();
        int classified = 0;
        for (int i = 0; i < count; i++) {
            Download download = new Download(URI.create("https://example.com/file-" + i + ".zip"));
            assertNotNull(download.getId());
            if (Download.Protocol.fromUri(download.getUri()) != null) {
                classified++;
            }
        }
        long elapsed = System.nanoTime() - start;

        assertEquals(count, classified);
        PerfReporter.report("DownloadModel", "create-classify", count, elapsed, "");
        assertTrue(elapsed < 30_000_000_000L, "creation took " + elapsed / 1_000_000 + "ms");
    }
}
