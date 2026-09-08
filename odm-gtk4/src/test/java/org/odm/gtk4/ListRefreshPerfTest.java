package org.odm.gtk4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.download.Download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the real list-store refresh path with production widgets loaded
 * from {@code main-window.ui}: initial fill, the in-place progress-tick
 * update (the 1&nbsp;Hz hot path), and a structural reorder rebuild.
 * Requires a display (runs under Xvfb in Docker, like {@code WindowSmokeTest}).
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("List refresh performance")
class ListRefreshPerfTest {

    @BeforeAll
    static void initGtk() {
        Gtk.init();
    }

    private static DownloadListPresenter presenter() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        return new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                Widgets.require(builder, "download_store", ListStore.class),
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class),
                () -> {
                });
    }

    @Test
    @Timeout(120)
    @DisplayName("Refresh 500 rows: fill, progress tick, and reorder rebuild")
    void refreshPaths() {
        DownloadListPresenter presenter = presenter();
        List<Download> downloads = UiPerf.history(500);

        long fillStart = System.nanoTime();
        DownloadListPresenter.RefreshSummary fill = presenter.refresh(downloads);
        long fillElapsed = System.nanoTime() - fillStart;
        assertEquals(500, fill.totalCount());
        UiPerf.report("ListRefresh", "fill-500", downloads.size(), fillElapsed, "");

        // Progress tick: same objects, same order — must stay in place so the
        // tree selection survives the 1 Hz update rate.
        long tickStart = System.nanoTime();
        DownloadListPresenter.RefreshSummary tick = presenter.refresh(downloads);
        long tickElapsed = System.nanoTime() - tickStart;
        assertFalse(tick.modelRebuilt(), "progress tick must update rows in place");
        UiPerf.report("ListRefresh", "tick-500", downloads.size(), tickElapsed, "in-place");

        // Structural change: reversed order forces a full store rebuild.
        List<Download> reordered = new ArrayList<>(downloads);
        Collections.reverse(reordered);
        long rebuildStart = System.nanoTime();
        DownloadListPresenter.RefreshSummary rebuild = presenter.refresh(reordered);
        long rebuildElapsed = System.nanoTime() - rebuildStart;
        assertTrue(rebuild.modelRebuilt(), "reorder must rebuild the store");
        UiPerf.report("ListRefresh", "rebuild-500", reordered.size(), rebuildElapsed, "reordered");

        // Second tick on the new order is in place again.
        long tick2Start = System.nanoTime();
        DownloadListPresenter.RefreshSummary tick2 = presenter.refresh(reordered);
        long tick2Elapsed = System.nanoTime() - tick2Start;
        assertFalse(tick2.modelRebuilt());
        UiPerf.report("ListRefresh", "tick-after-rebuild-500", reordered.size(), tick2Elapsed,
                "in-place");

        assertTrue(fillElapsed < 60_000_000_000L, "fill took " + fillElapsed / 1_000_000 + "ms");
        assertTrue(tickElapsed < 60_000_000_000L, "tick took " + tickElapsed / 1_000_000 + "ms");
        assertTrue(rebuildElapsed < 60_000_000_000L,
                "rebuild took " + rebuildElapsed / 1_000_000 + "ms");
    }
}
