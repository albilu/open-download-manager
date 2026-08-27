package org.odm.gtk4;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeView;
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

    static final String[] STATUS_FILTERS = {"All Status", "Active", "Queuing", "Finished", "Deleted"};
    static final String[] CATEGORIES = {"All", "Videos", "Audios", "Photos", "Programs", "Others"};

    // download_store columns (0-11 gchararray, 12 gint — matches the original glade)
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
    private static final int COL_TOR_ICON = 11;
    private static final int COL_PROGRESS = 12; // gint

    // status_store / category_store columns
    private static final int SC_ICON = 0;
    private static final int SC_COUNT = 1;
    private static final int SC_LABEL = 2;

    /**
     * Aggregate numbers of one refresh pass — the side-band labels and the
     * spinner stay widget concerns of the window.
     */
    record RefreshSummary(int totalCount, long downBytesPerSec, long upBytesPerSec,
            int totalSeeders, boolean anyActive, long totalBytes, long doneBytes) {
    }

    private final ListStore statusStore;
    private final ListStore categoryStore;
    private final ListStore downloadsStore;
    private final ListStore globalProgressStore;
    private final TreeView statusTreeview;
    private final TreeView categoryTreeview;
    private final Runnable fullRefresh;

    private List<Download> rowSnapshot = new ArrayList<>();
    /** Last filter-store counts; filter stores rebuild only when these change. */
    private int[] lastStatusCounts = new int[0];
    private int[] lastCategoryCounts = new int[0];
    private int lastTotalCount = -1;

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
        return index < rowSnapshot.size() ? rowSnapshot.get(index) : null;
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
     * Refreshes the download view. Progress ticks (the dominant event rate)
     * update existing rows in place, preserving the tree selection; only
     * structural changes (add/remove/reorder/filter switch) rebuild the
     * store. Filter stores are rebuilt only when their counts actually
     * changed. GTK thread only.
     *
     * @return aggregate totals over ALL downloads (not just filtered rows)
     */
    RefreshSummary refresh(List<Download> downloads) {
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
            if (download.getStatus() == Download.Status.DOWNLOADING) {
                totalDownSpeed += download.getSpeed();
                totalUpSpeed += download.getUploadSpeed();
                totalSeeders += download.getSeeders();
                anyActive = true;
            }
        }

        int[] counts = computeCounts(downloads);
        if (!java.util.Arrays.equals(counts, lastStatusCounts)
                || downloads.size() != lastTotalCount) {
            lastStatusCounts = counts;
            lastTotalCount = downloads.size();
            rebuildFilterStore(statusStore, STATUS_FILTERS, counts, downloads.size(), statusFilter);
            int[] categoryCounts = computeCategoryCounts(downloads);
            lastCategoryCounts = categoryCounts;
            rebuildFilterStore(categoryStore, CATEGORIES, categoryCounts, downloads.size(),
                    categoryFilter);
        }

        // In-place row updates when the visible id sequence is unchanged;
        // full rebuild only when the structure changed
        if (rowStructureMatches(rowSnapshot, display)) {
            TreeIter iter = new TreeIter();
            if (downloadsStore.getIterFirst(iter)) {
                int row = 0;
                do {
                    if (row < display.size()) {
                        updateRowCells(downloadsStore, iter, row, display.get(row));
                    }
                    row++;
                } while (downloadsStore.iterNext(iter));
            }
        } else {
            downloadsStore.clear();
            rowSnapshot = new ArrayList<>(display.size());
            TreeIter iter = new TreeIter();
            for (int row = 0; row < display.size(); row++) {
                Download download = display.get(row);
                downloadsStore.append(iter);
                rowSnapshot.add(download);
                updateRowCells(downloadsStore, iter, row, download);
            }
        }

        globalProgressStore.clear();
        TreeIter progressIter = new TreeIter();
        globalProgressStore.append(progressIter);
        ListStoreCells.setInt(globalProgressStore, progressIter, 0,
                totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0);

        return new RefreshSummary(downloads.size(), (long) totalDownSpeed, (long) totalUpSpeed,
                totalSeeders, anyActive, totalBytes, doneBytes);
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
            case "Active" -> download.getStatus() == Download.Status.DOWNLOADING;
            case "Queuing" -> download.getStatus() == Download.Status.QUEUED
                    || download.getStatus() == Download.Status.CONNECTING
                    || download.getStatus() == Download.Status.PAUSED;
            case "Finished" -> download.getStatus() == Download.Status.COMPLETED;
            case "Deleted" -> download.getStatus() == Download.Status.ERROR
                    || download.getStatus() == Download.Status.CANCELED;
            default -> true;
        };
    }

    /** Counts per status filter, index-aligned with STATUS_FILTERS (minus "All Status"). */
    static int[] computeCounts(List<Download> downloads) {
        int active = 0, queuing = 0, finished = 0, deleted = 0;
        for (Download d : downloads) {
            switch (d.getStatus()) {
                case DOWNLOADING -> active++;
                case QUEUED, CONNECTING, PAUSED -> queuing++;
                case COMPLETED -> finished++;
                case ERROR, CANCELED -> deleted++;
                default -> { }
            }
        }
        return new int[]{active, queuing, finished, deleted};
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

    /** Writes all cells of one download row (shared by update and rebuild paths). */
    private void updateRowCells(ListStore store, TreeIter iter, int row, Download download) {
        ListStoreCells.setString(store, iter, COL_NUMBER, String.valueOf(row + 1));
        ListStoreCells.setString(store, iter, COL_NAME, download.getName());
        ListStoreCells.setString(store, iter, COL_COMPLETE, DownloadFormats.size(download.getDownloaded()));
        ListStoreCells.setString(store, iter, COL_SIZE, DownloadFormats.size(download.getSize()));
        ListStoreCells.setInt(store, iter, COL_PROGRESS, (int) download.getProgress());
        ListStoreCells.setString(store, iter, COL_ELAPSED, DownloadFormats.elapsed(download));
        ListStoreCells.setString(store, iter, COL_LEFT,
                DownloadFormats.size(Math.max(0, download.getSize() - download.getDownloaded())));
        ListStoreCells.setString(store, iter, COL_SPEED,
                DownloadFormats.size((long) download.getSpeed()) + "/s");
        ListStoreCells.setString(store, iter, COL_UP_SPEED, "—");
        ListStoreCells.setString(store, iter, COL_RETRY, "—");
        ListStoreCells.setString(store, iter, COL_START,
                download.getCreatedAt() != null
                        ? DownloadFormats.DATE_FORMAT.format(download.getCreatedAt())
                        : "—");
        ListStoreCells.setString(store, iter, COL_END,
                download.getCompletedAt() != null
                        ? DownloadFormats.DATE_FORMAT.format(download.getCompletedAt())
                        : "—");
        ListStoreCells.setString(store, iter, COL_TOR_ICON, engineIconName(download));
    }

    private void rebuildFilterStore(ListStore store, String[] labels, int[] counts, int total,
            String selected) {
        store.clear();
        for (int i = 0; i < labels.length; i++) {
            int count = i < counts.length ? counts[i] : (i == 0 ? total : 0);
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

    private static String iconForFilterRow(String label) {
        return switch (label) {
            case "All Status" -> "view-list-symbolic";
            case "Active" -> "media-playback-start-symbolic";
            case "Queuing" -> "view-grid-symbolic";
            case "Finished" -> "emblem-ok-symbolic";
            case "Deleted" -> "edit-delete-symbolic";
            case "All" -> "view-list-symbolic";
            case "Videos" -> "video-x-generic-symbolic";
            case "Audios" -> "audio-x-generic-symbolic";
            case "Photos" -> "image-x-generic-symbolic";
            case "Programs" -> "application-x-executable-symbolic";
            case "Others" -> "text-x-generic-symbolic";
            default -> "text-x-generic-symbolic";
        };
    }

    private static String engineIconName(Download download) {
        return switch (download.getType()) {
            case ARIA2 -> "network-server-symbolic";
            case CURL -> "network-wired-symbolic";
            case YOUTUBE -> "video-x-generic-symbolic";
            case WEBSITE_SCRAPING -> "edit-find-symbolic";
            case PROXYCHAINS -> "network-proxy-symbolic";
            case TOR -> "network-wireless-symbolic";
            default -> "text-x-generic-symbolic";
        };
    }
}
