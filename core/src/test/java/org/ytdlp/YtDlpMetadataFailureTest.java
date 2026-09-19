package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class YtDlpMetadataFailureTest {
    @TempDir Path directory;

    private YtDlpClient client(String body, Duration timeout) throws Exception {
        Path executable = directory.resolve("yt-dlp-fixture");
        Files.writeString(executable, "#!/bin/sh\necho $$ > '" + directory.resolve("pid") + "'\n" + body);
        assertTrue(executable.toFile().setExecutable(true));
        return new YtDlpClient(executable.toString(), false, false, directory.resolve("archive.db"), timeout);
    }

    private YtDlpSettings settings() {
        return new YtDlpSettings().setUseDownloadArchive(false).setUseAria2c(false);
    }

    @Test void timeoutKeepsItsReasonAndTerminatesTheMetadataProcess() throws Exception {
        var client = client("exec sleep 70\n", Duration.ofMillis(200));
        try {
            var failure = assertThrows(Exception.class, () -> client.download("https://fixture.invalid/video",
                    settings(), directory, null, "timeout", false, names -> {}).get(5, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("metadata request timed out"), failure.toString());
            assertFalse(failure.getMessage().contains("cancelled"));
            assertStopped(client);
        } finally { client.shutdown(); }
    }

    @Test void outputLimitKeepsItsReasonAndTerminatesTheMetadataProcess() throws Exception {
        var client = client("head -c 4194305 /dev/zero\nexec sleep 70\n", Duration.ofSeconds(10));
        try {
            var failure = assertThrows(Exception.class, () -> client.download("https://fixture.invalid/video",
                    settings(), directory, null, "limit", false, names -> {}).get(5, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("4 MiB limit"), failure.toString());
            assertFalse(failure.getMessage().contains("cancelled"));
            assertStopped(client);
        } finally { client.shutdown(); }
    }

    @Test void explicitCancellationDuringMetadataRemainsCancellation() throws Exception {
        var client = client("exec sleep 70\n", Duration.ofSeconds(10));
        try {
            var run = client.download("https://fixture.invalid/video", settings(), directory,
                    null, "cancel", false, names -> {});
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(directory.resolve("pid")));
            client.cancelDownload("cancel");
            var failure = assertThrows(Exception.class, () -> run.get(5, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertStopped(client);
        } finally { client.shutdown(); }
    }

    private void assertStopped(YtDlpClient client) throws Exception {
        long pid = Long.parseLong(Files.readString(directory.resolve("pid")).strip());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertEquals(0, client.getActiveProcessCount());
    }

    @Test void pauseDuringMetadataKeepsTheTaskPaused() throws Exception {
        var client = client("exec sleep 70\n", Duration.ofSeconds(10));
        try {
            var task = new YtDlpDownloadTask("paused", "https://fixture.invalid/video", settings(), directory, client);
            task.setOutputNamePreparation(names -> {});
            task.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(directory.resolve("pid")));
            assertTrue(task.pause());
            assertTrue(task.awaitRunCompletion(Duration.ofSeconds(5)));
            assertEquals(YtDlpDownloadTask.Status.PAUSED, task.getStatus());
            assertStopped(client);
        } finally { client.shutdown(); }
    }

    @Test void timeoutRemainsEligibleForStartupMediaRecovery() throws Exception {
        var client = client("""
                case "$*" in
                  */resolved.mp4*) printf '%s\n' '|odmname|"resolved.mp4"' '|odmfile|resolved.mp4'; exit 0 ;;
                esac
                exec sleep 70
                """, Duration.ofMillis(200));
        var resolver = org.mockito.Mockito.mock(MediaInfoResolver.class);
        var page = java.net.URI.create("https://fixture.invalid/page");
        var media = java.net.URI.create("https://fixture.invalid/resolved.mp4");
        var result = new MediaInfoResolver.Result(page, media, new YtDlpClient.VideoInfo(),
                new MediaRequestContext(page.toString(), "", "", "", ""));
        org.mockito.Mockito.when(resolver.probe(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(result));
        try {
            var settings = settings();
            settings.setMediaProbeOnFailure(true);
            var task = new YtDlpDownloadTask("recover-timeout", page.toString(), settings, directory, client);
            task.setOutputNamePreparation(names -> {});
            task.setMediaRecovery(() -> resolver, ignored -> {});
            task.start().get(5, TimeUnit.SECONDS);
            assertEquals(YtDlpDownloadTask.Status.COMPLETED, task.getStatus());
            assertEquals(media.toString(), task.getUrl());
            assertFalse(settings.isMediaProbeOnFailure());
            org.mockito.Mockito.verify(resolver).probe(org.mockito.ArgumentMatchers.eq(page),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        } finally { client.shutdown(); }
    }
}
