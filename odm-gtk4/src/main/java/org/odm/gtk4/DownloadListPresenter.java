package org.odm.gtk4;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gnome.gtk.ListStore;
import org.gnome.gtk.SortType;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeSortable;
import org.gnome.gtk.TreeView;
import org.javagi.interop.MemoryCleaner;
import org.manager.download.Download;

/**
 * Owns the download list stores: row rebuild and in-place row updates,
 * filter matching, filter-store rebuilds with count-based change
 * detection, and refresh coalescing.
 *
 * <p>GTK-thread surface: {@link #refresh(List)} and the selection methods
 * must run on the GTK main loop. {@link #scheduleRefresh()} is the
 * any-thread entry point and marshals through {@link UiThread}.
 */
final class DownloadListPresenter {

    static final String[] STATUS_FILTERS = {
        "All Status", "Active", "Seeding", "Queued", "Paused", "Finished", "Error", "Canceled"
    };
    static final String[] CATEGORIES = {"All", "Videos", "Audios", "Photos", "Programs", "Others"};

    // Visible download_store columns. Number is gint; 1-10 are strings,
    // 11 is the engine GIcon, progress is gint, and 13-14 are strings.
    private static final int COL_NUMBER = 0;
    private static final int COL_NAME = 1;
    private static final int COL_COMPLETE = 2;
    private static final int COL_SIZE = 3;
    private static final int COL_ELAPSED = 4;
    private static final int COL_LEFT = 5;
    private static final int COL_SPEED = 6;
    private static final int COL_UP_SPEED = 7;
    private static final int COL_RETRY = 8;
    private static final int COL_START = 9;
    private static final int COL_END = 10;
    private static final int COL_ENGINE_ICON = 11;
    private static final int COL_PROGRESS = 12; // gint
    private static final int COL_STATUS_ICON = 13;
    private static final int COL_PROGRESS_TEXT = 14;
    // Hidden identity and typed sort keys. Formatted display strings must not
    // drive ordering ("10 GB" sorts before "2 MB" lexically).
    private static final int COL_DOWNLOAD_ID = 15;
    private static final int COL_STATUS_SORT = 16;
    private static final int COL_COMPLETE_SORT = 17;
    private static final int COL_SIZE_SORT = 18;
    private static final int COL_PROGRESS_SORT = 19;
    private static final int COL_ELAPSED_SORT = 20;
    private static final int COL_LEFT_SORT = 21;
    private static final int COL_SPEED_SORT = 22;
    private static final int COL_UP_SPEED_SORT = 23;
    private static final int COL_START_SORT = 24;
    private static final int COL_END_SORT = 25;
    private static final int COL_TYPE_SORT = 26;
    private static final int COL_PROGRESS_PULSE = 27;

    // status_store / category_store columns
    private static final int SC_ICON = 0;
    private static final int SC_COUNT = 1;
    private static final int SC_LABEL = 2;

    /**
     * Aggregate numbers of one refresh pass — the side-band labels and the
     * spinner stay widget concerns of the window.
     */
    record RefreshSummary(int totalCount, long downBytesPerSec, long upBytesPerSec,
            int totalSeeders, boolean anyActive, long totalBytes, long doneBytes,
            boolean modelRebuilt) {
    }

    private final ListStore statusStore;
    private final ListStore categoryStore;
    private final ListStore downloadsStore;
    private final ListStore globalProgressStore;
    private final TreeView statusTreeview;
    private final TreeView categoryTreeview;
    private final Runnable fullRefresh;

    private List<Download> rowSnapshot = new ArrayList<>();
    /** Current objects by stable model identity, independent of sort order. */
    private Map<String, Download> rowsById = Map.of();
    /** Tracks a logical row while GTK reorders the sorted ListStore. */
    private final Map<String, TreeRowReference> rowReferences = new HashMap<>();
    /** Last filter-store counts; filter stores rebuild only when these change. */
    private int[] lastStatusCounts = new int[0];
    private int[] lastCategoryCounts = new int[0];
    private int lastStatusTotalCount = -1;
    private int lastCategoryTotalCount = -1;

