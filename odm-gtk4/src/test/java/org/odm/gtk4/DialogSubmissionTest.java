package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.*;
import org.ytdlp.YtDlpClient;

class DialogSubmissionTest {
    @TempDir Path directory;
    DownloadManagerImpl actual;
    DownloadManager manager;

    @BeforeAll static void gtk() {
        Gtk.init();
        // Initialize GLib types here before DownloadManager background callbacks start.
        org.gnome.glib.GLib.getMonotonicTime();
        MainContext.default_();
    }

    @BeforeEach void setup() {
        actual = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        actual.getGlobalSettings().setProperty("ui.offline", "true");
        actual.getGlobalSettings().setGlobalProxyEnabled(false);
        actual.getGlobalSettings().setDefaultDownloadDirectory(directory);
        manager = spy(actual);
    }

    @AfterEach void cleanup() {
        actual.getAllDownloads().forEach(d -> actual.cancelDownload(d, false).join());
    }

    private Object dialog(String kind) {
        return switch (kind) {
            case "file" -> new NewDownloadDialog(null, manager, () -> { });
            case "media" -> new NewMediaDialog(null, manager, () -> { });
            default -> new NewWebsiteDialog(null, manager, () -> { });
        };
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mediaFetchInheritsGlobalProxyOrTorBeforeAnyExtraction(boolean torEnabled) throws Exception {
        var global = new org.manager.GlobalSettings()
                .setGlobalProxyEnabled(true).setGlobalProxyAddress("http://proxy.example:8080");
        global.setProperty("tor.enabled", Boolean.toString(torEnabled));
        var isolatedManager = mock(DownloadManager.class);
        when(isolatedManager.getGlobalSettings()).thenReturn(global);
        var torService = mock(org.tor.TorService.class);
        when(torService.isRunning()).thenReturn(true);
        when(torService.getSocksPort()).thenReturn(19050);
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        when(resolver.fetch(any(), any(), any(), any())).thenReturn(new CompletableFuture<>());
        var dialog = new NewMediaDialog(null, isolatedManager, () -> { }, torService, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            field(dialog, "urlEntry", Entry.class).setText("https://example.com/page");
            invoke(dialog, "onFetchInfo");
            var settings = org.mockito.ArgumentCaptor.forClass(org.ytdlp.YtDlpSettings.class);
            verify(resolver).fetch(eq(URI.create("https://example.com/page")), settings.capture(), any(), any());
            assertTrue(settings.getValue().isUseProxy());
            assertEquals(torEnabled ? "socks5h://127.0.0.1:19050" : "http://proxy.example:8080",
                    settings.getValue().getProxyAddress());
        } finally { window.close(); }
    }

    @ParameterizedTest @ValueSource(strings = {"file", "media", "website"})
    void repeatedInvalidInputCreatesNoHistoryAndRetryReadsCurrentInput(String kind) throws Exception {
        Object dialog = dialog(kind);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        Entry url = field(dialog, "urlEntry", Entry.class);
        NetworkOptionsPane network = field(dialog, "networkOptions", NetworkOptionsPane.class);
        try {
            url.setText("http://127.0.0.1:9/first");
            field(network, "proxyType", DropDown.class).setSelected(1);
            field(network, "proxyHost", Entry.class).setText("");
            invoke(dialog, "onStart");
            invoke(dialog, "onStart");
            assertTrue(actual.getAllDownloads().isEmpty(), "validation must precede insertion");
            if (kind.equals("file")) {
                field(network, "proxyType", DropDown.class).setSelected(0);
                field(dialog, "filenameEntry", Entry.class).setText("../invalid.bin");
                invoke(dialog, "onStart");
                invoke(dialog, "onStart");
                assertTrue(actual.getAllDownloads().isEmpty());
                field(dialog, "filenameEntry", Entry.class).setText("good.bin");
            }
            field(network, "proxyType", DropDown.class).setSelected(0);
            AtomicInteger calls = new AtomicInteger();
            doAnswer(call -> {
                Download draft = call.getArgument(0);
                return actual.queueDownload(draft).thenCompose(ignored -> calls.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("fixture rejection"))
                        : CompletableFuture.completedFuture(null));
            }).when(manager).queueDownload(any(Download.class));
            invoke(dialog, "onStart");
            Button start = field(dialog, "startButton", Button.class);
            pump(() -> calls.get() == 1 && start.getSensitive());
            assertTrue(actual.getAllDownloads().isEmpty(), "failed queue insertion must roll back");
            url.setText("http://127.0.0.1:9/edited");
            if (!kind.equals("website")) { setField(dialog, "destinationFolder", directory.resolve("edited")); }
            invoke(dialog, "onStart");
            pump(() -> calls.get() == 2 && actual.getAllDownloads().size() == 1);
            Download accepted = actual.getAllDownloads().getFirst();
            assertEquals(URI.create("http://127.0.0.1:9/edited"), accepted.getUri());
            assertEquals(kind.equals("website") ? directory : directory.resolve("edited"), accepted.getDestination());
        } finally { window.close(); }
    }

