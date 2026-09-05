package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import utils.SocksHttpServer;
import static org.junit.jupiter.api.Assertions.*;

class YtDlpSocksRoutingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"socks4", "socks4a", "socks5", "socks5h"})
    void socksOverridesExternalAria2EvenWhenExternalConfigurationIsHonored(String scheme) {
        YtDlpClient client = new YtDlpClient("yt-dlp", true, true);
        try {
            YtDlpSettings settings = settings(scheme + "://127.0.0.1:9050");
            var command = client.buildDownloadCommand("http://media.invalid/clip.mp4", settings, directory);
            assertEquals("native", command.get(command.indexOf("--external-downloader") + 1));
            assertTrue(command.contains("http,ftp,m3u8,dash:native"));
            assertEquals(settings.getProxyAddress(), command.get(command.indexOf("--proxy") + 1));
            assertFalse(command.contains("--external-downloader-args"));
            assertTrue(settings.isUseAria2c(), "the saved preference remains unchanged");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void realYtDlpDownloadsThroughAuthenticatedSocksWithRemoteDns() throws Exception {
        String payload = "ODM media payload through SOCKS";
        try (SocksHttpServer proxy = new SocksHttpServer(true, payload)) {
            YtDlpClient client = new YtDlpClient(org.manager.tools.ToolPaths.ytDlp(), true, true);
            try {
                String url = "http://media.odm.invalid/clip.mp4";
                YtDlpSettings settings = settings("socks5h://user:secret@127.0.0.1:" + proxy.port());
                settings.setOutputTemplate("clip.mp4");
                var command = client.buildDownloadCommand(url, settings, directory);
                command.removeLast();
                Path info = Files.writeString(directory.resolve("info.json"),
                        "{\"id\":\"clip\",\"title\":\"clip\",\"url\":\"" + url
                        + "\",\"ext\":\"mp4\",\"extractor\":\"generic\",\"webpage_url\":\"" + url + "\"}");
                command.add("--load-info-json");
                command.add(info.toString());
                Path config = Files.writeString(directory.resolve("yt-dlp.conf"),
                        "--downloader http:aria2c\n");
                command.add("--config-locations");
                command.add(config.toString());
                Path log = directory.resolve("native.log");
                Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
                try {
                    assertTrue(process.waitFor(20, TimeUnit.SECONDS), "yt-dlp timed out");
                    assertEquals(0, process.exitValue(), Files.readString(log));
                    assertEquals(payload, Files.readString(directory.resolve("clip.mp4")));
                    assertTrue(proxy.hosts.contains("media.odm.invalid"));
                    assertTrue(proxy.credentials.contains("user:secret"));
                } finally {
                    process.destroyForcibly();
                }
            } finally {
                client.shutdown();
            }
        }
    }

    private YtDlpSettings settings(String proxy) {
        YtDlpSettings settings = new YtDlpSettings().setUseAria2c(true)
                .setAria2cPath("/unavailable/aria2c");
        settings.setUseProxy(true);
        settings.setProxyAddress(proxy);
        return settings;
    }
}
