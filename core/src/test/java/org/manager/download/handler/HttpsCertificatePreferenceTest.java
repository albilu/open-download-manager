package org.manager.download.handler;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import utils.SocksHttpServer;
import utils.TestTlsCertificate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises settings-to-process wiring, including the real SOCKS fallback path. */
@Timeout(60)
class HttpsCertificatePreferenceTest {
    @TempDir static Path certificates;
    @TempDir Path directory;
    private static TestTlsCertificate certificate;

    @BeforeAll static void createCertificate() throws Exception {
        certificate = TestTlsCertificate.create(certificates);
    }

    @ParameterizedTest
    @CsvSource({"ARIA2,true", "ARIA2,false", "CURL,true", "CURL,false",
            "PROXYCHAINS,true", "PROXYCHAINS,false"})
    void nativeTransfersRespectTheGlobalCertificatePreference(Download.Type engine, boolean verify)
            throws Exception {
        String payload = "Downloaded through the selected HTTPS engine";
        GlobalSettings global = new GlobalSettings().setDefaultDownloadDirectory(directory);
        global.setVerifyHttpsCertificates(verify);
        global.setProperty("network.maxConnections", "1");
        global.setProperty("network.maxRetries", "1");
        global.setProperty("aria2.fileAllocation", "none");
        try (var socket = new ServerSocket(0)) { global.setAria2RpcPort(socket.getLocalPort()); }
        var factory = new DownloadSettingsFactory(global);
        var tools = new ToolManagerFactory(global, directory.resolve("tools"));
        try (var server = new MockWebServer(); var executor = Executors.newCachedThreadPool()) {
            server.useHttps(certificate.context().getSocketFactory(), false);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setBody(payload);
                }
            });
            server.start();
            try (var socks = SocksHttpServer.tunnel(new InetSocketAddress("127.0.0.1", server.getPort()))) {
                DownloadHandler handler = switch (engine) {
                    case ARIA2 -> new Aria2DownloadHandler(global, factory, executor, tools);
                    case CURL -> new CurlDownloadHandler(global, factory, executor, tools);
                    case PROXYCHAINS -> new ProxychainsDownloadHandler(global, factory, executor, tools);
                    default -> throw new AssertionError(engine);
                };
                try {
                    handler.initialize().get(20, TimeUnit.SECONDS);
                    URI source = engine == Download.Type.PROXYCHAINS
                            ? URI.create("https://certificate.odm.invalid:" + server.getPort() + "/file.bin")
                            : server.url("/file.bin").uri();
                    Download download = new Download(source);
                    download.setDestination(directory);
                    download.setType(engine);
                    download.setName("file.bin");
                    download.setSettings(factory.createSettings(engine, download.getProtocol()));
                    if (engine == Download.Type.PROXYCHAINS) {
                        download.setUseProxy(true).setProxyAddress("socks5://127.0.0.1:" + socks.port());
                    }
                    handler.startDownload(download).get(20, TimeUnit.SECONDS);
                    await().atMost(Duration.ofSeconds(20)).until(() ->
                            download.getStatus() == Download.Status.COMPLETED
                                    || download.getStatus() == Download.Status.ERROR);
                    assertEquals(verify ? Download.Status.ERROR : Download.Status.COMPLETED,
                            download.getStatus(), download.getErrorMessage());
                    if (verify) {
                        assertEquals(0, server.getRequestCount(), "invalid TLS must fail before sending HTTP");
                    } else {
                        assertEquals(payload, Files.readString(directory.resolve("file.bin")));
                    }
                    if (engine == Download.Type.PROXYCHAINS) {
                        assertTrue(socks.hosts.contains("certificate.odm.invalid"));
                    }
                } finally {
                    handler.shutdown().get(15, TimeUnit.SECONDS);
                }
            }
        }
    }
}
