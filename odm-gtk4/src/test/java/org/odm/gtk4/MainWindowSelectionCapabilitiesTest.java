package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.aria2.Aria2Settings;
import org.manager.download.Download;

class MainWindowSelectionCapabilitiesTest {

    @Test
    void singleOnlyActionsAreDisabledForMultipleDownloads() {
        Download first = download("first.bin", Download.Status.COMPLETED);
        Download second = download("second.bin", Download.Status.COMPLETED);
        MainWindow.DownloadSelectionCapabilities capabilities =
                MainWindow.selectionCapabilities(List.of(first, second));

        assertTrue(capabilities.any());
        assertFalse(capabilities.single());
        assertFalse(capabilities.openFile());
        assertFalse(capabilities.openFolder());
        assertFalse(capabilities.copyMagnet());
        assertFalse(capabilities.changeDestination());
        assertTrue(capabilities.delete());
        assertTrue(capabilities.deleteWithFiles());
        assertTrue(capabilities.properties());
    }

    @Test
    void batchLifecycleActionsRequireEverySelectedStatusToSupportThem() {
        Download active = download("active.bin", Download.Status.DOWNLOADING);
        Download queued = download("queued.bin", Download.Status.QUEUED);
        Download paused = download("paused.bin", Download.Status.PAUSED);
        Download seeding = download("seeding.bin", Download.Status.SEEDING);

        assertTrue(MainWindow.selectionCapabilities(List.of(active, queued)).pause());
        assertFalse(MainWindow.selectionCapabilities(List.of(active, paused)).pause());
        assertTrue(MainWindow.selectionCapabilities(List.of(paused)).resume());
        assertFalse(MainWindow.selectionCapabilities(List.of(paused, queued)).resume());
        assertTrue(MainWindow.selectionCapabilities(List.of(queued)).start());
        assertFalse(MainWindow.selectionCapabilities(List.of(active)).start());
        assertTrue(MainWindow.selectionCapabilities(List.of(seeding)).pause());
        assertTrue(MainWindow.canStartOrResume(queued));
        assertTrue(MainWindow.canStartOrResume(paused));
        assertFalse(MainWindow.canStartOrResume(seeding));
    }

    @Test
    void singleActionsReflectTheSelectedDownloadData() {
        Download completed = download("ready.bin", Download.Status.COMPLETED);
        completed.setInfoHash("0123456789abcdef");
        MainWindow.DownloadSelectionCapabilities completedCapabilities =
                MainWindow.selectionCapabilities(List.of(completed));

        assertTrue(completedCapabilities.openFile());
        assertTrue(completedCapabilities.openFolder());
        assertTrue(completedCapabilities.copyMagnet());
        assertTrue(completedCapabilities.changeDestination());

        Download queued = download("queued.bin", Download.Status.QUEUED);
        MainWindow.DownloadSelectionCapabilities queuedCapabilities =
                MainWindow.selectionCapabilities(List.of(queued));
        assertFalse(queuedCapabilities.openFile());
        assertTrue(queuedCapabilities.changeDestination());

        Download active = download("active.bin", Download.Status.DOWNLOADING);
        assertTrue(MainWindow.selectionCapabilities(List.of(active)).changeDestination(),
                "an active transfer can be paused, moved, and resumed");
    }

    @Test
    void doubleClickOpensFinishedFilesAndRevealsAllOtherDownloads() {
        assertTrue(MainWindow.activationFor(download("finished.bin",
                Download.Status.COMPLETED)) == MainWindow.DownloadActivation.OPEN_FILE);
        assertTrue(MainWindow.activationFor(download("active.bin",
                Download.Status.DOWNLOADING)) == MainWindow.DownloadActivation.REVEAL_IN_FOLDER);
        assertTrue(MainWindow.activationFor(download("paused.bin",
                Download.Status.PAUSED)) == MainWindow.DownloadActivation.REVEAL_IN_FOLDER);
    }

    @Test
    void informationFolderIsAlwaysTheDisplayedBaseDestination() {
        Download torrent = download("release", Download.Status.COMPLETED);
        Path destination = Path.of("/tmp/downloads");
        torrent.recordOutputPath(destination.resolve("release/subfolder/video.mkv"));

        assertEquals(destination, MainWindow.displayedSaveFolder(torrent));
    }

    @Test
    void verifyDataRequiresLiveAria2TaskAndIntegrityMetadata() {
        Download magnet = download("payload", Download.Status.DOWNLOADING);
        magnet.setUri(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        magnet.setGid("live-magnet");
        magnet.setSettings(new Aria2Settings());
        assertTrue(MainWindow.canVerifyData(magnet));

        magnet.setStatus(Download.Status.COMPLETED);
        assertFalse(MainWindow.canVerifyData(magnet),
                "a completed aria2 GID has already been retired");

        Download http = download("archive.iso", Download.Status.DOWNLOADING);
        http.setGid("live-http");
        http.setSettings(new Aria2Settings());
        assertFalse(MainWindow.canVerifyData(http),
                "ordinary HTTP data has nothing authoritative to verify against");
        http.setExpectedChecksum("00".repeat(32));
        assertTrue(MainWindow.canVerifyData(http));

        http.setGid(null);
        assertFalse(MainWindow.canVerifyData(http));
    }

    @Test
    void historyPaginationUsesFixedFiveHundredRecordBatches() {
        assertEquals(500, MainWindow.nextHistoryFetchLimit(0, 10_000));
        assertEquals(1_000, MainWindow.nextHistoryFetchLimit(500, 10_000));
        assertEquals(10_000, MainWindow.nextHistoryFetchLimit(9_500, 10_000));
        assertEquals(1_200, MainWindow.nextHistoryFetchLimit(1_000, 1_200));

        assertFalse(MainWindow.isNearScrollBottom(100, 400, 2_000));
        assertTrue(MainWindow.isNearScrollBottom(1_550, 400, 2_000));
        assertTrue(MainWindow.isNearScrollBottom(0, 500, 400),
                "an under-filled viewport should immediately fetch another page");
    }

    private static Download download(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.com/" + name));
        download.setName(name);
        download.setDestination(Path.of("/tmp/downloads"));
        download.setStatus(status);
        return download;
    }
}
