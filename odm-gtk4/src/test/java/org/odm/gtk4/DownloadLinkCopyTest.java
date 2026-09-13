package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;

class DownloadLinkCopyTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final String MAGNET = "magnet:?xt=urn:btih:" + HASH
            + "&dn=My%20release&tr=https%3A%2F%2Ftracker.test%2Fannounce";

    @Test
    void ordinaryDownloadsCopyTheirOriginalUrls() {
        String url = "https://files.test/video%20name.mp4?token=a%2Bb#chapter";
        DownloadLinkCopy copy = DownloadLinkCopy.from(List.of(download(url)));

        assertTrue(copy.available());
        assertEquals("Copy URL", copy.label());
        assertEquals(url, copy.text());
        assertEquals("URL copied", copy.confirmation());
    }

    @Test
    void magnetCopyPreservesNameTrackersAndEscaping() {
        Download magnet = download(MAGNET);
        magnet.setInfoHash(HASH);
        DownloadLinkCopy copy = DownloadLinkCopy.from(List.of(magnet));

        assertEquals("Copy Magnet URI", copy.label());
        assertEquals(MAGNET, copy.text());
        assertEquals("Magnet URI copied", copy.confirmation());
    }

    @Test
    void torrentUsesItsHashWhenAvailableAndItsSourceBeforeMetadataArrives() {
        Download torrent = download("https://files.test/release.torrent");
        assertEquals("https://files.test/release.torrent",
                DownloadLinkCopy.from(List.of(torrent)).text());

        torrent.setInfoHash(HASH);
        DownloadLinkCopy copy = DownloadLinkCopy.from(List.of(torrent));
        assertEquals("magnet:?xt=urn:btih:" + HASH, copy.text());
        assertEquals("Copy Magnet URI", copy.label());

        torrent.setUri(null);
        assertTrue(DownloadLinkCopy.from(List.of(torrent)).available(),
                "a retained torrent hash is sufficient even without the source URI");
    }

    @Test
    void multipleUrlsRetainSelectionOrderAndOneLinePerRecord() {
        Download first = download("https://files.test/a.zip");
        Download second = download("ftp://files.test/b.zip");
        DownloadLinkCopy copy = DownloadLinkCopy.from(List.of(first, second, first));

        assertEquals("Copy URLs", copy.label());
        assertEquals(first.getUri() + "\n" + second.getUri() + "\n" + first.getUri(), copy.text());
        assertEquals("3 URLs copied", copy.confirmation());
    }

    @Test
    void multipleMagnetsAndMixedSelectionsHaveAppropriateLabels() {
        Download torrent = download("https://files.test/release.torrent");
        torrent.setInfoHash(HASH);
        Download magnet = download(MAGNET);
        DownloadLinkCopy magnets = DownloadLinkCopy.from(List.of(torrent, magnet));
        assertEquals("Copy Magnet URIs", magnets.label());
        assertEquals("magnet:?xt=urn:btih:" + HASH + "\n" + MAGNET, magnets.text());

        Download ordinary = download("https://files.test/video.mp4");
        DownloadLinkCopy mixed = DownloadLinkCopy.from(List.of(ordinary, magnet));
        assertEquals("Copy Links", mixed.label());
        assertEquals(ordinary.getUri() + "\n" + MAGNET, mixed.text());
        assertTrue(MainWindow.selectionCapabilities(List.of(ordinary, magnet)).copyLinks());
    }

    @Test
    void emptyOrIncompleteSelectionsDoNotCopyAPartialBatch() {
        assertFalse(DownloadLinkCopy.from(null).available());
        assertFalse(DownloadLinkCopy.from(List.of()).available());
        Download missing = new Download();
        List<Download> selection = List.of(download(MAGNET), missing);
        assertFalse(DownloadLinkCopy.from(selection).available());
        assertEquals("", DownloadLinkCopy.from(selection).text());
        assertFalse(MainWindow.selectionCapabilities(selection).copyLinks());
    }

    private static Download download(String uri) {
        return new Download(URI.create(uri));
    }
}
