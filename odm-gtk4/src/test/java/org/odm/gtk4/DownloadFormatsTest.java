package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;

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
    void downloadedAndTotalShareAUnitAndUseLocalizedDecimals() {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Download download = new Download(URI.create("https://example.test/file"));
            download.setDownloaded(Math.round(2.3 * 1024 * 1024 * 1024));
            download.setSize(Math.round(4.15 * 1024 * 1024 * 1024));
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals("2.3 / 4.15 GB", DownloadFormats.downloadedSize(download));
            Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
            assertEquals("2,3 / 4,15 GB", DownloadFormats.downloadedSize(download));
            download.setDownloaded(512 * 1024 * 1024);
            assertEquals("0,5 / 4,15 GB", DownloadFormats.downloadedSize(download));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void downloadedAndTotalKeepUnknownTotalsAndEmptyFilesDistinct() {
        Download download = new Download(URI.create("https://example.test/file"));
        assertEquals("0 / 0 B", DownloadFormats.downloadedSize(download));
        download.setDownloaded(4096);
        assertEquals("4 / — KB", DownloadFormats.downloadedSize(download));
        download.setSize(8192);
        assertEquals("4 / 8 KB", DownloadFormats.downloadedSize(download));
        download.setType(Download.Type.WEBSITE_SCRAPING);
        download.setSize(0);
        download.setDownloaded(0);
        assertEquals("0 / — B", DownloadFormats.downloadedSize(download));
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
    @Test
    void unknownWebsiteTotalsStayUnknownWhileKnownBytesAndEmptyFilesRemainSizes() {
        Download website = new Download(URI.create("https://example.test/site"));
        website.setType(Download.Type.WEBSITE_SCRAPING);
        website.setDownloaded(4096);
        assertEquals("—", DownloadFormats.totalSize(website));
        assertEquals("—", DownloadFormats.remainingSize(website));
        website.setSize(8192);
        assertEquals("8 KB", DownloadFormats.totalSize(website));
        assertEquals("4 KB", DownloadFormats.remainingSize(website));
        Download empty = new Download(URI.create("https://example.test/empty.bin"));
        assertEquals("0 B", DownloadFormats.totalSize(empty));
    }

    @Test
    void sizesRenderTerabytes() {
        assertEquals("1.00 TB", DownloadFormats.size(1099511627776L));
        assertEquals("1.50 TB", DownloadFormats.size(1649267441664L));
        assertEquals("2.00 TB", DownloadFormats.size(2L * 1024 * 1024 * 1024 * 1024));
    }

    @Test
    void downloadedAndTotalShareTerabyteUnit() {
        Download download = new Download(URI.create("https://example.test/big"));
        download.setDownloaded(1L * 1024 * 1024 * 1024 * 1024);
        download.setSize(2L * 1024 * 1024 * 1024 * 1024);
        assertEquals("1 / 2 TB", DownloadFormats.downloadedSize(download));
    }

    @Test
    void ratesRenderTerabytesPerSecond() {
        assertEquals("2.00 TB/s", DownloadFormats.rate(2L * 1024 * 1024 * 1024 * 1024));
    }

}
