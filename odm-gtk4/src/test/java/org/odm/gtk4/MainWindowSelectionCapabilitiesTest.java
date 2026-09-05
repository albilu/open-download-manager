package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.aria2.Aria2Settings;
import org.httrack.HttrackSettings;
import org.manager.download.Download;

class MainWindowSelectionCapabilitiesTest {

    @TempDir
    Path tempDir;

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
    void recheckDataRequiresLiveDirectAria2TaskAndIntegrityMetadata() {
        Download magnet = download("payload", Download.Status.DOWNLOADING);
        magnet.setUri(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        magnet.setGid("live-magnet");
        magnet.setSettings(new Aria2Settings());
        assertTrue(MainWindow.canRecheckData(magnet));

        magnet.setStatus(Download.Status.COMPLETED);
        assertFalse(MainWindow.canRecheckData(magnet),
                "a completed aria2 GID has already been retired");

        Download http = download("archive.iso", Download.Status.DOWNLOADING);
        http.setGid("live-http");
        http.setSettings(new Aria2Settings());
        assertFalse(MainWindow.canRecheckData(http),
                "ordinary HTTP data has nothing authoritative to verify against");
        http.setChecksumAlgorithm("sha256");
        http.setExpectedChecksum("00".repeat(32));
        assertTrue(MainWindow.canRecheckData(http));

        http.setGid(null);
        assertFalse(MainWindow.canRecheckData(http));
    }

    @Test
    void recheckDataExcludesProxychainsAndUnsupportedEngines() {
        Download routed = download("payload", Download.Status.DOWNLOADING);
        routed.setUri(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        routed.setGid("proxychains-process-id");
        routed.setSettings(new Aria2Settings());
        routed.setType(Download.Type.PROXYCHAINS);

        assertFalse(MainWindow.canRecheckData(routed),
                "proxychains owns a separate aria2 CLI process, not an RPC GID");

        for (Download.Type type : Download.Type.values()) {
            if (type == Download.Type.ARIA2) {
                continue;
            }
            routed.setType(type);
            assertFalse(MainWindow.canRecheckData(routed),
                    () -> type + " must not expose the aria2-only command");
        }
    }

    @Test
    void completedHttrackMirrorExposesUpdateAndAvailableDiagnostics() throws Exception {
        Path mirror = tempDir.resolve("example.test");
        Files.createDirectories(mirror.resolve("hts-cache"));
        Files.writeString(mirror.resolve("hts-log.txt"), "mirror log");
        Download website = new Download(URI.create("https://example.test/"));
        website.setName("example.test");
        website.setDestination(tempDir);
        website.setType(Download.Type.WEBSITE_SCRAPING);
        website.setSettings(new HttrackSettings());
        website.setStatus(Download.Status.COMPLETED);
        website.recordOutputPath(mirror);

        MainWindow.DownloadSelectionCapabilities capabilities =
                MainWindow.selectionCapabilities(List.of(website));

        assertTrue(capabilities.updateMirror());
        assertTrue(capabilities.openHttrackLog());
        assertFalse(capabilities.openHttrackErrorLog());
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

    @Test
    void downloadListStatusPrioritizesTheSelectionCount() {
        assertEquals("1 download selected",
                MainWindow.downloadListStatusText(1, 500, 1_200));
        assertEquals("3 downloads selected",
                MainWindow.downloadListStatusText(3, 500, 1_200));
        assertEquals("500 of 1200 download(s) loaded",
                MainWindow.downloadListStatusText(0, 500, 1_200));
        assertEquals("12 download(s)",
                MainWindow.downloadListStatusText(0, 12, 12));
    }

    @Test
    void queueMovementSupportsMultiSelectionAndRemainsBoundaryAware() {
        Download first = download("first.bin", Download.Status.QUEUED);
        Download middle = download("middle.bin", Download.Status.QUEUED);
        Download last = download("last.bin", Download.Status.QUEUED);
        first.setQueuePosition(1);
        middle.setQueuePosition(2);
        last.setQueuePosition(3);
        List<Download> queue = List.of(last, first, middle);

        MainWindow.QueueMovementCapabilities firstCapabilities =
                MainWindow.queueMovementCapabilities(List.of(first), queue);
        assertFalse(firstCapabilities.up());
        assertFalse(firstCapabilities.top());
        assertTrue(firstCapabilities.down());
        assertTrue(firstCapabilities.bottom());

        MainWindow.QueueMovementCapabilities middleCapabilities =
                MainWindow.queueMovementCapabilities(List.of(middle), queue);
        assertTrue(middleCapabilities.up());
        assertTrue(middleCapabilities.top());
        assertTrue(middleCapabilities.down());
        assertTrue(middleCapabilities.bottom());

        MainWindow.QueueMovementCapabilities lastCapabilities =
                MainWindow.queueMovementCapabilities(List.of(last), queue);
        assertTrue(lastCapabilities.up());
        assertTrue(lastCapabilities.top());
        assertFalse(lastCapabilities.down());
        assertFalse(lastCapabilities.bottom());

        Download paused = download("paused.bin", Download.Status.PAUSED);
        assertEquals(MainWindow.QueueMovementCapabilities.NONE,
                MainWindow.queueMovementCapabilities(List.of(paused), queue));

        MainWindow.QueueMovementCapabilities leadingGroup =
                MainWindow.queueMovementCapabilities(List.of(first, middle), queue);
        assertFalse(leadingGroup.up());
        assertFalse(leadingGroup.top());
        assertTrue(leadingGroup.down());
        assertTrue(leadingGroup.bottom());

        MainWindow.QueueMovementCapabilities trailingGroup =
                MainWindow.queueMovementCapabilities(List.of(middle, last), queue);
        assertTrue(trailingGroup.up());
        assertTrue(trailingGroup.top());
        assertFalse(trailingGroup.down());
        assertFalse(trailingGroup.bottom());

        MainWindow.QueueMovementCapabilities splitGroup =
                MainWindow.queueMovementCapabilities(List.of(first, last), queue);
        assertTrue(splitGroup.up());
        assertTrue(splitGroup.down());
        assertEquals(MainWindow.QueueMovementCapabilities.NONE,
                MainWindow.queueMovementCapabilities(List.of(first, paused), queue));
    }

    @Test
    void multiQueueMovesInvokeSingleRecordOperationsWithoutReversingSelection() {
        Download first = download("first.bin", Download.Status.QUEUED);
        Download middle = download("middle.bin", Download.Status.QUEUED);
        Download last = download("last.bin", Download.Status.QUEUED);
        first.setQueuePosition(1);
        middle.setQueuePosition(2);
        last.setQueuePosition(3);
        List<Download> queue = List.of(last, first, middle);
        List<Download> selection = List.of(middle, last);

        assertEquals(List.of(middle, last), MainWindow.queueMoveTargets(
                selection, queue, MainWindow.QueueMove.UP));
        assertEquals(List.of(last, middle), MainWindow.queueMoveTargets(
                selection, queue, MainWindow.QueueMove.TOP));
        assertEquals(List.of(), MainWindow.queueMoveTargets(
                selection, queue, MainWindow.QueueMove.DOWN));
        assertEquals(List.of(), MainWindow.queueMoveTargets(
                selection, queue, MainWindow.QueueMove.BOTTOM));

        List<Download> leadingSelection = List.of(first, middle);
        assertEquals(List.of(middle, first), MainWindow.queueMoveTargets(
                leadingSelection, queue, MainWindow.QueueMove.DOWN));
        assertEquals(List.of(first, middle), MainWindow.queueMoveTargets(
                leadingSelection, queue, MainWindow.QueueMove.BOTTOM));
    }

    private static Download download(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.com/" + name));
        download.setName(name);
        download.setDestination(Path.of("/tmp/downloads"));
        download.setStatus(status);
        return download;
    }
}
