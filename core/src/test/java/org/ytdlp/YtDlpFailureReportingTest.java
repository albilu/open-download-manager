package org.ytdlp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class YtDlpFailureReportingTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @Timeout(30)
    void errorOutputDoesNotTerminateALiveProcessAndFinalOutcomeIsReportedOnce(int exitCode) throws Exception {
        Path tool = directory.resolve("controlled-yt-dlp");
        Files.writeString(tool, """
                #!/bin/sh
                echo '[download] Destination: video.mp4'
                echo 'ERROR: HTTP Error 403: Forbidden'
                echo '[download] 50.0%% of 1.00KiB at 1.00KiB/s |odmbytes|512|1024'
                while [ ! -e "$0.release" ]; do sleep 0.05; done
                printf 'payload' > video.mp4
                exit %d
                """.formatted(exitCode));
        assertTrue(tool.toFile().setExecutable(true));
        var client = new YtDlpClient(tool.toString());
        var errors = new AtomicInteger();
        var completions = new AtomicInteger();
        var progressAfterError = new CountDownLatch(1);
        var callback = new YtDlpClient.ProgressCallback() {
            public void onStart(String name) { }
            public void onProgress(float percent, long bytes, long total, float speed) { progressAfterError.countDown(); }
            public void onError(String error) { assertTrue(error.contains("403")); errors.incrementAndGet(); }
            public void onComplete(String name) { completions.incrementAndGet(); }
        };
        try {
            var settings = new YtDlpSettings().setUseAria2c(false).setUseDownloadArchive(false);
            settings.setIgnoreErrors(exitCode == 0);
            var result = client.download("http://media.invalid/video", settings, directory, callback);
            assertTrue(progressAfterError.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "the native process still owns this operation");
            assertEquals(0, errors.get(), "an error line must not release a queue slot");
            assertEquals(0, completions.get());
            Files.createFile(Path.of(tool + ".release"));
            if (exitCode == 0) { result.get(10, TimeUnit.SECONDS); }
            else { assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS)); }
            assertEquals(exitCode, errors.get());
            assertEquals(1 - exitCode, completions.get());
        } finally { client.shutdown(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void realMetadataFailureIncludesHttpStatusWithoutTheSignedUrl(boolean filenameProbe) throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));
            server.start();
            var client = new YtDlpClient();
            try {
                String url = server.url("/video.mp4?signature=private-signature").toString();
                java.util.concurrent.CompletableFuture<?> request = filenameProbe
                        ? client.download(url, new YtDlpSettings().setUseDownloadArchive(false), directory,
                                null, "name-probe", false, names -> fail("a failed probe cannot reserve names"))
                        : client.previewMedia(url, new YtDlpSettings());
                var error = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> request.get(20, TimeUnit.SECONDS));
                assertTrue(error.getMessage().contains("403"), error.getMessage());
                assertFalse(error.getMessage().contains("private-signature"));
                assertFalse(error.getMessage().contains(server.url("/").toString()));
            } finally { client.shutdown(); }
        }
    }

    @Test
    @Timeout(20)
    void parsingFailureTerminatesTheProcessBeforeReportingItsError() throws Exception {
        Path tool = directory.resolve("controlled-yt-dlp");
        Files.writeString(tool, """
                #!/bin/sh
                echo $$ > native.pid
                echo '[download] Destination: video.mp4'
                echo '[download] 50.0% of 1.00KiB at 1.00KiB/s |odmbytes|512|1024'
                sleep 300
                """);
        assertTrue(tool.toFile().setExecutable(true));
        var errors = new AtomicInteger();
        var aliveAtError = new java.util.concurrent.atomic.AtomicBoolean();
        var client = new YtDlpClient(tool.toString());
        try {
            var callback = new YtDlpClient.ProgressCallback() {
                public void onStart(String name) { }
                public void onComplete(String name) { fail("the parser failed"); }
                public void onProgress(float percent, long bytes, long total, float speed) {
                    throw new IllegalArgumentException("invalid progress");
                }
                public void onError(String message) {
                    try {
                        long pid = Long.parseLong(Files.readString(directory.resolve("native.pid")).trim());
                        aliveAtError.set(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                    } catch (Exception error) { throw new AssertionError(error); }
                    errors.incrementAndGet();
                }
            };
            var result = client.download("http://media.invalid/video", new YtDlpSettings()
                    .setUseDownloadArchive(false).setUseAria2c(false), directory, callback);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertEquals(1, errors.get());
            assertFalse(aliveAtError.get());
        } finally { client.shutdown(); }
    }
}
