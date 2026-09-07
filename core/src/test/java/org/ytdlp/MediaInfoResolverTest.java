package org.ytdlp;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaInfoResolverTest {
    private final URI page = URI.create("https://example.com/page");
    private final String media = "https://cdn.example/movie.mp4?token=abc%2Fdef";
    private final YtDlpClient client = mock(YtDlpClient.class);
    private final BrowserMediaProbe probe = mock(BrowserMediaProbe.class);
    private final YtDlpClient.VideoInfo info = new YtDlpClient.VideoInfo();
    private final MediaRequestContext context = new MediaRequestContext(page.toString(), page.toString(),
            "Browser UA", "https://example.com", "# Netscape HTTP Cookie File\n");

    @Test void successfulExtractionDoesNotLaunchBrowser() throws Exception {
        when(client.previewMedia(anyString(), any())).thenReturn(CompletableFuture.completedFuture(info));
        try (var resolver = new MediaInfoResolver(client, probe)) {
            var result = resolver.fetch(page, new YtDlpSettings(), CompletableFuture.completedFuture(null), ignored -> { })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(page, result.downloadUrl());
            verifyNoInteractions(probe);
        }
    }

    @Test void failedPageResolvesDirectMediaAndCarriesContext() throws Exception {
        when(client.previewMedia(eq(page.toString()), any())).thenReturn(failure());
        when(probe.probe(eq(page), any(), isNull())).thenReturn(CompletableFuture.completedFuture(
                List.of(new MediaCandidate(media, 90, 1234, context))));
        when(client.previewMedia(eq(media), any())).thenAnswer(call -> {
            YtDlpSettings settings = call.getArgument(1);
            assertEquals(context, settings.getMediaRequestContext());
            assertEquals("Browser UA", settings.getUserAgent());
            assertEquals(page.toString(), settings.getReferer());
            return CompletableFuture.completedFuture(info);
        });
        try (var resolver = new MediaInfoResolver(client, probe)) {
            var statuses = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var result = resolver.fetch(page, new YtDlpSettings(), CompletableFuture.completedFuture(null), statuses::add)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(URI.create(media), result.downloadUrl());
            assertEquals(page, result.enteredUrl());
            assertEquals(context, result.context());
            assertEquals(List.of("Probing media…", "Fetching media info…"), statuses);
            verify(client, times(2)).previewMedia(anyString(), any());
        }
    }

    @Test void missingToolAndFailedReadinessDoNotLaunchBrowser() throws Exception {
        when(client.previewMedia(anyString(), any())).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("missing tool")));
        try (var resolver = new MediaInfoResolver(client, probe)) {
            assertThrows(Exception.class, () -> resolver.fetch(page, new YtDlpSettings(),
                    CompletableFuture.completedFuture(null), ignored -> { }).get(5, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> resolver.fetch(page, new YtDlpSettings(),
                    CompletableFuture.failedFuture(new IllegalStateException("Tor unavailable")), ignored -> { }).get(5, TimeUnit.SECONDS));
            verifyNoInteractions(probe);
            verify(client).previewMedia(anyString(), any());
        }
    }

    @Test void cancellingPropagatesToActiveProbeAndNeverRetriesMetadata() throws Exception {
        when(client.previewMedia(anyString(), any())).thenReturn(failure());
        var pending = new CompletableFuture<List<MediaCandidate>>();
        when(probe.probe(any(), any(), any())).thenReturn(pending);
        try (var resolver = new MediaInfoResolver(client, probe)) {
            var result = resolver.fetch(page, new YtDlpSettings(), CompletableFuture.completedFuture(null), ignored -> { });
            verify(probe, timeout(3000)).probe(any(), any(), any());
            assertTrue(result.cancel(true));
            org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS).until(pending::isCancelled);
            verify(client).previewMedia(anyString(), any());
        }
    }

    @Test void cancellingOriginalMetadataDoesNotFallBack() {
        var pending = new CompletableFuture<YtDlpClient.VideoInfo>();
        when(client.previewMedia(anyString(), any())).thenReturn(pending);
        try (var resolver = new MediaInfoResolver(client, probe)) {
            var result = resolver.fetch(page, new YtDlpSettings(), CompletableFuture.completedFuture(null), ignored -> { });
            verify(client, timeout(3000)).previewMedia(anyString(), any());
            result.cancel(true);
            org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS).until(pending::isCancelled);
            verifyNoInteractions(probe);
        }
    }

    @Test void equallyPlausibleUnrelatedStreamsRequireSpecificUrl() {
        when(client.previewMedia(anyString(), any())).thenReturn(failure());
        when(probe.probe(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(List.of(
                new MediaCandidate(media, 100, 0, context),
                new MediaCandidate("https://cdn.example/other.mpd", 100, 0, context))));
        try (var resolver = new MediaInfoResolver(client, probe)) {
            Exception failure = assertThrows(Exception.class, () -> resolver.fetch(page, new YtDlpSettings(),
                    CompletableFuture.completedFuture(null), ignored -> { }).get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Several media sources"));
            verify(client).previewMedia(anyString(), any());
        }
    }

    private CompletableFuture<YtDlpClient.VideoInfo> failure() {
        return CompletableFuture.failedFuture(new YtDlpClient.MediaExtractionException("unsupported page"));
    }
}
