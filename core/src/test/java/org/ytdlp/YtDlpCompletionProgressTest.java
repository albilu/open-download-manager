package org.ytdlp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.YtDlpDownloadHandler;
import org.manager.tools.ToolManagerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Timeout(60)
class YtDlpCompletionProgressTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"NA", "1048576"})
    void mergedOutputReplacesLastStreamsProgress(String total) throws Exception {
        Path tool = tempDir.resolve("yt-dlp-fixture");
        Files.writeString(tool, """
                #!/bin/sh
                echo '[download] Destination: video.f399.mp4'
                echo '[download] 100.0%% of N/A at 12.00MiB/s |odmbytes|2000000|%s'
                echo '[download] Destination: video.f251.webm'
                echo '[download] 100.0%% of N/A at 96.30MiB/s |odmbytes|500000|%s'
                printf 'merged media payload' > 'final video.webm'
                printf '|odmfile|%%s/final video.webm\n' "$PWD"
                """.formatted(total, total));
        assertTrue(tool.toFile().setExecutable(true));
        YtDlpClient client = new YtDlpClient(tool.toString());
        try {
            YtDlpDownloadTask task = new YtDlpDownloadTask("merged", "https://example.test/watch",
                    new YtDlpSettings().setUseDownloadArchive(false), tempDir, client);
            YtDlpClient.ProgressCallback listener = mock(YtDlpClient.ProgressCallback.class);
            task.setProgressListener(listener);
            String filename = task.start().get(10, TimeUnit.SECONDS);
            long actualSize = Files.size(Path.of(filename));
            assertEquals(actualSize, task.getDownloadedBytes());
            assertEquals(actualSize, task.getTotalBytes());
            assertEquals(100, task.getProgress());
            assertEquals(0, task.getSpeed());
            var ordered = inOrder(listener);
            ordered.verify(listener).onProgress(100, actualSize, actualSize, 0);
            ordered.verify(listener).onComplete(filename);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void playlistTotalsOnlyFinalOutputsAndDeduplicatesPaths() throws Exception {
        Path tool = tempDir.resolve("yt-dlp-fixture");
        Files.writeString(tool, """
                #!/bin/sh
                echo '[download] Destination: retained-input.mp4'
                printf 'retained intermediate stream' > retained-input.mp4
                printf 'first final output' > first.webm
                echo '|odmfile|first.webm'
                printf '|odmfile|%s/first.webm\n' "$PWD"
                printf 'second final output' > second.webm
                echo '[download] 100.0% of N/A at 2.00MiB/s |odmbytes|19|NA'
                echo '|odmfile|second.webm'
                """);
        assertTrue(tool.toFile().setExecutable(true));
        YtDlpClient client = new YtDlpClient(tool.toString());
        try {
            YtDlpClient.ProgressCallback listener = mock(YtDlpClient.ProgressCallback.class);
            client.download("https://example.test/playlist", new YtDlpSettings().setUseDownloadArchive(false),
                    tempDir, listener).get(10, TimeUnit.SECONDS);
            long actualSize = Files.size(tempDir.resolve("first.webm"))
                    + Files.size(tempDir.resolve("second.webm"));
            verify(listener).onProgress(100, actualSize, actualSize, 0);
        } finally {
            client.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realMediaDownloadPublishesFinalModelBeforeCompletion(boolean aria2) throws Exception {
        GlobalSettings globals = new GlobalSettings();
        globals.setDefaultDownloadDirectory(tempDir);
        globals.setProperty("ytdlp.skipDownloaded", "false");
        YtDlpToolManager tool = mock(YtDlpToolManager.class);
        when(tool.getToolPath()).thenReturn("yt-dlp");
        ToolManagerFactory tools = mock(ToolManagerFactory.class);
        when(tools.getYtDlpManager()).thenReturn(tool);
        YtDlpFactory.clearInstance();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var media = YtDlpLocalMediaServer.start()) {
            YtDlpDownloadHandler handler = new YtDlpDownloadHandler(globals,
                    new DownloadSettingsFactory(globals), executor, tools);
            try {
                Download download = new Download(URI.create(media.mediaUrl()));
                download.setType(Download.Type.YOUTUBE);
                download.setDestination(tempDir);
                download.setSettings(new YtDlpSettings().setUseAria2c(aria2)
                        .setOutputTemplate("final.mp4").setEmbedMetadata(true));
                CompletableFuture<Download> completed = new CompletableFuture<>();
                AtomicInteger knownTotalUpdates = new AtomicInteger();
                DownloadListener listener = mock(DownloadListener.class);
                doAnswer(invocation -> {
                    if ((long) invocation.getArgument(3) > 0) {
                        knownTotalUpdates.incrementAndGet();
                    }
                    return null;
                }).when(listener).onDownloadProgress(any(), anyFloat(), anyLong(), anyLong(), anyFloat());
                doAnswer(invocation -> {
                    completed.complete(invocation.getArgument(0));
                    return null;
                }).when(listener).onDownloadComplete(any());
                doAnswer(invocation -> {
                    completed.completeExceptionally(new AssertionError((String) invocation.getArgument(1)));
                    return null;
                }).when(listener).onDownloadError(any(), anyString());
                handler.addDownloadListener(listener);
                handler.initialize().get(15, TimeUnit.SECONDS);
                handler.startDownload(download).get(15, TimeUnit.SECONDS);
                Download result = completed.get(30, TimeUnit.SECONDS);
                long actualSize = Files.size(tempDir.resolve("final.mp4"));
                assertTrue(actualSize > 0);
                assertEquals(Download.Status.COMPLETED, result.getStatus());
                assertEquals(actualSize, result.getSize());
                assertEquals(actualSize, result.getDownloaded());
                assertEquals(100, result.getProgress());
                assertEquals(0, result.getSpeed());
                assertTrue(knownTotalUpdates.get() >= 2,
                        "yt-dlp must report a known stream total as well as the final file snapshot");
            } finally {
                handler.shutdown().get(15, TimeUnit.SECONDS);
                YtDlpFactory.clearInstance();
            }
        }
    }
}
