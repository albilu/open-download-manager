package org.aria2;

import static org.junit.jupiter.api.Assertions.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Aria2DiscoveryPrivacyTest {
    @TempDir Path directory;

    @Test void restartingForPrivacyRetiresDiscoveryAndDisablesUdpTrackers() throws Exception {
        int rpcPort;
        try (var socket = new ServerSocket(0)) { rpcPort = socket.getLocalPort(); }
        try (var tracker = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            tracker.setSoTimeout(4000);
            var client = new Aria2Client(org.manager.tools.ToolPaths.aria2c(),
                    "http://127.0.0.1:" + rpcPort + "/jsonrpc", "privacy-fixture");
            try {
                assertTrue(client.startAria2cWithRpc(List.of("--enable-dht=true", "--enable-dht6=false",
                        "--dht-entry-point=127.0.0.1:" + tracker.getLocalPort(),
                        "--dht-file-path=" + directory.resolve("dht"))));
                Object session = client.getSessionInfo().get("sessionId");
                String magnet = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"
                        + "&tr=udp%3A%2F%2F127.0.0.1%3A" + tracker.getLocalPort() + "%2Fannounce";
                client.addUriRpc(magnet, Map.of("dir", directory.toString()));
                tracker.receive(new DatagramPacket(new byte[4096], 4096));
                assertTrue(client.restartWithPeerDiscoveryOptions(List.of("--enable-dht=false", "--enable-dht6=false",
                        "--enable-peer-exchange=false", "--bt-enable-lpd=false")));
                assertNotEquals(session, client.getSessionInfo().get("sessionId"));
                assertEquals("false", client.getGlobalOption().get("enable-dht"));
                assertEquals("false", client.getGlobalOption().get("enable-dht6"));
                tracker.setSoTimeout(100);
                try {
                    while (true) { tracker.receive(new DatagramPacket(new byte[4096], 4096)); }
                } catch (SocketTimeoutException drained) { }
                client.addUriRpc(magnet, Map.of("dir", directory.toString()));
                tracker.setSoTimeout(1500);
                assertThrows(SocketTimeoutException.class,
                        () -> tracker.receive(new DatagramPacket(new byte[4096], 4096)));
            } finally { client.stopAria2c(); }
        }
    }
}