    @Test void closingBeforeReadinessCreatesNoRowAndKeepsOriginalDescriptor() throws Exception {
        Path original = java.nio.file.Files.writeString(directory.resolve("source%20 name.meta4"), "descriptor");
        Download draft = DownloadSubmission.draft(manager, original.toUri(), directory, Download.Type.ARIA2);
        assertEquals(original.getFileName().toString(), draft.getName());
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        var submission = DownloadSubmission.submit(manager, draft, ready, closed, original);
        closed.set(true);
        ready.complete(null);
        assertThrows(java.util.concurrent.CompletionException.class, submission::join);
        assertTrue(actual.getAllDownloads().isEmpty());
        assertTrue(java.nio.file.Files.exists(original));
    }

    @Test void rejectedDescriptorSubmissionRetainsOriginalAndReleasesCopyAndActions() throws Exception {
        Path original = java.nio.file.Files.writeString(directory.resolve("source%20 name.meta4"), "descriptor");
        Download draft = DownloadSubmission.draft(manager, original.toUri(), directory, Download.Type.ARIA2);
        manager.addAfterCompletionAction(draft, new org.manager.download.action.ExecuteCommandAction("true"));
        doAnswer(call -> actual.queueDownload(call.getArgument(0)).thenCompose(ignored ->
                CompletableFuture.failedFuture(new IllegalStateException("queue rejected"))))
                .when(manager).queueDownload(any(Download.class));
        var submission = DownloadSubmission.submit(manager, draft, CompletableFuture.completedFuture(null),
                new java.util.concurrent.atomic.AtomicBoolean(), original);
        assertThrows(java.util.concurrent.CompletionException.class, submission::join);
        assertTrue(actual.getAllDownloads().isEmpty());
        assertTrue(java.nio.file.Files.exists(original));
        assertNotEquals(original.toUri(), draft.getUri());
        assertFalse(java.nio.file.Files.exists(Path.of(draft.getUri())));
        assertTrue(actual.getAfterCompletionActions(draft).isEmpty());
    }

