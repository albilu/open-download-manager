package org.tor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Truthful DNS-leak verdicts. The old logic stored the probe destination's
 * A records as "DNS servers" and compared them with the SOCKS endpoint or
 * bind address; that comparison cannot identify the resolver and reports
 * "DNS properly routed" from fabricated data. The verdict may only claim:
 * LEAK when local resolvers (read from /etc/resolv.conf, never fabricated)
 * coincide with evidence that the probe domain was resolved locally;
 * ROUTED when the probe carried the hostname unresolved through the SOCKS
 * proxy; otherwise UNVERIFIED — never "properly routed".
 */
@DisplayName("TorLeakChecker DNS-leak verdict semantics")
class TorLeakCheckerDnsVerdictTest {

    private static final List<String> NO_RESOLVERS = List.of();
    private static final List<String> LOCAL_RESOLVERS = List.of("192.168.1.1");

    @Test
    @DisplayName("scenario the old fabricated-list logic called 'properly routed' is unverified")
    void fabricatedOldSecureScenarioReportsUnverified() throws Exception {
        List<String> fabricatedDirect = List.of("93.184.216.34");
        List<String> fabricatedTor = List.of("0.0.0.0");
        boolean oldLogicSecure = !fabricatedTor.isEmpty()
                && (fabricatedDirect.isEmpty() || !fabricatedDirect.equals(fabricatedTor));
        assertTrue(oldLogicSecure, "fixture must reproduce the old logic's false 'properly routed'");

        try (FakeSocksProxy proxy = new FakeSocksProxy()) {
            TorLeakChecker checker = checker(NO_RESOLVERS, true, proxy.port());
            TorLeakChecker.DnsLeakResult result = checker.checkDnsLeak();

            assertEquals(TorLeakChecker.DnsVerdict.UNVERIFIED, result.verdict,
                    "without a readable local resolver the local resolution cannot be attributed — "
                            + "the verdict must not claim safety");
            assertFalse(result.isSecure);
            assertFalse(result.message.toLowerCase().contains("properly routed"));
            checker.shutdown();
        }
    }

    @Test
    @DisplayName("local resolver coinciding with locally resolved probe domain is a leak")
    void localResolutionCoincidenceReportsLeak() throws Exception {
        try (FakeSocksProxy proxy = new FakeSocksProxy()) {
            TorLeakChecker checker = checker(LOCAL_RESOLVERS, true, proxy.port());

            assertEquals(TorLeakChecker.DnsVerdict.DNS_LEAK, checker.checkDnsLeak().verdict,
                    "a resolv.conf resolver plus a locally resolved probe domain is DNS leak evidence");
            checker.shutdown();
        }
    }

    @Test
    @DisplayName("SOCKS-routed probe with unresolved destination is routed")
    void socksRoutedUnresolvedReportsRouted() throws Exception {
        try (FakeSocksProxy proxy = new FakeSocksProxy()) {
            TorLeakChecker checker = checker(LOCAL_RESOLVERS, false, proxy.port());
            TorLeakChecker.DnsLeakResult result = checker.checkDnsLeak();

            assertEquals(TorLeakChecker.DnsVerdict.ROUTED_THROUGH_TOR, result.verdict,
                    "a probe that carried the hostname through the SOCKS proxy unresolved proves "
                            + "Tor-side resolution");
            assertTrue(result.isSecure);
            checker.shutdown();
        }
    }

    private static TorLeakChecker checker(List<String> resolvers, boolean resolveProbeDestination, int proxyPort) {
        return new TorLeakChecker("127.0.0.1", proxyPort, 2000, 2000) {
            @Override
            List<String> readResolvConfNameservers() {
                return resolvers;
            }

            @Override
            SocketAddress probeDestination(String domain, int port) {
                if (!resolveProbeDestination) {
                    return InetSocketAddress.createUnresolved(domain, port);
                }
                try {
                    return new InetSocketAddress(
                            InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}), port);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** Minimal SOCKS5 mock: completes any number of greeting/CONNECT handshakes. */
    private static final class FakeSocksProxy implements AutoCloseable {

        private final ServerSocket listener;
        private final ExecutorService acceptor;

        FakeSocksProxy() throws IOException {
            listener = new ServerSocket(0);
            acceptor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fake-socks");
                t.setDaemon(true);
                return t;
            });
            acceptor.execute(() -> {
                while (!listener.isClosed()) {
                    try (Socket client = listener.accept()) {
                        handle(client);
                    } catch (Exception e) {
                        break;
                    }
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        private static void handle(Socket client) throws IOException {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            in.read(); // VER
            int nmethods = in.read();
            in.readNBytes(nmethods);
            out.write(new byte[]{0x05, 0x00});
            out.flush();
            in.read(); // VER
            in.read(); // CMD
            in.read(); // RSV
            int atyp = in.read();
            if (atyp == 0x03) {
                int len = in.read();
                in.readNBytes(len);
            } else if (atyp == 0x01) {
                in.readNBytes(4);
            } else if (atyp == 0x04) {
                in.readNBytes(16);
            }
            in.readNBytes(2); // DST.PORT
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 80});
            out.flush();
        }

        @Override
        public void close() throws IOException {
            try {
                acceptor.shutdownNow();
                awaitTermination();
            } finally {
                listener.close();
            }
        }

        private void awaitTermination() {
            try {
                acceptor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
