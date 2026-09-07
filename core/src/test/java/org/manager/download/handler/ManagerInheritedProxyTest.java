package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.aria2.Aria2Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.*;
import org.manager.tools.ToolPaths;

class ManagerInheritedProxyTest {
    @TempDir Path directory;

    /** Keep real RPC tasks paused so network timing cannot obscure their option state. */
    private static class PausedRpcHandler extends Aria2DownloadHandler {
        private final Aria2Client client;
        PausedRpcHandler(GlobalSettings settings, ExecutorService executor,
                Aria2Client client, ScheduledExecutorService poller) {
            super(settings, new DownloadSettingsFactory(settings), executor, client, poller);
            this.client = client;
            initialized = true;
        }
        @Override public CompletableFuture<String> startDownload(Download download) {
            try {
                String first = client.addUriRpc(new String[] { download.getUri().toString() },
                        Map.of("pause", "true", "all-proxy", java.util.Objects.toString(download.getProxyAddress(), "")));
                String second = client.addUriRpc(new String[] { download.getUri().toString() + "?mirror" },
                        Map.of("pause", "true", "all-proxy", java.util.Objects.toString(download.getProxyAddress(), "")));
                registerTrackedDownload(download, List.of(first, second));
                return CompletableFuture.completedFuture(first);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    @Test
    void managerChangesAndClearsInheritedProxyOnEveryOwnedGidWhilePreservingExplicitProxy() throws Exception {
        DownloadManager manager = DownloadManagerFactory.getInstance();
        GlobalSettings global = manager.getGlobalSettings();
        global.setGlobalProxyEnabled(true).setGlobalProxyAddress("http://127.0.0.1:18081");
        int port;
        try (ServerSocket freePort = new ServerSocket(0)) { port = freePort.getLocalPort(); }
        Aria2Client client = new Aria2Client(ToolPaths.aria2c(),
                "http://127.0.0.1:" + port + "/jsonrpc", "proxy-regression");
        try (ExecutorService executor = Executors.newCachedThreadPool();
                ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor()) {
            assertTrue(client.startAria2cWithRpc());
            PausedRpcHandler handler = new PausedRpcHandler(global, executor, client, poller);
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.ARIA2, handler);
            Download inherited = manager.createDownload(URI.create("http://file.odm.invalid/inherited.bin"), directory);
            Download explicit = manager.createDownload(URI.create("http://file.odm.invalid/explicit.bin"), directory);
            explicit.setProxyAddress(global.getGlobalProxyAddress());
            assertTrue(inherited.getSettings().isProxyInherited());
            assertFalse(explicit.getSettings().isProxyInherited());
            try {
                manager.queueDownload(inherited).join();
                manager.queueDownload(explicit).join();
                await().atMost(Duration.ofSeconds(10)).until(() -> inherited.getGid() != null && explicit.getGid() != null);
                global.setGlobalProxyAddress("http://127.0.0.1:18082");
                manager.applyGlobalSettingsToActiveDownloads();
                await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertEquals(2, handler.trackedGidsFor(inherited.getId()).size());
                    assertEquals(2, handler.trackedGidsFor(explicit.getId()).size());
                    for (String gid : handler.trackedGidsFor(inherited.getId())) {
                        assertEquals("http://127.0.0.1:18082/", client.getOption(gid).get("all-proxy"));
                    }
                    assertEquals("http://127.0.0.1:18082", inherited.getProxyAddress());
                });
                Object previousSession = client.getSessionInfo().get("sessionId");
                global.setGlobalProxyEnabled(false);
                manager.applyGlobalSettingsToActiveDownloads();
                await().ignoreExceptions().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertEquals(2, handler.trackedGidsFor(inherited.getId()).size());
                    assertEquals(2, handler.trackedGidsFor(explicit.getId()).size());
                    assertNotEquals(previousSession, client.getSessionInfo().get("sessionId"));
                    for (String gid : handler.trackedGidsFor(inherited.getId())) {
                        assertEquals("", client.getOption(gid).get("all-proxy"));
                    }
                    assertFalse(inherited.isUseProxy());
                });
                for (String gid : handler.trackedGidsFor(explicit.getId())) {
                    assertEquals("http://127.0.0.1:18081/", client.getOption(gid).get("all-proxy"));
                }
            } finally {
                manager.cancelDownload(inherited, false).join();
                manager.cancelDownload(explicit, false).join();
                handler.shutdown().join();
            }
        } finally {
            client.stopAria2c();
            client.disconnectWebSocket();
        }
    }
}
