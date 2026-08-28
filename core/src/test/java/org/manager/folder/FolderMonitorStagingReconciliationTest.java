package org.manager.folder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup reconciliation of the descriptor staging directory: orphans
 * (original gone, download never created) are re-announced; duplicates
 * (original still present) are removed; and repeated announcement failures
 * reuse one staged entry per source instead of accumulating copies.
 */
@DisplayName("Folder monitor staged-descriptor reconciliation")
class FolderMonitorStagingReconciliationTest {

    @TempDir
    Path tempDir;

    private FolderMonitorServiceImpl service;
    private Path stagingRoot;
    private Path watchFolder;

    @BeforeEach
    void setUp() throws IOException {
        stagingRoot = tempDir.resolve("descriptor-staging");
        watchFolder = tempDir.resolve("inbox");
        Files.createDirectories(watchFolder);
        service = new FolderMonitorServiceImpl(stagingRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] validTorrentBytes(String payloadName) {
        String content = "d8:announce33:http://tracker.example.com/announce4:infod6:lengthi1024e4:name"
                + payloadName.length() + ":" + payloadName
                + "12:piece lengthi32768e6:pieces20:aaaaaaaaaaaaaaaaaaaaee";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private FolderMonitorSettings torrentSettings() {
        return new FolderMonitorSettings()
                .setFileExtensions(java.util.Set.of(".torrent"))
                .setFileAction(FolderMonitorSettings.FileAction.KEEP)
                .setProcessExistingFiles(false)
                .setDebounceDelay(Duration.ofMillis(100));
    }

    @Test
    @Timeout(20)
    @DisplayName("An orphaned staged descriptor is re-announced when monitoring starts")
    void orphanStagedFileIsReAnnouncedAtStart() throws Exception {
        Path staged = stageOrphan("orphan.torrent", validTorrentBytes("orphan.bin"));

        List<Path> announced = new CopyOnWriteArrayList<>();
        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.add(filePath);
            }
        });

        service.startMonitoring(watchFolder, torrentSettings()).get(5, TimeUnit.SECONDS);

        assertFalse(announced.isEmpty(),
                "the orphaned staged descriptor must be re-announced at monitor start");
        assertTrue(announced.get(0).toAbsolutePath().normalize()
                        .startsWith(stagingRoot.toAbsolutePath().normalize()),
                "the re-announced path must be the staged copy: " + announced.get(0));
    }

    @Test
    @Timeout(20)
    @DisplayName("A staged duplicate is removed when the original still exists in the watched folder")
    void stagedDuplicateRemovedWhenOriginalStillPresent() throws Exception {
        Path original = watchFolder.resolve("dup.torrent");
        Files.write(original, validTorrentBytes("dup.bin"));
        org.manager.util.DescriptorStaging.stageFile(original, stagingRoot);

        AtomicInteger announcements = new AtomicInteger();
        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announcements.incrementAndGet();
            }
        });

        service.startMonitoring(watchFolder, torrentSettings()).get(5, TimeUnit.SECONDS);

        try (Stream<Path> entries = Files.list(stagingRoot)) {
            assertEquals(0, entries.count(),
                    "the staged duplicate must be deleted when the original still exists");
        }
        assertTrue(Files.exists(original), "the original must remain in place");
        assertEquals(0, announcements.get(),
                "a still-present original is not re-announced by reconciliation");
    }

    @Test
    @Timeout(20)
    @DisplayName("A staged descriptor already dispatched to the queue is not re-announced")
    void dispatchedStagedEntryIsNotReAnnounced() throws Exception {
        Path staged = stageOrphan("dispatched.torrent", validTorrentBytes("dispatched.bin"));
        org.manager.util.DescriptorStaging.markDispatched(staged);

        List<Path> announced = new CopyOnWriteArrayList<>();
        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.add(filePath);
            }
        });

        service.startMonitoring(watchFolder, torrentSettings()).get(5, TimeUnit.SECONDS);

        assertTrue(announced.isEmpty(),
                "a dispatched staged entry is owned by the download queue and must not be re-announced");
        assertTrue(Files.exists(staged), "the dispatched entry must remain for ingestion");
    }

    @Test
    @Timeout(30)
    @DisplayName("Repeated announcement failures reuse one staged entry per source")
    void repeatedFailuresDoNotAccumulateCopies() throws Exception {
        Path source = watchFolder.resolve("movie.torrent");
        Files.write(source, validTorrentBytes("movie.bin"));

        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                throw new RuntimeException("simulated dispatch failure");
            }
        });

        FolderMonitorSettings settings = torrentSettings();
        for (int round = 0; round < 3; round++) {
            service.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);
        }

        try (Stream<Path> entries = Files.list(stagingRoot)) {
            assertEquals(1, entries.count(),
                    "repeated failed rounds must reuse a single staged entry per source");
        }
        assertTrue(Files.exists(source), "failed announcements keep the original in place");
    }

    /**
     * Stages an orphan the way a crashed round leaves it: the original is
     * staged from the watched folder with its real source key, then the
     * original disappears (move/delete finished, download never created).
     */
    private Path stageOrphan(String originalName, byte[] bytes) throws IOException {
        Path original = watchFolder.resolve(originalName);
        Files.write(original, bytes);
        Path staged = org.manager.util.DescriptorStaging.stageFile(original, stagingRoot);
        Files.delete(original);
        return staged;
    }

    @Test
    @Timeout(20)
    @DisplayName("Restarting monitoring on the same folder announces an orphan exactly once")
    void orphanIsAnnouncedExactlyOnceAcrossRestarts() throws Exception {
        stageOrphan("restart.torrent", validTorrentBytes("restart.bin"));

        List<Path> announced = new CopyOnWriteArrayList<>();
        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.add(filePath);
            }
        });

        // Production starts the torrent AND the metalink monitor on one
        // service instance for the same configured folder
        service.startMonitoring(watchFolder, torrentSettings()).get(5, TimeUnit.SECONDS);
        service.startMonitoring(watchFolder, torrentSettings()).get(5, TimeUnit.SECONDS);

        assertEquals(1, announced.size(),
                "one staged orphan must be announced exactly once across monitor restarts, "
                        + "not once per startMonitoring call");
    }

    @Test
    @Timeout(20)
    @DisplayName("Reconciliation of an unrelated folder does not announce a foreign orphan")
    void foreignFolderDoesNotAnnounceAnotherFoldersOrphan() throws Exception {
        Path staged = stageOrphan("foreign.torrent", validTorrentBytes("foreign.bin"));
        Path otherFolder = tempDir.resolve("other-inbox");
        Files.createDirectories(otherFolder);

        List<Path> announced = new CopyOnWriteArrayList<>();
        service.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.add(filePath);
            }
        });

        service.startMonitoring(otherFolder, torrentSettings()).get(5, TimeUnit.SECONDS);

        assertTrue(announced.isEmpty(),
                "a staged orphan keyed to another watched folder must not be re-announced "
                        + "attributed to this folder");
        assertTrue(Files.exists(staged),
                "the foreign orphan must remain staged for its own folder's reconciliation");
    }
}
