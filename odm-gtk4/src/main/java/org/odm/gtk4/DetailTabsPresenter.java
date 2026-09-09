package org.odm.gtk4;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.javagi.interop.MemoryCleaner;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadOperationResult;
import org.manager.download.action.CompletionActionResult;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(DetailTabsPresenter.class);
    static final int FILE_PATH_COLUMN = FileTreeSupport.PATH_COLUMN;
    static final int ACTION_OUTPUT_COLUMN = 5;
    static final int ACTION_EXPOSES_OUTPUT_COLUMN = 6;

    /** Immutable snapshot fetched off-thread for the trackers/peers/files tabs. */
    private record DetailTabData(List<List<String>> trackers, List<Map<String, Object>> peers,
            List<Map<String, Object>> files) {
    }

    private record TrackerRow(String url, String tier) {
    }

    private record PeerRow(String address, String peerId, String downSpeed,
            String upSpeed, String state) {
    }

    private record DetailRow(String id, String action, String status, String result,
            String started, String finished, String output, boolean exposesOutput,
            Instant startedAt) {
    }

    private final DownloadManager downloadManager;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final TreeStore filesStore;
    private final TreeView filesView;
    private final ListStore completionDetailsStore;
    private final Supplier<Download> currentSelection;
    private final ExecutorService fetchExecutor;

    /** Monotonic token; a fetch whose epoch no longer matches is stale. */
    private final AtomicLong fetchEpoch = new AtomicLong();

    /** Id of the selection whose fetch is in flight; GTK-thread confined. */
    private String inFlightTargetId;
    /** Id currently represented by the three stores; GTK-thread confined. */
    private String displayedTargetId;
    private String displayedCompletionTargetId;
    /**
     * GTK removes a {@code GtkCellRendererCombo} editor when its model row is
     * changed. Progress polling normally reconciles file rows every second, so
     * hold only the file-store update while the priority editor is open.
     */
    private boolean filePriorityEditing;
    private final Map<String, TreeRowReference> trackerRows = new java.util.HashMap<>();
    private final Map<String, TreeRowReference> peerRows = new java.util.HashMap<>();
    private final Map<String, TreeRowReference> fileRows = new java.util.HashMap<>();
    private final Map<String, TreeRowReference> completionRows = new java.util.HashMap<>();

    DetailTabsPresenter(DownloadManager downloadManager, ListStore trackersStore,
            ListStore peersStore, TreeStore filesStore, TreeView filesView,
            ListStore completionDetailsStore, Supplier<Download> currentSelection) {
        this.downloadManager = downloadManager;
        this.trackersStore = trackersStore;
        this.peersStore = peersStore;
        this.filesStore = filesStore;
        this.filesView = filesView;
        this.completionDetailsStore = completionDetailsStore;
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
            displayedTargetId = null;
            clearStore(trackersStore, trackerRows);
            clearStore(peersStore, peerRows);
            FileTreeSupport.clear(filesStore, fileRows);
            clearStore(completionDetailsStore, completionRows);
            displayedCompletionTargetId = null;
            return;
        }
        populateCompletionDetails(target);
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
                        LOGGER.warn(
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
        if (!Objects.equals(displayedTargetId, targetId)) {
            clearStore(trackersStore, trackerRows);
            clearStore(peersStore, peerRows);
            FileTreeSupport.clear(filesStore, fileRows);
            displayedTargetId = targetId;
        }
        populateDetailStores(data, selected);
    }

    /** Releases the fetch executor; part of the window's teardown path. */
    void shutdown() {
        fetchExecutor.shutdown();
        freeRowReferences(trackerRows);
        freeRowReferences(peerRows);
        FileTreeSupport.freeReferences(fileRows);
        freeRowReferences(completionRows);
    }

    boolean isShutdown() {
        return fetchExecutor.isShutdown();
    }

    /** GTK-thread hook used by the Files-tab priority cell editor. */
    void setFilePriorityEditing(boolean editing) {
        boolean refreshAfterEdit = filePriorityEditing && !editing;
        filePriorityEditing = editing;
        if (refreshAfterEdit) {
            load();
        }
    }

    /** Populates the detail tab stores from a fetched snapshot. GTK thread only. */
    private void populateDetailStores(DetailTabData data, Download selectedDownload) {
        LinkedHashMap<String, TrackerRow> trackers = new LinkedHashMap<>();
        int tier = 0;
        for (List<String> urls : data.trackers()) {
            int position = 0;
            for (String url : urls) {
                trackers.put(tier + ":" + position++ + ":" + url,
                        new TrackerRow(url, "tier " + tier));
            }
            tier++;
        }
        reconcile(trackersStore, trackerRows, trackers, (store, iter, row) -> {
            ListStoreCells.setString(store, iter, 0, row.url());
            ListStoreCells.setString(store, iter, 1, row.tier());
            ListStoreCells.setString(store, iter, 2, "—");
            ListStoreCells.setString(store, iter, 3, "—");
            ListStoreCells.setString(store, iter, 4, "—");
            ListStoreCells.setString(store, iter, 5, "—");
        });

        LinkedHashMap<String, PeerRow> peers = new LinkedHashMap<>();
        for (Map<String, Object> peer : data.peers()) {
            String address = String.valueOf(peer.getOrDefault("ip", "—"))
                    + ":" + peer.getOrDefault("port", "");
            String peerId = displayPeerId(peer.get("peerId"));
            String state = Boolean.parseBoolean(String.valueOf(peer.getOrDefault("seeder", false)))
                    ? "Seeder" : (Boolean.parseBoolean(String.valueOf(peer.getOrDefault("peerChoking", false)))
                            ? "Choking" : "Transferring");
            String key = uniqueKey(peers, address + "\u0000" + peerId);
            peers.put(key, new PeerRow(address, peerId,
                    DownloadFormats.size(parseLong(peer.get("downloadSpeed"), 0)) + "/s",
                    DownloadFormats.size(parseLong(peer.get("uploadSpeed"), 0)) + "/s",
                    state));
        }
        reconcile(peersStore, peerRows, peers, (store, iter, row) -> {
            ListStoreCells.setString(store, iter, 0, row.address());
            ListStoreCells.setString(store, iter, 1, row.peerId());
            ListStoreCells.setString(store, iter, 2, row.downSpeed());
            ListStoreCells.setString(store, iter, 3, row.upSpeed());
            ListStoreCells.setString(store, iter, 4, row.state());
        });

        // Updating even a stable TreeStore row dismisses GTK's in-cell combo
        // editor. Keep the current snapshot visible until the user commits or
        // cancels the edit, then setFilePriorityEditing(false) fetches fresh
        // engine state immediately.
        if (filePriorityEditing) {
            return;
        }

        Map<Integer, String> priorities = selectedDownload.getSettings()
                instanceof org.aria2.Aria2Settings aria2Settings
                        ? aria2Settings.getFilePriorities() : Map.of();
        List<FileTreeSupport.Entry> files = new java.util.ArrayList<>();
        int fallbackIndex = 1;
        for (Map<String, Object> file : data.files()) {
            boolean selected = !"false".equalsIgnoreCase(
                    String.valueOf(file.getOrDefault("selected", "true")));
            long length = parseLong(file.get("length"), 0);
            long completedLength = parseLong(file.get("completedLength"), 0);
            int index = (int) parseLong(file.get("index"), fallbackIndex++);
            String path = String.valueOf(file.getOrDefault("path", "—"));
            files.add(new FileTreeSupport.Entry(selected, path, length, completedLength,
                    index, priorities.getOrDefault(index, FileTreeSupport.PRIORITY_NORMAL)));
        }
        boolean structureChanged = FileTreeSupport.reconcile(filesStore, fileRows, files,
                selectedDownload.getDestination());
        if (structureChanged) {
            FileTreeSupport.expandTopLevel(filesView, filesStore);
        }
    }

    /** Reconciles persisted completion-action history without an RPC fetch. */
    private void populateCompletionDetails(Download download) {
        if (!Objects.equals(displayedCompletionTargetId, download.getId())) {
            clearStore(completionDetailsStore, completionRows);
            displayedCompletionTargetId = download.getId();
        }
        List<DetailRow> history = new ArrayList<>();
        for (CompletionActionResult result : download.getCompletionActionResults()) {
            history.add(new DetailRow(
                    result.id(),
                    result.description(),
                    completionStatus(result),
                    result.message().isBlank() ? "—" : UiErrors.message(result.message()),
                    DownloadFormats.DATE_FORMAT.format(result.startedAt()),
                    result.finishedAt() == null
                            ? "—" : DownloadFormats.DATE_FORMAT.format(result.finishedAt()),
                    result.output(),
                    result.exposesOutput(),
                    result.startedAt()));
        }
        for (DownloadOperationResult result : download.getOperationResults()) {
            history.add(new DetailRow(
                    result.id(),
                    result.description(),
                    operationStatus(result),
                    result.message().isBlank() ? "—" : UiErrors.message(result.message()),
                    DownloadFormats.DATE_FORMAT.format(result.startedAt()),
                    result.finishedAt() == null
                            ? "—" : DownloadFormats.DATE_FORMAT.format(result.finishedAt()),
                    "",
                    false,
                    result.startedAt()));
        }
        history.sort(Comparator.comparing(DetailRow::startedAt)
                .thenComparing(DetailRow::id));
        LinkedHashMap<String, DetailRow> rows = new LinkedHashMap<>();
        for (DetailRow row : history) {
            rows.put(row.id(), row);
        }
        reconcile(completionDetailsStore, completionRows, rows, (store, iter, row) -> {
            ListStoreCells.setString(store, iter, 0, row.action());
            ListStoreCells.setString(store, iter, 1, row.status());
            ListStoreCells.setString(store, iter, 2, row.result());
            ListStoreCells.setString(store, iter, 3, row.started());
            ListStoreCells.setString(store, iter, 4, row.finished());
            ListStoreCells.setString(store, iter, ACTION_OUTPUT_COLUMN, row.output());
            ListStoreCells.setBoolean(store, iter, ACTION_EXPOSES_OUTPUT_COLUMN,
                    row.exposesOutput());
        });
    }

    private static String completionStatus(CompletionActionResult result) {
        return switch (result.status()) {
            case RUNNING -> "Running";
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed (" + result.severity().name().toLowerCase() + ")";
            case INTERRUPTED -> "Interrupted";
        };
    }

    private static String operationStatus(DownloadOperationResult result) {
        return switch (result.status()) {
            case RUNNING -> "Running";
            case ACCEPTED -> "Accepted";
            case FAILED -> "Failed";
            case INTERRUPTED -> "Interrupted";
        };
    }

    private static <T> String uniqueKey(Map<String, T> rows, String base) {
        String key = base;
        int duplicate = 2;
        while (rows.containsKey(key)) {
            key = base + "#" + duplicate++;
        }
        return key;
    }

    /** Updates stable rows in place; only membership changes rebuild a store. */
    private static <T> void reconcile(ListStore store,
            Map<String, TreeRowReference> references, LinkedHashMap<String, T> rows,
            RowWriter<T> writer) {
        boolean sameStructure = references.keySet().equals(rows.keySet());
        if (sameStructure) {
            for (Map.Entry<String, T> entry : rows.entrySet()) {
                TreeRowReference reference = references.get(entry.getKey());
                TreePath path = reference == null ? null : reference.getPath();
                if (path == null) {
                    sameStructure = false;
                    break;
                }
                try {
                    TreeIter iter = new TreeIter();
                    if (!store.getIter(iter, path)) {
                        sameStructure = false;
                        break;
                    }
                    writer.write(store, iter, entry.getValue());
                } finally {
                    MemoryCleaner.free(path.handle());
                }
            }
        }
        if (sameStructure) {
            return;
        }

        clearStore(store, references);
        for (Map.Entry<String, T> entry : rows.entrySet()) {
            TreeIter iter = new TreeIter();
            store.append(iter);
            writer.write(store, iter, entry.getValue());
            TreePath path = store.getPath(iter);
            try {
                references.put(entry.getKey(), new TreeRowReference(store, path));
            } finally {
                MemoryCleaner.free(path.handle());
            }
        }
    }

    private static void clearStore(ListStore store,
            Map<String, TreeRowReference> references) {
        freeRowReferences(references);
        store.clear();
    }

    private static void freeRowReferences(Map<String, TreeRowReference> references) {
        for (TreeRowReference reference : references.values()) {
            MemoryCleaner.free(reference.handle());
        }
        references.clear();
    }

    @FunctionalInterface
    private interface RowWriter<T> {
        void write(ListStore store, TreeIter iter, T row);
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

    /** Returns a display-only basename while preserving unusual paths safely. */
    static String fileName(String path) {
        if (path == null || path.isBlank() || "—".equals(path)) {
            return "—";
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 && separator + 1 < path.length()
                ? path.substring(separator + 1) : path;
    }

    /**
     * aria2 returns BitTorrent peer IDs with each raw byte percent-encoded.
     * Decode printable ASCII (including the useful client prefix) and retain
     * binary bytes as explicit hex escapes instead of showing URL syntax or
     * invisible control characters.
     */
    static String displayPeerId(Object value) {
        if (value == null) {
            return "—";
        }
        String encoded = value.toString();
        if (encoded.isBlank()) {
            return "—";
        }
        StringBuilder result = new StringBuilder(encoded.length());
        int printableBytes = 0;
        for (int i = 0; i < encoded.length(); i++) {
            char current = encoded.charAt(i);
            if (current == '%' && i + 2 < encoded.length()) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    int valueByte = (high << 4) | low;
                    printableBytes += appendPeerByte(result, valueByte) ? 1 : 0;
                    i += 2;
                    continue;
                }
            }
            if (current >= 0x20 && current <= 0x7e) {
                result.append(current);
                printableBytes++;
            } else if (current <= 0xff) {
                appendPeerByte(result, current);
            } else {
                result.append(current);
                printableBytes++;
            }
        }
        // aria2 represents an unavailable peer id as twenty NUL bytes. A
        // row of "\x00" escapes is diagnostic noise, not an identity.
        return printableBytes == 0 ? "—" : result.toString();
    }

    private static boolean appendPeerByte(StringBuilder target, int value) {
        if (value >= 0x20 && value <= 0x7e) {
            target.append((char) value);
            return true;
        } else {
            target.append("\\x");
            target.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
            target.append(Character.toUpperCase(Character.forDigit(value & 0x0f, 16)));
            return false;
        }
    }
}
