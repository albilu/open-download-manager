package org.manager.folder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watched-descriptor ownership tests: listeners are notified with a durable
 * staged copy so a delayed consumer (the asynchronous download queue) can
 * still read the descriptor bytes after the source disposition removed the
 * original, and failures before dispatch leave the original in place.
 */
@DisplayName("Folder Monitor Descriptor Staging Tests")
class FolderMonitorDescriptorStagingTest {

    private FolderMonitorServiceImpl folderMonitorService;

    /** Temp staging root: tests must never write into the real ODM data dir. */
    private Path stagingRoot;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        stagingRoot = tempDir.resolve("descriptor-staging");
        folderMonitorService = new FolderMonitorServiceImpl(stagingRoot);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (folderMonitorService != null) {
            folderMonitorService.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    /** Minimal bencode fixture that passes the service's format validation. */
    private static byte[] validTorrentBytes(String payloadName) {
        String content = "d8:announce33:http://tracker.example.com/announce4:infod6:lengthi1024e4:name"
                + payloadName.length() + ":" + payloadName
                + "12:piece lengthi32768e6:pieces20:aaaaaaaaaaaaaaaaaaaaee";
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Staging failure preserves the original and reports the error")
    @Timeout(20)
    void stagingFailurePreservesOriginalAndReportsError() throws Exception {
        // Sabotage the staging root: a regular file where the exclusive
        // staging directory should be makes every staging copy fail
        Path stagingRoot = tempDir.resolve("staging-root");
        Files.writeString(stagingRoot, "not a directory", StandardCharsets.UTF_8);

        FolderMonitorServiceImpl sabotagedService = new FolderMonitorServiceImpl(stagingRoot);
        try {
            Path watchFolder = tempDir.resolve("inbox");
            Files.createDirectories(watchFolder);
            Path source = watchFolder.resolve("movie.torrent");
            Files.write(source, validTorrentBytes("movie.bin"));

            CountDownLatch errorReported = new CountDownLatch(1);
            AtomicReference<Path> announced = new AtomicReference<>();
            sabotagedService.addFolderMonitorListener(new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    announced.set(filePath);
                }

                @Override
                public void onFileProcessingError(Path folderPath, Path filePath, Throwable error,
                        FolderMonitorSettings settings) {
                    errorReported.countDown();
                }
            });

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setFileAction(FolderMonitorSettings.FileAction.DELETE);

            sabotagedService.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);

            assertTrue(errorReported.await(5, TimeUnit.SECONDS),
                    "the staging failure must be reported through onFileProcessingError");
            assertTrue(Files.exists(source),
                    "the original must remain in place when staging fails");
            assertNull(announced.get(),
                    "listeners must not be announced when staging failed");
        } finally {
            sabotagedService.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Announced descriptors live beneath the staging root under their original name")
    @Timeout(20)
    void announcedDescriptorLivesBeneathStagingRootWithOriginalNameSuffix() throws Exception {
        Path watchFolder = tempDir.resolve("inbox");
        Files.createDirectories(watchFolder);
        Path source = watchFolder.resolve("movie.torrent");
        byte[] originalBytes = validTorrentBytes("movie.bin");
        Files.write(source, originalBytes);

        AtomicReference<Path> announced = new AtomicReference<>();
        folderMonitorService.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.set(filePath);
            }
        });

        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setFileExtensions(Set.of(".torrent"))
                .setFileAction(FolderMonitorSettings.FileAction.DELETE);

        folderMonitorService.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);

        Path descriptor = announced.get();
        assertNotNull(descriptor, "listener must have been notified");
        assertTrue(descriptor.toAbsolutePath().normalize()
                        .startsWith(stagingRoot.toAbsolutePath().normalize()),
                "announced descriptor must be staged beneath the exclusive root: " + descriptor);
        assertTrue(descriptor.getFileName().toString().endsWith("movie.torrent"),
                "staged name must preserve the original file name");
        assertArrayEquals(originalBytes, Files.readAllBytes(descriptor));
    }

    @Test
    @DisplayName("Failed announce is retried after repair; the original survives until announce succeeds")
    @Timeout(30)
    void failedAnnounceIsRetriedAfterRepairAndOriginalSurvivesUntilSuccess() throws Exception {
        // Sabotage the staging root so the first announcement fails
        Path retryStagingRoot = tempDir.resolve("retry-staging-root");
        Files.writeString(retryStagingRoot, "not a directory", StandardCharsets.UTF_8);

        FolderMonitorServiceImpl retryService = new FolderMonitorServiceImpl(retryStagingRoot);
        try {
            Path watchFolder = tempDir.resolve("watch");
            Files.createDirectories(watchFolder);
            Path source = watchFolder.resolve("movie.torrent");
            byte[] originalBytes = validTorrentBytes("movie.bin");

            CountDownLatch failureReported = new CountDownLatch(1);
            AtomicReference<Path> announced = new AtomicReference<>();
            retryService.addFolderMonitorListener(new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    announced.set(filePath);
                }

                @Override
                public void onFileProcessingError(Path folderPath, Path filePath, Throwable error,
                        FolderMonitorSettings settings) {
                    failureReported.countDown();
                }
            });

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setFileAction(FolderMonitorSettings.FileAction.DELETE)
                    .setDebounceDelay(Duration.ofMillis(200))
                    .setProcessExistingFiles(false);

            retryService.startMonitoring(watchFolder, settings).get(5, TimeUnit.SECONDS);

            // First round: staging fails, no announcement, original kept
            Files.write(source, originalBytes);
            assertTrue(failureReported.await(10, TimeUnit.SECONDS),
                    "the staging failure must be reported");
            assertNull(announced.get(), "no announcement may happen while staging fails");
            assertTrue(Files.exists(source), "the original must survive the failed round");

            // Repair staging, then touch the file so a MODIFY event triggers
            // a new debounce round
            Files.delete(retryStagingRoot);
            Files.createDirectories(retryStagingRoot);
            Files.write(source, originalBytes, java.nio.file.StandardOpenOption.APPEND);

            // Second round: the announcement must be RETRIED with the staged
            // path, and only then may the disposition consume the original
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> announced.get() != null);
            Path descriptor = announced.get();
            assertTrue(descriptor.toAbsolutePath().normalize()
                            .startsWith(retryStagingRoot.toAbsolutePath().normalize()),
                    "retried announcement must use a staged copy: " + descriptor);
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> !Files.exists(source));
            byte[] stagedBytes = Files.readAllBytes(descriptor);
            assertEquals(originalBytes.length * 2, stagedBytes.length,
                    "the staged copy must hold the bytes as of the successful round");
            assertArrayEquals(originalBytes,
                    java.util.Arrays.copyOfRange(stagedBytes, 0, originalBytes.length));
        } finally {
            retryService.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Delayed consumer can read the announced descriptor after source disposition")
    @Timeout(20)
    void delayedConsumerCanReadAnnouncedDescriptorAfterSourceDisposition() throws Exception {
        Path watchFolder = tempDir.resolve("inbox");
        Files.createDirectories(watchFolder);
        Path source = watchFolder.resolve("movie.torrent");
        byte[] originalBytes = validTorrentBytes("movie.bin");
        Files.write(source, originalBytes);

        AtomicReference<Path> announced = new AtomicReference<>();
        folderMonitorService.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                announced.set(filePath);
            }
        });

        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setFileExtensions(Set.of(".torrent"))
                .setFileAction(FolderMonitorSettings.FileAction.DELETE);

        folderMonitorService.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);

        // The DELETE disposition must have removed the source...
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Files.exists(source));

        // ...yet the announced descriptor must still be readable with the
        // original bytes: the asynchronous download queue reads it later
        Path descriptor = announced.get();
        assertNotNull(descriptor, "listener must have been notified");
        assertNotEquals(source, descriptor, "announced path must be the staged copy, not the source");
        assertTrue(Files.isRegularFile(descriptor), "announced descriptor must be a durable readable file");
        assertArrayEquals(originalBytes, Files.readAllBytes(descriptor),
                "delayed consumer must read the original descriptor bytes");
    }

    @Test
    @DisplayName("Listener dispatch failure preserves the original and reports the error")
    @Timeout(20)
    void listenerDispatchFailurePreservesOriginalAndReportsError() throws Exception {
        Path watchFolder = tempDir.resolve("inbox");
        Files.createDirectories(watchFolder);
        Path source = watchFolder.resolve("movie.torrent");
        Files.write(source, validTorrentBytes("movie.bin"));

        CountDownLatch errorReported = new CountDownLatch(1);
        folderMonitorService.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                throw new RuntimeException("simulated synchronous dispatch failure");
            }

            @Override
            public void onFileProcessingError(Path folderPath, Path filePath, Throwable error,
                    FolderMonitorSettings settings) {
                errorReported.countDown();
            }
        });

        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setFileExtensions(Set.of(".torrent"))
                .setFileAction(FolderMonitorSettings.FileAction.DELETE);

        folderMonitorService.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);

        assertTrue(errorReported.await(5, TimeUnit.SECONDS),
                "the dispatch failure must be reported through onFileProcessingError");
        assertTrue(Files.exists(source),
                "the original must remain in place when synchronous listener dispatch fails");
    }

    @Test
    @DisplayName("Move-to-directory disposition reserves a collision-safe name and never replaces")
    @Timeout(20)
    void moveToDirectoryDispositionReservesCollisionSafeName() throws Exception {
        Path watchFolder = tempDir.resolve("inbox");
        Path moveToDir = tempDir.resolve("processed");
        Files.createDirectories(watchFolder);
        Files.createDirectories(moveToDir);

        // A previously processed file already occupies the target name
        Path occupiedTarget = moveToDir.resolve("movie.torrent");
        Files.write(occupiedTarget, "pre-existing processed torrent".getBytes(StandardCharsets.UTF_8));

        Path source = watchFolder.resolve("movie.torrent");
        byte[] sourceBytes = validTorrentBytes("movie.bin");
        Files.write(source, sourceBytes);

        folderMonitorService.addFolderMonitorListener(new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                // no-op: only the disposition is under test here
            }
        });

        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setFileExtensions(Set.of(".torrent"))
                .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
                .setMoveToDirectory(moveToDir);

        folderMonitorService.scanFolder(watchFolder, settings).get(5, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !Files.exists(source));

        assertEquals("pre-existing processed torrent",
                Files.readString(occupiedTarget, StandardCharsets.UTF_8),
                "an occupied destination name must never be replaced");

        // The watched file must have landed under a different, collision-free name
        try (Stream<Path> entries = Files.list(moveToDir)) {
            assertTrue(entries.filter(p -> !p.equals(occupiedTarget))
                            .anyMatch(p -> {
                                try {
                                    return Files.isRegularFile(p)
                                            && java.util.Arrays.equals(sourceBytes, Files.readAllBytes(p));
                                } catch (IOException e) {
                                    return false;
                                }
                            }),
                    "the source must be moved to a reserved collision-safe destination name");
        }
    }
}
