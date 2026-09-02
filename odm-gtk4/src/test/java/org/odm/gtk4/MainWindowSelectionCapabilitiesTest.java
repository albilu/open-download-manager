package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    private static Download download(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.com/" + name));
        download.setName(name);
        download.setDestination(Path.of("/tmp/downloads"));
        download.setStatus(status);
        return download;
    }
}
