package org.odm.gtk4;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Button;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeModel;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeView;
import org.gnome.gobject.Value;
import org.javagi.base.Out;
import org.javagi.gobject.types.Types;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;

/**
 * Main window — 1:1 GTK4 port of main-window.glade. Same widget ids and
 * layout; behaviors wired for the core surface, with the rest (Tor toggle,
 * queue reorder, live search, DHT/BT tabs) deferred to Step 5 but their
 * widgets present in the .ui so the structure matches the original.
 *
 * All core events arrive through UiThread.marshal — the only safe seam.
 */
public class MainWindow {

    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

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

    private static final String[] STATUS_FILTERS = {"All Status", "Active", "Queuing", "Finished", "Deleted"};
    private static final String[] CATEGORIES = {"All", "Videos", "Audios", "Photos", "Programs", "Others"};

    private final ApplicationWindow window;
    private final ListStore statusStore;
    private final ListStore categoryStore;
    private final ListStore downloadsStore;
    private final TreeView statusTreeview;
    private final TreeView categoryTreeview;
    private final TreeView downloadsTreeview;
    private final Label infoLabel;
    private final Label downSpeedLabel;
    private final Label upSpeedLabel;
    private final Label dhtStatusLabel;
    private final Spinner activitySpinner;
    private final ProgressBar infoProgressBar;
    private final Label totalSizeValue;
    private final Label addedOnValue;
    private final Label infoHashValue;
    private final Label folderValue;
    private final Label etaValue;
    private final Label downloadedValue;
    private final Label connectionsValue;
    private final Label seedsPeersValue;
    private final DownloadManager downloadManager;

    private List<Download> rowSnapshot = new ArrayList<>();
    private Download selectedDownload;
    private String statusFilter = "All Status";

    public MainWindow(Application app, DownloadManager downloadManager) {
        this.downloadManager = downloadManager;

        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");

        this.window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        this.statusStore = Widgets.require(builder, "status_store", ListStore.class);
        this.categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        this.downloadsStore = Widgets.require(builder, "download_store", ListStore.class);
        this.statusTreeview = Widgets.require(builder, "status_treeview", TreeView.class);
        this.categoryTreeview = Widgets.require(builder, "category_treeview", TreeView.class);
        this.downloadsTreeview = Widgets.require(builder, "download_treeview", TreeView.class);
        this.infoLabel = Widgets.require(builder, "info_label", Label.class);
        this.downSpeedLabel = Widgets.require(builder, "down_speed_label", Label.class);
        this.upSpeedLabel = Widgets.require(builder, "up_speed_label", Label.class);
        this.dhtStatusLabel = Widgets.require(builder, "dht_status_label", Label.class);
        this.activitySpinner = Widgets.require(builder, "activity_spinner", Spinner.class);
        this.infoProgressBar = Widgets.require(builder, "info_progress_bar", ProgressBar.class);
        this.totalSizeValue = Widgets.require(builder, "total_size_value", Label.class);
        this.addedOnValue = Widgets.require(builder, "added_on_value", Label.class);
        this.infoHashValue = Widgets.require(builder, "info_hash_v1_value", Label.class);
        this.folderValue = Widgets.require(builder, "folder_value", Label.class);
        this.etaValue = Widgets.require(builder, "eta_value", Label.class);
        this.downloadedValue = Widgets.require(builder, "downloaded_value", Label.class);
        this.connectionsValue = Widgets.require(builder, "connections_value", Label.class);
        this.seedsPeersValue = Widgets.require(builder, "seeds_peers_value", Label.class);
        window.setApplication(app);

        statusTreeview.getSelection().onChanged(this::onStatusSelectionChanged);
        downloadsTreeview.getSelection().onChanged(this::onDownloadSelectionChanged);

        var rightClick = new GestureClick();
        rightClick.setButton(3);
        rightClick.onPressed((nPress, x, y) -> showContextMenu());
        downloadsTreeview.addController(rightClick);

        Widgets.require(builder, "new_download_button", Button.class).onClicked(this::onAddClicked);
        Widgets.require(builder, "pause_button", Button.class).onClicked(this::onPauseClicked);
        Widgets.require(builder, "resume_button", Button.class).onClicked(this::onResumeClicked);
        Widgets.require(builder, "delete_button", Button.class).onClicked(this::onDeleteClicked);
        Widgets.require(builder, "settings_button", Button.class).onClicked(this::onSettingsClicked);
        // tor_switch, move_*_button, search_entry: widgets present, behavior deferred to Step 5
        MenuButton menuButton = Widgets.require(builder, "menu_button", MenuButton.class);
        menuButton.setPopover(buildMainMenu().getPopover());

        downloadManager.addDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) {
                UiThread.marshal(MainWindow.this::refresh);
            }
            @Override public void onDownloadPause(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadResume(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadComplete(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadError(Download d, String errorMessage) {
                UiThread.marshal(MainWindow.this::refresh);
            }
            @Override public void onDownloadCanceled(Download d) { UiThread.marshal(MainWindow.this::refresh); }
        });

        refresh();
    }

