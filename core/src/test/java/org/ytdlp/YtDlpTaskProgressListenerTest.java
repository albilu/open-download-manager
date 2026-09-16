package org.ytdlp;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handler must receive yt-dlp progress as PUSHED events (one thread, no
 * polling hop): the task's internal callback has to forward to an installed
 * listener. This replaces the old per-download 1 Hz monitor thread that
 * re-read the very fields the callback had just written.
 */
@DisplayName("YtDlpDownloadTask forwards progress events to an installed listener")
class YtDlpTaskProgressListenerTest {

    @Test
    @DisplayName("Transfer progress and final output size reach the listener as separate events")
    void progressEventsForwardedToListener() throws Exception {
        YtDlpClient mockClient = mock(YtDlpClient.class);
        when(mockClient.download(any(), any(), any(Path.class), any(), anyString()))
                .thenReturn(new CompletableFuture<>());

        YtDlpDownloadTask task = new YtDlpDownloadTask(
                "listener-test", "http://example.test/v",
                new YtDlpSettings(), Path.of("/tmp"), mockClient);

        CountDownLatch progressed = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> completedFile = new AtomicReference<>();
        AtomicInteger progressEvents = new AtomicInteger();
        AtomicLong finalSize = new AtomicLong(-1);

        task.setProgressListener(new YtDlpClient.ProgressCallback() {
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
                progressEvents.incrementAndGet();
                progressed.countDown();
            }

            @Override
            public void onFinalSize(long completedBytes) {
                finalSize.set(completedBytes);
            }

            @Override
            public void onStart(String filename) {
            }

            @Override
            public void onComplete(String filename) {
                completedFile.set(filename);
                completed.countDown();
            }

            @Override
            public void onError(String error) {
            }
        });

        task.start();

        org.mockito.ArgumentCaptor<YtDlpClient.ProgressCallback> captor =
                org.mockito.ArgumentCaptor.forClass(YtDlpClient.ProgressCallback.class);
        verify(mockClient).download(any(), any(), any(Path.class), captor.capture(), anyString());

        YtDlpClient.ProgressCallback internal = captor.getValue();
        internal.onProgress(42f, 420L, 1000L, 10f);
        internal.onFinalSize(350L);
        internal.onComplete("video.mp4");

        assertTrue(progressed.await(5, TimeUnit.SECONDS), "progress must be forwarded");
        assertTrue(completed.await(5, TimeUnit.SECONDS), "completion must be forwarded");
        assertEquals("video.mp4", completedFile.get());
        assertEquals(1, progressEvents.get(), "final accounting must not add a speed sample");
        assertEquals(350, finalSize.get());
        assertEquals(350, task.getDownloadedBytes());
        assertEquals(350, task.getTotalBytes());
        assertEquals(100, task.getProgress());
        assertEquals(0, task.getSpeed());
    }
}
