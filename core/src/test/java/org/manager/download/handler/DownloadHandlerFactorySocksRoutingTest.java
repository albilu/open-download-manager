package org.manager.download.handler;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.curl.CurlSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownloadHandlerFactorySocksRoutingTest {

    private ExecutorService executor;
    private DownloadHandlerFactory factory;
    private DownloadHandler proxychains;
    private DownloadHandler curl;

    @BeforeEach
    void setUp() {
        ApplicationContext.initialize();
        executor = Executors.newSingleThreadExecutor();
        factory = new DownloadHandlerFactory(new GlobalSettings(), new DownloadSettingsFactory(),
                executor, mock(ToolManagerFactory.class));
        proxychains = mock(DownloadHandler.class);
        curl = mock(DownloadHandler.class);
        when(proxychains.canHandle(any())).thenReturn(true);
        when(curl.canHandle(any())).thenReturn(true);
        factory.registerHandler(Download.Type.PROXYCHAINS, proxychains);
        factory.registerHandler(Download.Type.CURL, curl);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test void httpProxiedTorrentUsesProxychainsForPeers() {
        Download download = new Download(URI.create("magnet:?xt=urn:btih:abcdef"));
        download.setUseProxy(true).setProxyAddress("http://127.0.0.1:8080");
        assertSame(proxychains, factory.getHandler(download));
        verify(curl, never()).canHandle(any());
    }

    @Test void tlsProxyUsesCurlForAnOrdinaryTransfer() {
        Download download = new Download(URI.create("https://download.invalid/payload.bin"));
        download.setUseProxy(true).setProxyAddress("https://127.0.0.1:8443");
        assertSame(curl, factory.getHandler(download));
        assertEquals(Download.Type.CURL, download.getType());
        assertEquals("https://127.0.0.1:8443", download.getProxyAddress());
    }

    @Test
    void routesMagnetThroughProxychainsAndNeverCurl() {
        Download download = new Download(URI.create("magnet:?xt=urn:btih:abcdef"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");

        assertSame(proxychains, factory.getHandler(download));
        assertEquals(Download.Type.PROXYCHAINS, download.getType());
        verify(curl, never()).canHandle(any());
    }

    @Test
    void routesTorrentThroughProxychainsAndNeverCurl() {
        Download download = Download.fromTorrent(Path.of("/tmp/example.torrent"), Path.of("/tmp"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");

        assertSame(proxychains, factory.getHandler(download));
        assertEquals(Download.Type.PROXYCHAINS, download.getType());
        verify(curl, never()).canHandle(any());
    }

    @Test
    void missingProxychainsFallsBackToCurlForPlainUrlWithSameProxy() {
        DownloadHandlerFactory withoutProxychains = new DownloadHandlerFactory(
                new GlobalSettings(), new DownloadSettingsFactory(), executor,
                mock(ToolManagerFactory.class));
        withoutProxychains.registerHandler(Download.Type.CURL, curl);
        Download download = new Download(URI.create("https://example.test/file.bin"));
        download.setUseProxy(true);
        download.setProxyAddress("socks4://127.0.0.1:9050");
        download.getSettings().setMaxRetries(7);
        download.getSettings().setReferer("https://referrer.test/");

        assertSame(curl, withoutProxychains.getHandler(download));
        assertEquals(Download.Type.CURL, download.getType());
        assertTrue(download.getSettings() instanceof CurlSettings);
        assertTrue(download.getSettings().isUseProxy());
        assertEquals("socks4://127.0.0.1:9050", download.getSettings().getProxyAddress());
        assertEquals(7, download.getSettings().getMaxRetries());
        assertEquals("https://referrer.test/", download.getSettings().getReferer());
        verify(curl).canHandle(download);
    }

    @Test
    void proxychainsRejectionFallsBackToCurlForPlainUrl() {
        when(proxychains.canHandle(any())).thenReturn(false);
        Download download = new Download(URI.create("ftp://example.test/file.bin"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5://127.0.0.1:1080");

        assertSame(curl, factory.getHandler(download));
        assertEquals(Download.Type.CURL, download.getType());
        assertEquals("socks5://127.0.0.1:1080", download.getSettings().getProxyAddress());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https", "ftp", "sftp"})
    void restoredProxychainsRecordFallsBackWhenProxychainsIsMissing(String scheme) {
        GlobalSettings global = new GlobalSettings();
        global.setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://proxy-user:proxy-secret@127.0.0.1:9050");
        DownloadHandlerFactory restoredFactory = new DownloadHandlerFactory(
                global, new DownloadSettingsFactory(global), executor,
                mock(ToolManagerFactory.class));
        when(curl.canHandle(any())).thenAnswer(call ->
                ((Download) call.getArgument(0)).getType() == Download.Type.CURL);
        restoredFactory.registerHandler(Download.Type.CURL, curl);
        Download download = new Download(URI.create(scheme + "://user:secret@example.test/file.bin"));
        download.setType(Download.Type.PROXYCHAINS);
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://proxy-user:proxy-secret@127.0.0.1:9050");
        download.getSettings().setProxyInherited(true);
        download.getSettings().setOption("ssh-host-key-md", "md5=0123456789abcdef0123456789abcdef");

        assertSame(curl, restoredFactory.getHandler(download));
        assertEquals(Download.Type.CURL, download.getType());
        assertTrue(download.getSettings() instanceof CurlSettings);
        assertTrue(download.getSettings().isProxyInherited());
        assertEquals("socks5h://proxy-user:proxy-secret@127.0.0.1:9050", download.getProxyAddress());
        assertEquals("user:secret", download.getUri().getUserInfo());
        assertEquals("md5=0123456789abcdef0123456789abcdef",
                download.getSettings().getOption("ssh-host-key-md"));
        assertSame(curl, restoredFactory.getHandler(download), "later lookups retain the Curl fallback");
    }

    @Test
    void freshSftpUsesProxychainsThenCurlWhenRejected() {
        Download download = new Download(URI.create("sftp://user:secret@example.test/file.bin"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");
        assertSame(proxychains, factory.getHandler(download));
        when(proxychains.canHandle(any())).thenThrow(new IllegalStateException("tool unavailable"));
        assertSame(curl, factory.getHandler(download));
        assertEquals(Download.Type.CURL, download.getType());
    }

    @Test
    void missingProxychainsNeverFallsBackTorrentMagnetOrMetalinkToCurl() {
        DownloadHandlerFactory withoutProxychains = new DownloadHandlerFactory(
                new GlobalSettings(), new DownloadSettingsFactory(), executor,
                mock(ToolManagerFactory.class));
        DownloadHandler aria2 = mock(DownloadHandler.class);
        when(aria2.canHandle(any())).thenReturn(true);
        withoutProxychains.registerHandler(Download.Type.ARIA2, aria2);
        withoutProxychains.registerHandler(Download.Type.CURL, curl);

        for (URI uri : List.of(
                URI.create("magnet:?xt=urn:btih:abcdef"),
                URI.create("https://example.test/file.torrent"),
                URI.create("https://example.test/file.meta4"))) {
            Download download = new Download(uri);
            download.setUseProxy(true);
            download.setProxyAddress("socks5h://127.0.0.1:9050");

            assertNull(withoutProxychains.getHandler(download), uri.toString());
            assertEquals(Download.Type.ARIA2, download.getType(), uri.toString());
            download.setType(Download.Type.PROXYCHAINS);
            assertNull(withoutProxychains.getHandler(download), "restored " + uri);
            assertEquals(Download.Type.PROXYCHAINS, download.getType());
        }
        verify(aria2, never()).canHandle(any());
        verify(curl, never()).canHandle(any());
    }
}
