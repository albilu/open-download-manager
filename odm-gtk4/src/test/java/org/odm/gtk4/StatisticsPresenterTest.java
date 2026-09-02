package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

/**
 * Plain unit tests for the statistics aggregation.
 */
class StatisticsPresenterTest {

    private static Download download(String name, Download.Status status, long size, long done) {
        try {
            Download d = new Download(new URI("https://example.com/" + name));
            d.setName(name);
            d.setStatus(status);
            d.setSize(size);
            d.setDownloaded(done);
            return d;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void emptyListAggregatesToZero() {
        StatisticsPresenter.Stats stats = StatisticsPresenter.aggregate(List.of());

        assertEquals(0, stats.total());
        assertEquals(0, stats.active());
        assertEquals(0, stats.queued());
        assertEquals(0, stats.finished());
        assertEquals(0, stats.errors());
        assertEquals(0, stats.totalSize());
        assertEquals(0, stats.doneSize());
    }

    @Test
    void aggregatesStatusClassesAndSizes() {
        StatisticsPresenter.Stats stats = StatisticsPresenter.aggregate(List.of(
                download("a.zip", Download.Status.DOWNLOADING, 100, 40),
                download("b.zip", Download.Status.CONNECTING, 50, 0),
                download("seed.iso", Download.Status.SEEDING, 100, 100),
                download("c.zip", Download.Status.QUEUED, 10, 0),
                download("d.zip", Download.Status.PAUSED, 10, 5),
                download("e.zip", Download.Status.COMPLETED, 200, 200),
                download("f.zip", Download.Status.ERROR, 30, 10),
                download("g.zip", Download.Status.CANCELED, 30, 0)));

        assertEquals(8, stats.total());
        assertEquals(3, stats.active());
        assertEquals(2, stats.queued());
        assertEquals(1, stats.finished());
        assertEquals(2, stats.errors());
        assertEquals(530, stats.totalSize());
        assertEquals(355, stats.doneSize());
    }
}
