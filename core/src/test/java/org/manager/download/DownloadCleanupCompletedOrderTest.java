package org.manager.download;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

/**
 * Regression test for completed-history pruning order: the retention
 * promise is to keep recently COMPLETED entries, so ordering must use
 * completedAt (falling back to createdAt when null), not createdAt. A
 * long-running download created early but finished recently must survive
 * while a recently created but long-finished one is pruned.
 */
class DownloadCleanupCompletedOrderTest {

    private Download completed(String name, Instant createdAt, Instant completedAt) throws Exception {
        Download download = new Download(name + "-id", createdAt);
        download.setUri(new URI("http://example.test/" + name + ".bin"));
        download.setName(name);
        download.setType(Download.Type.ARIA2);
        repository.addDownload(download);
        repository.updateDownloadStatus(download, Download.Status.COMPLETED);
        download.setCompletedAt(completedAt);
        return download;
    }

    private PaginatedDownloadRepository repository;
    private DownloadCleanupManager manager;

    private void setUp() {
        GlobalSettings settings = new GlobalSettings();
        repository = new PaginatedDownloadRepository(settings);
        manager = new DownloadCleanupManager(repository, settings);
    }

    @Test
    @DisplayName("Pruning by count keeps the most recently COMPLETED, not the most recently created")
    void pruneByCountKeepsRecentlyCompleted() throws Exception {
        setUp();

        Instant now = Instant.now();
        Download finishedRecently = completed("created-early-finished-recently",
                now.minusSeconds(86_400 * 30), now.minusSeconds(60));
        Download finishedLongAgo = completed("created-recently-finished-long-ago",
                now.minusSeconds(60), now.minusSeconds(86_400 * 30));

        int removed = manager.pruneCompletedDownloadsByCount(1);

        assertEquals(1, removed, "exactly one completed download must be pruned");
        List<String> remaining = repository.getDownloadsByStatus(Download.Status.COMPLETED, 0,
                Integer.MAX_VALUE).getDownloads().stream().map(Download::getName).toList();
        assertTrue(remaining.contains(finishedRecently.getName()),
                "a download finished only a minute ago must be kept regardless of creation time");
        assertFalse(remaining.contains(finishedLongAgo.getName()),
                "a download finished a month ago must be pruned regardless of creation time");
    }

    @Test
    @DisplayName("A null completedAt falls back to createdAt for pruning order")
    void nullCompletedAtFallsBackToCreatedAt() throws Exception {
        setUp();

        Instant now = Instant.now();
        Download noCompletionTime = completed("old-creation-no-completion",
                now.minusSeconds(86_400 * 30), null);
        Download finishedRecently = completed("finished-recently",
                now.minusSeconds(86_400 * 60), now.minusSeconds(60));

        int removed = manager.pruneCompletedDownloadsByCount(1);

        assertEquals(1, removed, "exactly one completed download must be pruned");
        List<String> remaining = repository.getDownloadsByStatus(Download.Status.COMPLETED, 0,
                Integer.MAX_VALUE).getDownloads().stream().map(Download::getName).toList();
        assertTrue(remaining.contains(finishedRecently.getName()),
                "the recently finished download must be kept");
        assertFalse(remaining.contains(noCompletionTime.getName()),
                "the oldest item without a completion time must be pruned via its creation time");
    }
}
