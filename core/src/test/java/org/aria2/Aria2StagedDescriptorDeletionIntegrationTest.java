package org.aria2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.Aria2DownloadHandler;
import org.manager.util.DescriptorStaging;

/**
 * Watched-descriptor ownership on the consumption side, against a real aria2c
 * process: after aria2 successfully ingests a torrent via addTorrent, the
 * handler deletes the descriptor only when it is an ODM-managed staged file
 * (containment beneath the staging root); manually selected files stay
 * user-owned, and failed ingestion never removes the staged input.
 */
@DisplayName("Aria2 Staged Descriptor Deletion Integration Tests")
class Aria2StagedDescriptorDeletionIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initContext() {
        ApplicationContext.initialize();
    }

    private Aria2DownloadHandler newHandler(Path downloadDir) {
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        DownloadSettingsFactory settingsFactory = new DownloadSettingsFactory(globalSettings);
        ExecutorService executor = Executors.newCachedThreadPool();
        return new Aria2DownloadHandler(
                globalSettings,
                settingsFactory,
                executor,
                ApplicationContext.getToolManagerFactory());
    }

    /** Minimal, structurally valid single-file torrent (1 piece); aria2
     *  validates bencode structure at add time, not piece hash correctness. */
    private static byte[] validTorrentBytes(String payloadName) throws Exception {
        String announce = "http://tracker.test/announce";
        String infoPrefix = "d6:lengthi262144e4:name" + payloadName.length() + ":" + payloadName
                + "12:piece lengthi262144e6:pieces20:";
        ByteArrayOutputStream torrent = new ByteArrayOutputStream();
        torrent.write(("d8:announce" + announce.length() + ":" + announce + "4:info")
                .getBytes(StandardCharsets.UTF_8));
        torrent.write(infoPrefix.getBytes(StandardCharsets.UTF_8));
        torrent.write(new byte[20]);
        torrent.write("ee".getBytes(StandardCharsets.UTF_8));
        return torrent.toByteArray();
    }

    @Test
    @DisplayName("Successful ingestion removes only ODM-managed staged files")
    @Timeout(120)
    void successfulIngestionRemovesOnlyManagedStagedFiles() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // ODM-managed descriptor: staged beneath the exclusive staging root
        Path stagingRoot = DescriptorStaging.stagingRoot();
        Files.createDirectories(stagingRoot);
        Path staged = stagingRoot.resolve(UUID.randomUUID() + "-watched.torrent");
        byte[] stagedBytes = validTorrentBytes("staged-payload.bin");
        Files.write(staged, stagedBytes);

        // User-owned descriptor: manually selected, outside the staging root
        Path manual = tempDir.resolve("manually-selected.torrent");
        byte[] manualBytes = validTorrentBytes("manual-payload.bin");
        Files.write(manual, manualBytes);

        Aria2DownloadHandler handler = newHandler(downloadDir);
        try {
            handler.initialize().get(30, TimeUnit.SECONDS);

            String stagedGid = handler.startDownload(Download.fromTorrent(staged, downloadDir))
                    .get(30, TimeUnit.SECONDS);
            assertNotNull(stagedGid, "aria2 must accept the staged torrent");

            String manualGid = handler.startDownload(Download.fromTorrent(manual, downloadDir))
                    .get(30, TimeUnit.SECONDS);
            assertNotNull(manualGid, "aria2 must accept the manual torrent");

            // Deletion is tied to successful ingestion and happens before the
            // start future completes
            assertFalse(Files.exists(staged),
                    "successfully ingested staged descriptor must be removed");
            assertTrue(Files.exists(manual),
                    "manually selected descriptor must never be auto-deleted");
            assertArrayEquals(manualBytes, Files.readAllBytes(manual));
        } finally {
            handler.shutdown().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Failed ingestion keeps the staged descriptor")
    @Timeout(120)
    void failedIngestionKeepsStagedDescriptor() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        Path stagingRoot = DescriptorStaging.stagingRoot();
        Files.createDirectories(stagingRoot);
        Path staged = stagingRoot.resolve(UUID.randomUUID() + "-broken.torrent");
        byte[] garbage = "this is not bencode and aria2 must reject it".getBytes(StandardCharsets.UTF_8);
        Files.write(staged, garbage);

        Aria2DownloadHandler handler = newHandler(downloadDir);
        try {
            handler.initialize().get(30, TimeUnit.SECONDS);

            boolean rejected = false;
            try {
                handler.startDownload(Download.fromTorrent(staged, downloadDir)).get(30, TimeUnit.SECONDS);
            } catch (Exception expected) {
                rejected = true;
            }
            assertTrue(rejected, "aria2 must reject a non-bencode torrent");

            assertTrue(Files.exists(staged),
                    "a failed ingestion must keep the staged descriptor so the input is not lost");
            assertArrayEquals(garbage, Files.readAllBytes(staged));
        } finally {
            handler.shutdown().get(30, TimeUnit.SECONDS);
        }
    }
}
