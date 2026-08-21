package org.manager.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.GlobalSettings;

/**
 * Concurrency contract for the repository query cache: read-path cache
 * accesses run under only the read lock, so two concurrent readers used to
 * mutate the access-order LinkedHashMap simultaneously (get relinks, put
 * inserts and evicts). That corrupts the internal linked list — lost cache
 * entries, wrong query results, or infinite loops that later hang any
 * writer holding the write lock. Hammering readers with more distinct cache
 * keys than the cache capacity must stay correct and terminate.
 */
@DisplayName("Repository query cache survives concurrent readers with eviction churn")
class PaginatedDownloadRepositoryCacheConcurrencyTest {

    private static final int READERS = 8;
    private static final int ITERATIONS = 20_000;
    /** More distinct page sizes than MAX_CACHE_SIZE (100) to force eviction. */
    private static final int DISTINCT_KEYS = 150;

    @Test
    @Timeout(value = 60)
    @DisplayName("Concurrent paginated queries return consistent results and terminate")
    void concurrentReadersStayConsistentUnderEviction() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        PaginatedDownloadRepository repository = new PaginatedDownloadRepository(settings);

        final int total = 50;
        final int completed = 30;
        for (int i = 0; i < total; i++) {
            Download download = new Download(new URI("http://example.test/cache-" + i + ".bin"));
            download.setName("cache-" + i);
            repository.addDownload(download);
            if (i < completed) {
                repository.updateDownloadStatus(download, Download.Status.COMPLETED);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(READERS);
        try {
            List<Future<Void>> workers = new ArrayList<>();
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < READERS; t++) {
                final int seed = t;
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        int pageSize = 1 + ((seed * 31 + i) % DISTINCT_KEYS);

                        PaginatedDownloadRepository.DownloadPage all =
                                repository.getAllDownloads(0, pageSize);
                        assertEquals(total, all.getTotalCount(),
                                "all-query total must stay consistent (corrupted cache)");

                        PaginatedDownloadRepository.DownloadPage byStatus =
                                repository.getDownloadsByStatus(Download.Status.COMPLETED, 0, pageSize);
                        assertEquals(completed, byStatus.getTotalCount(),
                                "status-query total must stay consistent (corrupted cache)");
                    }
                    return null;
                }));
            }
            start.countDown();

            for (Future<Void> worker : workers) {
                worker.get(50, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // A corrupted access-order list tends to surface as a hang or
        // ConcurrentModificationException in the next write-locked
        // invalidation, which iterates the cache key set.
        for (int i = 0; i < 5; i++) {
            Download extra = new Download(new URI("http://example.test/after-" + i + ".bin"));
            extra.setName("after-" + i);
            repository.addDownload(extra);
        }
        assertEquals(total + 5, repository.getAllDownloads(0, Integer.MAX_VALUE).getTotalCount());
        assertTrue(repository.getTotalCount() == total + 5);
    }
}
