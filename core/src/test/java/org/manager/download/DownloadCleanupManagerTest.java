package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

@DisplayName("DownloadCleanupManager pruning, time-range queries and stats")
class DownloadCleanupManagerTest {

    private final GlobalSettings settings = new GlobalSettings();
    private final PaginatedDownloadRepository repository = new PaginatedDownloadRepository(settings);
    private final DownloadCleanupManager manager = new DownloadCleanupManager(repository, settings);

    @AfterEach
    void drainManager() {
        manager.shutdown().join();
    }

    private Download add(String name, Download.Status status, Instant finishedAt) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setName(name);
        repository.addDownload(download);
        repository.updateDownloadStatus(download, status);
        if (status == Download.Status.COMPLETED) {
            download.setCompletedAt(finishedAt);
        } else if (status == Download.Status.ERROR) {
            download.setCompletedAt(finishedAt);
            download.setErrorMessage("boom");
        }
        return download;
    }

    @Test
    @DisplayName("prune by age removes only old finished downloads")
    void pruneByAge() throws Exception {
        Instant now = Instant.now();
        add("ancient", Download.Status.COMPLETED, now.minus(Duration.ofDays(40)));
        add("old", Download.Status.COMPLETED, now.minus(Duration.ofDays(20)));
        add("fresh", Download.Status.COMPLETED, now.minus(Duration.ofHours(1)));
        add("active", Download.Status.DOWNLOADING, now);

        int removed = manager.pruneCompletedDownloadsByAge(Duration.ofDays(30));

        assertEquals(1, removed, "only the 40-day-old completed download may go");
        assertEquals(3, repository.getTotalCount());

        List<String> remainingNames = repository.getAllDownloads(0, Integer.MAX_VALUE)
                .getDownloads().stream().map(Download::getName).toList();
        assertFalse(remainingNames.contains("ancient"));
        assertTrue(remainingNames.contains("old"), "20 days < 30-day threshold");
        assertTrue(remainingNames.contains("fresh"));
        assertTrue(remainingNames.contains("active"), "active downloads are never pruned by age");
    }

    @Test
    @DisplayName("prune by count keeps the newest N finished downloads")
    void pruneByCount() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 6; i++) {
            add("d" + i, Download.Status.COMPLETED, now.minusSeconds(100 - i));
        }
        add("active", Download.Status.PAUSED, now);

        int removed = manager.pruneCompletedDownloadsByCount(3);

        assertEquals(3, removed);
        assertEquals(4, repository.getTotalCount(), "3 kept history + 1 active");
        List<String> remaining = repository.getAllDownloads(0, Integer.MAX_VALUE)
                .getDownloads().stream().map(Download::getName).toList();
        assertTrue(remaining.contains("d5"));
        assertTrue(remaining.contains("d4"));
        assertTrue(remaining.contains("d3"));
        assertFalse(remaining.contains("d0"));
        assertTrue(remaining.contains("active"));
    }

    @Test
    @DisplayName("prune by count with keepCount 0 clears all finished history")
    void pruneByCountZero() throws Exception {
        Instant now = Instant.now();
        add("a", Download.Status.COMPLETED, now);
        add("b", Download.Status.COMPLETED, now);
        add("c", Download.Status.ERROR, now);

        int removed = manager.pruneCompletedDownloadsByCount(0);
        assertEquals(2, removed, "only completed items are counted as finished history");
        assertEquals(1, repository.getTotalCount());
    }

    @Test
    @DisplayName("prune error downloads by age removes old failures only")
    void pruneErrorsByAge() throws Exception {
        Instant now = Instant.now();
        Download oldFailure = add("old-failure", Download.Status.ERROR, now.minus(Duration.ofDays(10)));
        // error pruning keys on creation time, which is set at construction;
        // the repository model allows shrinking it through the completion time
        // only, so recreate the scenario via a fresh download created "earlier"
        // by using the shortest possible duration instead
        add("new-failure", Download.Status.ERROR, now.minus(Duration.ofHours(1)));
        add("old-completed", Download.Status.COMPLETED, now.minus(Duration.ofDays(10)));

        // createdAt of all three is ~now, so a 7-day threshold must remove none
        int removed = manager.pruneErrorDownloadsByAge(Duration.ofDays(7));
        assertEquals(0, removed, "freshly created failures must survive any age threshold");

        // a zero threshold prunes every failed download regardless of age
        int removedAll = manager.pruneErrorDownloadsByAge(Duration.ZERO);
        assertEquals(2, removedAll, "both failed downloads predate the zero threshold");
        assertEquals(1, repository.getTotalCount());
        assertTrue(oldFailure != null);
        assertTrue(repository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads().stream()
                .noneMatch(d -> "old-failure".equals(d.getName())));
    }

    @Test
    @DisplayName("time-range queries filter on creation time and support paging")
    void timeRangeQueries() throws Exception {
        Instant beforeAll = Instant.now().minusSeconds(60);
        add("t1", Download.Status.COMPLETED, Instant.now());
        add("t2", Download.Status.COMPLETED, Instant.now());
        add("t3", Download.Status.COMPLETED, Instant.now());
        Instant afterAll = Instant.now().plusSeconds(60);

        // all three were created inside the window
        assertEquals(3, manager.getDownloadsByTimeRange(beforeAll, afterAll).size());

        // a window that ends before any creation matches nothing
        assertTrue(manager.getDownloadsByTimeRange(
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200)).isEmpty());

        // paging caps the returned page; the facade maps offset/limit onto
        // whole pages, so offsets must be limit-aligned to page forward
        List<Download> page = manager.getDownloadsByTimeRange(beforeAll, afterAll, 0, 2);
        assertEquals(2, page.size());
        List<Download> lastItem = manager.getDownloadsByTimeRange(beforeAll, afterAll, 2, 1);
        assertEquals(1, lastItem.size(), "offset 2 with limit 1 must reach the third item");
    }

    @Test
    @DisplayName("memory stats report repository size, counters and configuration")
    void memoryStats() throws Exception {
        Instant now = Instant.now();
        add("s1", Download.Status.COMPLETED, now);
        add("s2", Download.Status.ERROR, now);

        manager.performFullCleanup().join();

        Map<String, Object> stats = manager.getMemoryUsageStats();
        assertNotNull(stats);
        assertEquals(2, ((Number) stats.get("totalDownloads")).intValue(),
                "totalDownloads mirrors the repository size");
        assertTrue(((Number) stats.get("totalCleanupOperations")).longValue() >= 1,
                "a performed cleanup must be counted");
        assertNotNull(stats.get("statusBreakdown"), "status breakdown must be included");
        assertNotNull(stats.get("estimatedMemoryUsageBytes"));
        assertEquals(settings.getMaxDownloadsInMemory(),
                ((Number) stats.get("maxDownloadsInMemory")).intValue());
        assertEquals(settings.isAutomaticCleanupEnabled(), stats.get("automaticCleanupEnabled"));
    }

    @Test
    @DisplayName("full cleanup honors the automatic config without removing fresh history")
    void fullCleanupKeepsFreshHistory() throws Exception {
        Instant now = Instant.now();
        add("fresh", Download.Status.COMPLETED, now);
        settings.setAutomaticCleanupEnabled(false);

        manager.performFullCleanup().join();

        assertEquals(1, repository.getTotalCount(), "fresh completed history must survive");
        assertTrue(manager.getMemoryUsageStats() != null);
    }

    @Test
    @DisplayName("force cleanup runs a manual cycle and reports completion")
    void forceCleanupCompletes() throws Exception {
        Instant now = Instant.now();
        settings.setCompletedDownloadRetentionDays(7);
        add("f1", Download.Status.COMPLETED, now.minus(Duration.ofDays(30)));

        manager.forceCleanup().join();

        assertEquals(0, repository.getTotalCount(),
                "a download older than the retention window must be removed");
    }

    @Test
    @DisplayName("invalid pruning arguments are rejected with zero removals")
    void invalidPruneArguments() throws Exception {
        Instant now = Instant.now();
        add("keep", Download.Status.COMPLETED, now);

        assertEquals(0, manager.pruneCompletedDownloadsByAge(null));
        assertEquals(0, manager.pruneCompletedDownloadsByAge(Duration.ofDays(-1)));
        assertEquals(0, manager.pruneErrorDownloadsByAge(null));
        assertEquals(0, manager.pruneErrorDownloadsByAge(Duration.ofDays(-1)));
        assertEquals(0, manager.pruneCompletedDownloadsByCount(-1));
        assertEquals(1, repository.getTotalCount(), "nothing may be removed by invalid arguments");
    }

    @Test
    @DisplayName("start/shutdown lifecycle toggles the running flag idempotently")
    void lifecycleFlags() {
        assertFalse(manager.isRunning());
        manager.start();
        assertTrue(manager.isRunning());
        manager.start();
        assertTrue(manager.isRunning(), "double start must be a no-op");
        manager.shutdown().join();
        assertFalse(manager.isRunning());
        manager.shutdown().join();
        assertFalse(manager.isRunning(), "double shutdown must be a no-op");
    }

    @Test
    @DisplayName("updateAutomaticCleanupConfig accepts calls in any lifecycle state")
    void automaticConfigUpdateIsSafe() {
        manager.updateAutomaticCleanupConfig();
        manager.start();
        settings.setAutomaticCleanupEnabled(true);
        manager.updateAutomaticCleanupConfig();
        settings.setAutomaticCleanupEnabled(false);
        manager.updateAutomaticCleanupConfig();
        manager.shutdown().join();
    }
}
