package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

/**
 * Plain unit tests for the GTK-free list logic: filter matching, status
 * and category counts, and the row-structure comparison that decides
 * in-place updates vs full rebuilds.
 */
class DownloadListPresenterTest {

    private static Download download(String name, Download.Status status) {
        try {
            Download d = new Download(new URI("https://example.com/" + name));
            d.setName(name);
            d.setStatus(status);
            return d;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void countsByStatusFilterClass() {
        List<Download> downloads = List.of(
                download("new.zip", Download.Status.CREATED),
                download("starting.zip", Download.Status.STARTING),
                download("a.zip", Download.Status.DOWNLOADING),
                download("b.zip", Download.Status.CONNECTING),
                download("c.zip", Download.Status.QUEUED),
                download("d.zip", Download.Status.PAUSED),
                download("e.zip", Download.Status.COMPLETED),
                download("f.zip", Download.Status.ERROR),
                download("g.zip", Download.Status.CANCELED));

        // index-aligned with STATUS_FILTERS[1..]: active, queuing, finished, deleted
        assertArrayEquals(new int[]{3, 3, 1, 2},
                DownloadListPresenter.computeCounts(downloads));
    }

    @Test
    void categoryOfMapsExtensionsLikeTheOriginalUi() {
        assertEquals("Videos", DownloadListPresenter.categoryOf(download("movie.mp4", Download.Status.QUEUED)));
        assertEquals("Audios", DownloadListPresenter.categoryOf(download("song.flac", Download.Status.QUEUED)));
        assertEquals("Photos", DownloadListPresenter.categoryOf(download("pic.PNG", Download.Status.QUEUED)));
        assertEquals("Programs", DownloadListPresenter.categoryOf(download("app.AppImage", Download.Status.QUEUED)));
        assertEquals("Others", DownloadListPresenter.categoryOf(download("archive.tar.gz", Download.Status.QUEUED)));
        assertEquals("Others", DownloadListPresenter.categoryOf(download("noextension", Download.Status.QUEUED)));
        Download unnamed = download("x", Download.Status.QUEUED);
        unnamed.setName(null);
        assertEquals("Others", DownloadListPresenter.categoryOf(unnamed));
    }

    @Test
    void categoryCountsIncludeTheAllBucket() {
        List<Download> downloads = List.of(
                download("movie.mp4", Download.Status.COMPLETED),
                download("song.mp3", Download.Status.COMPLETED),
                download("data.bin", Download.Status.COMPLETED));

        assertArrayEquals(new int[]{3, 1, 1, 0, 0, 1},
                DownloadListPresenter.computeCategoryCounts(downloads));
    }

    @Test
    void matchesFiltersCombinesSearchCategoryAndStatus() {
        Download movie = download("Holiday-Movie.mp4", Download.Status.DOWNLOADING);

        assertTrue(DownloadListPresenter.matchesFilters(movie, "", "All", "All Status"));
        assertTrue(DownloadListPresenter.matchesFilters(movie, "holiday", "All", "Active"));
        // search is case-insensitive substring over the name
        assertFalse(DownloadListPresenter.matchesFilters(movie, "nomatch", "All", "All Status"));
        // wrong category excludes even when search matches
        assertFalse(DownloadListPresenter.matchesFilters(movie, "", "Audios", "All Status"));
        // Queuing covers CREATED/QUEUED/PAUSED, while CONNECTING is active
        assertFalse(DownloadListPresenter.matchesFilters(movie, "", "All", "Queuing"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.PAUSED), "", "All", "Queuing"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.CONNECTING), "", "All", "Active"));
        // Deleted covers ERROR and CANCELED
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.CANCELED), "", "All", "Deleted"));
        // Finished covers COMPLETED only
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.COMPLETED), "", "All", "Finished"));
        // a download without a name never matches a search
        Download unnamed = download("y", Download.Status.DOWNLOADING);
        unnamed.setName(null);
        assertFalse(DownloadListPresenter.matchesFilters(unnamed, "anything", "All", "All Status"));
    }

    @Test
    void rowStructureMatchesComparesIdSequencesNullSafe() {
        Download a = download("a.zip", Download.Status.QUEUED);
        Download b = download("b.zip", Download.Status.QUEUED);

        assertTrue(DownloadListPresenter.rowStructureMatches(List.of(a, b), List.of(a, b)));
        assertFalse(DownloadListPresenter.rowStructureMatches(List.of(a, b), List.of(b, a)));
        assertFalse(DownloadListPresenter.rowStructureMatches(List.of(a), List.of(a, b)));
        assertFalse(DownloadListPresenter.rowStructureMatches(null, List.of(a)));
        assertTrue(DownloadListPresenter.rowStructureMatches(List.of(), List.of()));
    }

    @Test
    void queuedRowsFollowQueuePositionWithoutMovingHistoryRows() {
        Download history = download("finished.zip", Download.Status.COMPLETED);
        Download later = download("later.zip", Download.Status.QUEUED);
        later.setQueuePosition(2);
        Download active = download("active.zip", Download.Status.DOWNLOADING);
        Download first = download("first.zip", Download.Status.QUEUED);
        first.setQueuePosition(1);

        List<Download> ordered = DownloadListPresenter.orderQueuedRows(
                List.of(history, later, active, first));

        assertEquals(List.of(history, first, active, later), ordered);
    }
}
