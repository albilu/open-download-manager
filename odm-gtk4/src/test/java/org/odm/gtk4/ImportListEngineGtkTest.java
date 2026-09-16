package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.gnome.glib.MainContext;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpSettings;

import com.sun.net.httpserver.HttpServer;

@Timeout(30)
class ImportListEngineGtkTest {
    @TempDir Path directory;

    @BeforeAll static void initializeGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
    }

    @Test void importButtonUsesTheCapturedEngineAndOnlyMarkedUrls() throws Exception {
        var queued = new CopyOnWriteArrayList<Download>();
        var accepted = new CompletableFuture<Void>();
        var manager = manager(queued, accepted);
        var done = new AtomicBoolean();
        var imported = dialog(manager, List.of("https://files.test/page", "https://files.test/skip.zip"), done);
        GtkBuilder builder = builder(imported);
        Window window = Widgets.require(builder, "import_dialog", Window.class);
        try {
            imported.present();
            DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
            assertEquals("Auto", ((org.gnome.gtk.StringObject) engine.getSelectedItem()).getString());
            engine.setSelected(ImportEngine.YT_DLP.ordinal());
            Widgets.require(builder, "mark_renderer", CellRendererToggle.class).emitToggled("1");
            Widgets.require(builder, "validate_button", Button.class).emitClicked();
            engine.setSelected(ImportEngine.ARIA2.ordinal());
            awaitGtk(() -> queued.size() == 1);
            Download download = queued.getFirst();
            assertEquals(URI.create("https://files.test/page"), download.getUri());
            assertEquals(Download.Type.YOUTUBE, download.getType());
            assertTrue(assertInstanceOf(YtDlpSettings.class, download.getSettings()).isMediaProbeOnFailure());
            assertEquals(directory, download.getDestination());
            assertFalse(done.get());
            accepted.complete(null);
            awaitGtk(done::get);
            assertFalse(window.getVisible());
        } finally {
            accepted.complete(null);
            window.destroy();
        }
    }

    @Test void incompatibleUrlsKeepTheDialogOpenUntilTheSelectionIsCorrected() throws Exception {
        var queued = new CopyOnWriteArrayList<Download>();
        var manager = manager(queued, CompletableFuture.completedFuture(null));
        var done = new AtomicBoolean();
        var imported = dialog(manager, List.of("https://files.test/page",
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"), done);
        GtkBuilder builder = builder(imported);
        Window window = Widgets.require(builder, "import_dialog", Window.class);
        try {
            imported.present();
            Widgets.require(builder, "engine_combo", DropDown.class).setSelected(ImportEngine.YT_DLP.ordinal());
            Button submit = Widgets.require(builder, "validate_button", Button.class);
            submit.emitClicked();
            awaitGtk(() -> submit.getSensitive()
                    && Widgets.require(builder, "disk_space_label", Label.class).getLabel().contains("Media:"));
            assertTrue(window.getVisible());
            assertFalse(done.get());
            verify(manager, never()).createDownload(any(), any());
            verify(manager, never()).queueDownload(any());

            Widgets.require(builder, "mark_renderer", CellRendererToggle.class).emitToggled("1");
            submit.emitClicked();
            awaitGtk(done::get);
            assertEquals(1, queued.size());
            assertEquals(Download.Type.YOUTUBE, queued.getFirst().getType());
        } finally {
            window.destroy();
        }
    }

    static Stream<Arguments> htmlEngines() {
        return Stream.of("file", "remote").flatMap(route ->
                Stream.of(ImportEngine.values()).map(engine -> Arguments.of(route, engine)));
    }

    @ParameterizedTest
    @MethodSource("htmlEngines")
    void htmlImportsOfferTheSameEnginesAndSubmitOnlyMarkedLinks(String route, ImportEngine selection)
            throws Exception {
        String page = "https://files.test/page";
        String media = "https://www.youtube.com/watch?v=12345678901";
        String html = "<base href='https://files.test/'><a href='page'>Page</a>"
                + "<a href='" + media + "'>Media</a><a href='skip.zip'>Skip</a>"
                + "<a href='page'>Duplicate</a><a href='javascript:alert(1)'>Invalid</a>";
        List<String> urls;
        if (route.equals("file")) {
            Path file = directory.resolve("links.html");
            Files.writeString(file, html);
            urls = HtmlImportExport.readHtmlLinks(file, ImportLimits.defaults());
        } else {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/links", exchange -> {
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start();
            try {
                urls = HtmlImportExport.fetchRemoteHtmlLinks(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/links"), null, ImportLimits.defaults());
            } finally {
                server.stop(0);
            }
        }
        assertEquals(List.of(page, media, "https://files.test/skip.zip"), urls);
        var queued = new CopyOnWriteArrayList<Download>();
        var done = new AtomicBoolean();
        var imported = ImportListDialog.presentHtmlUrls(null,
                manager(queued, CompletableFuture.completedFuture(null)), () -> done.set(true),
                urls, ImportLimits.defaults(), null);
        GtkBuilder builder = builder(imported);
        Window window = Widgets.require(builder, "import_dialog", Window.class);
        try {
            assertEquals("Import Links from HTML", window.getTitle());
            DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
            StringList choices = (StringList) engine.getModel();
            assertEquals(List.of("Auto", "HTTP/Torrent", "Media", "Web Scrap"),
                    java.util.stream.IntStream.range(0, choices.getNItems())
                            .mapToObj(choices::getString).toList());
            assertEquals(ImportEngine.AUTO.ordinal(), engine.getSelected());
            engine.setSelected(selection.ordinal());
            Widgets.require(builder, "mark_renderer", CellRendererToggle.class).emitToggled("2");
            Widgets.require(builder, "validate_button", Button.class).emitClicked();
            awaitGtk(done::get);
            assertFalse(window.getVisible());
            assertEquals(List.of(URI.create(page), URI.create(media)),
                    queued.stream().map(Download::getUri).toList());
            for (Download download : queued) {
                Download.Type expected = selection == ImportEngine.AUTO
                        ? download.getUri().toString().equals(media) ? Download.Type.YOUTUBE : Download.Type.ARIA2
                        : selection.type();
                assertEquals(expected, download.getType());
                assertEquals(directory, download.getDestination());
                if (expected == Download.Type.YOUTUBE) {
                    assertTrue(assertInstanceOf(YtDlpSettings.class, download.getSettings()).isMediaProbeOnFailure());
                }
            }
        } finally {
            window.destroy();
        }
    }

    private DownloadManager manager(List<Download> queued, CompletableFuture<Void> accepted) {
        var manager = mock(DownloadManager.class);
        var global = new GlobalSettings();
        global.setDefaultDownloadDirectory(directory);
        when(manager.getGlobalSettings()).thenReturn(global);
        when(manager.createDownload(any(), any())).thenAnswer(call -> {
            var download = new Download(call.<URI>getArgument(0));
            download.initSettings(new DownloadSettingsFactory(global));
            download.setDestination(call.getArgument(1));
            return download;
        });
        when(manager.queueDownload(any())).thenAnswer(call -> {
            Download download = call.getArgument(0);
            download.validateSourcesForTransfer();
            queued.add(download);
            return accepted;
        });
        return manager;
    }

    private static ImportListDialog dialog(DownloadManager manager, List<String> urls,
            AtomicBoolean done) throws Exception {
        var constructor = ImportListDialog.class.getDeclaredConstructor(Window.class,
                DownloadManager.class, Runnable.class, List.class, ImportLimits.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, manager, (Runnable) () -> done.set(true), urls,
                ImportLimits.defaults());
    }

    private static GtkBuilder builder(ImportListDialog dialog) throws Exception {
        var field = ImportListDialog.class.getDeclaredField("builder");
        field.setAccessible(true);
        return (GtkBuilder) field.get(dialog);
    }

    private static void awaitGtk(BooleanSupplier ready) throws InterruptedException {
        MainContext context = MainContext.default_();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            while (context.pending()) { context.iteration(false); }
            if (ready.getAsBoolean()) { return; }
            Thread.sleep(10);
        }
        fail("GTK import did not reach the expected state");
    }
}
