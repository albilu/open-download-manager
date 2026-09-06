package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ManagerUrlAdmissionTest {
    @TempDir Path directory;

    static final class RecordingHandler extends AbstractDownloadHandler {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger resumes = new AtomicInteger();
        URI received;

        RecordingHandler() { super(null, null, null); }
        @Override public Download.Type getSupportedType() { return Download.Type.ARIA2; }
        @Override protected void doInitialize() { }
        @Override protected void doShutdown() { }
        @Override public CompletableFuture<String> startDownload(Download download) {
            received = download.getUri();
            return CompletableFuture.completedFuture("url-test-" + starts.incrementAndGet());
        }
        @Override public CompletableFuture<Void> pauseDownload(Download download) {
            notifyDownloadPause(download);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> resumeDownload(Download download) {
            resumes.incrementAndGet();
            notifyDownloadResume(download);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> cancelDownload(Download download, boolean files) {
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void rawDraftsCannotBypassCreationQueueStartOrResumeValidation() throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            RecordingHandler handler = new RecordingHandler();
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.ARIA2, handler);
            // Invalid candidates must be rejected even while scheduling would hold valid work.
            manager.setDownloadGate(id -> false);
            try {
                List<Function<Download, CompletableFuture<Void>>> queuePaths = List.of(
                        manager::queueDownload, manager::queueDownloadForManualStart,
                        manager::queueDownloadFromBackgroundSource);
                List<URI> invalidUris = Arrays.asList(null, URI.create("README.md"),
                        URI.create("ordinary-text"), URI.create("javascript:alert(1)"),
                        URI.create("https:///missing-host.zip"),
                        URI.create("https://example.com:70000/file.zip"),
                        URI.create("https://exa_mple.com/file.zip"),
                        URI.create("magnet:?xt=urn:btih:short"),
                        URI.create("file:///tmp/arbitrary.txt"));
                for (URI uri : invalidUris) {
                    assertThrows(IllegalArgumentException.class,
                            () -> manager.createDownload(uri, directory));
                    assertThrows(CompletionException.class,
                            () -> manager.previewDownloadFiles(uri).join());
                    for (var queue : queuePaths) {
                        Download draft = rawDraft(uri);
                        assertThrows(CompletionException.class, () -> queue.apply(draft).join());
                        assertEquals(Download.Status.ERROR, draft.getStatus());
                        assertNull(manager.getDownload(draft.getId()));
                    }
                    Download directStart = rawDraft(uri);
                    manager.startDownload(directStart).join();
                    assertEquals(Download.Status.ERROR, directStart.getStatus());
                    Download resume = rawDraft(uri);
                    resume.setStatus(Download.Status.PAUSED);
                    assertThrows(CompletionException.class, () -> manager.resumeDownload(resume).join());
                    assertEquals(Download.Status.ERROR, resume.getStatus());
                }
                assertEquals(0, manager.getDownloadCount());
                assertTrue(manager.getDownloadsByStatus(Download.Status.ERROR).isEmpty(),
                        "rejected drafts must not leave entries in the history indexes");
                assertEquals(0, handler.starts.get());
                assertEquals(0, handler.resumes.get());
                assertEquals(0, manager.getRunningDownloadCount());

                assertThrows(IllegalArgumentException.class,
                        () -> manager.createTorrentDownload(directory.resolve("arbitrary.txt"), directory));
                URI magnet = URI.create("magnet:?xt=urn:btih:" + "a".repeat(40));
                assertThrows(IllegalArgumentException.class,
                        () -> manager.createYoutubeDownload(magnet, directory, null));
                assertThrows(IllegalArgumentException.class,
                        () -> manager.createWebsiteDownload(magnet, directory, null));
                assertThrows(IllegalArgumentException.class,
                        () -> manager.createMetaLinkDownload(magnet, directory));
                for (Download.Type type : List.of(Download.Type.YOUTUBE, Download.Type.WEBSITE_SCRAPING)) {
                    Download forced = rawDraft(magnet);
                    forced.setType(type);
                    assertThrows(CompletionException.class, () -> manager.queueDownload(forced).join());
                }

                Download draft = rawDraft(URI.create("HTTPS://Example.COM/file.zip?token=a%2Bb&n=1"));
                draft.addMirror(URI.create("mirror.example.org/file.zip"));
                draft.setFileSources("", List.of("Example.COM/file.zip?token=a%2Bb&n=1"));
                manager.queueDownloadForManualStart(draft).join();
                URI normalized = URI.create("https://example.com/file.zip?token=a%2Bb&n=1");
                assertEquals(normalized.toString(), draft.getUri().toString());
                assertEquals(List.of(URI.create("https://mirror.example.org/file.zip")), draft.getMirrors());
                assertEquals(List.of(normalized.toString()), draft.getSourceUris());
                manager.setDownloadGate(null);
                manager.startDownload(draft).join();
                assertEquals(1, handler.starts.get());
                assertEquals(normalized, handler.received);
                manager.pauseDownload(draft).join();
                draft.setUri(URI.create("https://example.com:70000/file.zip"));
                assertThrows(CompletionException.class, () -> manager.resumeDownload(draft).join());
                assertEquals(0, handler.resumes.get(), "live resume must validate before calling the handler");
                assertEquals(0, manager.getRunningDownloadCount());
            } finally {
                manager.getAllDownloads().forEach(download -> manager.cancelDownload(download, false).join());
                DownloadManagerFactory.shutdown();
            }
        });
    }

    @Test
    void mirrorAndOverrideValidationIsAtomicAndRetainsDescriptorOverrides() {
        URI original = URI.create("HTTPS://Example.COM/download?id=123");
        Download draft = rawDraft(original);
        draft.setProtocol(Download.Protocol.METALINK);
        draft.addMirror(URI.create("mirror.example.org/file.zip"));
        draft.setSourceOverrides(Map.of("file.zip", List.of("javascript:alert(1)")));
        assertThrows(IllegalArgumentException.class, draft::validateSourcesForTransfer);
        assertEquals(original.toString(), draft.getUri().toString());
        assertEquals(List.of(URI.create("mirror.example.org/file.zip")), draft.getMirrors());
        draft.setSourceOverrides(Map.of("file.zip", List.of("cdn.example.org/file.zip")));
        draft.validateSourcesForTransfer();
        assertEquals(Download.Protocol.METALINK, draft.getProtocol());
        assertEquals(Map.of("file.zip", List.of("https://cdn.example.org/file.zip")), draft.getSourceOverrides());
        draft.addMirror(URI.create("file:///tmp/file.torrent"));
        assertThrows(IllegalArgumentException.class, draft::validateSourcesForTransfer);
    }

    private Download rawDraft(URI uri) {
        Download draft = new Download();
        draft.setUri(uri);
        draft.setType(Download.Type.ARIA2);
        draft.setName("fixture.zip");
        draft.setDestination(directory);
        return draft;
    }
}
