package org.manager.download.handler;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpDownloadTask;
import org.ytdlp.YtDlpFactory;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Handler lifecycle around pause and settings changes: pausing terminates
 * the current run whose completion callback settles asynchronously — it
 * must not remove the task mapping that resume needs, and an old run's
 * late callback must never remove or shut down a replacement started by
 * changeSettings.
 */
@DisplayName("YtDlpDownloadHandler pause/resume/replacement lifecycle")
class YtDlpPauseLifecycleTest {

    private static final long GRACE_MS = 1500;

    @TempDir
    Path tempDir;

    private YtDlpFactory factory;
    private YtDlpDownloadHandler handler;
    private ExecutorService executor;
    private GlobalSettings settings;

    @BeforeEach
    void setUp() throws Exception {
        Path fakeTool = tempDir.resolve("fake-yt-dlp");
        Files.writeString(fakeTool, "#!/bin/bash\n"
                + "case \" $* \" in *\" --version \"*) echo \"2024.01.01\"; exit 0;; esac\n"
                + "printf '%s\\n' \"$@\" > \"$0.args\"\n"
                + "echo \"[download] Destination: video.mp4\"\n"
                + "sleep 300\n");
        Files.setPosixFilePermissions(fakeTool, PosixFilePermissions.fromString("rwxr-xr-x"));

        YtDlpFactory.clearInstance();

        settings = mock(GlobalSettings.class);
        when(settings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        org.ytdlp.YtDlpToolManager toolManager = mock(org.ytdlp.YtDlpToolManager.class);
        when(toolManager.getToolPath()).thenReturn(fakeTool.toString());
        org.manager.tools.ToolManagerFactory toolManagerFactory =
                mock(org.manager.tools.ToolManagerFactory.class);
        when(toolManagerFactory.getYtDlpManager()).thenReturn(toolManager);

        factory = YtDlpFactory.getInstance(settings, toolManagerFactory);
        executor = Executors.newCachedThreadPool();
        handler = new YtDlpDownloadHandler(settings,
                new DownloadSettingsFactory(settings), executor, toolManagerFactory);
        handler.initialize().get(30, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        handler.shutdown().get(30, TimeUnit.SECONDS);
        executor.shutdownNow();
        YtDlpFactory.clearInstance();
    }

    private Download newDownload(String id) throws Exception {
        Download download = new Download(new URI("http://example.test/watch?v=1"));
        download.setDestination(tempDir.resolve("dest-" + id));
        return download;
    }

    private static void awaitRunning(YtDlpDownloadTask task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (task.getStatus() != YtDlpDownloadTask.Status.DOWNLOADING
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(YtDlpDownloadTask.Status.DOWNLOADING, task.getStatus(),
                "test prerequisite: the run must be DOWNLOADING (destination reported)");
    }

    @Test
    @Timeout(120)
    @DisplayName("Pause keeps the task mapping so the next resume works")
    void pauseKeepsTaskMappingForResume() throws Exception {
        Download download = newDownload("pause");
        handler.startDownload(download).get(30, TimeUnit.SECONDS);
        YtDlpDownloadTask task = factory.getDownloadTask(download.getId());
        assertNotNull(task, "started download must be tracked");
        awaitRunning(task);

        handler.pauseDownload(download).get(30, TimeUnit.SECONDS);
        assertTrue(task.awaitRunCompletion(java.time.Duration.ofSeconds(15)),
                "the paused run must terminate");
        Thread.sleep(GRACE_MS); // let the run's completion callbacks settle

        assertNotNull(factory.getDownloadTask(download.getId()),
                "pause must retain the task mapping for resume");

        handler.resumeDownload(download).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.DOWNLOADING, download.getStatus(),
                "resume must proceed on the retained task");
        awaitRunning(task);
        assertEquals(YtDlpDownloadTask.Status.DOWNLOADING, task.getStatus(),
                "the resumed run must be running again");
    }

    @Test
    @Timeout(120)
    void archivePreferenceOverridesStoredChoicesOnStartAndResume() throws Exception {
        com.github.stefanbirkner.systemlambda.SystemLambda
                .withEnvironmentVariable("XDG_STATE_HOME", tempDir.resolve("state").toString())
                .execute(() -> {
                    when(settings.getBooleanProperty("ytdlp.skipDownloaded", true)).thenReturn(true);
                    Download download = newDownload("archive");
                    download.setSettings(new org.ytdlp.YtDlpSettings().setUseDownloadArchive(false));
                    handler.startDownload(download).get(30, TimeUnit.SECONDS);
                    YtDlpDownloadTask task = factory.getDownloadTask(download.getId());
                    awaitRunning(task);
                    Path arguments = tempDir.resolve("fake-yt-dlp.args");
                    assertTrue(Files.readAllLines(arguments).contains("--download-archive"));

                    handler.pauseDownload(download).get(30, TimeUnit.SECONDS);
                    assertTrue(task.awaitRunCompletion(java.time.Duration.ofSeconds(15)));
                    when(settings.getBooleanProperty("ytdlp.skipDownloaded", true)).thenReturn(false);
                    handler.resumeDownload(download).get(30, TimeUnit.SECONDS);
                    awaitRunning(task);
                    assertTrue(Files.readAllLines(arguments).contains("--no-download-archive"));
                    assertFalse(Files.readAllLines(arguments).contains("--download-archive"));
                    handler.cancelDownload(download, false).get(30, TimeUnit.SECONDS);
                });
    }

    @Test
    @Timeout(120)
    @DisplayName("An old run's late callback cannot remove the changeSettings replacement")
    void oldRunCallbackCannotRemoveReplacement() throws Exception {
        when(settings.isOverrideOutputPath()).thenReturn(true);
        Download download = newDownload("replace");
        download.setRequestedFileName("video.mp4");
        Files.createDirectories(download.getDestination());
        Path output = Files.writeString(download.getDestination().resolve("video.mp4"), "old output");
        handler.startDownload(download).get(30, TimeUnit.SECONDS);
        YtDlpDownloadTask original = factory.getDownloadTask(download.getId());
        assertNotNull(original);
        awaitRunning(original);
        assertFalse(Files.exists(output), "a new download overrides the existing output before starting");
        Files.writeString(output, "partially downloaded content");

        handler.changeSettings(download).get(60, TimeUnit.SECONDS);
        assertEquals("partially downloaded content", Files.readString(output),
                "replacing the engine task must leave partial output for the engine to resume");

        YtDlpDownloadTask replacement = factory.getDownloadTask(download.getId());
        assertNotNull(replacement, "the replacement must be tracked");
        assertNotSame(original, replacement, "changeSettings must start a replacement task");

        // The ORIGINAL run's completion callback settles only now, after the
        // replacement is already running
        assertTrue(original.awaitRunCompletion(java.time.Duration.ofSeconds(15)),
                "the original run must terminate");
        Thread.sleep(GRACE_MS);

        assertNotNull(factory.getDownloadTask(download.getId()),
                "the old run's late callback must not remove the replacement");
        awaitRunning(replacement);
        assertEquals(YtDlpDownloadTask.Status.DOWNLOADING, replacement.getStatus(),
                "the replacement must still be running after the old run settled");
        assertFalse(replacement.isCancelled(), "the replacement must not be cancelled");
    }
}
