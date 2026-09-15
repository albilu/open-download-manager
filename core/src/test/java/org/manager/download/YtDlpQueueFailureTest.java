package org.manager.download;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.download.handler.YtDlpDownloadHandler;
import org.manager.tools.ToolManagerFactory;
import org.slf4j.LoggerFactory;
import org.ytdlp.YtDlpFactory;
import org.ytdlp.YtDlpToolManager;

class YtDlpQueueFailureTest {
    @TempDir Path directory;

    @Test
    @Timeout(45)
    void nativeFailureKeepsItsQueueSlotUntilExitAndIsReportedOnceAtErrorLevel() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(this::verifyQueue);
    }

    private void verifyQueue() throws Exception {
        Path tool = directory.resolve("controlled-yt-dlp");
        Files.writeString(tool, """
                #!/bin/sh
                case " $* " in *" --version "*) echo '2026.09.15'; exit 0;; esac
                echo $$ > native.pid
                echo '[download] Destination: video.mp4'
                echo 'ERROR: HTTP Error 403: Forbidden https://media.invalid/video?token=private-token'
                echo '[download] 50.0% of 1.00KiB at 1.00KiB/s |odmbytes|512|1024'
                while [ ! -e release ]; do sleep 0.05; done
                case "$PWD" in */first) exit 1;; esac
                printf 'payload' > video.mp4
                exit 0
                """);
        assertTrue(tool.toFile().setExecutable(true));
        var manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        var global = manager.getGlobalSettings();
        global.setMaxConcurrentDownloads(1).setUniquifyOutputName(false);
        global.setProperty("ytdlp.skipDownloaded", "false");
        global.setProperty("ytdlp.useAria2c", "false");
        YtDlpFactory.clearInstance();
        var toolManager = mock(YtDlpToolManager.class);
        when(toolManager.getToolPath()).thenReturn(tool.toString());
        var tools = mock(ToolManagerFactory.class);
        when(tools.getYtDlpManager()).thenReturn(toolManager);
        var executor = Executors.newCachedThreadPool();
        var handler = new YtDlpDownloadHandler(global, new DownloadSettingsFactory(global), executor, tools);
        var errors = new AtomicInteger();
        var completions = new AtomicInteger();
        var logs = new ConcurrentLinkedQueue<ILoggingEvent>();
        Logger logger = (Logger) LoggerFactory.getLogger(DownloadManagerImpl.class);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { logs.add(event); }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            handler.initialize().get(5, TimeUnit.SECONDS);
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.YOUTUBE, handler);
            Download first = manager.createYoutubeDownload(URI.create("http://media.invalid/first.mp4"),
                    directory.resolve("first"), Map.of());
            Download second = manager.createYoutubeDownload(URI.create("http://media.invalid/second.mp4"),
                    directory.resolve("second"), Map.of());
            first.getSettings().setMaxRetries(0);
            second.getSettings().setMaxRetries(0);
            manager.addDownloadListener(new DownloadListener() {
                public void onDownloadStart(Download download) { }
                public void onDownloadProgress(Download download, float progress, long bytes, long total, float speed) { }
                public void onDownloadPause(Download download) { }
                public void onDownloadResume(Download download) { }
                public void onDownloadCanceled(Download download) { }
                public void onDownloadComplete(Download download) { completions.incrementAndGet(); }
                public void onDownloadError(Download download, String message) { errors.incrementAndGet(); }
            });
            manager.startDownload(first).get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).until(() -> first.getDownloaded() == 512);
            long pid = Long.parseLong(Files.readString(first.getDestination().resolve("native.pid")).trim());
            assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
            manager.startDownload(second).get(5, TimeUnit.SECONDS);
            assertEquals(Download.Status.DOWNLOADING, first.getStatus());
            assertEquals(Download.Status.QUEUED, second.getStatus());
            assertEquals(1, manager.getRunningDownloadCount());
            assertEquals(0, errors.get());
            assertFalse(Files.exists(second.getDestination().resolve("native.pid")));

            Files.createFile(first.getDestination().resolve("release"));
            await().atMost(Duration.ofSeconds(10)).until(() -> second.getDownloaded() == 512);
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals(Download.Status.ERROR, first.getStatus());
            assertEquals(1, manager.getRunningDownloadCount());
            Files.createFile(second.getDestination().resolve("release"));
            await().atMost(Duration.ofSeconds(10)).until(() -> completions.get() == 1 && errors.get() == 1);
            assertEquals(Download.Status.COMPLETED, second.getStatus());
            assertEquals(0, manager.getRunningDownloadCount());
            assertEquals("payload", Files.readString(second.getDestination().resolve("video.mp4")));
            var failures = logs.stream().filter(event -> event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().startsWith("Download " + first.getId() + " failed:")).toList();
            assertEquals(1, failures.size());
            assertTrue(failures.getFirst().getFormattedMessage().contains("403"));
            assertFalse(failures.getFirst().getFormattedMessage().contains("private-token"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            DownloadManagerFactory.shutdown();
            handler.shutdown().get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
            YtDlpFactory.clearInstance();
        }
    }
}
