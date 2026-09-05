package org.httrack;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import utils.SocksHttpServer;

class HttrackSocksRoutingTest {
    @TempDir Path directory;

    @Test
    void globalSocksRouteReachesSelectedProxyWithRemoteDnsAndCredentials() throws Exception {
        String html = "<html><body>routed website regression</body></html>";
        try (SocksHttpServer proxy = new SocksHttpServer(true, html)) {
            GlobalSettings global = new GlobalSettings().setGlobalProxyEnabled(true)
                    .setGlobalProxyAddress("socks5h://audit:p%40ss%3Aword@127.0.0.1:" + proxy.port());
            HttrackSettings settings = (HttrackSettings) new DownloadSettingsFactory(global)
                    .createSettings(Download.Type.WEBSITE_SCRAPING, Download.Protocol.HTTP);
            settings.setUrl("http://website.odm.invalid/index.html");
            settings.setOutputDirectory(directory);
            settings.setDepth(1);
            HttrackClient client = new HttrackClient();
            var terminal = new java.util.concurrent.CompletableFuture<HttrackJob>();
            client.addNotificationListener(new HttrackClient.HttrackNotificationListener() {
                @Override public void onJobCompleted(HttrackJob job) { terminal.complete(job); }
                @Override public void onJobError(HttrackJob job, String error) { terminal.complete(job); }
            });
            try {
                client.startMirror(settings).get(10, TimeUnit.SECONDS);
                assertEquals(HttrackJob.Status.COMPLETED, terminal.get(25, TimeUnit.SECONDS).getStatus());
                assertTrue(proxy.hosts.contains("website.odm.invalid"), "DNS must travel through SOCKS");
                assertTrue(proxy.credentials.contains("audit:p@ss:word"));
                try (var files = Files.walk(directory)) {
                    assertTrue(files.filter(Files::isRegularFile).anyMatch(path -> {
                        try { return Files.readString(path).contains("routed website regression"); }
                        catch (Exception ignored) { return false; }
                    }));
                }
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void socksConfigurationLivesUntilLaunchIsFinishedAndNeverAddsNativeProxy() throws Exception {
        HttrackClient client = new HttrackClient("/bin/true");
        HttrackSettings settings = new HttrackSettings().setUrl("http://website.odm.invalid/")
                .setUseProxy(true).setProxyAddress("socks5://[::1]:1080");
        Path config;
        try (HttrackClient.PreparedCommand command = client.prepareCommand(settings)) {
            config = command.configFile();
            assertTrue(Files.readString(config).contains("socks5 ::1 1080"));
            assertTrue(command.arguments().contains("-f"));
            assertFalse(command.arguments().contains("-P"));
        } finally {
            client.shutdown();
        }
        assertFalse(Files.exists(config));
    }
}
