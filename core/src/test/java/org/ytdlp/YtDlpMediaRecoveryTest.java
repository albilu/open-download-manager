package org.ytdlp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native failures/retries; controlled discovery futures exercise lifecycle races. */
@Timeout(30)
class YtDlpMediaRecoveryTest {
    @TempDir Path directory;
    private final URI page = URI.create("https://media.invalid/page");
    private final URI media = URI.create("https://cdn.invalid/media.mp4?token=opaque%2Fvalue");
    private final MediaRequestContext context = new MediaRequestContext(page.toString(), page.toString(),
            "Browser UA", "https://media.invalid", "# Netscape HTTP Cookie File\n");

    @ParameterizedTest
    @ValueSource(strings = {"page", "empty-output", "same-source"})
    void nativeFailureOrEmptySuccessRetriesOnceWithDiscoveredContext(String source) throws Exception {
        var settings = settings(true);
        settings.setFormat("best");
        settings.setOutputNameCounter(2);
        URI recoveredUrl = source.equals("same-source") ? URI.create("https://media.invalid/" + source) : media;
        var resolver = resolver(CompletableFuture.completedFuture(result(recoveredUrl)));
        var discoveries = new AtomicInteger();
        try (var client = client()) {
            var task = new YtDlpDownloadTask("recover", "https://media.invalid/" + source, settings, directory, client);
            var resolved = new AtomicReference<MediaInfoResolver.Result>();
            task.setMediaRecovery(() -> { discoveries.incrementAndGet(); return resolver; }, resolved::set);
            assertNotNull(task.start().get(10, TimeUnit.SECONDS));
            assertEquals(YtDlpDownloadTask.Status.COMPLETED, task.getStatus());
            assertEquals(recoveredUrl.toString(), task.getUrl());
            assertEquals(recoveredUrl, resolved.get().downloadUrl());
            assertEquals(context, settings.getMediaRequestContext());
            assertEquals("Browser UA", settings.getUserAgent());
            assertEquals(page.toString(), settings.getReferer());
            assertEquals("best", settings.getFormat());
            assertEquals(2, settings.getOutputNameCounter());
            assertFalse(settings.isMediaProbeOnFailure());
            assertEquals(1, discoveries.get());
            assertEquals("payload", Files.readString(directory.resolve("recovered.mp4")));
            assertEquals(2, Files.readAllLines(directory.resolve("invocations")).size());
            verify(resolver, timeout(3000)).close();
        }
    }

