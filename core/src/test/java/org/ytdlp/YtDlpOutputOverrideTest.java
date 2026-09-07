package org.ytdlp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.YtDlpDownloadHandler;
import org.manager.tools.ToolManagerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(90)
class YtDlpOutputOverrideTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void newRecordReplacesGeneratedOutputOnlyWhenOverrideIsEnabled(boolean override, boolean aria2)
            throws Exception {
        var globals = new GlobalSettings().setOverrideOutputPath(override);
        globals.setProperty("ytdlp.skipDownloaded", "false");
        YtDlpToolManager tool = mock(YtDlpToolManager.class);
        when(tool.getToolPath()).thenReturn("yt-dlp");
        ToolManagerFactory tools = mock(ToolManagerFactory.class);
        when(tools.getYtDlpManager()).thenReturn(tool);
        YtDlpFactory.clearInstance();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var server = YtDlpLocalMediaServer.start()) {
            var settings = new YtDlpSettings().setFormat("best").setUseAria2c(aria2);
            Path output;
            var first = new YtDlpClient("yt-dlp");
            try {
                output = Path.of(first.download(server.mediaUrl(), settings, directory, null)
                        .get(30, TimeUnit.SECONDS));
            } finally {
                first.shutdown();
            }
            byte[] originalMedia = Files.readAllBytes(output);
            Files.writeString(output, "old output");
            Path oldInode = Files.createLink(directory.resolve("keep-old-inode"), output);
            Path unrelated = Files.writeString(directory.resolve("watch"), "unrelated file");
            var handler = new YtDlpDownloadHandler(globals,
                    new DownloadSettingsFactory(globals), executor, tools);
            try {
                Download download = new Download(URI.create(server.mediaUrl()));
                download.setType(Download.Type.YOUTUBE);
                download.setName("watch");
                download.setSettings(settings);
                download.setDestination(directory);
                var complete = new CompletableFuture<Download>();
                DownloadListener listener = mock(DownloadListener.class);
                doAnswer(invocation -> {
                    complete.complete(invocation.getArgument(0));
                    return null;
                }).when(listener).onDownloadComplete(any());
                doAnswer(invocation -> {
                    complete.completeExceptionally(new AssertionError((String) invocation.getArgument(1)));
                    return null;
                }).when(listener).onDownloadError(any(), anyString());
                handler.addDownloadListener(listener);
                handler.initialize().get(15, TimeUnit.SECONDS);

                handler.startDownload(download).get(15, TimeUnit.SECONDS);
                complete.get(30, TimeUnit.SECONDS);

                if (override) {
                    assertArrayEquals(originalMedia, Files.readAllBytes(output),
                            "the generated output must be freshly downloaded");
                    assertFalse(Files.isSameFile(output, oldInode),
                            "override must unlink the old output before downloading");
                } else {
                    assertEquals("old output", Files.readString(output),
                            "the engine's existing-file policy must remain in effect");
                }
                assertEquals("old output", Files.readString(oldInode), "unrelated hard links must remain");
                assertEquals("unrelated file", Files.readString(unrelated), "a display name is not an output");
                assertTrue(settings.isAria2cContinue(), "the saved resume policy must not change");
            } finally {
                handler.shutdown().get(15, TimeUnit.SECONDS);
                YtDlpFactory.clearInstance();
            }
        }
    }

    @Test
    void replacementFlagsDoNotPersistIntoOrdinaryRunsOrChangeTheirRoute() {
        var client = new YtDlpClient("yt-dlp");
        try {
            var settings = new YtDlpSettings().setUseAria2c(true);
            var initial = client.buildDownloadCommand("https://example.test/watch", settings, directory, true);
            assertTrue(initial.contains("--force-overwrites"));
            assertTrue(initial.contains("--no-continue"));
            assertTrue(initial.get(initial.indexOf("--external-downloader-args") + 1)
                    .endsWith("--continue=false"));
            assertTrue(settings.isAria2cContinue());

            var resumed = client.buildDownloadCommand("https://example.test/watch", settings, directory);
            assertFalse(resumed.contains("--force-overwrites"));
            assertFalse(resumed.contains("--no-continue"));
            assertFalse(resumed.get(resumed.indexOf("--external-downloader-args") + 1)
                    .contains("--continue=false"));

            settings.setUseProxy(true).setProxyAddress("socks5h://127.0.0.1:9050");
            var routed = client.buildDownloadCommand("https://example.test/watch", settings, directory, true);
            assertEquals("socks5h://127.0.0.1:9050", routed.get(routed.indexOf("--proxy") + 1));
            assertEquals("native", routed.get(routed.indexOf("--external-downloader") + 1));
            assertFalse(routed.contains("--external-downloader-args"));
        } finally {
            client.shutdown();
        }
    }
}
