package org.manager.tools;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.Download;

class NetworkPrivacyRegressionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"curl", "aria2", "subliminal", "bounded", "httrack"})
    void unavailableProxyNeverFallsBackToDirectDespiteNativeBypassSettings(String engine) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] data = "private payload".getBytes();
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        destination.start();
        int proxyPort;
        try (var port = new ServerSocket(0)) { proxyPort = port.getLocalPort(); }
        String url = "http://127.0.0.1:" + destination.getAddress().getPort() + "/payload.bin";
        Path log = directory.resolve("child.log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), engine, url,
                Integer.toString(proxyPort), directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("NO_PROXY", "*");
        builder.environment().put("no_proxy", "127.0.0.1");
        builder.environment().put("HTTP_PROXY", "http://127.0.0.1:1");
        builder.environment().put("http_proxy", "http://127.0.0.1:1");
        Files.writeString(directory.resolve(".curlrc"), "noproxy = \"*\"\nproxy = \"\"\n");
        builder.environment().put("CURL_HOME", directory.toString());
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Native privacy check timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            assertEquals(0, hits.get(), "The destination was contacted outside the selected proxy");
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            destination.stop(0);
        }
    }

    @Test void rejectsAnEnabledProxyWithoutAnAddress() {
        var settings = new org.curl.CurlSettings();
        settings.setUseProxy(true);
        assertThrows(IllegalArgumentException.class, () -> NetworkProcessPolicy.selectedProxy(settings));
    }

    @Test void tlsProxySchemeIsPreserved() {
        assertEquals("https://user:password@localhost:8443",
                NetworkProcessPolicy.proxyAddress("https://user:password@localhost:8443"));
    }

    @Test void httpsProxyConnectionStartsWithTlsRatherThanPlainConnect() throws Exception {
        try (var proxy = new ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(3000);
            var observed = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var socket = proxy.accept()) {
                    socket.setSoTimeout(3000);
                    return socket.getInputStream().readNBytes(3);
                } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
            assertThrows(java.io.IOException.class, () -> BoundedHttpFetcher.fetch(
                    URI.create("https://origin.odm.invalid/resource"), 1024,
                    java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2),
                    "https://127.0.0.1:" + proxy.getLocalPort()));
            byte[] header = observed.get(4, TimeUnit.SECONDS);
            assertEquals(22, header[0], "TLS handshake record required before any proxy request");
            assertEquals(3, header[1]);
        }
    }

    /** An independent JVM supplies hostile inherited settings to the actual production clients. */
    public static class Child {
        public static void main(String[] args) throws Exception {
            String url = args[1];
            String socks = "socks5h://127.0.0.1:" + args[2];
            Path directory = Path.of(args[3]);
            switch (args[0]) {
                case "bounded" -> {
                    try {
                        BoundedHttpFetcher.fetch(URI.create(url), 1024,
                                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1), socks);
                        throw new AssertionError("The request bypassed its unavailable proxy");
                    } catch (java.io.IOException expected) { }
                }
                case "curl" -> {
                    var client = new org.curl.CurlClient(ToolPaths.curl());
                    try {
                        var download = new Download(URI.create(url));
                        var settings = new org.curl.CurlSettings();
                        settings.setUseProxy(true).setProxyAddress(socks);
                        settings.setRetryCount(0);
                        settings.setConnectTimeout(1);
                        download.setSettings(settings);
                        download.setDestination(directory);
                        client.startDownload(download, null).get(5, TimeUnit.SECONDS);
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (download.getStatus() != Download.Status.ERROR && download.getStatus() != Download.Status.COMPLETED
                                && System.nanoTime() < deadline) { Thread.sleep(20); }
                        if (download.getStatus() != Download.Status.ERROR) { throw new AssertionError(download.getStatus()); }
                    } finally { client.shutdown(); }
                }
                case "aria2" -> {
                    int port;
                    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
                    var client = new org.aria2.Aria2Client(ToolPaths.aria2c(),
                            "http://127.0.0.1:" + port + "/jsonrpc", "fixture-secret");
                    try {
                        if (!client.startAria2cWithRpc(List.of("--enable-dht=false", "--enable-dht6=false"))) {
                            throw new AssertionError("aria2 did not start");
                        }
                        var settings = new org.aria2.Aria2Settings();
                        settings.setUseProxy(true).setProxyAddress("http://127.0.0.1:" + args[2]);
                        settings.setOption("all-proxy", "");
                        settings.setOption("http-proxy", "");
                        Map<String, Object> options = settings.toRpcOptions();
                        options.put("dir", directory.toString());
                        options.put("max-tries", "1");
                        String gid = client.addUriRpc(url, options);
                        String state = "";
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!state.equals("error") && !state.equals("complete") && System.nanoTime() < deadline) {
                            state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(client.tellStatus(gid)).path("status").asText();
                            Thread.sleep(20);
                        }
                        if (!state.equals("error")) { throw new AssertionError(state); }
                    } finally { client.stopAria2c(); }
                }
                case "httrack" -> {
                    var client = new org.httrack.HttrackClient();
                    try {
                        var settings = new org.httrack.HttrackSettings().setUrl(url)
                                .setOutputDirectory(directory).setDepth(1).setMaxDurationSeconds(3)
                                .setUseProxy(true).setProxyAddress("http://127.0.0.1:" + args[2]);
                        String wrongProxy = "127.0.0.1:" + URI.create(url).getPort();
                        settings.setOption("P", wrongProxy);
                        settings.setOption("w", "P" + wrongProxy);
                        var terminal = new java.util.concurrent.CompletableFuture<org.httrack.HttrackJob>();
                        client.addNotificationListener(new org.httrack.HttrackClient.HttrackNotificationListener() {
                            @Override public void onJobCompleted(org.httrack.HttrackJob job) { terminal.complete(job); }
                            @Override public void onJobError(org.httrack.HttrackJob job, String error) { terminal.complete(job); }
                        });
                        client.startMirror(settings).get(5, TimeUnit.SECONDS);
                        if (terminal.get(10, TimeUnit.SECONDS).getStatus() != org.httrack.HttrackJob.Status.ERROR) {
                            throw new AssertionError("HTTrack bypassed the selected route");
                        }
                    } finally { client.shutdown(); }
                }
                case "subliminal" -> {
                    Path executable = directory.resolve("provider");
                    Files.writeString(executable, "#!/usr/bin/python3\nimport urllib.request\nurllib.request.urlopen('" + url + "', timeout=2).read()\n");
                    executable.toFile().setExecutable(true);
                    var client = new org.subliminal.SubliminalClient(executable.toString());
                    try {
                        var result = client.downloadWithResult(directory.resolve("movie.mp4"),
                                new org.subliminal.SubliminalSettings().setProxyAddress(socks), "private-subtitles");
                        if (result.successful()) { throw new AssertionError("Subtitle provider bypassed the proxy"); }
                    } finally { client.shutdown(); }
                }
                default -> throw new AssertionError(args[0]);
            }
        }
    }
}
