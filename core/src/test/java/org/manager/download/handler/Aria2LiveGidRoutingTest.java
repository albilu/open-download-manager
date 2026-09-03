package org.manager.download.handler;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.aria2.Aria2Client;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;

/** Regression tests for session-scoped aria2 GID ownership and routing. */
@DisplayName("Aria2 live GID routing")
class Aria2LiveGidRoutingTest {

    private static final class InitializedHandler extends Aria2DownloadHandler {

        InitializedHandler(GlobalSettings settings, DownloadSettingsFactory settingsFactory,
                ExecutorService executor, Aria2Client client,
                ScheduledExecutorService progressPoller) {
            super(settings, settingsFactory, executor, client, progressPoller);
            initialized = true;
        }
    }

    private static final class NoOpScheduledFuture implements ScheduledFuture<Object> {

        private volatile boolean canceled;

        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            canceled = true;
            return true;
        }
        @Override public boolean isCancelled() { return canceled; }
        @Override public boolean isDone() { return canceled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) throws TimeoutException { return null; }
    }

    /** Scheduler that records immediate submissions but never starts background polling. */
    private static final class RecordingPoller extends ScheduledThreadPoolExecutor {

        private final AtomicInteger immediateExecutions = new AtomicInteger();

        RecordingPoller() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                long period, TimeUnit unit) {
            return new NoOpScheduledFuture();
        }

        @Override
        public void execute(Runnable command) {
            immediateExecutions.incrementAndGet();
        }
    }

    /** In-memory RPC endpoint: records GID routing without starting a daemon. */
    private static final class RecordingClient extends Aria2Client {

        private final Path outputPath;
        private final List<String> optionGids = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> optionValues = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> addUriOptions = new CopyOnWriteArrayList<>();
        private final List<String> peerGids = new CopyOnWriteArrayList<>();
        private final List<String> fileGids = new CopyOnWriteArrayList<>();
        private final List<String> statusGids = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch removeEntered;
        private volatile CountDownLatch releaseRemove;

        RecordingClient(Path outputPath) {
            super("aria2c");
            this.outputPath = outputPath;
        }

        @Override
        public String changeOption(String gid, Map<String, Object> options) {
            optionGids.add(gid);
            optionValues.add(Map.copyOf(options));
            return "OK";
        }

        @Override
        public String addUriRpc(String[] uris, Map<String, Object> options) {
            addUriOptions.add(Map.copyOf(options));
            return "uri-gid";
        }

        @Override
        public List<Map<String, Object>> getPeers(String gid) {
            peerGids.add(gid);
            return List.of(Map.of("peerId", "peer-1"));
        }

        @Override
        public List<Map<String, Object>> getFiles(String gid) {
            fileGids.add(gid);
            return List.of(Map.of("path", outputPath.toString()));
        }

        @Override
        public String tellStatus(String gid, String[] keys) {
            statusGids.add(gid);
            return "{\"bittorrent\":{\"announceList\":[[\"udp://tracker.test\"]]}}";
        }

        @Override
        public String remove(String gid) throws IOException {
            CountDownLatch entered = removeEntered;
            CountDownLatch release = releaseRemove;
            if (entered != null && release != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release fake aria2.remove");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted in fake aria2.remove", e);
                }
            }
            return gid;
        }

        void blockRemovals(CountDownLatch entered, CountDownLatch release) {
            removeEntered = entered;
            releaseRemove = release;
        }

        void clearRecordings() {
            optionGids.clear();
            optionValues.clear();
            addUriOptions.clear();
            peerGids.clear();
            fileGids.clear();
            statusGids.clear();
        }
    }

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private RecordingPoller poller;
    private RecordingClient client;
    private InitializedHandler handler;
    private GlobalSettings globalSettings;

    @BeforeEach
    void setUp() {
        globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(tempDir);
        executor = Executors.newSingleThreadExecutor();
        poller = new RecordingPoller();
        client = new RecordingClient(tempDir.resolve("payload.bin"));
        handler = new InitializedHandler(globalSettings,
                new DownloadSettingsFactory(globalSettings),
                executor, client, poller);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        poller.shutdownNow();
    }

    private static Map<String, Object> status(String state, long completed, long total) {
        Map<String, Object> status = new HashMap<>();
        status.put("status", state);
        status.put("completedLength", String.valueOf(completed));
        status.put("totalLength", String.valueOf(total));
        status.put("downloadSpeed", "0");
        return status;
    }

    @Test
    @DisplayName("Retiring magnet metadata promotes a payload GID and settings reach every payload")
    void metadataRetirementPromotesPayloadsForSettings() throws Exception {
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        handler.registerTrackedDownload(download, List.of("metadata-gid"));

        Map<String, Object> metadataComplete = status("complete", 10, 10);
        metadataComplete.put("followedBy", List.of("payload-b", "payload-a"));
        handler.processProgressUpdate(download.getId(), "metadata-gid", metadataComplete);

        assertEquals("payload-a", download.getGid(),
                "the stored primary GID must advance when metadata retires");
        assertEquals(Set.of("payload-a", "payload-b"),
                handler.trackedGidsFor(download.getId()));

        handler.changeSettings(download).join();

        assertEquals(Set.of("payload-a", "payload-b"), Set.copyOf(client.optionGids));
        assertFalse(client.optionGids.contains("metadata-gid"));
    }

    @Test
    @DisplayName("Disabled seeding stops an active local seeder and then completes normally")
    void disabledSeedingStopsLegacySeeder() {
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        handler.registerTrackedDownload(download, List.of("payload-gid"));
        Map<String, Object> seeding = status("active", 1_000, 1_000);
        seeding.put("seeder", "true");
        seeding.put("infoHash", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        handler.processProgressUpdate(download.getId(), "payload-gid", seeding);
        handler.processProgressUpdate(download.getId(), "payload-gid", seeding);

        assertEquals(List.of("payload-gid"), client.optionGids,
                "the stop request is sent once even if several active polls arrive");
        assertEquals(List.of(Map.of("seed-time", "0")), client.optionValues);
        assertEquals(Download.Status.SEEDING, download.getStatus(),
                "ODM waits for aria2's authoritative complete transition");

        handler.processProgressUpdate(download.getId(), "payload-gid",
                status("complete", 1_000, 1_000));
        assertEquals(Download.Status.COMPLETED, download.getStatus());
    }

    @Test
    @DisplayName("Enabled seeding leaves an active local seeder running")
    void enabledSeedingIsNotStopped() {
        globalSettings.setProperty("aria2.enableSeeding", "true");
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:cccccccccccccccccccccccccccccccccccccccc"));
        handler.registerTrackedDownload(download, List.of("seed-gid"));
        Map<String, Object> seeding = status("active", 100, 100);
        seeding.put("seeder", "true");

        handler.processProgressUpdate(download.getId(), "seed-gid", seeding);

        assertTrue(client.optionValues.isEmpty());
        assertEquals(Download.Status.SEEDING, download.getStatus());
    }

    @Test
    @DisplayName("A paused live GID receives the relocated output directory")
    void changeDestinationRepointsEveryLiveGid() {
        Download download = new Download(URI.create("https://example.test/archive.bin"));
        handler.registerTrackedDownload(download, List.of("gid-a", "gid-b"));
        Path previous = tempDir.resolve("old");
        Path destination = tempDir.resolve("new");

        handler.changeDestination(download, previous, destination).join();

        assertEquals(Set.of("gid-a", "gid-b"), Set.copyOf(client.optionGids));
        assertEquals(List.of(Map.of("dir", destination.toString()),
                Map.of("dir", destination.toString())), client.optionValues);
    }

    @Test
    @DisplayName("Recheck Data reaches every owned live GID without changing persistent options")
    void recheckDataIsAOneShotLiveRequest() {
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:dddddddddddddddddddddddddddddddddddddddd"));
        download.initSettings(new DownloadSettingsFactory(globalSettings));
        handler.registerTrackedDownload(download, List.of("verify-b", "verify-a"));
        download.setStatus(Download.Status.DOWNLOADING);
        client.clearRecordings();

        handler.recheckData(download).join();

        assertEquals(Set.of("verify-a", "verify-b"), Set.copyOf(client.optionGids));
        assertEquals(List.of(Map.of("check-integrity", "true"),
                Map.of("check-integrity", "true")), client.optionValues);
        assertFalse(((Aria2Settings) download.getSettings()).isCheckIntegrity(),
                "the context action must not permanently enable startup verification");
    }

    @Test
    @DisplayName("Recheck Data reports a retired task instead of claiming success")
    void recheckDataFailsWhenNoLiveGidExists() {
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"));
        download.initSettings(new DownloadSettingsFactory(globalSettings));
        download.setGid("persisted-stale-gid");
        download.setStatus(Download.Status.DOWNLOADING);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> handler.recheckData(download).join());

        assertTrue(failure.getCause().getMessage().contains("data recheck"));
        assertTrue(client.optionGids.isEmpty(),
                "a persisted or retired GID must never receive an aria2 RPC");
    }

    @Test
    @DisplayName("HTTP recheck supplies both the expected checksum and check-integrity")
    void httpRecheckSuppliesExpectedChecksum() {
        Download download = new Download(URI.create("https://example.test/archive.iso"));
        download.setDestination(tempDir);
        download.setStatus(Download.Status.DOWNLOADING);
        download.initSettings(new DownloadSettingsFactory(globalSettings));
        download.setChecksumAlgorithm("SHA256");
        download.setExpectedChecksum("AB".repeat(32));
        handler.registerTrackedDownload(download, List.of("http-gid"));
        client.clearRecordings();

        handler.recheckData(download).join();

        assertEquals(List.of(Map.of(
                "check-integrity", "true",
                "checksum", "sha-256=" + "ab".repeat(32))), client.optionValues);
    }

    @Test
    @DisplayName("HTTP downloads give aria2 a detected checksum when the task starts")
    void httpStartSuppliesExpectedChecksum() {
        Download download = new Download(URI.create("https://example.test/archive.iso"));
        download.setDestination(tempDir);
        download.initSettings(new DownloadSettingsFactory(globalSettings));
        download.setChecksumAlgorithm("sha256");
        download.setExpectedChecksum("cd".repeat(32));

        handler.startDownload(download).join();

        assertEquals("sha-256=" + "cd".repeat(32),
                client.addUriOptions.getFirst().get("checksum"));
    }

    @Test
    @DisplayName("Detail RPCs ignore persisted GIDs and only query current mapped tasks")
    void detailQueriesUseOnlyLiveMappedGids() throws Exception {
        Download magnet = new Download(URI.create(
                "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98"));
        magnet.setGid("persisted-stale-gid");

        assertTrue(handler.getDownloadPeers(magnet).isEmpty());
        assertTrue(handler.getDownloadFiles(magnet).isEmpty());
        assertTrue(handler.getDownloadTrackers(magnet).isEmpty());
        assertTrue(client.peerGids.isEmpty());
        assertTrue(client.fileGids.isEmpty());
        assertTrue(client.statusGids.isEmpty());

        handler.registerTrackedDownload(magnet, List.of("live-gid"));

        assertEquals(1, handler.getDownloadPeers(magnet).size());
        assertEquals(1, handler.getDownloadFiles(magnet).size());
        assertEquals(List.of(List.of("udp://tracker.test")),
                handler.getDownloadTrackers(magnet));
        assertEquals(List.of("live-gid"), client.peerGids);
        assertEquals(List.of("live-gid"), client.fileGids);
        assertEquals(List.of("live-gid"), client.statusGids);

        Download http = new Download(URI.create("https://example.test/archive.iso"));
        handler.registerTrackedDownload(http, List.of("http-live-gid"));
        assertTrue(handler.getDownloadTrackers(http).isEmpty(),
                "ordinary HTTP downloads must not issue torrent tracker queries");
        assertEquals(List.of("live-gid"), client.statusGids);
    }

    @Test
    @DisplayName("A cancellation notification cannot re-arm polling after ownership is removed")
    void cancellationNotificationCannotRearmPolling() throws Exception {
        Download download = new Download(URI.create(
                "magnet:?xt=urn:btih:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        handler.registerTrackedDownload(download, List.of("root-gid"));
        Map<String, Object> active = status("active", 1, 100);
        active.put("followedBy", List.of("payload-gid"));
        handler.processProgressUpdate(download.getId(), "root-gid", active);

        CountDownLatch removeEntered = new CountDownLatch(1);
        CountDownLatch releaseRemove = new CountDownLatch(1);
        client.blockRemovals(removeEntered, releaseRemove);
        client.clearRecordings();

        CompletableFuture<Void> cancellation = handler.cancelDownload(download, false);
        try {
            assertTrue(removeEntered.await(5, TimeUnit.SECONDS));
            handler.requestImmediatePoll("payload-gid");
            assertEquals(0, poller.immediateExecutions.get());
            assertTrue(client.statusGids.isEmpty());
        } finally {
            releaseRemove.countDown();
        }
        cancellation.get(5, TimeUnit.SECONDS);
        assertEquals(Download.Status.CANCELED, download.getStatus());
    }
}
