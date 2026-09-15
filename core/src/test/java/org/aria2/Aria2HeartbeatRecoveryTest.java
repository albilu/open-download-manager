package org.aria2;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class Aria2HeartbeatRecoveryTest {
    @Test
    @Timeout(40)
    void missedPongFailsPendingCallsAndConcurrentCallersShareRecovery() throws Exception {
        var ready = new CountDownLatch(1);
        var requests = new CountDownLatch(2);
        var connections = new AtomicInteger();
        var removes = new AtomicInteger();
        var firstConnection = new AtomicReference<WebSocket>();
        var serverFailure = new AtomicReference<Throwable>();
        var json = new ObjectMapper();
        var server = new WebSocketServer(new InetSocketAddress("127.0.0.1", 0)) {
            public void onStart() { ready.countDown(); }
            public void onOpen(WebSocket connection, ClientHandshake handshake) {
                connections.incrementAndGet();
                firstConnection.compareAndSet(null, connection);
            }
            public void onClose(WebSocket connection, int code, String reason, boolean remote) { }
            public void onError(WebSocket connection, Exception error) { serverFailure.set(error); }
            @Override public void onWebsocketPing(WebSocket connection, Framedata frame) {
                if (connection != firstConnection.get()) { super.onWebsocketPing(connection, frame); }
            }
            public void onMessage(WebSocket connection, String message) {
                try {
                    var request = json.readTree(message);
                    assertEquals("token:fixture-secret", request.path("params").get(0).asText());
                    if (request.path("method").asText().equals("aria2.remove")) { removes.incrementAndGet(); }
                    if (connection == firstConnection.get()) {
                        requests.countDown();
                        return; // Leave RPCs pending and suppress pongs on this connection.
                    }
                    connection.send(json.writeValueAsString(Map.of("jsonrpc", "2.0",
                            "id", request.get("id"), "result", Map.of("version", "fixture"))));
                } catch (Throwable failure) { serverFailure.set(failure); }
            }
        };
        server.setConnectionLostTimeout(0);
        server.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        Aria2Client client = new Aria2Client("aria2c", "http://127.0.0.1:" + server.getPort() + "/jsonrpc",
                "fixture-secret") {
            // This fixture exercises the real WebSocket transport without a
            // daemon. The listening endpoint remains available during recovery.
            @Override public boolean isAria2Running() { return true; }
        };
        var callers = Executors.newFixedThreadPool(6);
        client.setUseWebSocket(true);
        try {
            client.connectWebSocket();
            var socketField = Aria2Client.class.getDeclaredField("wsClient");
            socketField.setAccessible(true);
            WebSocketClient firstSocket = (WebSocketClient) socketField.get(client);
            var mutation = callers.submit(() -> client.remove("pending-gid"));
            var read = callers.submit(() -> client.getFiles("pending-gid"));
            assertTrue(requests.await(5, TimeUnit.SECONDS));
            firstSocket.setConnectionLostTimeout(1);

            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> mutation.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof java.io.IOException);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> read.get(1, TimeUnit.SECONDS));
            assertEquals(0, client.pendingWebSocketResponseCount());

            var recovered = callers.invokeAll(java.util.stream.IntStream.range(0, 6)
                    .<java.util.concurrent.Callable<String>>mapToObj(i -> () ->
                            client.getVersion().get("version").toString()).toList(), 10, TimeUnit.SECONDS);
            for (var result : recovered) { assertEquals("fixture", result.get()); }
            assertEquals(2, connections.get(), "concurrent calls must share one replacement socket");
            assertEquals(1, removes.get(), "a failed mutation must never be replayed");

            // A delayed callback from the retired socket cannot close the replacement.
            firstSocket.onClose(1006, "late close", false);
            assertEquals("fixture", client.getVersion().get("version"));
            assertEquals(2, connections.get());
            assertNull(serverFailure.get());
        } finally {
            client.disconnectWebSocket();
            callers.shutdownNow();
            server.stop(1000);
        }
    }
}
