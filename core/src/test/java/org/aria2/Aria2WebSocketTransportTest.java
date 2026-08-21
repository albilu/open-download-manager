package org.aria2;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