    @Test void unsuccessfulProbeAndReplacementFailureDoNotLoop() throws Exception {
        for (boolean replacementFails : new boolean[]{false, true}) {
            var settings = settings(true);
            var pending = replacementFails ? CompletableFuture.completedFuture(result(URI.create("https://cdn.invalid/fail.mp4")))
                    : CompletableFuture.<MediaInfoResolver.Result>failedFuture(new IllegalStateException("No media source found"));
            var resolver = resolver(pending);
            try (var client = client()) {
                var task = new YtDlpDownloadTask("failed", page.toString(), settings, directory, client);
                task.setMediaRecovery(() -> resolver, ignored -> { });
                Exception failure = assertThrows(Exception.class, () -> task.start().get(10, TimeUnit.SECONDS));
                if (!replacementFails) {
                    assertTrue(failure.getMessage().contains("No media source found"));
                    assertTrue(failure.getMessage().contains("Unsupported URL"));
                }
                assertEquals(YtDlpDownloadTask.Status.ERROR, task.getStatus());
                assertFalse(settings.isMediaProbeOnFailure());
                verify(resolver).probe(any(), any(), any());
                var retried = new YtDlpDownloadTask("manual-retry", page.toString(), settings, directory, client);
                retried.setMediaRecovery(() -> { fail("a consumed probe must not run again"); return resolver; }, ignored -> { });
                assertThrows(Exception.class, () -> retried.start().get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void normalDownloadsAndMissingExecutablesNeverProbe() throws Exception {
        for (boolean missingTool : new boolean[]{false, true}) {
            var settings = settings(missingTool);
            try (var client = missingTool ? new ClosingClient(directory.resolve("absent").toString()) : client()) {
                var task = new YtDlpDownloadTask("no-probe", page.toString(), settings, directory, client);
                task.setMediaRecovery(() -> { fail("discovery must remain dormant"); return null; }, ignored -> { });
                assertThrows(Exception.class, () -> task.start().get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void successfulInitialDownloadDoesNotProbeAndConsumesEligibility() throws Exception {
        var settings = settings(true);
        try (var client = client()) {
            var task = new YtDlpDownloadTask("direct", media.toString(), settings, directory, client);
            task.setMediaRecovery(() -> { fail("successful downloads must not probe"); return null; }, ignored -> { });
            task.start().get(10, TimeUnit.SECONDS);
            assertFalse(settings.isMediaProbeOnFailure());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"destination", "progress", "zero-progress", "postprocessing"})
    void failureAfterTransferStartsNeverProbesEvenAfterReopening(String stage) throws Exception {
        var settings = settings(true);
        var discoveries = new AtomicInteger();
        var resolver = resolver(CompletableFuture.failedFuture(new IllegalStateException("Unexpected probe")));
        try (var client = client()) {
            var task = new YtDlpDownloadTask("transfer-failure", "https://media.invalid/fail-after-" + stage,
                    settings, directory, client);
            task.setMediaRecovery(() -> { discoveries.incrementAndGet(); return resolver; }, ignored -> { });
            Exception failure = assertThrows(Exception.class, () -> task.start().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("Transfer already started"), failure.getMessage());
            assertEquals(YtDlpDownloadTask.Status.ERROR, task.getStatus());
            assertFalse(settings.isMediaProbeOnFailure());
            assertEquals(0, discoveries.get());

            var restored = reopenSettings((YtDlpSettings) settings.copy());
            var restarted = new YtDlpDownloadTask("reopened", page.toString(), restored, directory, client);
            restarted.setMediaRecovery(() -> { discoveries.incrementAndGet(); return resolver; }, ignored -> { });
            Exception restartedFailure = assertThrows(Exception.class, () -> restarted.start().get(10, TimeUnit.SECONDS));
            assertTrue(restartedFailure.getMessage().contains("Unsupported URL"), restartedFailure.getMessage());
            assertFalse(restored.isMediaProbeOnFailure());
            assertEquals(0, discoveries.get(), "a resumed transfer's extraction failure must not trigger discovery");
            assertEquals(2, Files.readAllLines(directory.resolve("invocations")).size());
            verifyNoInteractions(resolver);
        }
    }

    @Test void pauseAndResumeAfterTransferStartsNeverReenablesDiscovery() throws Exception {
        var settings = settings(true);
        var discoveries = new AtomicInteger();
        var resolver = resolver(CompletableFuture.failedFuture(new IllegalStateException("Unexpected probe")));
        try (var client = client()) {
            var task = new YtDlpDownloadTask("pause-transfer", "https://media.invalid/pause-transfer",
                    settings, directory, client);
            task.setMediaRecovery(() -> { discoveries.incrementAndGet(); return resolver; }, ignored -> { });
            task.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> task.getDownloadedBytes() == 32
                    && Files.exists(directory.resolve("transfer-started")));
            assertTrue(task.pause());
            assertTrue(task.awaitRunCompletion(Duration.ofSeconds(5)));
            assertFalse(settings.isMediaProbeOnFailure());
            Exception failure = assertThrows(Exception.class, () -> task.resume().get(10, TimeUnit.SECONDS));
            assertTrue(failure.getMessage().contains("Unsupported URL"), failure.getMessage());
            assertEquals(YtDlpDownloadTask.Status.ERROR, task.getStatus());
            assertEquals(0, discoveries.get());
            assertEquals(2, Files.readAllLines(directory.resolve("invocations")).size());
            verifyNoInteractions(resolver);
        }
    }

    @Test void cancelStopsDiscoveryAndLateResultsCannotRestartTheDownload() throws Exception {
        var pending = new CompletableFuture<MediaInfoResolver.Result>();
        var resolver = resolver(pending);
        try (var client = client()) {
            var task = new YtDlpDownloadTask("cancel-probe", page.toString(), settings(true), directory, client);
            task.setMediaRecovery(() -> resolver, ignored -> fail("cancelled discovery must not mutate the record"));
            var running = task.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> task.getStatus() == YtDlpDownloadTask.Status.STARTING
                    && Files.exists(directory.resolve("invocations")) && !running.isDone());
            verify(resolver, timeout(5000)).probe(any(), any(), any());
            assertTrue(task.cancel());
            assertTrue(pending.isCancelled());
            assertFalse(pending.complete(result(media)));
            assertTrue(task.awaitRunCompletion(Duration.ofSeconds(5)));
            assertEquals(YtDlpDownloadTask.Status.CANCELED, task.getStatus());
            assertEquals(page.toString(), task.getUrl());
            assertEquals(1, Files.readAllLines(directory.resolve("invocations")).size());
            verify(resolver, timeout(3000)).close();
        }
    }

    @Test void pausedProbeCanBeResumedWithoutAcceptingItsRetiredResult() throws Exception {
        var pending = new CompletableFuture<MediaInfoResolver.Result>();
        var first = resolver(pending);
        var second = resolver(CompletableFuture.completedFuture(result(media)));
        var count = new AtomicInteger();
        var settings = settings(true);
        try (var client = client()) {
            var task = new YtDlpDownloadTask("pause-probe", page.toString(), settings, directory, client);
            task.setMediaRecovery(() -> count.getAndIncrement() == 0 ? first : second, ignored -> { });
            task.start();
            verify(first, timeout(5000)).probe(any(), any(), any());
            assertTrue(task.pause());
            assertTrue(pending.isCancelled());
            assertEquals(YtDlpDownloadTask.Status.PAUSED, task.getStatus());
            assertTrue(settings.isMediaProbeOnFailure());
            task.resume().get(10, TimeUnit.SECONDS);
            assertEquals(YtDlpDownloadTask.Status.COMPLETED, task.getStatus());
            assertEquals(media.toString(), task.getUrl());
            assertEquals(2, count.get());
            verify(first, timeout(3000)).close();
            verify(second, timeout(3000)).close();
        }
    }

    @Test void pendingRecoverySurvivesSettingsCopyAndJsonReopening() throws Exception {
        var settings = settings(true);
        assertTrue(((YtDlpSettings) settings.copy()).isMediaProbeOnFailure());
        var restored = reopenSettings(settings);
        assertTrue(restored.isMediaProbeOnFailure());
        assertFalse(new YtDlpSettings().isMediaProbeOnFailure());
    }

    private YtDlpSettings reopenSettings(YtDlpSettings settings) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper.readValue(mapper.writeValueAsBytes(settings), YtDlpSettings.class);
    }

    private YtDlpSettings settings(boolean recover) {
        var settings = new YtDlpSettings();
        settings.setMediaProbeOnFailure(recover);
        settings.setUseDownloadArchive(false);
        return settings;
    }

    private MediaInfoResolver.Result result(URI destination) {
        return new MediaInfoResolver.Result(page, destination, new YtDlpClient.VideoInfo(), context);
    }

    private MediaInfoResolver resolver(CompletableFuture<MediaInfoResolver.Result> result) {
        var resolver = mock(MediaInfoResolver.class);
        when(resolver.probe(any(), any(), any())).thenReturn(result);
        return resolver;
    }

    private ClosingClient client() throws Exception {
        Path script = directory.resolve("controlled-yt-dlp");
        Files.writeString(script, """
                #!/bin/sh
                for argument do source="$argument"; done
                echo run >> invocations
                case "$source" in
                    */empty-output) exit 0;;
                    */page|*/fail.mp4) echo 'ERROR: Unsupported URL'; exit 1;;
                    */same-source) case "$*" in *"Browser UA"*) ;; *) echo 'ERROR: HTTP Error 403'; exit 1;; esac;;
                    */fail-after-destination) echo '[download] Destination: partial.mp4';;
                    */fail-after-progress) echo '[download] |odmbytes|32|64';;
                    */fail-after-zero-progress) echo '[download] |odmbytes|0|64';;
                    */fail-after-postprocessing) echo '|odmfile|finished.mp4';;
                    */pause-transfer)
                        if [ -f transfer-started ]; then echo 'ERROR: Unsupported URL'; exit 1; fi
                        echo '[download] Destination: partial.mp4'
                        echo '[download] |odmbytes|32|64'
                        touch transfer-started
                        while :; do sleep 1; done;;
                esac
                case "$source" in
                    */fail-after-*) echo 'ERROR: Transfer already started'; exit 1;;
                esac
                echo '[download] Destination: recovered.mp4'
                printf payload > recovered.mp4
                exit 0
                """);
        assertTrue(script.toFile().setExecutable(true));
        return new ClosingClient(script.toString());
    }

    private static final class ClosingClient extends YtDlpClient implements AutoCloseable {
        ClosingClient(String executable) { super(executable); }
        @Override public void close() { shutdown(); }
    }
}
