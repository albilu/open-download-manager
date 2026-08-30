package org.manager.download;

import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

/**
 * Regression test for the explicit repository transition API: handlers
 * mutate {@code Download.status} before the manager reindexes, so the
 * caller must pass the TRUE prior status. The transition must invalidate
 * the source-status cache bucket too — a prewarmed query for the old
 * status must stop returning the download immediately, not after the
 * cache TTL.
 */
class PaginatedDownloadRepositoryTransitionTest {

    private PaginatedDownloadRepository repository;
    private Download download;

    @BeforeEach
    void setUp() throws Exception {
        repository = new PaginatedDownloadRepository(new GlobalSettings());
        download = new Download(new URI("http://example.test/transition.bin"));
        download.setName("transition.bin");
        download.setType(Download.Type.ARIA2);
        download.setStatus(Download.Status.QUEUED);
        repository.addDownload(download);
    }

    private List<String> idsByStatus(Download.Status status) {
        return repository.getDownloadsByStatus(status, 0, Integer.MAX_VALUE)
                .getDownloads().stream().map(Download::getId).toList();
    }

    @Test
    @DisplayName("Handler-first transition to PAUSED immediately drops the download from QUEUED queries")
    void handlerFirstPauseInvalidatesSourceBucket() {
        assertTrue(idsByStatus(Download.Status.QUEUED).contains(download.getId()),
                "precondition: prewarmed QUEUED query returns the download");

        download.setStatus(Download.Status.PAUSED);
        repository.transitionDownloadStatus(download, Download.Status.QUEUED, Download.Status.PAUSED);

        assertFalse(idsByStatus(Download.Status.QUEUED).contains(download.getId()),
                "the prewarmed QUEUED cache entry must be invalidated by the transition");
        assertTrue(idsByStatus(Download.Status.PAUSED).contains(download.getId()),
                "the download must be indexed under its new status");
        assertEquals(Download.Status.PAUSED, download.getStatus());
    }

    @Test
    @DisplayName("Handler-first transition to COMPLETED immediately drops the download from QUEUED queries")
    void handlerFirstCompletionInvalidatesSourceBucket() {
        assertTrue(idsByStatus(Download.Status.QUEUED).contains(download.getId()),
                "precondition: prewarmed QUEUED query returns the download");

        download.setStatus(Download.Status.COMPLETED);
        repository.transitionDownloadStatus(download, Download.Status.QUEUED, Download.Status.COMPLETED);

        assertFalse(idsByStatus(Download.Status.QUEUED).contains(download.getId()),
                "the prewarmed QUEUED cache entry must be invalidated by the transition");
        assertTrue(idsByStatus(Download.Status.COMPLETED).contains(download.getId()),
                "the download must be indexed under its new status");
    }
}
