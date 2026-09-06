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

    @BeforeAll static void gtk() { Gtk.init(); }

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
        NewMediaDialog dialog = (NewMediaDialog) dialog("media");
        Window window = field(dialog, "dialog", Window.class);
        window.present();
        YtDlpClient client = mock(YtDlpClient.class);
        YtDlpClient original = field(dialog, "ytDlpClient", YtDlpClient.class);
        original.shutdown();
        setField(dialog, "ytDlpClient", client);
        Entry url = field(dialog, "urlEntry", Entry.class);
        Button fetch = field(dialog, "fetchInfoButton", Button.class);
        try {
            for (String invalid : List.of("http://[", "ordinary text", "README.md",
                    "magnet:?xt=urn:btih:" + "a".repeat(40))) {
                url.setText(invalid);
                invoke(dialog, "onFetchInfo");
                assertTrue(fetch.getSensitive());
            }
            verify(client, never()).previewMedia(anyString(), any());
            url.setText("Example.COM/first");
            CompletableFuture<YtDlpClient.VideoInfo> first = new CompletableFuture<>();
            CompletableFuture<YtDlpClient.VideoInfo> second = new CompletableFuture<>();
            when(client.previewMedia(anyString(), any())).thenReturn(first, second);
            invoke(dialog, "onFetchInfo");
            verify(client).previewMedia(eq("https://example.com/first"), any());
            assertFalse(fetch.getSensitive());
            url.setText("http://127.0.0.1/second");
            assertTrue(fetch.getSensitive());
            invoke(dialog, "onFetchInfo");
            first.completeExceptionally(new IllegalStateException("stale"));
            drain();
            assertFalse(fetch.getSensitive(), "old completion must not enable a newer fetch");
            YtDlpClient.VideoInfo info = new YtDlpClient.VideoInfo();
            info.setTitle("Current metadata");
            second.complete(info);
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
