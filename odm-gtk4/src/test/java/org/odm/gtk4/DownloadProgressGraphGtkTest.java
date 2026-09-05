package org.odm.gtk4;

import java.lang.foreign.Arena;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.gnome.glib.MainContext;
import org.gnome.graphene.Rect;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.DrawingArea;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Paned;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.WidgetPaintable;
import org.gnome.gtk.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import static org.junit.jupiter.api.Assertions.*;

class DownloadProgressGraphGtkTest {

    @BeforeAll
    static void initGtk() {
        Gtk.init();
    }

    @Test
    void switchingAndClearingSelectionUpdatesAllGraphLabels() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        var graph = new DownloadProgressGraph(builder);
        try {
            Download download = download();
            download.setSize(10_000);
            download.setDownloaded(4267);
            download.setStatus(Download.Status.DOWNLOADING);
            download.setSpeed(2048);
            var snapshot = new DownloadSpeedHistory.Snapshot(List.of(
                    new DownloadSpeedHistory.Sample(1000, 1000, 1024),
                    new DownloadSpeedHistory.Sample(2000, 4267, 2048)), 1536);
            graph.update(download, snapshot);
            assertEquals("42.67%", label(builder, "info_progress_value"));
            assertEquals("Speed: 2 KB/s", label(builder, "info_speed_value"));
            assertEquals("Average: 1 KB/s", label(builder, "info_average_speed_value"));
            assertTrue(Widgets.require(builder, "info_progress_bar", DrawingArea.class)
                    .getTooltipText().contains("Dashed line: average speed"));

            download.setStatus(Download.Status.PAUSED);
            graph.update(download, snapshot);
            assertEquals("Speed: 0 B/s", label(builder, "info_speed_value"));
            assertEquals("Average: 1 KB/s", label(builder, "info_average_speed_value"));

            graph.update(download(), DownloadSpeedHistory.Snapshot.EMPTY);
            assertEquals("Average: —", label(builder, "info_average_speed_value"));
            graph.update(null, DownloadSpeedHistory.Snapshot.EMPTY);
            assertEquals("—", label(builder, "info_progress_value"));
            assertEquals("Speed: —", label(builder, "info_speed_value"));
            assertEquals("Average: —", label(builder, "info_average_speed_value"));
        } finally {
            graph.dispose();
        }
    }

    @Test
    void unknownSizesUseTimeAndCompletedDownloadsShowFullProgress() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        var graph = new DownloadProgressGraph(builder);
        try {
            Download download = download();
            var snapshot = new DownloadSpeedHistory.Snapshot(List.of(
                    new DownloadSpeedHistory.Sample(10_000, 1000, 1024),
                    new DownloadSpeedHistory.Sample(13_000, 4267, 2048)), 1536);
            graph.update(download, snapshot);
            assertEquals("Size unknown", label(builder, "info_progress_value"));
            assertEquals("Active transfer time", label(builder, "progress_axis_label"));
            assertEquals("0s", label(builder, "progress_axis_start"));
            assertEquals("3s", label(builder, "progress_axis_end"));
            download.setStatus(Download.Status.COMPLETED);
            graph.update(download, snapshot);
            assertEquals("100.00%", label(builder, "info_progress_value"));
            assertEquals("Speed: 0 B/s", label(builder, "info_speed_value"));
        } finally {
            graph.dispose();
        }
    }

    @Test
    @Timeout(20)
    void generalTabRendersWithActualCairoGraph(@TempDir Path directory) throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        var graph = new DownloadProgressGraph(builder);
        Box tab = Widgets.require(builder, "general_tab", Box.class);
        Window window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        try {
            Download download = download();
            download.setSize(100_000);
            download.setDownloaded(65_000);
            download.setStatus(Download.Status.DOWNLOADING);
            download.setSpeed(3072);
            var history = new DownloadSpeedHistory();
            for (int i = 0; i <= 65; i++) {
                history.record(download, i * 1000L, i * 1000L,
                        2048 + 1024 * Math.sin(i * 0.4));
            }
            graph.update(download, history.snapshot(download));
            window.setDefaultSize(1100, 800);
            window.present();
            // Allow the native draw callback to run before taking the GTK snapshot.
            for (int i = 0; i < 30; i++) {
                while (MainContext.default_().iteration(false)) { }
                Thread.sleep(10);
            }
            DrawingArea area = Widgets.require(builder, "info_progress_bar", DrawingArea.class);
            assertTrue(area.getMapped());
            assertTrue(area.getWidth() > 600);
            assertTrue(area.getHeight() >= 88);
            Notebook notebook = Widgets.require(builder, "info_notebook", Notebook.class);
            try (Arena arena = Arena.ofConfined()) {
                Rect axisBounds = new Rect(arena);
                Rect tabBounds = new Rect(arena);
                assertTrue(Widgets.require(builder, "progress_axis_end", Label.class)
                        .computeBounds(notebook, axisBounds));
                assertTrue(notebook.getTabLabel(notebook.getNthPage(0)).computeBounds(notebook, tabBounds));
                assertTrue(axisBounds.getY() + axisBounds.getHeight() <= tabBounds.getY(),
                        "the graph axis must fit above the tab buttons");
                assertTrue(tabBounds.getY() + tabBounds.getHeight() <= notebook.getHeight(),
                        "tab buttons must remain inside the visible notebook");
            }
            var snapshot = new org.gnome.gtk.Snapshot();
            new WidgetPaintable(window).snapshot(snapshot, window.getWidth(), window.getHeight());
            var node = snapshot.toNode();
            assertNotNull(node, "the mapped General tab must produce a render node");
            var texture = window.getRenderer().renderTexture(node, null);
            Path output = directory.resolve("general-progress.png");
            assertTrue(texture.saveToPng(output.toString()));
            assertTrue(java.nio.file.Files.size(output) > 1000);
            String capture = System.getProperty("odm.progress.capture");
            if (capture != null) {
                assertTrue(texture.saveToPng(capture));
            }
        } finally {
            graph.dispose();
            window.destroy();
        }
    }

    @Test
    @Timeout(20)
    void compressedDetailsGiveSpaceToTheListAndKeepTheGraphReachable() throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        var graph = new DownloadProgressGraph(builder);
        Window window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        Paned pane = Widgets.require(builder, "content_paned", Paned.class);
        Notebook notebook = Widgets.require(builder, "info_notebook", Notebook.class);
        ScrolledWindow scrolled = Widgets.require(builder, "general_scrolled", ScrolledWindow.class);
        try {
            window.setDefaultSize(1100, 540);
            window.present();
            settleGtk();
            int requestedPosition = pane.getHeight() - 140;
            pane.setPosition(requestedPosition);
            settleGtk();

            assertEquals(requestedPosition, pane.getPosition(), 2,
                    "the divider must move down instead of being stopped by the General layout");
            assertTrue(notebook.getHeight() <= 145, "the details pane must fit in the requested space");
            assertTrue(Widgets.require(builder, "download_scrolled_window", ScrolledWindow.class)
                    .getHeight() > notebook.getHeight() * 2, "the list must receive the freed space");
            var adjustment = scrolled.getVadjustment();
            assertTrue(adjustment.getUpper() > adjustment.getPageSize(),
                    "General content must scroll when compressed");
            adjustment.setValue(adjustment.getUpper() - adjustment.getPageSize());
            settleGtk();
            try (Arena arena = Arena.ofConfined()) {
                Rect axisBounds = new Rect(arena);
                Rect tabBounds = new Rect(arena);
                assertTrue(Widgets.require(builder, "progress_axis_end", Label.class)
                        .computeBounds(scrolled, axisBounds));
                assertTrue(axisBounds.getY() >= 0
                        && axisBounds.getY() + axisBounds.getHeight() <= scrolled.getHeight(),
                        "scrolling to the bottom must reveal the graph axis");
                assertTrue(notebook.getTabLabel(scrolled).computeBounds(notebook, tabBounds));
                assertTrue(tabBounds.getY() >= 0
                        && tabBounds.getY() + tabBounds.getHeight() <= notebook.getHeight(),
                        "tab buttons must stay visible while the General page scrolls");
            }

            // Growing the details pane again restores the full chart without a scrollbar.
            pane.setPosition(60);
            settleGtk();
            assertEquals(adjustment.getUpper(), adjustment.getPageSize(), 1);
        } finally {
            graph.dispose();
            window.destroy();
        }
    }

    private static void settleGtk() throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            while (MainContext.default_().iteration(false)) { }
            Thread.sleep(10);
        }
    }

    private static String label(GtkBuilder builder, String id) {
        return Widgets.require(builder, id, Label.class).getLabel();
    }

    private static Download download() {
        return new Download(URI.create("https://example.com/file"));
    }
}
