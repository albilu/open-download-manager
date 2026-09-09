package org.odm.gtk4;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.download.Download;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the GTK-free list paths behind the download view: search /
 * category / status filtering, sidebar counts, queue ordering and the
 * structure checks that decide in-place updates vs. full store rebuilds.
 * Hermetic: no display required.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("List filter and sort performance")
class ListFilterPerfTest {

    @Test
    @DisplayName("Filter 5000 rows with no restrictions")
    void filterAll() {
        List<Download> downloads = UiPerf.history(UiPerf.scale(5_000));
        for (int i = 0; i < 200; i++) {
            DownloadListPresenter.matchesFilters(downloads.get(i), "", "All", "All Status");
        }
        int matched = 0;
        long start = System.nanoTime();
        for (Download download : downloads) {
            if (DownloadListPresenter.matchesFilters(download, "", "All", "All Status")) {
                matched++;
            }
        }
        long elapsed = System.nanoTime() - start;

        assertTrue(matched == downloads.size());
        UiPerf.report("ListFilter", "match-all", downloads.size(), elapsed, "matched=" + matched);
        assertTrue(elapsed < 10_000_000_000L, "filter took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Filter 5000 rows by search text, category and status")
    void filterSearchCategoryStatus() {
        List<Download> downloads = UiPerf.history(UiPerf.scale(5_000));
        int repeats = 20;
        int matched = 0;
        long start = System.nanoTime();
        for (int r = 0; r < repeats; r++) {
            for (Download download : downloads) {
                if (DownloadListPresenter.matchesFilters(download, "movie", "Videos", "Active")) {
                    matched++;
                }
            }
        }
        long elapsed = System.nanoTime() - start;

        assertTrue(matched > 0);
        UiPerf.report("ListFilter", "match-search-category-status", (long) repeats * downloads.size(),
                elapsed, "matchedPerPass=" + matched / repeats);
        assertTrue(elapsed < 30_000_000_000L, "filtered passes took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Sidebar status and category counts over 5000 rows")
    void sidebarCounts() {
        List<Download> downloads = UiPerf.history(UiPerf.scale(5_000));
        int repeats = 200;
        long start = System.nanoTime();
        int[] counts = null;
        int[] categories = null;
        for (int r = 0; r < repeats; r++) {
            counts = DownloadListPresenter.computeCounts(downloads);
            categories = DownloadListPresenter.computeCategoryCounts(downloads);
        }
        long elapsed = System.nanoTime() - start;

        assertTrue(counts != null && counts.length == 7);
        assertTrue(categories != null && categories.length == DownloadListPresenter.CATEGORIES.length);
        UiPerf.report("ListFilter", "sidebar-counts", repeats, elapsed, "rows=" + downloads.size());
        assertTrue(elapsed < 10_000_000_000L, "counts took " + elapsed / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Queue ordering and structure checks over 5000 rows")
    void queueOrderAndStructure() {
        List<Download> downloads = UiPerf.history(UiPerf.scale(5_000));
        int repeats = 100;
        long orderStart = System.nanoTime();
        List<Download> ordered = null;
        for (int r = 0; r < repeats; r++) {
            ordered = DownloadListPresenter.orderQueuedRows(downloads);
        }
        long orderElapsed = System.nanoTime() - orderStart;
        UiPerf.report("ListFilter", "queue-order", repeats, orderElapsed,
                "rows=" + downloads.size());

        long structStart = System.nanoTime();
        for (int r = 0; r < 5_000; r++) {
            DownloadListPresenter.rowStructureMatches(downloads, ordered);
            DownloadListPresenter.rowStructureIsPrefix(downloads, ordered);
        }
        long structElapsed = System.nanoTime() - structStart;
        UiPerf.report("ListFilter", "structure-checks", 10_000, structElapsed,
                "rows=" + downloads.size());

        assertTrue(orderElapsed < 30_000_000_000L, "ordering took " + orderElapsed / 1_000_000 + "ms");
        assertTrue(structElapsed < 10_000_000_000L,
                "structure checks took " + structElapsed / 1_000_000 + "ms");
    }
}
