package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettings;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.mockito.Mockito;

/**
 * Routing table coverage: every download type must resolve to the handler
 * that owns it, the curl fallback must engage only for compatible work,
 * and the SOCKS chain must preserve privacy constraints.
 */
@DisplayName("DownloadHandlerFactory routing table")
class DownloadHandlerFactoryRoutingTest {

    private ExecutorService executor;
    private DownloadHandlerFactory factory;
    private DownloadHandler aria2;
    private DownloadHandler curl;
    private DownloadHandler httrack;
    private DownloadHandler ytdlp;
    private DownloadHandler proxychains;

    @BeforeEach
    void setUp() {
        ApplicationContext.initialize();
        executor = Executors.newSingleThreadExecutor();
        factory = new DownloadHandlerFactory(new GlobalSettings(), new DownloadSettingsFactory(),
                executor, mock(ToolManagerFactory.class));
        aria2 = Mockito.mock(DownloadHandler.class);
        curl = Mockito.mock(DownloadHandler.class);
        httrack = Mockito.mock(DownloadHandler.class);
        ytdlp = Mockito.mock(DownloadHandler.class);
        proxychains = Mockito.mock(DownloadHandler.class);
        for (DownloadHandler h : new DownloadHandler[] {aria2, curl, httrack, ytdlp, proxychains}) {
            when(h.canHandle(Mockito.any())).thenReturn(true);
        }
        factory.registerHandler(Download.Type.ARIA2, aria2);
        factory.registerHandler(Download.Type.CURL, curl);
        factory.registerHandler(Download.Type.WEBSITE_SCRAPING, httrack);
        factory.registerHandler(Download.Type.YOUTUBE, ytdlp);
        factory.registerHandler(Download.Type.PROXYCHAINS, proxychains);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** A factory holding only the given handlers (registration of nulls is illegal). */
    private DownloadHandlerFactory factoryWith(Download.Type[] types, DownloadHandler[] handlers) {
        DownloadHandlerFactory f = new DownloadHandlerFactory(new GlobalSettings(),
                new DownloadSettingsFactory(), executor, mock(ToolManagerFactory.class));
        for (int i = 0; i < types.length; i++) {
            f.registerHandler(types[i], handlers[i]);
        }
        return f;
    }

    private Download download(String uri, Download.Type type) {
        Download download = new Download(URI.create(uri));
        download.setType(type);
        return download;
    }

    @Test
    @DisplayName("each engine owns its download type")
    void directTypeRouting() {
        assertSame(aria2, factory.getHandler(download("http://e.test/a.bin", Download.Type.ARIA2)));
        assertSame(curl, factory.getHandler(download("http://e.test/a.bin", Download.Type.CURL)));
        assertSame(httrack, factory.getHandler(download("http://e.test/", Download.Type.WEBSITE_SCRAPING)));
        assertSame(ytdlp, factory.getHandler(download("http://e.test/", Download.Type.YOUTUBE)));
        assertSame(proxychains, factory.getHandler(download("http://e.test/a.bin", Download.Type.PROXYCHAINS)));
    }

    @Test
    @DisplayName("a missing handler falls back to curl only when curl can handle the work")
    void curlFallbackRules() {
        DownloadHandlerFactory noAria2 = factoryWith(
                new Download.Type[] {Download.Type.CURL}, new DownloadHandler[] {curl});
        // real curl refuses torrent-family work; mirror that in the mock
        when(curl.canHandle(Mockito.argThat(d -> d != null
                && (d.getUri().toString().endsWith(".meta4")
                        || d.getUri().toString().startsWith("magnet:"))))).thenReturn(false);

        Download metalink = download("http://e.test/a.meta4", Download.Type.ARIA2);
        assertNull(noAria2.getHandler(metalink), "metalink work must never fall back to curl");

        Download magnet = download("magnet:?xt=urn:btih:abcdef", Download.Type.ARIA2);
        assertNull(noAria2.getHandler(magnet), "magnet work must never fall back to curl");

        Download plainUrl = download("http://e.test/a.bin", Download.Type.ARIA2);
        assertSame(curl, noAria2.getHandler(plainUrl),
                "a plain URL may fall back to curl");
    }

    @Test
    @DisplayName("a download no handler accepts resolves to null")
    void unhandledDownloadIsNull() {
        when(curl.canHandle(Mockito.any())).thenReturn(false);
        DownloadHandlerFactory curlOnly = factoryWith(
                new Download.Type[] {Download.Type.CURL}, new DownloadHandler[] {curl});
        Download orphan = download("http://e.test/a.bin", Download.Type.ARIA2);
        assertNull(curlOnly.getHandler(orphan));
        assertNull(curlOnly.getHandler((Download) null));
    }

    @Test
    @DisplayName("a throwing curl handler propagates on the plain fallback path")
    void throwingHandlerPropagates() {
        when(curl.canHandle(Mockito.any())).thenThrow(new IllegalStateException("dead handler"));
        DownloadHandlerFactory noAria2 = factoryWith(
                new Download.Type[] {Download.Type.CURL}, new DownloadHandler[] {curl});
        Download plain = download("http://e.test/a.bin", Download.Type.ARIA2);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> noAria2.getHandler(plain),
                "the plain fallback path does not guard handler failures by design");
    }

