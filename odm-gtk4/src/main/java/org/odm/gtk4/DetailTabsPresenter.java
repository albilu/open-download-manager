package org.odm.gtk4;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
 * off the GTK thread on the instance-owned {@link #fetchExecutor} and only
 * the store population is marshalled back. Each fired fetch carries an
 * epoch token: a fetch for a newer selection supersedes the in-flight one
 * immediately, and a superseded fetch is discarded when it completes.
 * {@link #shutdown()} releases the executor and belongs on the window's
 * teardown path.
 */
final class DetailTabsPresenter {

    private static final Logger LOGGER = Logger.getLogger(DetailTabsPresenter.class.getName());

    /** Immutable snapshot fetched off-thread for the trackers/peers/files tabs. */
    private record DetailTabData(List<List<String>> trackers, List<Map<String, Object>> peers,
            List<Map<String, Object>> files) {
    }

    private final DownloadManager downloadManager;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final ListStore filesStore;
    private final Supplier<Download> currentSelection;
    private final ExecutorService fetchExecutor;

    /** Monotonic token; a fetch whose epoch no longer matches is stale. */
    private final AtomicLong fetchEpoch = new AtomicLong();

    /** Id of the selection whose fetch is in flight; GTK-thread confined. */
    private String inFlightTargetId;

    DetailTabsPresenter(DownloadManager downloadManager, ListStore trackersStore,
            ListStore peersStore, ListStore filesStore, Supplier<Download> currentSelection) {
        this.downloadManager = downloadManager;
        this.trackersStore = trackersStore;
        this.peersStore = peersStore;
        this.filesStore = filesStore;
        this.currentSelection = currentSelection;
        this.fetchExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "odm-detail-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Refreshes the detail tabs for the current selection. GTK thread; the
     * fetch itself runs on {@link #fetchExecutor}.
     */
    void load() {
        Download target = currentSelection.get();
        if (target == null) {
            fetchEpoch.incrementAndGet();
            inFlightTargetId = null;
            trackersStore.clear();
            peersStore.clear();
            filesStore.clear();
            return;
        }
        if (fetchExecutor.isShutdown()) {
            return;
        }
        String targetId = target.getId();
        if (Objects.equals(targetId, inFlightTargetId)) {
            // A fetch is already in flight for this selection; its result
            // still applies, which keeps the idle-queue bounded under 1 Hz
            // progress events.
            return;
        }
        inFlightTargetId = targetId;
        long epoch = fetchEpoch.incrementAndGet();
        CompletableFuture.supplyAsync(() -> new DetailTabData(
                downloadManager.getDownloadTrackers(target),
                downloadManager.getDownloadPeers(target),
                downloadManager.getDownloadFiles(target)), fetchExecutor)
                .whenComplete((data, error) -> {
                    if (error != null) {
                        LOGGER.log(Level.WARNING,
                                "Failed to load detail tabs for " + target.getName(), error);
                    }
                    UiThread.marshal(() -> settle(epoch, targetId, data, error));
                });
    }

    /** Applies or discards a completed fetch; GTK thread only. */
    private void settle(long epoch, String targetId, DetailTabData data, Throwable error) {
        if (epoch == fetchEpoch.get() && Objects.equals(targetId, inFlightTargetId)) {
            inFlightTargetId = null;
        }
        if (error != null || epoch != fetchEpoch.get()) {
            return; // stale fetch: a newer selection superseded it
        }
        Download selected = currentSelection.get();
        if (selected == null || !Objects.equals(targetId, selected.getId())) {
            return; // selection moved on while the fetch was in flight
        }
        populateDetailStores(data);
    }

    /** Releases the fetch executor; part of the window's teardown path. */
    void shutdown() {
        fetchExecutor.shutdown();
    }

    boolean isShutdown() {
        return fetchExecutor.isShutdown();
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
