package org.manager.download.handler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.aria2.Aria2Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.DownloadFileInfo;
import org.manager.download.DownloadSettingsFactory;

class Aria2FilePreviewTest {

    @TempDir
    Path tempDir;

    private ExecutorService executor;
    private ScheduledExecutorService poller;
    private RecordingClient client;
    private Aria2DownloadHandler handler;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        poller = Executors.newSingleThreadScheduledExecutor();
        client = new RecordingClient();
        handler = new InitializedHandler(new GlobalSettings(),
                new DownloadSettingsFactory(new GlobalSettings()), executor, client, poller);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        poller.shutdownNow();
    }

    @Test
    void localDescriptorIsParsedWithoutStartingAnAriaTask() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        handler = new Aria2DownloadHandler(settings,
                new DownloadSettingsFactory(settings), executor, client, poller);
        Path torrent = tempDir.resolve("single.torrent");
        Files.writeString(torrent,
                "d4:infod6:lengthi5e4:name8:file.txtee", StandardCharsets.US_ASCII);

        assertEquals(List.of(new DownloadFileInfo(1, "file.txt", 5)),
                handler.previewDownloadFiles(torrent.toUri(),
                        "socks5h://127.0.0.1:9050").join());
        assertEquals(0, client.addUriCalls.get());
    }

    @Test
    void invalidMetadataCandidatesAreRejectedBeforeAria2IsInvoked() {
        for (String input : List.of("ordinary-text", "README.md", "magnet:?xt=urn:btih:short",
                "https://example.com:70000/file.torrent", "file:///tmp/arbitrary.txt")) {
            assertThrows(CompletionException.class,
                    () -> handler.previewDownloadFiles(URI.create(input)).join());
        }
        assertEquals(0, client.addUriCalls.get());
    }

    @Test
    void socksMagnetPreviewFailsBeforeAnyDirectAriaTaskCanStart() {
        URI magnet = URI.create("magnet:?xt=urn:btih:"
                + "0123456789abcdef0123456789abcdef01234567");

        CompletionException failure = assertThrows(CompletionException.class,
                () -> handler.previewDownloadFiles(magnet,
                        "socks5h://127.0.0.1:9050").join());

        assertEquals(0, client.addUriCalls.get());
        assertTrue(failure.getCause().getMessage().contains("SOCKS/Tor"));
    }

    @Test
    void magnetPreviewUsesMetadataOnlyTaskAndAlwaysCleansItUp() {
        client.writeTorrentMetadata = true;
        URI magnet = URI.create("magnet:?xt=urn:btih:"
                + "0123456789abcdef0123456789abcdef01234567");

        assertEquals(List.of(new DownloadFileInfo(1, "file.txt", 5)),
                handler.previewDownloadFiles(magnet,
                        "http://127.0.0.1:8080").join());

        assertEquals(1, client.addUriCalls.get());
        assertEquals("true", client.options.get("bt-metadata-only"));
        assertEquals("true", client.options.get("bt-save-metadata"));
        assertEquals("http://127.0.0.1:8080", client.options.get("all-proxy"));
        assertEquals(1, client.forceRemoveCalls.get());
        assertEquals(1, client.removeResultCalls.get());
        assertTrue(client.previewDirectory != null
                && Files.notExists(client.previewDirectory));
    }

    private static final class InitializedHandler extends Aria2DownloadHandler {

        InitializedHandler(GlobalSettings settings, DownloadSettingsFactory settingsFactory,
                ExecutorService executor, Aria2Client client,
                ScheduledExecutorService poller) {
            super(settings, settingsFactory, executor, client, poller);
            initialized = true;
        }
    }

    private static final class RecordingClient extends Aria2Client {

        private final AtomicInteger addUriCalls = new AtomicInteger();
        private final AtomicInteger forceRemoveCalls = new AtomicInteger();
        private final AtomicInteger removeResultCalls = new AtomicInteger();
        private boolean writeTorrentMetadata;
        private Map<String, Object> options = Map.of();
        private Path previewDirectory;

        RecordingClient() {
            super("aria2c");
        }

        @Override
        public String addUriRpc(String uri, Map<String, Object> options) throws IOException {
            addUriCalls.incrementAndGet();
            this.options = Map.copyOf(options);
            previewDirectory = Path.of(String.valueOf(options.get("dir")));
            if (writeTorrentMetadata) {
                Files.writeString(previewDirectory.resolve("metadata.torrent"),
                        "d4:infod6:lengthi5e4:name8:file.txtee",
                        StandardCharsets.US_ASCII);
            }
            return "preview-gid";
        }

        @Override
        public String forceRemove(String gid) {
            forceRemoveCalls.incrementAndGet();
            return gid;
        }

        @Override
        public String removeDownloadResult(String gid) {
            removeResultCalls.incrementAndGet();
            return gid;
        }
    }
}
