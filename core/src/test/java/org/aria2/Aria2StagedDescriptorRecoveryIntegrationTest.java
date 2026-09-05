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
import com.github.stefanbirkner.systemlambda.SystemLambda;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.Aria2DownloadHandler;
import org.manager.util.DescriptorStaging;

/**
 * Managed descriptor retention and re-ingestion against fresh aria2 daemons.
 * Restart recovery must retain the source until the owning record is removed.
 */
@DisplayName("Aria2 Staged Descriptor Recovery Integration Tests")
class Aria2StagedDescriptorRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initContext() {
        ApplicationContext.initialize();
    }

    private final java.util.List<ExecutorService> executors = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    private Aria2DownloadHandler newHandler(Path downloadDir) throws Exception {
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        try (java.net.ServerSocket port = new java.net.ServerSocket(0)) {
            globalSettings.setProperty("aria2.rpcPort", Integer.toString(port.getLocalPort()));
        }
        DownloadSettingsFactory settingsFactory = new DownloadSettingsFactory(globalSettings);
        ExecutorService executor = Executors.newCachedThreadPool();
        executors.add(executor);
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

    private static Download descriptorDownload(String extension, Path source, Path destination) {
        return extension.equals("torrent") ? Download.fromTorrent(source, destination)
                : Download.fromMetaLink(source, destination);
    }

    private static byte[] descriptorBytes(String extension, String name) throws Exception {
        return extension.equals("torrent") ? validTorrentBytes(name)
                : ("<?xml version=\"1.0\"?><metalink xmlns=\"urn:ietf:params:xml:ns:metalink\">"
                    + "<file name=\"" + name + "\"><size>262144</size>"
                    + "<url>http://127.0.0.1:1/" + name + "</url></file></metalink>")
                    .getBytes(StandardCharsets.UTF_8);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"torrent", "meta4"})
    @DisplayName("Successful ingestion retains sources for a fresh daemon")
    @Timeout(120)
    void successfulIngestionRetainsSourcesForRestart(String extension) throws Exception {
        // XDG_DATA_HOME into the temp dir keeps the staging root hermetic
        // while still exercising the real production resolution
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", tempDir.toString()).execute(() -> {
            Path downloadDir = tempDir.resolve("downloads");
            Files.createDirectories(downloadDir);

            // ODM-managed descriptor: staged beneath the exclusive staging root
            Path stagingRoot = DescriptorStaging.stagingRoot();
            Files.createDirectories(stagingRoot);
            Path staged = stagingRoot.resolve(UUID.randomUUID() + "-watched." + extension);
            byte[] stagedBytes = descriptorBytes(extension, "staged-payload.bin");
            Files.write(staged, stagedBytes);

            // User-owned descriptor: manually selected, outside the staging root
            Path manual = tempDir.resolve("manually-selected." + extension);
            byte[] manualBytes = descriptorBytes(extension, "manual-payload.bin");
            Files.write(manual, manualBytes);

            Aria2DownloadHandler handler = newHandler(downloadDir);
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                String stagedGid = handler.startDownload(descriptorDownload(extension, staged, downloadDir))
                        .get(30, TimeUnit.SECONDS);
                assertNotNull(stagedGid, "aria2 must accept the staged torrent");

                String manualGid = handler.startDownload(descriptorDownload(extension, manual, downloadDir))
                        .get(30, TimeUnit.SECONDS);
                assertNotNull(manualGid, "aria2 must accept the manual torrent");

                assertArrayEquals(stagedBytes, Files.readAllBytes(staged),
                        "recovery needs the exact source accepted by aria2");
                assertTrue(Files.exists(manual),
                        "manually selected descriptor must never be auto-deleted");
                assertArrayEquals(manualBytes, Files.readAllBytes(manual));
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
            Aria2DownloadHandler recovered = newHandler(downloadDir);
            try {
                recovered.initialize().get(30, TimeUnit.SECONDS);
                assertNotNull(recovered.startDownload(descriptorDownload(extension, staged, downloadDir))
                        .get(30, TimeUnit.SECONDS), "a fresh daemon must re-ingest the retained descriptor");
                assertArrayEquals(stagedBytes, Files.readAllBytes(staged));
            } finally {
                recovered.shutdown().get(30, TimeUnit.SECONDS);
            }
        });
    }

    @Test
    @DisplayName("Failed ingestion keeps the staged descriptor")
    @Timeout(120)
    void failedIngestionKeepsStagedDescriptor() throws Exception {
        // XDG_DATA_HOME into the temp dir keeps the staging root hermetic
        // while still exercising the real production resolution
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", tempDir.toString()).execute(() -> {
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
        });
    }
}