    private String statusFilter = "All Status";
    /** Selected category filter (extension-based), "All" = no restriction. */
    private String categoryFilter = "All";
    private String searchText = "";
    /** Re-entrancy guard for programmatic status-row re-selection. */
    private boolean suppressStatusSelection;
    /** Re-entrancy guard for programmatic category-row re-selection. */
    private boolean suppressCategorySelection;

    /**
     * Coalesces refresh scheduling: core progress events arrive at up to 1 Hz
     * per active download, and a full refresh per event floods the GLib idle
     * queue. While a refresh is already pending, further events are absorbed
     * into it.
     */
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private int completionPulsePosition;

    DownloadListPresenter(ListStore statusStore, ListStore categoryStore, ListStore downloadsStore,
            ListStore globalProgressStore, TreeView statusTreeview, TreeView categoryTreeview,
            Runnable fullRefresh) {
        this.statusStore = statusStore;
        this.categoryStore = categoryStore;
        this.downloadsStore = downloadsStore;
        this.globalProgressStore = globalProgressStore;
        this.statusTreeview = statusTreeview;
        this.categoryTreeview = categoryTreeview;
        this.fullRefresh = fullRefresh;
    }

    /** True while a programmatic filter-row re-selection is in flight. */
    boolean isRestoringSelection() {
        return suppressStatusSelection || suppressCategorySelection;
    }

    /**
     * Applies the status filter at a filter-row index. Returns whether the
     * index was valid (the caller refreshes only then, mirroring the
     * original selection handler).
     */
    boolean selectStatusFilterAt(int index) {
        if (index >= STATUS_FILTERS.length) {
            return false;
        }
        statusFilter = STATUS_FILTERS[index];
        return true;
    }

    /** Applies the category filter at a category-row index; see {@link #selectStatusFilterAt}. */
    boolean selectCategoryAt(int index) {
        if (index >= CATEGORIES.length) {
            return false;
        }
        categoryFilter = CATEGORIES[index];
        return true;
    }

    /** Search text (lowercased, stripped) or an empty string. */
    void setSearchText(String text) {
        searchText = text == null ? "" : text;
    }

    /** Download backing the visible row index, or null past the end. */
    Download rowAt(int index) {
        if (index < 0) {
            return null;
        }
        // Use the vector constructor: java-gi's varargs binding requires a
        // native -1 sentinel and calling it with no varargs can walk garbage.
        TreePath path = TreePath.fromIndicesv(new int[]{index});
        try {
            TreeIter iter = new TreeIter();
            if (!downloadsStore.getIter(iter, path)) {
                return null;
            }
            return rowsById.get(ListStoreCells.getString(
                    downloadsStore, iter, COL_DOWNLOAD_ID));
        } finally {
            MemoryCleaner.free(path.handle());
        }
    }

