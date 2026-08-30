package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

/**
 * Regression tests for repository status-index consistency. Handlers call
 * {@code Download.setStatus} directly before the manager reindexes; the
 * repository transition must be membership-based so status queries and queue
 * progression keep working.
 */
class PaginatedDownloadRepositoryStatusTest {

    private final PaginatedDownloadRepository repository = new PaginatedDownloadRepository(new GlobalSettings());

    @Test
    void handlerFirstStatusWriteIsRepairedByRepositoryTransition() {
        Download download = newDownload();
        repository.addDownload(download); // indexed as QUEUED

        // The bug: handler completes the download before the manager reindexes
        download.setStatus(Download.Status.COMPLETED);

        // The manager's listener then reindexes; previously a no-op (old == new)
        repository.updateDownloadStatus(download, Download.Status.COMPLETED);

        assertEquals(List.of(download), byStatus(Download.Status.COMPLETED));
        assertTrue(byStatus(Download.Status.QUEUED).isEmpty(),
                "id must not linger in the QUEUED index after completion");
    }

    @Test
    void startTransitionMovesIdFromQueuedToDownloading() {
        Download download = newDownload();
        repository.addDownload(download);

        repository.updateDownloadStatus(download, Download.Status.DOWNLOADING);

        assertEquals(List.of(download), byStatus(Download.Status.DOWNLOADING));
        assertTrue(byStatus(Download.Status.QUEUED).isEmpty());
    }

    @Test
    void transitionIsIdempotent() {
        Download download = newDownload();
        repository.addDownload(download);
        repository.updateDownloadStatus(download, Download.Status.DOWNLOADING);
        repository.updateDownloadStatus(download, Download.Status.DOWNLOADING);

        assertEquals(List.of(download), byStatus(Download.Status.DOWNLOADING));
        assertEquals(1, byStatus(Download.Status.DOWNLOADING).size());
    }

    @Test
    void staleCompletedHeadNoLongerAppearsInQueuedQueries() {
        Download head = newDownload();
        Download queued = newDownload();
        repository.addDownload(head);
        repository.addDownload(queued);

        // Simulate the stale-head scenario that used to block queue progression
        head.setStatus(Download.Status.COMPLETED);
        repository.updateDownloadStatus(head, Download.Status.COMPLETED);

        List<Download> queuedNow = byStatus(Download.Status.QUEUED);
        assertEquals(1, queuedNow.size());
        assertEquals(queued.getId(), queuedNow.get(0).getId());
    }

    private Download newDownload() {
        Download download = new Download(URI.create("https://example.com/file-" + System.nanoTime() + ".bin"));
        download.setName("file.bin");
        download.setStatus(Download.Status.QUEUED);
        return download;
    }

    private List<Download> byStatus(Download.Status status) {
        return repository.getDownloadsByStatus(status, 0, Integer.MAX_VALUE).getDownloads();
    }
}