    public void present() {
        window.present();
    }

    private void onAddClicked() {
        new NewDownloadDialog(window, downloadManager, () -> UiThread.marshal(this::refresh)).present();
    }

    private void onSettingsClicked() {
        new SettingsDialog(window, downloadManager).present();
    }

    private void onPauseClicked() {
        if (selectedDownload != null) downloadManager.pauseDownload(selectedDownload);
    }

    private void onResumeClicked() {
        if (selectedDownload != null) downloadManager.resumeDownload(selectedDownload);
    }

    private void onDeleteClicked() {
        if (selectedDownload != null) downloadManager.cancelDownload(selectedDownload, false);
    }

    private void showContextMenu() {
        onDownloadSelectionChanged();
        if (selectedDownload == null) return;
        new PopupMenu()
                .add("Pause", this::onPauseClicked)
                .add("Resume", this::onResumeClicked)
                .add("Delete", this::onDeleteClicked)
                .separator()
                .add("Remove finished", () -> {
                    downloadManager.pruneCompletedDownloads(Duration.ZERO);
                    refresh();
                })
                .popup();
    }

    private PopupMenu buildMainMenu() {
        return new PopupMenu()
                .add("New download", this::onAddClicked)
                .add("Import from list", () -> new ImportListDialog(window, downloadManager,
                        () -> UiThread.marshal(this::refresh)).present())
                .separator()
                .add("Settings", this::onSettingsClicked)
                .add("About", () -> AboutDialogPresenter.present(window))
                .separator()
                .add("Exit", () -> window.getApplication().quit());
    }

    private void onStatusSelectionChanged() {
        selectRow(statusTreeview.getSelection(), (path, index) -> {
            if (index < STATUS_FILTERS.length) {
                statusFilter = STATUS_FILTERS[index];
                refresh();
            }
        });
    }

    private void onDownloadSelectionChanged() {
        selectRow(downloadsTreeview.getSelection(), (path, index) -> {
            selectedDownload = index < rowSnapshot.size() ? rowSnapshot.get(index) : null;
            updateInfoPanel();
        });
    }

    private void selectRow(TreeSelection selection, SelectionConsumer consumer) {
        TreeIter iter = new TreeIter();
        Out<TreeModel> model = new Out<>();
        if (selection.getSelected(model, iter)) {
            TreePath path = model.get().getPath(iter);
            int[] indices = path.getIndices();
            if (indices != null && indices.length > 0) {
                consumer.accept(path, indices[0]);
            }
        }
    }

    private interface SelectionConsumer {
        void accept(TreePath path, int index);
    }

