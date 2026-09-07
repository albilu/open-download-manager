package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.GlobalSettings;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardServiceListener;
import org.manager.clipboard.ClipboardSettings;
import org.manager.download.DownloadManager;
import org.mockito.ArgumentCaptor;

class ClipboardConfirmationRoutingTest {
    @BeforeAll static void gtk() {
        Gtk.init();
        org.gnome.glib.GLib.getMonotonicTime();
        MainContext.default_();
    }

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=abcdefghijk, New Media Download, media_url_entry",
        "https://cdn.example/master.m3u8?token=a%2Fb, New Media Download, media_url_entry",
        "https://cdn.example/stream.mpd, New Media Download, media_url_entry",
        "https://example.com/archive.zip, New Download, url_entry",
        "https://cdn.example/movie.mp4, New Download, url_entry",
        "https://youtube.com.example.org/page, New Download, url_entry",
        "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567, New Download, url_entry"
    })
    void confirmationOpensMatchingDialogWithFirstDetectedUrl(String first, String title, String entryId) throws Exception {
        DownloadManager manager = mock(DownloadManager.class);
        when(manager.getGlobalSettings()).thenReturn(new GlobalSettings());
        ClipboardService clipboard = mock(ClipboardService.class);
        when(clipboard.getSettings()).thenReturn(new ClipboardSettings());
        when(manager.getClipboardService()).thenReturn(clipboard);
        var main = new MainWindow(null, manager, mock(org.tor.TorService.class),
                mock(org.manager.schedule.ScheduleManager.class));
        Window confirmation = null;
        try {
            var listener = ArgumentCaptor.forClass(ClipboardServiceListener.class);
            verify(clipboard).addServiceListener(listener.capture());
            List<URI> urls = List.of(URI.create(first), URI.create("https://youtu.be/secondvideo"));
            CompletableFuture.runAsync(() -> listener.getValue().onConfirmationRequired(urls,
                    first + "\n" + urls.get(1))).get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (confirmation == null && System.nanoTime() < deadline) {
                drain();
                var windows = Window.getToplevels();
                for (int i = 0; i < windows.getNItems(); i++) {
                    Window candidate = (Window) windows.getItem(i);
                    if (candidate.getVisible() && title.equals(candidate.getTitle())) {
                        confirmation = candidate;
                        break;
                    }
                }
                if (confirmation == null) { Thread.sleep(10); }
            }
            assertNotNull(confirmation, "clipboard confirmation should open " + title);
            assertEquals(first, assertInstanceOf(Entry.class, find(confirmation, entryId)).getText());
            verify(manager, never()).queueDownload(any());
        } finally {
            if (confirmation != null) { confirmation.close(); }
            main.dispose();
            drain();
        }
    }

    private static Widget find(Widget parent, String id) {
        if (id.equals(parent.getBuildableId())) { return parent; }
        for (Widget child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            Widget found = find(child, id);
            if (found != null) { return found; }
        }
        return null;
    }

    private static void drain() {
        var context = MainContext.default_();
        while (context.pending()) { context.iteration(false); }
    }
}
