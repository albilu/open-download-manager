package org.tor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SOCKS probe destination must be UNRESOLVED even for a normal,
 * resolvable hostname. {@code new InetSocketAddress(domain, port)} resolves
 * eagerly through the LOCAL resolver and Java's SOCKS layer then sends that
 * IP (not the hostname) to the proxy — a DNS leak. Only an unresolved
 * address makes the SOCKS5 request carry the hostname so Tor resolves it
 * remotely. A {@code .invalid} host cannot expose this: its eager
 * resolution always fails, which leaves the address unresolved by accident
 * and hides the leak.
 */
@DisplayName("TorLeakChecker SOCKS probe destination stays unresolved")
class TorLeakCheckerDnsDestinationTest {

    @Test
    @DisplayName("a normal hostname reaches the SOCKS connector as an unresolved address")
    void normalHostnameReachesConnectorUnresolved() {
        AtomicReference<SocketAddress> captured = new AtomicReference<>();
        TorLeakChecker checker = new TorLeakChecker("127.0.0.1", 1, 100, 100) {
            @Override
            void connectProbe(Socket socket, SocketAddress destination, int timeout) throws IOException {
                captured.set(destination);
            }
        };
        try {
            checker.resolveDnsThroughTorForTest("example.com");

            InetSocketAddress destination = assertInstanceOf(InetSocketAddress.class, captured.get(),
                    "the probe must hand an InetSocketAddress to the connector");
            assertTrue(destination.isUnresolved(),
                    "the probe destination must stay UNRESOLVED so the SOCKS5 request carries "
                            + "the hostname and Tor resolves it remotely; a resolved destination "
                            + "leaked the lookup to the local resolver and sends the proxy an IP "
                            + "instead of the name — got " + destination);
            assertEquals("example.com", destination.getHostString(),
                    "the unresolved destination must preserve the requested hostname");
            assertEquals(80, destination.getPort(),
                    "the unresolved destination must preserve the requested port");
        } finally {
            checker.shutdown();
        }
    }

    @Test
    @DisplayName("the SOCKS5 CONNECT request carries the hostname, not a locally resolved IP")
    void socks5ConnectCarriesHostname() throws Exception {
        CountDownLatch sawConnect = new CountDownLatch(1);
        AtomicReference<String> target = new AtomicReference<>();
        ExecutorService acceptor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0)) {
            acceptor.submit(() -> {
                try (Socket client = listener.accept();
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream()) {
                    int ver = in.read();
                    int nmethods = in.read();
                    in.readNBytes(nmethods);
                    out.write(new byte[]{0x05, 0x00});
                    out.flush();
                    in.read(); // VER
                    in.read(); // CMD
                    in.read(); // RSV
                    int atyp = in.read();
                    String t;
                    if (atyp == 0x03) {
                        int len = in.read();
                        t = "DOMAIN:" + new String(in.readNBytes(len), StandardCharsets.ISO_8859_1);
                    } else if (atyp == 0x01) {
                        t = "IPV4:" + InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                    } else {
                        t = "ATYP:" + atyp;
                    }
                    in.readNBytes(2); // DST.PORT
                    target.set(t);
                    out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 80});
                    out.flush();
                    sawConnect.countDown();
                } catch (Exception e) {
                    // client may close early; the latch and target tell the story
                }
                return null;
            });

            TorLeakChecker checker = new TorLeakChecker("127.0.0.1", listener.getLocalPort(), 2000, 2000);
            try {
                checker.resolveDnsThroughTorForTest("example.com");

                assertTrue(sawConnect.await(5, TimeUnit.SECONDS),
                        "the probe must complete a SOCKS5 CONNECT handshake");
                assertEquals("DOMAIN:example.com", target.get(),
                        "the SOCKS5 request must carry the hostname (ATYP=DOMAIN) so Tor resolves "
                                + "it remotely; an IPv4 target means the local resolver already "
                                + "leaked the lookup — got " + target.get());
            } finally {
                checker.shutdown();
            }
        } finally {
            acceptor.shutdownNow();
        }
    }
}