    private boolean activeMatches(Download download) {
        return switch (statusFilter) {
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

    /** Rebuilds the stores. GTK thread only. */
    private void refresh() {
        List<Download> downloads = downloadManager.getAllDownloads();
        int[] counts = computeCounts(downloads);

        rebuildFilterStore(statusStore, STATUS_FILTERS, counts, downloads.size(), statusFilter);
        rebuildFilterStore(categoryStore, CATEGORIES, new int[CATEGORIES.length], downloads.size(), "All");

        downloadsStore.clear();
        rowSnapshot = new ArrayList<>();
        int row = 0;
        double totalDownSpeed = 0;
        boolean anyActive = false;
        for (Download download : downloads) {
            if (!activeMatches(download)) continue;
            TreeIter iter = new TreeIter();
            downloadsStore.append(iter);
            rowSnapshot.add(download);
            setStr(downloadsStore, iter, COL_NUMBER, String.valueOf(row + 1));
            setStr(downloadsStore, iter, COL_NAME, download.getName());
            setStr(downloadsStore, iter, COL_COMPLETE, formatSize(download.getDownloaded()));
            setStr(downloadsStore, iter, COL_SIZE, formatSize(download.getSize()));
            setInt(downloadsStore, iter, COL_PROGRESS, (int) download.getProgress());
            setStr(downloadsStore, iter, COL_ELAPSED, formatElapsed(download));
            setStr(downloadsStore, iter, COL_LEFT,
                    formatSize(Math.max(0, download.getSize() - download.getDownloaded())));
            setStr(downloadsStore, iter, COL_SPEED, formatSize((long) download.getSpeed()) + "/s");
            setStr(downloadsStore, iter, COL_UP_SPEED, "—");
            setStr(downloadsStore, iter, COL_RETRY, "—");
            setStr(downloadsStore, iter, COL_START,
                    download.getCreatedAt() != null ? DATE_FORMAT.format(download.getCreatedAt()) : "—");
            setStr(downloadsStore, iter, COL_END,
                    download.getCompletedAt() != null ? DATE_FORMAT.format(download.getCompletedAt()) : "—");
            setStr(downloadsStore, iter, COL_TOR_ICON, engineIconName(download));
            if (download.getStatus() == Download.Status.DOWNLOADING) {
                totalDownSpeed += download.getSpeed();
                anyActive = true;
            }
            row++;
        }

        infoLabel.setLabel(downloads.size() + " download(s)");
        downSpeedLabel.setLabel(formatSize((long) totalDownSpeed) + "/s");
        upSpeedLabel.setLabel("—");
        dhtStatusLabel.setLabel("DHT: —");
        activitySpinner.setSpinning(anyActive);
        updateInfoPanel();
    }

    private int[] computeCounts(List<Download> downloads) {
        int active = 0, queuing = 0, finished = 0, deleted = 0;
        for (Download d : downloads) {
            switch (d.getStatus()) {
                case DOWNLOADING -> active++;
                case QUEUED, CONNECTING, PAUSED -> queuing++;
                case COMPLETED -> finished++;
                case ERROR, CANCELED -> deleted++;
            }
        }
        return new int[]{active, queuing, finished, deleted};
    }

    private void rebuildFilterStore(ListStore store, String[] labels, int[] counts, int total, String selected) {
        store.clear();
        for (int i = 0; i < labels.length; i++) {
            int count = i < counts.length ? counts[i] : (i == 0 ? total : 0);
            TreeIter iter = new TreeIter();
            store.append(iter);
            setStr(store, iter, SC_ICON, iconForFilterRow(labels[i]));
            setInt(store, iter, SC_COUNT, count);
            setStr(store, iter, SC_LABEL, labels[i]);
            if (labels[i].equals(selected)) {
                if (store == statusStore) {
                    statusTreeview.getSelection().selectIter(iter);
                } else {
                    categoryTreeview.getSelection().selectIter(iter);
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

    private void updateInfoPanel() {
        if (selectedDownload == null) {
            infoProgressBar.setFraction(0);
            totalSizeValue.setLabel("—");
            addedOnValue.setLabel("—");
            infoHashValue.setLabel("—");
            folderValue.setLabel("—");
            etaValue.setLabel("—");
            downloadedValue.setLabel("—");
            connectionsValue.setLabel("—");
            seedsPeersValue.setLabel("—");
            return;
        }
        infoProgressBar.setFraction(selectedDownload.getProgress() / 100.0);
        totalSizeValue.setLabel(formatSize(selectedDownload.getSize()));
        addedOnValue.setLabel(selectedDownload.getCreatedAt() != null ? DATE_FORMAT.format(selectedDownload.getCreatedAt()) : "—");
        infoHashValue.setLabel("—"); // BitTorrent-only; wired when aria2 detail RPC lands (Step 5)
        folderValue.setLabel(selectedDownload.getDestination() != null ? selectedDownload.getDestination().toString() : "—");
        etaValue.setLabel(formatEta(selectedDownload));
        downloadedValue.setLabel(formatSize(selectedDownload.getDownloaded()));
        connectionsValue.setLabel("—"); // aria2 detail RPC (Step 5)
        seedsPeersValue.setLabel("—"); // aria2 detail RPC (Step 5)
    }

    private static String formatEta(Download download) {
        float speed = download.getSpeed();
        long remaining = download.getSize() - download.getDownloaded();
        if (speed <= 0 || remaining <= 0 || download.getStatus() != Download.Status.DOWNLOADING) {
            return "—";
        }
        long seconds = (long) (remaining / speed);
        Duration d = Duration.ofSeconds(seconds);
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return h > 0 ? String.format("%dh %dm", h, m) : m > 0 ? String.format("%dm %ds", m, s)
                : String.format("%ds", s);
    }

    private static String formatElapsed(Download download) {
        if (download.getCreatedAt() == null) return "—";
        Duration d = Duration.between(download.getCreatedAt(), Instant.now());
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return h > 0 ? String.format("%dh %dm", h, m) : m > 0 ? String.format("%dm %ds", m, s)
                : String.format("%ds", s);
    }

    private static void setStr(ListStore store, TreeIter iter, int column, String value) {
        Value v = new Value().init(Types.STRING);
        v.setString(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private static void setInt(ListStore store, TreeIter iter, int column, int value) {
        Value v = new Value().init(column == COL_PROGRESS ? Types.INT : Types.UINT);
        v.setInt(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