    @Test
    @DisplayName("SOCKS HTTP downloads take the proxychains route and set proxy settings")
    void socksRoutingSetsProxySettings() {
        Download download = download("http://e.test/a.bin", Download.Type.ARIA2);
        download.getSettings().setUseProxy(true);
        download.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");

        DownloadHandler routed = factory.getHandler(download);
        assertSame(proxychains, routed);
        assertEquals(Download.Type.PROXYCHAINS, download.getType(),
                "routing must retype the download so later lookups stay stable");
        assertTrue(download.getSettings().isUseProxy());
        assertEquals("socks5h://127.0.0.1:9050", download.getSettings().getProxyAddress());
    }

    @Test
    @DisplayName("isSocksProxyAddress recognizes socks schemes and rejects others")
    void socksAddressClassification() {
        assertTrue(DownloadHandlerFactory.isSocksProxyAddress("socks5h://127.0.0.1:9050"));
        assertTrue(DownloadHandlerFactory.isSocksProxyAddress("socks4://127.0.0.1:9050"));
        assertTrue(DownloadHandlerFactory.isSocksProxyAddress("socks5://127.0.0.1:9050"));
        assertFalse(DownloadHandlerFactory.isSocksProxyAddress("http://127.0.0.1:8080"));
        assertFalse(DownloadHandlerFactory.isSocksProxyAddress(null));
        assertFalse(DownloadHandlerFactory.isSocksProxyAddress(""));
    }

    @Test
    @DisplayName("handler availability is reported per type")
    void handlerAvailability() {
        assertTrue(factory.isHandlerAvailable(Download.Type.ARIA2));
        DownloadHandlerFactory curlOnly = factoryWith(
                new Download.Type[] {Download.Type.CURL}, new DownloadHandler[] {curl});
        assertFalse(curlOnly.isHandlerAvailable(Download.Type.ARIA2));
        assertTrue(curlOnly.isHandlerAvailable(Download.Type.CURL));
    }

    @Test
    @DisplayName("curl proxy fallback preparation validates the download and proxies")
    void curlProxyFallbackPreparation() throws Exception {
        Download torrent = Download.fromTorrent(Path.of("/tmp/example.torrent"), Path.of("/tmp"));
        torrent.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");
        assertFalse(factory.canPrepareCurlProxyFallback(torrent),
                "torrent work cannot fall back to curl");
        assertFalse(factory.canPrepareCurlProxyFallback(null));

        Download plain = download("http://e.test/a.bin", Download.Type.PROXYCHAINS);
        plain.getSettings().setUseProxy(true);
        plain.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");
        String originalProxy = plain.getSettings().getProxyAddress();
        assertTrue(factory.canPrepareCurlProxyFallback(plain),
                "a socks-proxied plain URL is curl-transferable");
        assertTrue(factory.prepareCurlProxyFallback(plain));
        assertEquals(Download.Type.CURL, plain.getType(),
                "the preparation retypes the download onto curl");
        assertEquals(originalProxy, plain.getSettings().getProxyAddress(),
                "the socks proxy must survive the handoff");
    }

    @Test
    @DisplayName("initializeHandlers registers the real engine handlers")
    void initializeRegistersRealHandlers() {
        DownloadHandlerFactory real = new DownloadHandlerFactory(new GlobalSettings(),
                new DownloadSettingsFactory(), executor,
                ApplicationContext.getToolManagerFactory());
        try {
            real.initializeHandlers();
            assertTrue(real.isHandlerAvailable(Download.Type.ARIA2));
            assertTrue(real.isHandlerAvailable(Download.Type.CURL));
            assertTrue(real.isHandlerAvailable(Download.Type.YOUTUBE));
            assertTrue(real.isHandlerAvailable(Download.Type.WEBSITE_SCRAPING));
            Download plain = download("http://e.test/x.bin", Download.Type.ARIA2);
            assertTrue(real.getHandler(plain) != null,
                    "the aria2 handler must resolve for a plain URL");
        } finally {
            real.shutdownHandlers();
        }
    }
}
