package org.aria2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;

/**
 * WebSocket transport contracts:
 *
 * 1. Request ids handed to concurrent callers must be unique — a duplicate
 *    id completes the wrong pending future in onMessage.
 * 2. The reconnect path must close the socket WITHOUT latching the final
 *    shutdown state: after an internal close, reconnection and WebSocket
 *    use must still be possible. Only the public disconnectWebSocket()
 *    (application teardown) may latch.
 */
@DisplayName("Aria2Client WebSocket request ids and reconnect-safe close")
class Aria2WebSocketTransportTest {

    @Test
    @DisplayName("Concurrent request-id allocation never hands out duplicates")
    void requestIdsAreUniqueUnderConcurrency() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);

        int threads = 8;
        int perThread = 2_000;
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<Future<Void>> workers = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(client.nextWsRequestId());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> worker : workers) {
                worker.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, seen.size(),
                "every allocated request id must be unique across threads");
    }

    @Test
    @DisplayName("Reconnect-safe close does not latch the shutdown state")
    void reconnectSafeCloseKeepsReconnectionPossible() {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null);
        client.enableWebSocketTransport();

        client.closeWebSocketSocket("reconnect");

        assertFalse(client.isShutdownLatched(),
                "internal close used by the reconnect path must not latch the permanent "
                        + "shutdown flag (it currently breaks all later reconnections)");
        assertFalse(client.isWebSocketUseDisabled(),
                "internal close must not permanently disable WebSocket use");

        // The public teardown API keeps its latching semantics
        client.disconnectWebSocket();
        assertTrue(client.isShutdownLatched(),
                "public disconnectWebSocket must latch the final shutdown state");
        assertTrue(client.isWebSocketUseDisabled(),
                "public disconnectWebSocket must disable further WebSocket use");
    }

    /**
     * A send that fails at the transport layer (dead/null client, runtime
     * send error) must fail CLEANLY and must not leave its already-registered
     * response future pending forever — leaked ids accumulate for the whole
     * session and only get swept by a full disconnect.
     */
    @Test
    @DisplayName("A failed WebSocket send leaves no pending response future behind")
    void failedSendDoesNotLeakPendingFuture() throws Exception {
        Aria2Client client = new Aria2Client("aria2c", "http://localhost:6800/jsonrpc", null) {
            @Override
            public void connectWebSocket() {
                // Simulates a reconnect that never completes: no client is
                // ever created, so the send path hits a null transport.
            }
        };
        client.enableWebSocketTransport();

        try {
            assertThrows(java.io.IOException.class, client::getVersion,
                    "a null WebSocket client must surface as a clean IOException");
            assertEquals(0, client.pendingWebSocketResponseCount(),
                    "the failed request's future must not stay registered");

            assertThrows(java.io.IOException.class, () -> client.systemMulticall(
                    client.tellStatusMulticallCalls(java.util.List.of("gid0"),
                            new String[] { "status" })),
                    "the raw/multicall send path must fail cleanly too");
            assertEquals(0, client.pendingWebSocketResponseCount(),
                    "the failed multicall's future must not stay registered");
        } finally {
            client.disconnectWebSocket();
        }
    }

    /**
     * Transport recovery contract: when the daemon closes the WebSocket
     * remotely, the client must reconnect (restarting the daemon over the
     * HTTP RPC transport when needed) WITHOUT latching the final shutdown
     * state — a later send must succeed over the WebSocket again.
     */
    @Test
    @DisplayName("Remote close reconnects and a subsequent send succeeds")
    @Timeout(120)
    void remoteCloseReconnectsAndSubsequentSendSucceeds(@TempDir Path tempDir) throws Exception {
        int port = 7360;
        String secret = "ws-reconnect-secret";
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        Process daemon = startForegroundDaemon(port, secret, downloadDir);
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"), "http://localhost:" + port + "/jsonrpc", secret);
        client.setUseWebSocket(true);
        try {
            client.connectWebSocket();
            Map<String, Object> version = client.getVersion();
            assertNotNull(version.get("version"), "first RPC must go over the WebSocket");

            // Killing the daemon closes the connection remotely
            daemon.destroy();
            assertTrue(daemon.waitFor(10, TimeUnit.SECONDS), "test daemon must die");

            // The first reconnect attempt (2s backoff + HTTP-probed
            // restart + reconnect) must recover the transport; a client
            // that burns every attempt probing over its own dead socket
            // only recovers — if at all — after all backoffs elapsed
            await().atMost(Duration.ofSeconds(12))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        try {
                            return client.getVersion().get("version") != null;
                        } catch (Exception e) {
                            return false;
                        }
                    });
            assertFalse(client.isWebSocketUseDisabled(),
                    "reconnection must not permanently disable the WebSocket transport");
        } finally {
            try {
                client.disconnectWebSocket();
            } catch (Exception e) {
                // ignore
            }
            try {
                client.stopAria2c();
            } catch (Exception e) {
                // ignore
            }
            daemon.destroyForcibly();
        }
    }

    /** Foreground (non-daemonized) aria2c RPC process the test fully controls. */
    private static Process startForegroundDaemon(int port, String secret, Path downloadDir)
            throws Exception {
        ApplicationContext.initialize();
        List<String> cmd = new ArrayList<>(Arrays.asList(
                ApplicationContext.getToolPath("aria2"),
                "--enable-rpc",
                "--rpc-listen-all=false",
                "--rpc-listen-port=" + port,
                "--rpc-secret=" + secret,
                "--dir=" + downloadDir));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Aria2Client probe = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + port + "/jsonrpc", secret);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                probe.getVersion();
                return process;
            } catch (Exception e) {
                Thread.sleep(200);
            }
        }
        process.destroyForcibly();
        throw new IOException("test daemon on port " + port + " never became responsive");
    }
}