    /** Selected downloads in visible tree order, ignoring invalid/duplicate rows. */
    static List<Download> rowsAt(List<Download> snapshot, List<Integer> indexes) {
        if (snapshot == null || indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        return indexes.stream()
                .filter(java.util.Objects::nonNull)
                .filter(index -> index >= 0 && index < snapshot.size())
                .distinct()
                .sorted()
                .map(snapshot::get)
                .toList();
    }

    List<Download> rowsAt(List<Integer> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return List.of();
        }
        return indexes.stream()
                .filter(Objects::nonNull)
                .filter(index -> index >= 0)
                .distinct()
                .sorted()
                .map(this::rowAt)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Restores download selection after a structural ListStore rebuild. Stable
     * ids are used because every former TreePath becomes invalid after clear().
     *
     * @return the number of visible records reselected
     */
    int restoreSelection(TreeSelection selection, Collection<String> downloadIds) {
        if (selection == null || downloadIds == null || downloadIds.isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (String id : new java.util.LinkedHashSet<>(downloadIds)) {
            TreeRowReference reference = rowReferences.get(id);
            TreePath path = reference == null ? null : reference.getPath();
            if (path == null) {
                continue;
            }
            try {
                selection.selectPath(path);
                restored++;
            } finally {
                MemoryCleaner.free(path.handle());
            }
        }
        return restored;
    }

    /** Clears an explicit column sort so queue-position ordering is visible. */
    void useQueueOrder() {
        // GTK_TREE_SORTABLE_UNSORTED_SORT_COLUMN_ID is -2. Gtk exposes the
        // constant in C but java-gi currently does not generate it.
        ((TreeSortable) downloadsStore).setSortColumnId(-2, SortType.ASCENDING);
    }

    /** Schedules one coalesced refresh on the GTK main loop. Any thread. */
    void scheduleRefresh() {
        if (refreshPending.compareAndSet(false, true)) {
            UiThread.marshal(() -> {
                // Clear before running so events arriving during the refresh
                // schedule a follow-up instead of being dropped
                refreshPending.set(false);
                fullRefresh.run();
            });
        }
    }

    /**
     * Advances only the activity cells of visible downloads whose completion
     * actions are running. This avoids rebuilding or refetching the list at
     * animation frequency.
     *
     * @return true when at least one visible row was pulsed
     */
    boolean pulseCompletionRows() {
        completionPulsePosition = (completionPulsePosition + 8) % 101;
        boolean anyVisible = false;
        for (Download download : rowSnapshot) {
            if (!download.hasRunningProgressCompletionActions()) {
                continue;
            }
            TreeRowReference reference = rowReferences.get(download.getId());
            TreePath path = reference == null ? null : reference.getPath();
            if (path == null) {
                continue;
            }
            try {
                TreeIter iter = new TreeIter();
                if (downloadsStore.getIter(iter, path)) {
                    ListStoreCells.setInt(downloadsStore, iter, COL_PROGRESS, 100);
                    ListStoreCells.setString(downloadsStore, iter, COL_PROGRESS_TEXT,
                            "100% · Finalizing…");
                    ListStoreCells.setInt(downloadsStore, iter, COL_PROGRESS_PULSE,
                            completionPulsePosition);
                    anyVisible = true;
                }
            } finally {
                MemoryCleaner.free(path.handle());
            }
        }
        return anyVisible;
    }

    /**
     * Refreshes the download view. Progress ticks (the dominant event rate)
     * update existing rows in place, preserving the tree selection; only
     * structural changes (add/remove/reorder/filter switch) rebuild the
     * store. Filter stores are rebuilt only when their counts actually
     * changed. GTK thread only.
     *
     * @return aggregate totals over ALL downloads (not just filtered rows)
     */
    RefreshSummary refresh(List<Download> downloads) {
        return refresh(downloads, downloads.size(), null);
    }

    /** Refreshes a bounded visible window while using repository-wide status
     * counts when supplied. This keeps sidebar totals exact without loading
     * every historical Download object on every progress tick. */
    RefreshSummary refresh(List<Download> downloads, int totalCount,
            java.util.Map<Download.Status, Integer> repositoryStatusCounts) {
        long totalBytes = 0;
        long doneBytes = 0;

        List<Download> display = new ArrayList<>(downloads.size());
        double totalDownSpeed = 0;
        double totalUpSpeed = 0;
        int totalSeeders = 0;
        boolean anyActive = false;
        for (Download download : downloads) {
            totalBytes += download.getSize();
            doneBytes += download.getDownloaded();
            if (matchesFilters(download, searchText, categoryFilter, statusFilter)) {
                display.add(download);
            }
            if (download.getStatus() == Download.Status.STARTING
                    || download.getStatus() == Download.Status.CONNECTING
                    || download.getStatus() == Download.Status.DOWNLOADING
                    || download.getStatus() == Download.Status.SEEDING) {
                anyActive = true;
                if (download.getStatus() == Download.Status.DOWNLOADING
                        || download.getStatus() == Download.Status.SEEDING) {
                    totalDownSpeed += download.getSpeed();
                    totalUpSpeed += download.getUploadSpeed();
                    totalSeeders += download.getSeeders();
                }
            }
        }
        display = orderQueuedRows(display);
        Map<String, Download> currentRowsById = new HashMap<>();
        for (Download download : display) {
            currentRowsById.put(download.getId(), download);
        }
        rowsById = Map.copyOf(currentRowsById);

        int[] counts = repositoryStatusCounts == null
                ? computeCounts(downloads)
                : computeCounts(repositoryStatusCounts);
        int[] categoryCounts = computeCategoryCounts(downloads);
        if (!java.util.Arrays.equals(counts, lastStatusCounts)
                || totalCount != lastStatusTotalCount) {
            lastStatusCounts = counts;
            lastStatusTotalCount = totalCount;
            rebuildFilterStore(statusStore, STATUS_FILTERS, counts, totalCount, statusFilter);
        }
        if (!java.util.Arrays.equals(categoryCounts, lastCategoryCounts)
                || downloads.size() != lastCategoryTotalCount) {
            lastCategoryCounts = categoryCounts;
            lastCategoryTotalCount = downloads.size();
            rebuildFilterStore(categoryStore, CATEGORIES, categoryCounts, downloads.size(),
                    categoryFilter);
        }

        // Preserve selection while pagination extends the existing id prefix:
        // update old rows in place and append only the newly fetched suffix.
        // Rebuild only for a true reorder/removal/filter structure change.
        boolean sameStructure = rowStructureMatches(rowSnapshot, display);
        boolean appendOnly = !sameStructure && rowStructureIsPrefix(rowSnapshot, display);
        boolean canUpdateInPlace = sameStructure || appendOnly;
        int existingRows = appendOnly ? rowSnapshot.size() : display.size();
        if (canUpdateInPlace) {
            for (int row = 0; row < existingRows; row++) {
                Download download = display.get(row);
                TreeRowReference reference = rowReferences.get(download.getId());
                TreePath path = reference == null ? null : reference.getPath();
                if (path == null) {
                    canUpdateInPlace = false;
                    break;
                }
                try {
                    TreeIter iter = new TreeIter();
                    if (!downloadsStore.getIter(iter, path)) {
                        canUpdateInPlace = false;
                        break;
                    }
                    updateRowCells(downloadsStore, iter, row, download);
                } finally {
                    MemoryCleaner.free(path.handle());
                }
            }
        }
        boolean modelRebuilt = false;
        if (canUpdateInPlace && appendOnly) {
            TreeIter iter = new TreeIter();
            for (int row = existingRows; row < display.size(); row++) {
                downloadsStore.append(iter);
                updateRowCells(downloadsStore, iter, row, display.get(row));
            }
            freeRowReferences();
            rebuildRowReferences();
        } else if (!canUpdateInPlace) {
            freeRowReferences();
            downloadsStore.clear();
            TreeIter iter = new TreeIter();
            for (int row = 0; row < display.size(); row++) {
                Download download = display.get(row);
                downloadsStore.append(iter);
                updateRowCells(downloadsStore, iter, row, download);
            }
            rebuildRowReferences();
            modelRebuilt = true;
        }
        rowSnapshot = new ArrayList<>(display);

        globalProgressStore.clear();
        TreeIter progressIter = new TreeIter();
        globalProgressStore.append(progressIter);
        double globalProgress = totalBytes > 0 ? doneBytes * 100.0 / totalBytes : 0;
        ListStoreCells.setInt(globalProgressStore, progressIter, 0,
                ProgressPresentation.wholePercentage(globalProgress));
        ListStoreCells.setString(globalProgressStore, progressIter, 1,
                ProgressPresentation.percentage(globalProgress));

        return new RefreshSummary(totalCount, (long) totalDownSpeed, (long) totalUpSpeed,
                totalSeeders, anyActive, totalBytes, doneBytes, modelRebuilt);
    }

    /**
     * Reorders queued rows by the manager's queue position while leaving
     * non-queued history in its repository order and slots. This makes the
     * Move Up/Down commands visible without redesigning the mixed list.
     */
    static List<Download> orderQueuedRows(List<Download> input) {
        List<Download> ordered = new ArrayList<>(input);
        List<Download> queued = input.stream()
                .filter(download -> download.getStatus() == Download.Status.QUEUED)
                .sorted(java.util.Comparator.comparingInt(Download::getQueuePosition)
                        .thenComparing(Download::getCreatedAt,
                                java.util.Comparator.nullsLast(
                                        java.util.Comparator.naturalOrder())))
                .toList();
        int queuedIndex = 0;
        for (int row = 0; row < ordered.size(); row++) {
            if (ordered.get(row).getStatus() == Download.Status.QUEUED) {
                ordered.set(row, queued.get(queuedIndex++));
            }
        }
        return ordered;
    }

    /** Whether the store's current row sequence matches the new display list. */
    static boolean rowStructureMatches(List<Download> snapshot, List<Download> display) {
        if (snapshot == null || snapshot.size() != display.size()) {
            return false;
        }
        for (int i = 0; i < display.size(); i++) {
            String a = snapshot.get(i) == null ? null : snapshot.get(i).getId();
            String b = display.get(i) == null ? null : display.get(i).getId();
            if (!Objects.equals(a, b)) {
                return false;
            }
        }
        return true;
    }

    /** True when pagination only adds rows after the existing logical order. */
    static boolean rowStructureIsPrefix(List<Download> snapshot, List<Download> display) {
        if (snapshot == null || display == null || snapshot.size() >= display.size()) {
            return false;
        }
        for (int i = 0; i < snapshot.size(); i++) {
            String existing = snapshot.get(i) == null ? null : snapshot.get(i).getId();
            String candidate = display.get(i) == null ? null : display.get(i).getId();
            if (!Objects.equals(existing, candidate)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a download passes the search text, category, and status
     * filters currently in effect.
     */
    static boolean matchesFilters(Download download, String searchText, String categoryFilter,
            String statusFilter) {
        if (searchText != null && !searchText.isEmpty() && (download.getName() == null
                || !download.getName().toLowerCase().contains(searchText))) {
            return false;
        }
        // Category filter (extension-based, mirrors the approved old UI)
        if (categoryFilter != null && !"All".equals(categoryFilter)
                && !categoryFilter.equals(categoryOf(download))) {
            return false;
        }
        return switch (statusFilter == null ? "All Status" : statusFilter) {
            case "All Status" -> true;
            case "Active" -> download.getStatus() == Download.Status.STARTING
                    || download.getStatus() == Download.Status.CONNECTING
                    || download.getStatus() == Download.Status.DOWNLOADING
                    || download.getStatus() == Download.Status.SEEDING;
            case "Seeding" -> download.getStatus() == Download.Status.SEEDING;
            case "Queued" -> download.getStatus() == Download.Status.CREATED
                    || download.getStatus() == Download.Status.QUEUED;
            case "Paused" -> download.getStatus() == Download.Status.PAUSED;
            case "Finished" -> download.getStatus() == Download.Status.COMPLETED;
            case "Error" -> download.getStatus() == Download.Status.ERROR;
            case "Canceled" -> download.getStatus() == Download.Status.CANCELED;
            default -> true;
        };
    }

    /** Counts per status filter, index-aligned with STATUS_FILTERS (minus "All Status"). */
    static int[] computeCounts(List<Download> downloads) {
        int active = 0, seeding = 0, queued = 0, paused = 0;
        int finished = 0, error = 0, canceled = 0;
        for (Download d : downloads) {
            switch (d.getStatus()) {
                case STARTING, CONNECTING, DOWNLOADING -> active++;
                case SEEDING -> {
                    active++;
                    seeding++;
                }
                case CREATED, QUEUED -> queued++;
                case PAUSED -> paused++;
                case COMPLETED -> finished++;
                case ERROR -> error++;
                case CANCELED -> canceled++;
                default -> { }
            }
        }
        return new int[]{active, seeding, queued, paused, finished, error, canceled};
    }

    static int[] computeCounts(java.util.Map<Download.Status, Integer> counts) {
        java.util.function.ToIntFunction<Download.Status> count =
                status -> counts.getOrDefault(status, 0);
        return new int[]{
            count.applyAsInt(Download.Status.STARTING)
                    + count.applyAsInt(Download.Status.CONNECTING)
                    + count.applyAsInt(Download.Status.DOWNLOADING)
                    + count.applyAsInt(Download.Status.SEEDING),
            count.applyAsInt(Download.Status.SEEDING),
            count.applyAsInt(Download.Status.CREATED)
                    + count.applyAsInt(Download.Status.QUEUED),
            count.applyAsInt(Download.Status.PAUSED),
            count.applyAsInt(Download.Status.COMPLETED),
            count.applyAsInt(Download.Status.ERROR),
            count.applyAsInt(Download.Status.CANCELED)
        };
    }

    /** Counts per category (extension-based), index-aligned with CATEGORIES. */
    static int[] computeCategoryCounts(List<Download> downloads) {
        int[] result = new int[CATEGORIES.length];
        for (Download download : downloads) {
            String category = categoryOf(download);
            for (int i = 1; i < CATEGORIES.length; i++) {
                if (CATEGORIES[i].equals(category)) {
                    result[i]++;
                    break;
                }
            }
            result[0]++; // "All"
        }
        return result;
    }

    /**
     * Extension-based category of a download — mirrors the approved old UI's
     * mapping exactly (Videos/Audios/Photos/Programs/Others).
     */
    static String categoryOf(Download download) {
        String name = download.getName();
        if (name == null) {
            return "Others";
        }
        int lastDot = name.lastIndexOf('.');
        String extension = lastDot > 0 ? name.substring(lastDot + 1).toLowerCase() : "";
        return switch (extension) {
            case "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma" -> "Audios";
            case "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "3gp" -> "Videos";
            case "jpg", "jpeg", "png", "gif", "bmp", "tiff", "svg", "ico" -> "Photos";
            case "exe", "msi", "deb", "rpm", "dmg", "appimage", "flatpak", "snap" -> "Programs";
            default -> "Others";
        };
    }

    /** Theme icon used by the dedicated lifecycle-status column. */
    static String statusIconName(Download.Status status) {
        if (status == null) {
            return "dialog-question-symbolic";
        }
        return switch (status) {
            case CREATED -> "document-new-symbolic";
            case STARTING -> "media-playback-start-symbolic";
            case DOWNLOADING -> "media-playback-start-symbolic";
            case SEEDING -> "network-transmit-symbolic";
            case QUEUED -> "view-grid-symbolic";
            case PAUSED -> "media-playback-pause-symbolic";
            case ERROR -> "dialog-error-symbolic";
            case COMPLETED -> "object-select-symbolic";
            case CONNECTING -> "network-transmit-receive-symbolic";
            case CANCELED -> "process-stop-symbolic";
        };
    }

    /** Writes all cells of one download row (shared by update and rebuild paths). */
    private void updateRowCells(ListStore store, TreeIter iter, int row, Download download) {
        ListStoreCells.setInt(store, iter, COL_NUMBER, row + 1);
        ListStoreCells.setString(store, iter, COL_NAME, download.getName());
        ListStoreCells.setString(store, iter, COL_COMPLETE, DownloadFormats.size(download.getDownloaded()));
        ListStoreCells.setString(store, iter, COL_SIZE, DownloadFormats.size(download.getSize()));
        boolean finalizing = download.hasRunningProgressCompletionActions();
        ListStoreCells.setInt(store, iter, COL_PROGRESS, finalizing
                ? 100 : ProgressPresentation.wholePercentage(download.getProgress()));
        ListStoreCells.setString(store, iter, COL_PROGRESS_TEXT, finalizing
                ? "100% · Finalizing…" : ProgressPresentation.percentage(download.getProgress()));
        ListStoreCells.setInt(store, iter, COL_PROGRESS_PULSE,
                finalizing ? completionPulsePosition : -1);
        ListStoreCells.setString(store, iter, COL_STATUS_ICON,
                statusIconName(download.getStatus()));
        ListStoreCells.setString(store, iter, COL_ELAPSED, DownloadFormats.elapsed(download));
        ListStoreCells.setString(store, iter, COL_LEFT,
                DownloadFormats.size(Math.max(0, download.getSize() - download.getDownloaded())));
        ListStoreCells.setString(store, iter, COL_SPEED,
                DownloadFormats.rate((long) download.getSpeed()));
        ListStoreCells.setString(store, iter, COL_UP_SPEED,
                DownloadFormats.rate((long) download.getUploadSpeed()));
        ListStoreCells.setString(store, iter, COL_RETRY,
                download.getStatus() == Download.Status.ERROR
                        && download.getErrorMessage() != null
                        && !download.getErrorMessage().isBlank()
                                ? download.getErrorMessage() : "—");
        ListStoreCells.setString(store, iter, COL_START,
                download.getStartedAt() != null
                        ? DownloadFormats.DATE_FORMAT.format(download.getStartedAt())
                        : "—");
        ListStoreCells.setString(store, iter, COL_END,
                download.getCompletedAt() != null
                        ? DownloadFormats.DATE_FORMAT.format(download.getCompletedAt())
                        : "—");
        ListStoreCells.setIcon(store, iter, COL_ENGINE_ICON,
                DownloadEnginePresentation.icon(download.getType()));

        ListStoreCells.setString(store, iter, COL_DOWNLOAD_ID, download.getId());
        ListStoreCells.setInt(store, iter, COL_STATUS_SORT,
                download.getStatus() == null ? -1 : download.getStatus().ordinal());
        ListStoreCells.setLong(store, iter, COL_COMPLETE_SORT, download.getDownloaded());
        ListStoreCells.setLong(store, iter, COL_SIZE_SORT, download.getSize());
        ListStoreCells.setDouble(store, iter, COL_PROGRESS_SORT, download.getProgress());
        ListStoreCells.setLong(store, iter, COL_ELAPSED_SORT, elapsedSeconds(download));
        ListStoreCells.setLong(store, iter, COL_LEFT_SORT,
                Math.max(0, download.getSize() - download.getDownloaded()));
        ListStoreCells.setDouble(store, iter, COL_SPEED_SORT, download.getSpeed());
        ListStoreCells.setDouble(store, iter, COL_UP_SPEED_SORT, download.getUploadSpeed());
        ListStoreCells.setLong(store, iter, COL_START_SORT,
                download.getStartedAt() == null ? 0 : download.getStartedAt().toEpochMilli());
        ListStoreCells.setLong(store, iter, COL_END_SORT,
                download.getCompletedAt() == null ? 0 : download.getCompletedAt().toEpochMilli());
        ListStoreCells.setInt(store, iter, COL_TYPE_SORT,
                download.getType() == null ? -1 : download.getType().ordinal());
    }

    private static long elapsedSeconds(Download download) {
        return Math.max(0, download.getActiveElapsedMillis() / 1000);
    }

    private void freeRowReferences() {
        for (TreeRowReference reference : rowReferences.values()) {
            MemoryCleaner.free(reference.handle());
        }
        rowReferences.clear();
    }

    private void rebuildRowReferences() {
        TreeIter iter = new TreeIter();
        if (!downloadsStore.getIterFirst(iter)) {
            return;
        }
        do {
            String id = ListStoreCells.getString(downloadsStore, iter, COL_DOWNLOAD_ID);
            TreePath path = downloadsStore.getPath(iter);
            try {
                rowReferences.put(id, new TreeRowReference(downloadsStore, path));
            } finally {
                MemoryCleaner.free(path.handle());
            }
        } while (downloadsStore.iterNext(iter));
    }

    private void rebuildFilterStore(ListStore store, String[] labels, int[] counts, int total,
            String selected) {
        store.clear();
        for (int i = 0; i < labels.length; i++) {
            int count = store == statusStore
                    ? (i == 0 ? total : (i - 1 < counts.length ? counts[i - 1] : 0))
                    : (i < counts.length ? counts[i] : 0);
            TreeIter iter = new TreeIter();
            store.append(iter);
            ListStoreCells.setString(store, iter, SC_ICON, iconForFilterRow(labels[i]));
            ListStoreCells.setInt(store, iter, SC_COUNT, count);
            ListStoreCells.setString(store, iter, SC_LABEL, labels[i]);
            if (labels[i].equals(selected)) {
                if (store == statusStore) {
                    suppressStatusSelection = true;
                    try {
                        statusTreeview.getSelection().selectIter(iter);
                    } finally {
                        suppressStatusSelection = false;
                    }
                } else {
                    suppressCategorySelection = true;
                    try {
                        categoryTreeview.getSelection().selectIter(iter);
                    } finally {
                        suppressCategorySelection = false;
                    }
                }
            }
        }
    }

    static String iconForFilterRow(String label) {
        return switch (label) {
            case "All Status" -> "view-list-symbolic";
            case "Active" -> "media-playback-start-symbolic";
            case "Seeding" -> "network-transmit-symbolic";
            case "Queued" -> "view-grid-symbolic";
            case "Paused" -> "media-playback-pause-symbolic";
            case "Finished" -> "object-select-symbolic";
            case "Error" -> "dialog-error-symbolic";
            case "Canceled" -> "process-stop-symbolic";
            case "All" -> "view-list-symbolic";
            case "Videos" -> "video-x-generic-symbolic";
            case "Audios" -> "audio-x-generic-symbolic";
            case "Photos" -> "image-x-generic-symbolic";
            case "Programs" -> "application-x-executable-symbolic";
            case "Others" -> "text-x-generic-symbolic";
            default -> "text-x-generic-symbolic";
        };
    }

}
