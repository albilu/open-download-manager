package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

/**
 * Plain unit tests for the shared download formatters.
 */
class DownloadFormatsTest {

    @Test
    void sizesRenderHumanReadableUnits() {
        assertEquals("512 B", DownloadFormats.size(512));
        assertEquals("2 KB", DownloadFormats.size(2048));
        assertEquals("1.5 MB", DownloadFormats.size(1024 * 1024 + 512 * 1024));
        assertEquals("2.00 GB", DownloadFormats.size(2L * 1024 * 1024 * 1024));
    }

    @Test
    void etaNeedsSpeedRemainderAndDownloadingStatus() throws Exception {
        Download d = new Download(new URI("https://example.com/file"));
        d.setSize(1000);
        d.setDownloaded(800);
        d.setSpeed(100);
        d.setStatus(Download.Status.DOWNLOADING);

        assertEquals("2s", DownloadFormats.eta(d));

        d.setStatus(Download.Status.PAUSED);
        assertEquals("—", DownloadFormats.eta(d));

        d.setStatus(Download.Status.DOWNLOADING);
        d.setSpeed(0);
        assertEquals("—", DownloadFormats.eta(d));
    }

    @Test
    void elapsedUsesActiveTransferTimeAndNotWallClockAge() throws Exception {
        Download d = new Download("id", null);

        assertEquals("—", DownloadFormats.elapsed(d));

        Download created = new Download("id", Instant.now().minusSeconds(90));
        assertEquals("—", DownloadFormats.elapsed(created));

        created.setStartedAt(Instant.now().minusSeconds(90));
        created.setActiveElapsedMillis(90_000);
        created.setStatus(Download.Status.PAUSED);
        assertEquals("1m 30s", DownloadFormats.elapsed(created));
    }
}
