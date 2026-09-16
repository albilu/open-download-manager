package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Button;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpSettings;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
class ImportSequenceEngineGtkTest {
    @TempDir Path directory;

    @BeforeAll static void initializeGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
    }

    static Stream<Arguments> sequenceEngines() {
        return Stream.of(false, true).flatMap(characterMode ->
                Stream.of(ImportEngine.values()).map(engine -> Arguments.of(characterMode, engine)));
    }

    @ParameterizedTest
    @MethodSource("sequenceEngines")
    void numericAndCharacterImportsCaptureTheEngineAndOptions(boolean characterMode, ImportEngine selection)
            throws Exception {
        var queued = new CopyOnWriteArrayList<Download>();
        var accepted = new CompletableFuture<Void>();
        var done = new AtomicBoolean();
        var imported = new ImportSequenceDialog(null, manager(queued, accepted), () -> done.set(true));
        GtkBuilder builder = builder(imported);
        try {
            imported.present();
            DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
            StringList choices = (StringList) engine.getModel();
            assertEquals(List.of("Auto", "aria2", "yt-dlp", "HTTrack"),
                    java.util.stream.IntStream.range(0, choices.getNItems())
                            .mapToObj(choices::getString).toList());
            assertEquals(ImportEngine.AUTO.ordinal(), engine.getSelected());
            Widgets.require(builder, "num_start_spin", SpinButton.class).setValue(1);
            Widgets.require(builder, "num_vers_spin", SpinButton.class).setValue(2);
            Widgets.require(builder, "char_entry", Entry.class).setText("a");
            Widgets.require(builder, "char_vers_entry", Entry.class).setText("b");
            Widgets.require(builder, "num_combo", DropDown.class).setSelected(characterMode ? 1 : 0);
            Widgets.require(builder, "uri_entry", Entry.class).setText("https://files.test/page-{}");
            // Selecting while preview work is pending must also govern its eventual capabilities.
            engine.setSelected(selection.ordinal());
            awaitGtk(() -> Widgets.require(builder, "item_count_label", Label.class).getLabel().equals("2 items"));
            double connectionLimit = selection == ImportEngine.YT_DLP ? 64
                    : selection == ImportEngine.HTTRACK ? 8 : 16;
            assertEquals(connectionLimit,
                    Widgets.require(builder, "max_connections_spin", SpinButton.class).getAdjustment().getUpper());
            Widgets.require(builder, "max_download_speed_spin", SpinButton.class).setValue(256);
            Widgets.require(builder, "user_agent_entry", Entry.class).setText("Sequence import UA");
            Button submit = Widgets.require(builder, "validate_button", Button.class);
            submit.emitClicked();
            engine.setSelected(selection == ImportEngine.YT_DLP
                    ? ImportEngine.ARIA2.ordinal() : ImportEngine.YT_DLP.ordinal());
            assertFalse(submit.getSensitive());
            awaitGtk(() -> queued.size() == 2);
            assertFalse(done.get(), "the dialog waits for queue admission");
            List<String> suffixes = characterMode ? List.of("a", "b") : List.of("1", "2");
            assertEquals(suffixes.stream().map(suffix -> URI.create("https://files.test/page-" + suffix)).toList(),
                    queued.stream().map(Download::getUri).toList());
            for (Download download : queued) {
                Download.Type expected = selection == ImportEngine.AUTO ? Download.Type.ARIA2 : selection.type();
                assertEquals(expected, download.getType());
                assertEquals(directory, download.getDestination());
                assertEquals(256, download.getSettings().getDownloadLimitKB());
                assertEquals("Sequence import UA", download.getSettings().getUserAgent());
                if (expected == Download.Type.YOUTUBE) {
                    assertTrue(assertInstanceOf(YtDlpSettings.class, download.getSettings()).isMediaProbeOnFailure());
                }
            }
            accepted.complete(null);
            awaitGtk(done::get);
            assertFalse(imported.isVisible());
        } finally {
            accepted.complete(null);
            imported.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ImportEngine.class, names = {"YT_DLP", "HTTRACK"})
    void incompatibleSequencesCanBeCorrectedWithoutCreatingPartialRecords(ImportEngine selection) throws Exception {
        var queued = new CopyOnWriteArrayList<Download>();
        var manager = manager(queued, CompletableFuture.completedFuture(null));
        var done = new AtomicBoolean();
        var imported = new ImportSequenceDialog(null, manager, () -> done.set(true));
        GtkBuilder builder = builder(imported);
        try {
            imported.present();
            Widgets.require(builder, "num_vers_spin", SpinButton.class).setValue(2);
            Widgets.require(builder, "uri_entry", Entry.class).setText("ftp://files.test/file-{}.zip");
            DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
            engine.setSelected(selection.ordinal());
            Button submit = Widgets.require(builder, "validate_button", Button.class);
            awaitGtk(submit::getSensitive);
            submit.emitClicked();
            awaitGtk(() -> submit.getSensitive() && Widgets.require(builder, "disk_space_label", Label.class)
                    .getLabel().contains(selection.label() + ":"));
            assertTrue(imported.isVisible());
            assertFalse(done.get());
            verify(manager, never()).createDownload(any(), any());
            verify(manager, never()).queueDownload(any());

            engine.setSelected(ImportEngine.ARIA2.ordinal());
            submit.emitClicked();
            awaitGtk(done::get);
            assertEquals(2, queued.size());
            queued.forEach(download -> assertEquals(Download.Type.ARIA2, download.getType()));
            assertFalse(imported.isVisible());
        } finally {
            imported.close();
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

    private static GtkBuilder builder(ImportSequenceDialog dialog) throws Exception {
        var field = ImportSequenceDialog.class.getDeclaredField("builder");
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
        fail("GTK sequence import did not reach the expected state");
    }
}
