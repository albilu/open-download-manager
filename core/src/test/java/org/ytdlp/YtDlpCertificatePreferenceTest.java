package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.GlobalSettings;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.NetworkProcessPolicy;
import org.manager.tools.ToolPaths;
import utils.TestTlsCertificate;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class YtDlpCertificatePreferenceTest {
    @TempDir static Path certificates;
    @TempDir Path directory;
    private static TestTlsCertificate certificate;
    private static byte[] media;

    @BeforeAll static void fixture() throws Exception {
        certificate = TestTlsCertificate.create(certificates);
        try (var input = YtDlpCertificatePreferenceTest.class.getResourceAsStream("/media/ytdlp-test-video.mp4")) {
            media = input.readAllBytes();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,true", "false,false", "true,true", "true,false"})
    void nativeAndExternalAria2DownloadsRespectVerification(boolean external, boolean verify) throws Exception {
        GlobalSettings global = new GlobalSettings();
        global.setVerifyHttpsCertificates(verify);
        YtDlpSettings settings = new DownloadSettingsFactory(global).createYtDlpSettings();
        settings.setUseAria2c(external);
        settings.setOutputTemplate("output.mp4");
        settings.setMaxRetries(1);
        settings.setAria2cMaxTries(1);
        settings.setOption("no-check-certificate", ""); // old per-record options must not override the global choice
        try (var server = server()) {
            String url = server.url("/video.mp4").toString();
            var client = new YtDlpClient(ToolPaths.ytDlp());
            try {
                // Skip extraction to exercise the selected downloader's TLS connection.
                Path info = Files.writeString(directory.resolve("info.json"),
                        "{\"id\":\"video\",\"title\":\"video\",\"url\":\"" + url
                        + "\",\"ext\":\"mp4\",\"extractor\":\"generic\",\"webpage_url\":\"" + url + "\"}");
                var command = client.buildDownloadCommand(url, settings, directory);
                command.removeLast();
                command.add("--load-info-json");
                command.add(info.toString());
                Path log = directory.resolve("download.log");
                Process process = NetworkProcessPolicy.prepare(new ProcessBuilder(command))
                        .directory(directory.toFile())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "yt-dlp timed out");
                    if (verify) {
                        assertNotEquals(0, process.exitValue(), Files.readString(log));
                        assertCertificateError(Files.readString(log));
                        assertEquals(0, server.getRequestCount());
                        assertFalse(Files.exists(directory.resolve("output.mp4")));
                    } else {
                        assertEquals(0, process.exitValue(), Files.readString(log));
                        assertArrayEquals(media, Files.readAllBytes(directory.resolve("output.mp4")));
                    }
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test void metadataAndFormatRequestsUseTheSamePreference() throws Exception {
        GlobalSettings global = new GlobalSettings();
        global.setVerifyHttpsCertificates(false);
        var factory = new DownloadSettingsFactory(global);
        var settings = factory.createYtDlpSettings();
        settings.setMaxRetries(1);
        try (var server = server()) {
            String url = server.url("/video.mp4").toString();
            var client = new YtDlpClient(ToolPaths.ytDlp());
            try {
                assertNotNull(client.extractInfo(url, settings).get(15, TimeUnit.SECONDS));
                assertFalse(client.listFormats(url, settings).get(15, TimeUnit.SECONDS).isEmpty());
                client.downloadSubtitles(url, settings, directory, "relaxed-subtitles").get(15, TimeUnit.SECONDS);
                int requests = server.getRequestCount();
                global.setVerifyHttpsCertificates(true);
                factory.applyGlobalTransferPreferences(settings);
                assertCertificateError(assertThrows(ExecutionException.class,
                        () -> client.extractInfo(url, settings).get(15, TimeUnit.SECONDS)).getMessage());
                assertCertificateError(assertThrows(ExecutionException.class,
                        () -> client.listFormats(url, settings).get(15, TimeUnit.SECONDS)).getMessage());
                assertThrows(ExecutionException.class, () -> client.downloadSubtitles(
                        url, settings, directory, "strict-subtitles").get(15, TimeUnit.SECONDS));
                // The subtitle wrapper discards native output; run its exact command
                // with a captured log to verify the failure really came from TLS.
                Path log = directory.resolve("subtitles.log");
                Process subtitles = NetworkProcessPolicy.prepare(new ProcessBuilder(
                        client.buildSubtitleCommand(url, settings, directory)))
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    assertTrue(subtitles.waitFor(15, TimeUnit.SECONDS));
                    assertNotEquals(0, subtitles.exitValue());
                    assertCertificateError(Files.readString(log));
                } finally {
                    subtitles.destroyForcibly();
                }
                assertEquals(requests, server.getRequestCount());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test void browserProbeUsesTheExplicitCertificatePreference() throws Exception {
        GlobalSettings global = new GlobalSettings();
        var factory = new DownloadSettingsFactory(global);
        var settings = factory.createYtDlpSettings();
        try (var server = server(); var probe = new BrowserMediaProbe(2500)) {
            var url = server.url("/page").uri();
            assertTrue(probe.probe(url, settings, null).get(15, TimeUnit.SECONDS).isEmpty());
            assertEquals(0, server.getRequestCount());
            global.setVerifyHttpsCertificates(false);
            factory.applyGlobalTransferPreferences(settings);
            var found = probe.probe(url, settings, null).get(15, TimeUnit.SECONDS);
            assertTrue(found.stream().anyMatch(candidate -> candidate.url().equals(server.url("/video.mp4").toString())));
        }
    }

    private static MockWebServer server() throws Exception {
        var server = new MockWebServer();
        server.useHttps(certificate.context().getSocketFactory(), false);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if ("/page".equals(request.getPath())) {
                    return new MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<html><video src='/video.mp4' preload='auto' controls></video></html>");
                }
                return new MockResponse().setHeader("Content-Type", "video/mp4")
                        .setBody(new Buffer().write(media));
            }
        });
        server.start();
        return server;
    }

    private static void assertCertificateError(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        assertTrue(lower.contains("not signed by known authorities")
                || (lower.contains("certificate") && (lower.contains("verify")
                    || lower.contains("verification") || lower.contains("not trusted"))), message);
        assertFalse(lower.contains("no such option"), message);
    }
}
