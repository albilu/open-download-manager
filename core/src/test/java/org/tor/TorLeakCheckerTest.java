package org.tor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import utils.SocksHttpServer;
import static org.junit.jupiter.api.Assertions.*;

class TorLeakCheckerTest {
    @Test void checksOnlyTheTorEndpointThroughSocks() throws Exception {
        try (var proxy = new SocksHttpServer(false, "{\"IsTor\":true,\"IP\":\"192.0.2.1\"}")) {
            var checker = new TorLeakChecker("127.0.0.1", proxy.port(), 1000, 1000,
                    URI.create("http://check.torproject.org/api/ip"));
            try {
                var result = checker.performLeakCheck().get(5, TimeUnit.SECONDS);
                assertTrue(result.isSecure, result.message);
                assertEquals("192.0.2.1", result.exitNodeIp);
                assertEquals(java.util.List.of("check.torproject.org"), proxy.hosts);
                assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
            } finally { checker.shutdown(); }
        }
    }

    @Test void falseIsTorDoesNotVerifyTheCircuit() throws Exception {
        var result = TorLeakChecker.parseResponse(
                "{\"IsTor\":false,\"IP\":\"192.0.2.2\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isSecure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "not json", "{\"IsTor\":\"true\",\"IP\":\"x\"}",
            "{\"IsTor\":true}", "{\"IsTor\":true,\"IP\":\"\"}"})
    void rejectsIncompleteOrMalformedVerdicts(String json) {
        assertThrows(java.io.IOException.class,
                () -> TorLeakChecker.parseResponse(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void closedCheckerCannotRestartNetworkActivity() throws Exception {
        var checker = new TorLeakChecker();
        checker.shutdown();
        assertFalse(checker.performLeakCheck().get().isSecure);
        assertNull(checker.getExternalIp().get());
    }

    @Test void unavailableProxyDoesNotFallBackToDirect() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { hits.incrementAndGet(); exchange.close(); });
        server.start();
        var closedPort = new java.net.ServerSocket(0);
        int port = closedPort.getLocalPort();
        closedPort.close();
        var checker = new TorLeakChecker("127.0.0.1", port, 200, 200,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        try {
            assertFalse(checker.performLeakCheck().get(3, TimeUnit.SECONDS).isSecure);
            assertEquals(0, hits.get());
        } finally { checker.shutdown(); server.stop(0); }
    }
}
