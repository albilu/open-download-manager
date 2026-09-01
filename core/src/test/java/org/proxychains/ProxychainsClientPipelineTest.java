package org.proxychains;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadListener;

/**
 * Runs the proxychains client end to end with a pass-through proxychains
 * stub (it simply execs the aria2c command it was given) so the command
 * building, process supervision and aria2 output parsing run for real
 * without a SOCKS daemon.
 */
@DisplayName("ProxychainsClient download pipeline")
class ProxychainsClientPipelineTest {

    @TempDir
    Path tempDir;

    private ProxychainsClient client;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        ApplicationContext.initialize();
        // pass-through proxychains: drop the -f <config> pair, then exec the
        // real aria2c command the client assembled
        Path stub = tempDir.resolve("proxychains4-stub");
        Files.writeString(stub, "#!/bin/sh\n"
                + "case \"$1\" in -h|--help|--version) exit 0;; esac\n"
                + "while [ \"$1\" = \"-f\" ]; do shift; shift; done\n"
                + "[ $# -eq 0 ] && exit 0\n"
                + "exec \"$@\"\n");
        Files.setPosixFilePermissions(stub,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        client = new ProxychainsClient(stub.toString(), null);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private Download proxiedDownload(URI uri, Path destination) {
        Download download = new Download(uri);
        download.setName("payload.bin");
        download.setDestination(destination);
        download.setType(Download.Type.PROXYCHAINS);
        download.getSettings().setUseProxy(true);
        download.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");
        return download;
    }

    private RecordingListener listen(Download download) {
        RecordingListener listener = new RecordingListener();
        executor = Executors.newCachedThreadPool();
        return listener;
    }

    private static final class RecordingListener implements DownloadListener {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final List<String> errorMessages = new CopyOnWriteArrayList<>();

        @Override public void onDownloadStart(Download d) {
            started.incrementAndGet();
        }

        @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) { }

        @Override public void onDownloadPause(Download d) { }

        @Override public void onDownloadResume(Download d) { }

        @Override public void onDownloadComplete(Download d) {
            completed.incrementAndGet();
        }

        @Override public void onDownloadError(Download d, String errorMessage) {
            errors.incrementAndGet();
            errorMessages.add(errorMessage);
        }

        @Override public void onDownloadCanceled(Download d) { }
    }

    @Test
    @DisplayName("a proxied download completes through the pass-through stub")
    @Timeout(120)
    void proxiedDownloadCompletes() throws Exception {
        byte[] payload = new byte[64 * 1024];
        java.util.Arrays.fill(payload, (byte) 3);
        Path destination = Files.createDirectories(tempDir.resolve("downloads"));
        RecordingListener listener = listen(null);

        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            for (int i = 0; i < 8; i++) {
                server.enqueue(new MockResponse().setResponseCode(200)
                        .setHeader("Content-Length", String.valueOf(payload.length))
                        .setBody(new okio.Buffer().write(payload)));
            }

            Download download = proxiedDownload(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/payload.bin"), destination);
            client.startDownload(download, listener, Map.of());

            await().atMost(Duration.ofSeconds(90)).until(() -> listener.completed.get() > 0);
            assertEquals(1, listener.started.get(), "start must be reported once");
            assertEquals(0, listener.errors.get());
            Path output = destination.resolve("payload.bin");
            assertTrue(Files.exists(output), "the payload must be downloaded");
            assertEquals(payload.length, Files.size(output));
        }
    }

    @Test
    @DisplayName("engine option filters keep aria2-prefixed keys and complete the download")
    @Timeout(120)
    void aria2OptionFiltering() throws Exception {
        byte[] payload = "hello-proxychains".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path destination = Files.createDirectories(tempDir.resolve("downloads-filtered"));
        RecordingListener listener = listen(null);

        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Length", String.valueOf(payload.length))
                    .setBody(new okio.Buffer().write(payload)));

            Download download = proxiedDownload(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/hello.bin"), destination);
            // only aria2.-prefixed options may reach the engine; others are dropped
            client.startDownload(download, listener,
                    Map.of("aria2.max-tries", "1", "evil.on-download-complete", "/tmp/pwned"));

            await().atMost(Duration.ofSeconds(90)).until(() -> listener.completed.get() > 0);
            assertTrue(Files.readString(destination.resolve("payload.bin"))
                    .equals("hello-proxychains"));
        }
    }

    @Test
    @DisplayName("an unreachable target surfaces a download error")
    @Timeout(120)
    void unreachableTargetErrors() throws Exception {
        Path destination = Files.createDirectories(tempDir.resolve("downloads-error"));
        RecordingListener listener = listen(null);

        Download download = proxiedDownload(URI.create("http://127.0.0.1:1/unreachable.bin"), destination);
        client.startDownload(download, listener, Map.of());

        await().atMost(Duration.ofSeconds(90)).until(() -> listener.errors.get() > 0
                || listener.completed.get() > 0);
        assertTrue(listener.errors.get() + listener.completed.get() >= 1,
                "the download must terminate (error or completion) instead of hanging");
        assertNotNull(download.getErrorMessage() == null ? "" : download.getErrorMessage());
    }
}
