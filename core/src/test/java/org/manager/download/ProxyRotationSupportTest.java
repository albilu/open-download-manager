package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.handler.RetryableDownloadHandler;
import org.manager.download.handler.DownloadHandler;
import org.manager.proxy.Proxy;
import org.manager.proxy.ProxyRotationManager;
import org.manager.util.ExecutorServiceManager;

@DisplayName("ProxyRotationSupport wrapping decisions")
class ProxyRotationSupportTest {

    @TempDir
    Path tempDir;

    private final ProxyRotationManager rotationManager = new ProxyRotationManager();
    private final ExecutorServiceManager executorManager = ExecutorServiceManager.getInstance();
    private final DownloadHandler baseHandler = mock(DownloadHandler.class);

    private ProxyRotationSupport newSupport(GlobalSettings settings) {
        return new ProxyRotationSupport(rotationManager, () -> settings, executorManager);
    }

    private Path proxyListFile(String... urls) throws IOException {
        Path file = tempDir.resolve("proxies-" + System.nanoTime() + ".txt");
        Files.write(file, String.join("\n", urls).getBytes());
        return file;
    }

    @Test
    @DisplayName("disabled rotation returns the original handler untouched")
    void disabledRotationReturnsOriginal() {
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(false);
        Download download = newDownload(Download.Type.ARIA2);

        assertSame(baseHandler, newSupport(settings).maybeWrap(baseHandler, download));
    }

    @Test
    @DisplayName("only HTTP-capable download types are wrapped")
    void onlyHttpCapableTypesAreWrapped() throws IOException {
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(true)
                .setProxyListFilePath(proxyListFile("http://1.2.3.4:8080").toString());

        ProxyRotationSupport support = newSupport(settings);
        Download aria2 = newDownload(Download.Type.ARIA2);
        Download youtube = newDownload(Download.Type.YOUTUBE);
        Download website = newDownload(Download.Type.WEBSITE_SCRAPING);

        assertTrue(support.maybeWrap(baseHandler, aria2) instanceof RetryableDownloadHandler,
                "aria2 downloads are proxy-rotatable");
        assertSame(baseHandler, support.maybeWrap(baseHandler, youtube),
                "media downloads must not be wrapped");
        assertSame(baseHandler, support.maybeWrap(baseHandler, website),
                "website mirrors must not be wrapped");
    }

    @Test
    void selectedSocksRouteIsNeverReplacedByRotationAfterCurlFallback() throws IOException {
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(true)
                .setProxyListFilePath(proxyListFile("http://1.2.3.4:8080").toString());
        Download download = newDownload(Download.Type.CURL);
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");
        assertSame(baseHandler, newSupport(settings).maybeWrap(baseHandler, download));
        assertEquals("socks5h://127.0.0.1:9050", download.getProxyAddress());
    }

    @Test
    @DisplayName("an empty proxy pool leaves the handler unwrapped")
    void emptyPoolLeavesHandlerUnwrapped() throws IOException {
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(true)
                .setProxyListFilePath(tempDir.resolve("missing-list.txt").toString());
        Download download = newDownload(Download.Type.CURL);

        assertSame(baseHandler, newSupport(settings).maybeWrap(baseHandler, download));
    }

    @Test
    @DisplayName("an enabled rotation with a non-empty pool wraps the handler")
    void enabledRotationWrapsHandler() throws IOException {
        Path list = proxyListFile("http://1.2.3.4:8080", "socks5://5.6.7.8:1080");
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(true)
                .setProxyListFilePath(list.toString());
        Download download = newDownload(Download.Type.CURL);

        DownloadHandler wrapped = newSupport(settings).maybeWrap(baseHandler, download);
        assertTrue(wrapped instanceof RetryableDownloadHandler,
                "a populated pool must produce a retryable wrapper");

        // the lazy load must happen only once: disabling the list afterwards
        // must not unwrap an already-populated manager
        settings.setProxyListFilePath(tempDir.resolve("now-missing.txt").toString());
        DownloadHandler second = newSupport(settings).maybeWrap(baseHandler,
                newDownload(Download.Type.ARIA2));
        assertTrue(second instanceof RetryableDownloadHandler);
    }

    @Test
    @DisplayName("loaded proxies land in the rotation manager")
    void proxiesAreLoadedIntoTheManager() throws IOException {
        GlobalSettings settings = new GlobalSettings().setProxyRotationEnabled(true)
                .setProxyListFilePath(proxyListFile("http://9.9.9.9:3128").toString());
        ProxyRotationSupport support = newSupport(settings);

        support.maybeWrap(baseHandler, newDownload(Download.Type.ARIA2));

        assertFalse(rotationManager.isEmpty());
        assertEquals(1, rotationManager.getPoolSize());
        Proxy proxy = rotationManager.getRandomProxy();
        assertNotNull(proxy);
        assertEquals("9.9.9.9", proxy.getHost());
    }

    @Test
    @DisplayName("health check scheduling follows the manager configuration")
    void healthCheckScheduling() {
        ProxyRotationSupport support = newSupport(new GlobalSettings());
        // cancelled before start: must be a safe no-op
        support.cancelHealthChecks();

        support.startHealthChecks();
        support.cancelHealthChecks();
    }

    private Download newDownload(Download.Type type) {
        try {
            Download download = new Download(new URI("http://example.test/file.bin"));
            download.setType(type);
            return download;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
