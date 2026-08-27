package org.odm.gtk4;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Async trackers/peers/files detail-tab presenter. The manager calls
 * underneath perform synchronous aria2 RPC round trips, so fetching runs
 * off the GTK thread ({@link #FETCH_EXECUTOR}) and only the store
 * population is marshalled back; results are discarded when the selection
 * changed while the fetch was in flight (stale-guard).
 */
final class DetailTabsPresenter {

    private static final Logger LOGGER = Logger.getLogger(DetailTabsPresenter.class.getName());

    /** Executor for detail-tab RPC fetches; keeps aria2 round trips off the GTK main loop. */
    static final ExecutorService FETCH_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "odm-detail-fetch");
        t.setDaemon(true);
        return t;
    });

    /** Immutable snapshot fetched off-thread for the trackers/peers/files tabs. */
    private record DetailTabData(List<List<String>> trackers, List<Map<String, Object>> peers,
            List<Map<String, Object>> files) {
    }

    private final DownloadManager downloadManager;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final ListStore filesStore;
    private final Supplier<Download> currentSelection;

    /** Coalesces detail fetches: at most one in flight; the next refresh re-arms it. */
    private final AtomicBoolean detailFetchPending = new AtomicBoolean(false);

    DetailTabsPresenter(DownloadManager downloadManager, ListStore trackersStore,
            ListStore peersStore, ListStore filesStore, Supplier<Download> currentSelection) {
        this.downloadManager = downloadManager;
        this.trackersStore = trackersStore;
        this.peersStore = peersStore;
        this.filesStore = filesStore;
        this.currentSelection = currentSelection;
    }

    /**
     * Refreshes the detail tabs for the current selection. GTK thread; the
     * fetch itself runs on {@link #FETCH_EXECUTOR}.
     */
    void load() {
        Download target = currentSelection.get();
        if (target == null) {
            trackersStore.clear();
            peersStore.clear();
            filesStore.clear();
            return;
        }
        if (!detailFetchPending.compareAndSet(false, true)) {
            // A fetch is already in flight; the next refresh re-triggers it,
            // which keeps the idle-queue bounded under 1 Hz progress events.
            return;
        }
        String targetId = target.getId();
        CompletableFuture.supplyAsync(() -> new DetailTabData(
                downloadManager.getDownloadTrackers(target),
                downloadManager.getDownloadPeers(target),
                downloadManager.getDownloadFiles(target)), FETCH_EXECUTOR)
                .whenComplete((data, error) -> {
                    detailFetchPending.set(false);
                    if (error != null) {
                        LOGGER.log(Level.WARNING,
                                "Failed to load detail tabs for " + target.getName(), error);
                        return;
                    }
                    UiThread.marshal(() -> {
                        Download selected = currentSelection.get();
                        if (selected == null || !targetId.equals(selected.getId())) {
                            return; // stale fetch: selection moved on
                        }
                        populateDetailStores(data);
                    });
                });
    }

    /** Populates the detail tab stores from a fetched snapshot. GTK thread only. */
    private void populateDetailStores(DetailTabData data) {
        // Trackers
        trackersStore.clear();
        int tier = 0;
        for (List<String> urls : data.trackers()) {
            for (String url : urls) {
                TreeIter iter = new TreeIter();
                trackersStore.append(iter);
                ListStoreCells.setString(trackersStore, iter, 0, url);
                ListStoreCells.setString(trackersStore, iter, 1, "tier " + tier);
                ListStoreCells.setString(trackersStore, iter, 2, "—");
                ListStoreCells.setString(trackersStore, iter, 3, "—");
                ListStoreCells.setString(trackersStore, iter, 4, "—");
                ListStoreCells.setString(trackersStore, iter, 5, "—");
            }
            tier++;
        }

        // Peers
        peersStore.clear();
        for (Map<String, Object> peer : data.peers()) {
            TreeIter iter = new TreeIter();
            peersStore.append(iter);
            ListStoreCells.setString(peersStore, iter, 0, String.valueOf(peer.getOrDefault("peerId", "—")));
            ListStoreCells.setString(peersStore, iter, 1, String.valueOf(peer.getOrDefault("downloadSpeed", "—")));
            ListStoreCells.setString(peersStore, iter, 2, String.valueOf(peer.getOrDefault("ip", "—"))
                    + ":" + peer.getOrDefault("port", ""));
            ListStoreCells.setString(peersStore, iter, 3, String.valueOf(peer.getOrDefault("peChoking", false)));
        }

        // Files
        filesStore.clear();
        for (Map<String, Object> file : data.files()) {
            TreeIter iter = new TreeIter();
            filesStore.append(iter);
            // Seed the checkbox from aria2's own per-file selected flag
            boolean selected = !"false".equalsIgnoreCase(
                    String.valueOf(file.getOrDefault("selected", "true")));
            ListStoreCells.setBoolean(filesStore, iter, 0, selected);
            ListStoreCells.setString(filesStore, iter, 1, String.valueOf(file.getOrDefault("path", "—")));
            ListStoreCells.setString(filesStore, iter, 2,
                    DownloadFormats.size(parseLong(file.get("length"), 0)));
            ListStoreCells.setString(filesStore, iter, 3, String.valueOf(
                    progressPercent(parseLong(file.get("completedLength"), 0),
                            parseLong(file.get("length"), 1))));
            ListStoreCells.setString(filesStore, iter, 4, "—");
        }
    }

    static long parseLong(Object value, long fallback) {
        try {
            return value != null ? Long.parseLong(value.toString()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static double progressPercent(long done, long total) {
        return total > 0 ? done * 100.0 / total : 0;
    }
}
