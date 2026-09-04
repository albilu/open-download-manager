package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task removal triggered from a run's completion callback must not block on
 * the reclaimed client's executor termination. The callback runs on that
 * very executor's thread; awaiting its termination inline self-deadlocks
 * until the shutdown timeout burns out (~10s per completed download).
 */
@DisplayName("YtDlpFactory removal must not block the client executor")
class YtDlpFactoryCleanupTest {

    @TempDir
    Path tempDir;

    private YtDlpFactory factory;
    private Path fakeTool;
    private org.mockito.Answers answers;

    @BeforeEach
    void setUp() throws Exception {
        fakeTool = tempDir.resolve("fake-yt-dlp");
        Files.writeString(fakeTool, "#!/bin/bash\n"
                + "case \" $* \" in *\" --version \"*) echo \"2024.01.01\"; exit 0;; esac\n"
                + "echo \"[download] Destination: video.mp4\"\n"
                + "exit 0\n");
        Files.setPosixFilePermissions(fakeTool, PosixFilePermissions.fromString("rwxr-xr-x"));

        YtDlpFactory.clearInstance();

        GlobalSettings settings = mock(GlobalSettings.class);
        when(settings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        org.ytdlp.YtDlpToolManager toolManager = mock(org.ytdlp.YtDlpToolManager.class);
        when(toolManager.getToolPath()).thenReturn(fakeTool.toString());
        org.manager.tools.ToolManagerFactory toolManagerFactory =
                mock(org.manager.tools.ToolManagerFactory.class);
        when(toolManagerFactory.getYtDlpManager()).thenReturn(toolManager);

        factory = YtDlpFactory.getInstance(settings, toolManagerFactory);
    }

    @AfterEach
    void tearDown() {
        YtDlpFactory.clearInstance();
    }

    @Test
    @Timeout(60)
    @DisplayName("Removal from the completion callback returns well under the shutdown timeout")
    void removalFromCompletionCallbackDoesNotBlockOnOwnExecutor() throws Exception {
        YtDlpDownloadTask task = factory.createDownloadTask("cleanup-timing",
                "http://example.test/v", null, tempDir);

        CountDownLatch removalDone = new CountDownLatch(1);
        AtomicLong elapsedMs = new AtomicLong(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        task.setProgressListener(new YtDlpClient.ProgressCallback() {
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
            }

            @Override
            public void onStart(String filename) {
            }

            @Override
            public void onComplete(String filename) {
                // Runs on the client executor thread, exactly like the
                // handler's completion callback would
                long start = System.nanoTime();
                try {
                    factory.removeDownloadTask(task.getTaskId());
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    removalDone.countDown();
                }
            }

            @Override
            public void onError(String error) {
            }
        });

        task.start().get(30, TimeUnit.SECONDS);

        assertTrue(removalDone.await(10, TimeUnit.SECONDS),
                "the completion callback must run");
        assertNotNull(task);
        if (failure.get() != null) {
            throw new AssertionError("removal failed", failure.get());
        }
        assertTrue(elapsedMs.get() < 3000,
                "removal must not block on the executor it runs on (took "
                        + elapsedMs.get() + "ms; shutdown timeout is 10s)");
    }
}
