package org.manager.download;

import static org.junit.jupiter.api.Assertions.*;
import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Path;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerDetailLookupTest {
    @TempDir Path directory;

    @Test
    void detailReadsNeverChangeTheEngineOrItsSelectedRoute() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            var manager = DownloadManagerFactory.getInstance();
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var global = manager.getGlobalSettings();
                var settingsFactory = new DownloadSettingsFactory(global);
                var tools = org.manager.ApplicationContext.getToolManagerFactory();
                var factory = DownloadManagerFactory.getContainer()
                        .getRequired(org.manager.download.handler.DownloadHandlerFactory.class);
                var proxychains = new org.manager.download.handler.ProxychainsDownloadHandler(
                        global, settingsFactory, executor, tools);
                proxychains.initialize().get(5, java.util.concurrent.TimeUnit.SECONDS);
                factory.registerHandler(Download.Type.PROXYCHAINS, proxychains);
                factory.registerHandler(Download.Type.ARIA2,
                        new org.manager.download.handler.Aria2DownloadHandler(global, settingsFactory, executor, tools));
                Download download = new Download(URI.create("http://details.invalid/file.bin"));
                download.setSettings(new Aria2Settings());
                download.setUseProxy(true);
                download.setProxyAddress("socks5h://127.0.0.1:1");
                for (Download.Type type : new Download.Type[]{Download.Type.ARIA2, Download.Type.PROXYCHAINS}) {
                    download.setType(type);
                    for (int i = 0; i < 10; i++) {
                        assertTrue(manager.getDownloadFiles(download).isEmpty());
                        assertTrue(manager.getDownloadPeers(download).isEmpty());
                        assertTrue(manager.getDownloadTrackers(download).isEmpty());
                        var sources = manager.getDownloadSources(download);
                        if (type == Download.Type.ARIA2) {
                            assertEquals(1, sources.size());
                            assertEquals(download.getUri().toString(), sources.getFirst().sources().getFirst().uri());
                        } else {
                            assertTrue(sources.isEmpty());
                        }
                        assertEquals(type, download.getType());
                        assertEquals("socks5h://127.0.0.1:1", download.getProxyAddress());
                        assertTrue(download.isUseProxy());
                    }
                }
                // Prove the fixture can actually route this record: detail
                // reads must not invoke the launch-time routing decision.
                download.setType(Download.Type.ARIA2);
                assertSame(proxychains, factory.getHandler(download));
                assertEquals(Download.Type.PROXYCHAINS, download.getType());
            } finally {
                DownloadManagerFactory.shutdown();
                executor.shutdownNow();
            }
        });
    }
}
