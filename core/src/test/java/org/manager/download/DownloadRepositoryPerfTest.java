package org.manager.download;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.GlobalSettings;
import org.manager.perf.PerfReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks large-history repository behaviour: bulk add throughput plus
 * cold vs. cached paginated and status-filtered queries over 5,000 items.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Download repository performance")
class DownloadRepositoryPerfTest {

    private static final int HISTORY = PerfReporter.scale(5_000);
    private static final int PAGE_SIZE = 50;

    private PaginatedDownloadRepository repositoryWithHistory() throws Exception {
        PaginatedDownloadRepository repository =
                new PaginatedDownloadRepository(new GlobalSettings());
        for (int i = 0; i < HISTORY; i++) {
            Download download = new Download(new URI("http://example.test/history-" + i + ".bin"));
            download.setName("history-" + i);
            repository.addDownload(download);
            if ((i & 1) == 0) {
                repository.updateDownloadStatus(download, Download.Status.COMPLETED);
            }
        }
        return repository;
    }

    @Test
    @DisplayName("Bulk add 5000 downloads")
    void bulkAdd() throws Exception {
        PaginatedDownloadRepository repository =
                new PaginatedDownloadRepository(new GlobalSettings());
        long start = System.nanoTime();
        for (int i = 0; i < HISTORY; i++) {
            Download download = new Download(new URI("http://example.test/bulk-" + i + ".bin"));
            download.setName("bulk-" + i);
            repository.addDownload(download);
        }
        long elapsed = System.nanoTime() - start;

        assertEquals(HISTORY, repository.getTotalCount());
        PerfReporter.report("Repository", "bulk-add", HISTORY, elapsed, "history=" + HISTORY);
        assertTrue(elapsed < 60_000_000_000L, "bulk add took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Paginated all-query cold then cached")
    void paginatedAllQuery() throws Exception {
        PaginatedDownloadRepository repository = repositoryWithHistory();

        // Cold pass across distinct pages (fills the query cache with misses).
        int pages = 40;
        long coldStart = System.nanoTime();
        for (int page = 0; page < pages; page++) {
            PaginatedDownloadRepository.DownloadPage result =
                    repository.getAllDownloads(page, PAGE_SIZE);
            assertEquals(HISTORY, result.getTotalCount());
        }
        long coldElapsed = System.nanoTime() - coldStart;
        PerfReporter.report("Repository", "page-all-cold", pages, coldElapsed,
                "history=" + HISTORY + " pageSize=" + PAGE_SIZE);

        // Hot pass on one page: every repeat should be a cache hit.
        repository.resetCacheMetrics();
        int repeats = 5_000;
        long hotStart = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            PaginatedDownloadRepository.DownloadPage result =
                    repository.getAllDownloads(0, PAGE_SIZE);
            assertEquals(HISTORY, result.getTotalCount());
        }
        long hotElapsed = System.nanoTime() - hotStart;
        Map<String, Object> stats = repository.getCacheStats();
        PerfReporter.report("Repository", "page-all-cached", repeats, hotElapsed,
                "hitRate=" + stats.get("hitRate") + " history=" + HISTORY);

        assertTrue(coldElapsed < 30_000_000_000L, "cold pages took " + coldElapsed / 1_000_000 + "ms");
        assertTrue(hotElapsed < 30_000_000_000L, "cached pages took " + hotElapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Status-filtered query throughput")
    void statusFilteredQuery() throws Exception {
        PaginatedDownloadRepository repository = repositoryWithHistory();

        int repeats = 2_000;
        long start = System.nanoTime();
        for (int i = 0; i < repeats; i++) {
            PaginatedDownloadRepository.DownloadPage result = repository
                    .getDownloadsByStatus(Download.Status.COMPLETED, 0, PAGE_SIZE);
            assertEquals(HISTORY / 2, result.getTotalCount());
        }
        long elapsed = System.nanoTime() - start;

        PerfReporter.report("Repository", "page-by-status", repeats, elapsed,
                "history=" + HISTORY + " pageSize=" + PAGE_SIZE);
        assertTrue(elapsed < 30_000_000_000L, "status queries took " + elapsed / 1_000_000 + "ms");
    }
}
