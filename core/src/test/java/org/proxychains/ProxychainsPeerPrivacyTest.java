package org.proxychains;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;

class ProxychainsPeerPrivacyTest {
    @TempDir Path directory;

    @Test void incomingPeersCannotReachAnInternetFacingListener() throws Exception {
        int listenerPort;
        int proxyPort;
        try (var socket = new ServerSocket(0)) { listenerPort = socket.getLocalPort(); }
        try (var socket = new ServerSocket(0)) { proxyPort = socket.getLocalPort(); }
        var download = new Download(java.net.URI.create(
                "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"));
        download.setDestination(directory);
        download.setUseProxy(true).setProxyAddress("socks5h://127.0.0.1:" + proxyPort);
        var client = new ProxychainsClient();
        client.setTorrentListenPorts(Integer.toString(listenerPort));
        try {
            client.startDownload(download, null, Map.of()).get(5, TimeUnit.SECONDS);
            final int port = listenerPort;
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> connects("127.0.0.1", port));
            // 127.0.0.2 distinguishes an explicit loopback bind from 0.0.0.0 even without a NIC.
            assertFalse(connects("127.0.0.2", port), "The peer listener accepted another local interface");
            for (var nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                    if (!address.isLoopbackAddress()) {
                        assertFalse(connects(address.getHostAddress(), port), "Peer listener exposed on " + address);
                    }
                }
            }
        } finally { client.shutdown(); }
    }

    @Test void proxyAuthorityAndDiscoveryFlagsSurviveImportedOptions() {
        var download = new Download(java.net.URI.create("magnet:?xt=urn:btih:abcdef"));
        download.setDestination(directory);
        var client = new ProxychainsClient();
        try {
            List<String> command = client.buildProxychainsCommand(download, directory.resolve("payload"),
                    directory.resolve("proxy.conf"), Map.of("aria2.enable-peer-exchange", "true",
                            "aria2.bt-enable-lpd", "true", "aria2.http-proxy", "http://other.invalid:80"));
            assertEquals("--enable-peer-exchange=false", command.stream()
                    .filter(value -> value.startsWith("--enable-peer-exchange=")).reduce((a, b) -> b).orElseThrow());
            assertTrue(command.contains("--enable-dht=false"));
            assertTrue(command.contains("--enable-dht6=false"));
            assertTrue(command.contains("--interface=127.0.0.1"));
            assertFalse(command.stream().anyMatch(value -> value.contains("other.invalid")));
        } finally { client.shutdown(); }
    }

    private static boolean connects(String address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 200);
            return true;
        } catch (java.io.IOException unavailable) { return false; }
    }
}
