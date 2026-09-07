package org.ytdlp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoutedMediaToolsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"http", "socks5h"})
    void liveHlsCannotEscapeThroughFfmpeg(String scheme) throws Exception {
        var hits = new AtomicInteger();
        var origin = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] data = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n".getBytes();
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        origin.start();
        int port;
        try (var closed = new ServerSocket(0)) { port = closed.getLocalPort(); }
        String proxy = scheme + "://127.0.0.1:" + port;
        String url = "http://127.0.0.1:" + origin.getAddress().getPort() + "/live.m3u8";
        var client = new YtDlpClient();
        try (var tools = RoutedMediaTools.prepare(proxy)) {
            var settings = new YtDlpSettings();
            settings.setUseProxy(true).setProxyAddress(proxy);
            settings.setUseAria2c(true);
            settings.setMaxRetries(0);
            var command = client.buildDownloadCommand(url, settings, directory);
            command.removeLast();
            Path info = Files.writeString(directory.resolve("live.json"),
                    "{\"id\":\"live\",\"title\":\"live\",\"url\":\"" + url
                    + "\",\"ext\":\"mp4\",\"protocol\":\"m3u8_native\",\"is_live\":true,"
                    + "\"extractor\":\"generic\",\"webpage_url\":\"" + url + "\"}");
            command.addAll(List.of("--load-info-json", info.toString(), "--verbose"));
            tools.applyTo(command);
            Path log = directory.resolve("live.log");
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("NO_PROXY", "*");
            builder.environment().put("http_proxy", proxy);
            Process process = org.manager.tools.NetworkProcessPolicy.prepare(builder).start();
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Live HLS check timed out");
                String output = Files.readString(log);
                assertNotEquals(0, process.exitValue(), output);
                assertTrue(output.contains("ffmpeg"), output);
                assertEquals(0, hits.get(), output);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } finally { client.shutdown(); origin.stop(0); }
    }

    @Test void ffmpegContactsTheSelectedSocksEndpoint() throws Exception {
        // A WAV header with one second of mono PCM samples.
        var bytes = java.nio.ByteBuffer.allocate(44 + 16000).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes()).putInt(36 + 16000).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(16000);
        try (var proxy = new utils.SocksHttpServer(false, bytes.array());
                var tools = RoutedMediaTools.prepare("socks5h://127.0.0.1:" + proxy.port())) {
            var command = new java.util.ArrayList<>(List.of("yt-dlp"));
            tools.applyTo(command);
            Path ffmpeg = Path.of(command.get(command.indexOf("--ffmpeg-location") + 1)).resolve("ffmpeg");
            Path log = directory.resolve("ffmpeg.log");
            var builder = new ProcessBuilder(ffmpeg.toString(), "-v", "error", "-i",
                    "http://audio.odm.invalid/clip.wav", "-f", "null", "-")
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("HTTP_PROXY", "socks5h://127.0.0.1:1");
            builder.environment().put("NO_PROXY", "*");
            Process process = builder.start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), Files.readString(log));
                assertTrue(proxy.hosts.contains("audio.odm.invalid"));
            } finally { process.destroyForcibly(); }
        }
    }

    @Test void unsupportedTlsProxyFailsBeforeStartingMediaTools() {
        assertThrows(java.io.IOException.class, () -> RoutedMediaTools.prepare("https://127.0.0.1:443"));
    }
}