    @Test void aFailedRollbackKeepsTheExistingDownloadVisibleWithoutSubmittingAnother() throws Exception {
        NewMediaDialog dialog = (NewMediaDialog) dialog("media");
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            field(dialog, "urlEntry", Entry.class).setText("http://127.0.0.1/file");
            doAnswer(call -> actual.queueDownload(call.getArgument(0)).thenCompose(ignored ->
                    CompletableFuture.failedFuture(new IllegalStateException("rejected"))))
                    .when(manager).queueDownload(any(Download.class));
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("cannot cancel")))
                    .when(manager).cancelDownload(any(Download.class), eq(false));
            invoke(dialog, "onStart");
            Label status = field(dialog, "statusLabel", Label.class);
            pump(() -> status.getLabel().contains("remains in Downloads"));
            assertFalse(field(dialog, "startButton", Button.class).getSensitive());
            invoke(dialog, "onStart");
            assertEquals(1, actual.getAllDownloads().size());
        } finally { window.close(); }
    }

    @Test void invalidAndSupersededMetadataRequestsRestoreControlsWithoutStaleCallbacks() throws Exception {
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        NewMediaDialog dialog = new NewMediaDialog(null, manager, () -> { }, null, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        Entry url = field(dialog, "urlEntry", Entry.class);
        Button fetch = field(dialog, "fetchInfoButton", Button.class);
        try {
            for (String invalid : List.of("http://[", "ordinary text", "README.md",
                    "magnet:?xt=urn:btih:" + "a".repeat(40))) {
                url.setText(invalid);
                invoke(dialog, "onFetchInfo");
                assertTrue(fetch.getSensitive());
            }
            verify(resolver, never()).fetch(any(), any(), any(), any());
            url.setText("Example.COM/first");
            CompletableFuture<org.ytdlp.MediaInfoResolver.Result> first = new CompletableFuture<>();
            CompletableFuture<org.ytdlp.MediaInfoResolver.Result> second = new CompletableFuture<>();
            when(resolver.fetch(any(), any(), any(), any())).thenReturn(first, second);
            invoke(dialog, "onFetchInfo");
            verify(resolver).fetch(eq(URI.create("https://example.com/first")), any(), any(), any());
            assertFalse(fetch.getSensitive());
            url.setText("http://127.0.0.1/second");
            assertTrue(fetch.getSensitive());
            invoke(dialog, "onFetchInfo");
            first.completeExceptionally(new IllegalStateException("stale"));
            drain();
            assertFalse(fetch.getSensitive(), "old completion must not enable a newer fetch");
            YtDlpClient.VideoInfo info = new YtDlpClient.VideoInfo();
            info.setTitle("Current metadata");
            URI current = URI.create("http://127.0.0.1/second");
            second.complete(new org.ytdlp.MediaInfoResolver.Result(current, current, info, null));
            pump(fetch::getSensitive);
            assertTrue(field(dialog, "infoLabel", Label.class).getLabel().contains("Current metadata"));
        } finally { window.close(); }
    }

    @Test void sequencePreviewExcludesRandomTextAndUsesNormalizedUrls() throws Exception {
        ImportSequenceDialog sequence = new ImportSequenceDialog(null, manager, () -> { });
        Window window = field(sequence, "dialog", Window.class);
        window.present();
        try {
            field(sequence, "numStartSpin", SpinButton.class).setValue(1);
            field(sequence, "numVersSpin", SpinButton.class).setValue(1);
            field(sequence, "numCountSpin", SpinButton.class).setValue(1);
            Entry entry = field(sequence, "uriEntry", Entry.class);
            Button accept = field(sequence, "validateButton", Button.class);
            SpinnerActivity activity = field(sequence, "activity", SpinnerActivity.class);
            entry.setText("Example.COM/file-{}.zip");
            pump(accept::getSensitive);
            assertEquals(List.of("https://example.com/file-1.zip"),
                    field(sequence, "currentPreviewUrls", List.class));
            entry.setText("random item-{}");
            pump(() -> activity.activeCount() == 0);
            assertFalse(accept.getSensitive());
            assertEquals(List.of(), field(sequence, "currentPreviewUrls", List.class));
        } finally { window.close(); }
    }

    @Test void mediaSubmissionUsesTheDiscoveredUrlFormatAndRequestContext() throws Exception {
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        URI page = URI.create("https://example.com/page");
        URI source = URI.create("https://cdn.example/movie.mp4?token=a%2Fb");
        var context = new org.ytdlp.MediaRequestContext(page.toString(), page.toString(), "Browser UA",
                "https://example.com", "# Netscape HTTP Cookie File\n");
        var info = new YtDlpClient.VideoInfo();
        var format = new YtDlpClient.VideoFormat();
        format.setFormatId("720");
        info.setFormats(List.of(format));
        when(resolver.fetch(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                new org.ytdlp.MediaInfoResolver.Result(page, source, info, context)));
        var dialog = new NewMediaDialog(null, manager, () -> { }, null, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            field(dialog, "urlEntry", Entry.class).setText(page.toString());
            invoke(dialog, "onFetchInfo");
            pump(() -> fieldUnchecked(dialog, "formatDrop", DropDown.class).getSensitive());
            field(dialog, "formatDrop", DropDown.class).setSelected(1);
            assertEquals(page.toString(), field(dialog, "urlEntry", Entry.class).getText());
            invoke(dialog, "onStart");
            pump(() -> !actual.getAllDownloads().isEmpty());
            Download download = actual.getAllDownloads().iterator().next();
            assertEquals(source, download.getUri());
            var settings = (org.ytdlp.YtDlpSettings) download.getSettings();
            assertEquals(context, settings.getMediaRequestContext());
            assertEquals("720", settings.getFormat());
            assertEquals("Browser UA", settings.getUserAgent());
            assertEquals(page.toString(), settings.getReferer());
        } finally { window.close(); }
    }

    @Test void requestChangesInvalidateResolvedMediaAndClosingCancelsTheWholeFetch() throws Exception {
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        URI page = URI.create("https://example.com/page");
        var info = new YtDlpClient.VideoInfo();
        var pending = new CompletableFuture<org.ytdlp.MediaInfoResolver.Result>();
        when(resolver.fetch(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                new org.ytdlp.MediaInfoResolver.Result(page, URI.create("https://cdn.example/file.mp4"), info,
                        new org.ytdlp.MediaRequestContext(page.toString(), page.toString(), "UA", "", ""))), pending);
        var dialog = new NewMediaDialog(null, manager, () -> { }, null, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            field(dialog, "urlEntry", Entry.class).setText(page.toString());
            invoke(dialog, "onFetchInfo");
            pump(() -> fieldUnchecked(dialog, "infoLabel", Label.class).getVisible());
            assertNotNull(field(dialog, "resolvedMedia", org.ytdlp.MediaInfoResolver.Result.class));
            var network = field(dialog, "networkOptions", NetworkOptionsPane.class);
            field(network, "referer", Entry.class).setText("https://example.com/changed");
            assertNull(field(dialog, "resolvedMedia", org.ytdlp.MediaInfoResolver.Result.class));
            assertFalse(field(dialog, "formatDrop", DropDown.class).getSensitive());
            invoke(dialog, "onFetchInfo");
            assertTrue(field(dialog, "metadataSpinner", Spinner.class).getVisible());
            window.close();
            assertTrue(pending.isCancelled());
            verify(resolver, timeout(3000)).close();
        } finally { window.close(); }
    }

    @Test void aRejectedMediaQueueSubmissionKeepsTheResolvedSourceForRetry() throws Exception {
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        URI page = URI.create("https://example.com/page");
        URI media = URI.create("https://cdn.example/movie.mp4");
        when(resolver.fetch(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(
                new org.ytdlp.MediaInfoResolver.Result(page, media, new YtDlpClient.VideoInfo(),
                        new org.ytdlp.MediaRequestContext(page.toString(), page.toString(), "UA", "", ""))));
        var dialog = new NewMediaDialog(null, manager, () -> { }, null, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            field(dialog, "urlEntry", Entry.class).setText(page.toString());
            invoke(dialog, "onFetchInfo");
            pump(() -> fieldUnchecked(dialog, "infoLabel", Label.class).getVisible());
            doReturn(CompletableFuture.failedFuture(new IllegalStateException("rejected")))
                    .doAnswer(call -> actual.queueDownload(call.getArgument(0)))
                    .when(manager).queueDownload(any(Download.class));
            invoke(dialog, "onStart");
            pump(() -> fieldUnchecked(dialog, "statusLabel", Label.class).getLabel().contains("Press Download to retry"));
            invoke(dialog, "onStart");
            pump(() -> !actual.getAllDownloads().isEmpty());
            assertEquals(media, actual.getAllDownloads().iterator().next().getUri());
        } finally { window.close(); }
    }

    private static <T> T fieldUnchecked(Object object, String name, Class<T> type) {
        try { return field(object, name, type); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test void destroyingTheParentStopsAnOpenMediaFetch() throws Exception {
        var resolver = mock(org.ytdlp.MediaInfoResolver.class);
        var pending = new CompletableFuture<org.ytdlp.MediaInfoResolver.Result>();
        when(resolver.fetch(any(), any(), any(), any())).thenReturn(pending);
        Window parent = new Window();
        parent.present();
        var dialog = new NewMediaDialog(parent, manager, () -> { }, null, resolver);
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        try {
            pump(() -> parent.getRealized() && window.getRealized());
            field(dialog, "urlEntry", Entry.class).setText("https://example.com/page");
            invoke(dialog, "onFetchInfo");
            parent.destroy();
            assertFalse(parent.getRealized(), "parent should unrealize immediately");
            pump(pending::isCancelled);
            verify(resolver, timeout(3000)).close();
        } finally { window.destroy(); parent.destroy(); }
    }

    private static void invoke(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(object);
    }
    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    private static void drain() {
        var context = MainContext.default_();
        while (context.pending()) { context.iteration(false); }
    }
    private static void pump(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { drain(); Thread.sleep(10); }
        drain();
        assertTrue(condition.getAsBoolean(), "dialog operation did not settle");
    }
}
