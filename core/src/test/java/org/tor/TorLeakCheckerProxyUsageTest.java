package org.tor;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DNS-through-Tor probe must actually route through the configured SOCKS
 * proxy. The old implementation built a Proxy object and then never used it:
 * a plain Socket connected DIRECTLY to the test domain — emitting
 * non-anonymized traffic, the exact leak class the checker exists to detect,
 * and making its results meaningless (both resolution lists were direct).
 */
@DisplayName("TorLeakChecker DNS probe routes through the SOCKS proxy")
class TorLeakCheckerProxyUsageTest {

    @Test
    @DisplayName("resolveDnsThroughTor connects via the configured proxy, not directly")
    void dnsProbeUsesSocksProxy() throws Exception {
        // A fake SOCKS endpoint: we only need to observe that the probe's
        // TCP connection arrives HERE (at the configured proxy address).
        // The SOCKS handshake need not succeed.
        CountDownLatch proxyConnection = new CountDownLatch(1);
        ExecutorService acceptor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            int proxyPort = listener.getLocalPort();
            acceptor.submit(() -> {
                try (Socket client = listener.accept()) {
                    client.getInputStream().read(); // SOCKS greeting byte
                    proxyConnection.countDown();
                } catch (Exception e) {
                    // connection reset after handshake attempt is fine
                }
                return null;
            });

            TorLeakChecker checker = new TorLeakChecker("127.0.0.1", proxyPort, 2000, 2000);
            try {
                checker.resolveDnsThroughTorForTest("leak-check-probe.invalid");

                assertTrue(proxyConnection.await(5, TimeUnit.SECONDS),
                        "the DNS probe must connect through the configured SOCKS proxy — "
                                + "a direct connection is precisely the leak it must never make");
            } finally {
                checker.shutdown();
            }
        } finally {
            acceptor.shutdownNow();
        }
    }
}
