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
import java.util.Map;
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
    private final org.gnome.gtk.Switch torSwitch;
    private final org.gnome.gtk.SearchEntry searchEntry;
    private final MenuButton menuButton;
    private final DownloadManager downloadManager;

    private List<Download> rowSnapshot = new ArrayList<>();
    private Download selectedDownload;
    private String statusFilter = "All Status";
    private String searchText = "";
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final ListStore filesStore;
    private final org.tor.TorService torService;
    private final org.manager.download.action.AfterCompletionActionManager completionActionManager =
            new org.manager.download.action.AfterCompletionActionManager();
    private org.manager.download.action.AfterCompletionAction completionAction;

    public MainWindow(Application app, DownloadManager downloadManager, org.tor.TorService torService) {
        this.downloadManager = downloadManager;
        this.torService = torService;

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
        this.trackersStore = Widgets.require(builder, "trackers_store", ListStore.class);
        this.peersStore = Widgets.require(builder, "peers_store", ListStore.class);
        this.filesStore = Widgets.require(builder, "files_store", ListStore.class);
        this.infoHashValue = Widgets.require(builder, "info_hash_v1_value", Label.class);
        this.folderValue = Widgets.require(builder, "folder_value", Label.class);
        this.etaValue = Widgets.require(builder, "eta_value", Label.class);
        this.downloadedValue = Widgets.require(builder, "downloaded_value", Label.class);
        this.connectionsValue = Widgets.require(builder, "connections_value", Label.class);
        this.seedsPeersValue = Widgets.require(builder, "seeds_peers_value", Label.class);
        window.setApplication(app);

        restoreWindowState(builder);
        window.onCloseRequest(() -> {
            saveWindowState(builder);
            return false; // allow close
        });

        statusTreeview.getSelection().onChanged(this::onStatusSelectionChanged);
        downloadsTreeview.getSelection().onChanged(this::onDownloadSelectionChanged);
        downloadsTreeview.onRowActivated((path, column) -> onPropertiesClicked());

        var rightClick = new GestureClick();
        rightClick.setButton(3);
        rightClick.onPressed((nPress, x, y) -> showContextMenu());
        downloadsTreeview.addController(rightClick);

        Widgets.require(builder, "new_download_button", Button.class).onClicked(this::onAddClicked);
        Widgets.require(builder, "pause_button", Button.class).onClicked(this::onPauseClicked);
        Widgets.require(builder, "resume_button", Button.class).onClicked(this::onResumeClicked);
        Widgets.require(builder, "delete_button", Button.class).onClicked(this::onDeleteClicked);
        Widgets.require(builder, "move_up_button", Button.class)
                .onClicked(() -> { downloadManager.moveDownloadUp(selectedDownload); refresh(); });
        Widgets.require(builder, "move_top_button", Button.class)
                .onClicked(() -> { downloadManager.moveDownloadToTop(selectedDownload); refresh(); });
        Widgets.require(builder, "move_down_button", Button.class)
                .onClicked(() -> { downloadManager.moveDownloadDown(selectedDownload); refresh(); });
        Widgets.require(builder, "move_bottom_button", Button.class)
                .onClicked(() -> { downloadManager.moveDownloadToBottom(selectedDownload); refresh(); });
        Widgets.require(builder, "settings_button", Button.class).onClicked(this::onSettingsClicked);
        this.searchEntry = Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        searchEntry.onSearchChanged(this::onSearchChanged);
        // tor_switch: wired below
        this.torSwitch = Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        torSwitch.onStateSet(this::onTorToggled);
        this.menuButton = Widgets.require(builder, "menu_button", MenuButton.class);
        menuButton.setPopover(buildMainMenu().getPopover());

        downloadManager.addDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) {
                UiThread.marshal(MainWindow.this::refresh);
            }
            @Override public void onDownloadPause(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadResume(Download d) { UiThread.marshal(MainWindow.this::refresh); }
            @Override public void onDownloadComplete(Download d) {
                executeCompletionAction(d);
                UiThread.marshal(MainWindow.this::refresh);
            }
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

    private void onSearchChanged() {
        searchText = searchEntry.getText().strip().toLowerCase();
        refresh();
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

    private void onPropertiesClicked() {
        onDownloadSelectionChanged();
        if (selectedDownload != null) {
            new PropertyDialog(window, downloadManager, selectedDownload).present();
        }
    }

    private void showContextMenu() {
        onDownloadSelectionChanged();
        if (selectedDownload == null) return;
        new PopupMenu()
                .add("Pause", this::onPauseClicked)
                .add("Resume", this::onResumeClicked)
                .add("Delete", this::onDeleteClicked)
                .separator()
                .add("Properties", this::onPropertiesClicked)
                .add("Remove finished", () -> {
                    downloadManager.pruneCompletedDownloads(Duration.ZERO);
                    refresh();
                })
                .popup();
    }

    private PopupMenu buildMainMenu() {
        return new PopupMenu()
                .add("New download", this::onAddClicked)
                .add("New website scrape", this::onScraperClicked)
                .add("Import from list", () -> new ImportListDialog(window, downloadManager,
                        () -> UiThread.marshal(this::refresh)).present())
                .add("Import URL sequence", () -> new ImportSequenceDialog(window, downloadManager,
                        () -> UiThread.marshal(this::refresh)).present())
                .separator()
                .add("Toggle clipboard monitor (currently "
                        + (downloadManager.isClipboardMonitoringEnabled() ? "on" : "off") + ")", () -> {
                    downloadManager.setClipboardMonitoringEnabled(
                            !downloadManager.isClipboardMonitoringEnabled());
                    menuButtonRefresh();
                })
                .separator()
                .add("On completion: none", () -> setCompletionAction(null))
                .add("On completion: notify (sound)", () -> setCompletionAction(
                        new org.manager.download.action.PlayNotificationAction(
                                org.manager.download.action.PlayNotificationAction.NotificationSound.SUCCESS)))
                .add("On completion: shutdown", () -> setCompletionAction(
                        new org.manager.download.action.ShutdownComputerAction(30)))
                .separator()
                .add("Settings", this::onSettingsClicked)
                .add("About", () -> AboutDialogPresenter.present(window))
                .separator()
                .add("Exit", () -> window.getApplication().quit());
    }

    private void menuButtonRefresh() {
        // Rebuild the main menu so toggle-state labels stay current
        menuButton.setPopover(buildMainMenu().getPopover());
    }

    private void setCompletionAction(org.manager.download.action.AfterCompletionAction action) {
        this.completionAction = action;
        LOGGER.info("After-completion action set to: "
                + (action == null ? "none" : action.getClass().getSimpleName()));
    }

    private void executeCompletionAction(Download download) {
        if (completionAction == null) {
            return;
        }
        completionActionManager.clearActions(download);
        completionActionManager.addAction(download, completionAction);
        completionActionManager.executeActions(download)
                .exceptionally(e -> {
                    LOGGER.log(java.util.logging.Level.WARNING,
                            "Completion action failed for " + download.getName(), e);
                    return null;
                });
    }

    private boolean onTorToggled(boolean active) {
        if (active) {
            torService.start().thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    LOGGER.info("Tor started; downloads can route via SOCKS5 127.0.0.1:9050");
                    downloadManager.getGlobalSettings().setGlobalProxyEnabled(true);
                    downloadManager.getGlobalSettings().setGlobalProxyAddress("socks5://127.0.0.1:9050");
                } else {
                    LOGGER.warning("Tor failed to start");
                    UiThread.marshal(() -> torSwitchSet(false));
                }
            });
        } else {
            torService.stop();
            downloadManager.getGlobalSettings().setGlobalProxyEnabled(false);
            LOGGER.info("Tor stopped");
        }
        return false; // let the switch apply its new state
    }

    private void torSwitchSet(boolean active) {
        UiThread.marshal(() -> torSwitch.setActive(active));
    }

    /** Restores window geometry + paned positions persisted from the last session. */
    private void restoreWindowState(GtkBuilder builder) {
        var s = downloadManager.getGlobalSettings();
        int w = s.getIntProperty("ui.window.width", -1);
        int h = s.getIntProperty("ui.window.height", -1);
        if (w > 0 && h > 0) {
            window.setDefaultSize(w, h);
        }
        int mainPos = s.getIntProperty("ui.paned.mainPosition", -1);
        if (mainPos > 0) {
            Widgets.require(builder, "main_paned", org.gnome.gtk.Paned.class).setPosition(mainPos);
        }
        int contentPos = s.getIntProperty("ui.paned.contentPosition", -1);
        if (contentPos > 0) {
            Widgets.require(builder, "content_paned", org.gnome.gtk.Paned.class).setPosition(contentPos);
        }
    }

    /** Persists window geometry + paned positions for the next session. */
    private void saveWindowState(GtkBuilder builder) {
        var s = downloadManager.getGlobalSettings();
        s.setProperty("ui.window.width", String.valueOf(window.getWidth()));
        s.setProperty("ui.window.height", String.valueOf(window.getHeight()));
        s.setProperty("ui.paned.mainPosition",
                String.valueOf(Widgets.require(builder, "main_paned", org.gnome.gtk.Paned.class).getPosition()));
        s.setProperty("ui.paned.contentPosition",
                String.valueOf(Widgets.require(builder, "content_paned", org.gnome.gtk.Paned.class).getPosition()));
        s.save();
    }

    private void onScraperClicked() {
        // Website scrape via httrack: minimal dialog -> createWebsiteDownload
        showScraperDialog();
    }

    private void showScraperDialog() {
        org.gnome.gtk.Window scraper = new org.gnome.gtk.Window();
        scraper.setTitle("New Website Scrape");
        scraper.setModal(true);
        scraper.setTransientFor(window);
        scraper.setDefaultSize(520, -1);

        org.gnome.gtk.Box box = new org.gnome.gtk.Box(org.gnome.gtk.Orientation.VERTICAL, 10);
        box.setMarginStart(16);
        box.setMarginEnd(16);
        box.setMarginTop(16);
        box.setMarginBottom(16);

        org.gnome.gtk.Entry urlEntry = new org.gnome.gtk.Entry();
        urlEntry.setPlaceholderText("https://example.com/site");
        org.gnome.gtk.SpinButton depthSpin = org.gnome.gtk.SpinButton.withRange(0, 20, 1);
        depthSpin.setValue(3);

        org.gnome.gtk.Label statusLabel = new org.gnome.gtk.Label("");

        org.gnome.gtk.Button cancelButton = new org.gnome.gtk.Button();
        cancelButton.setLabel("Cancel");
        cancelButton.onClicked(scraper::close);
        org.gnome.gtk.Button startButton = new org.gnome.gtk.Button();
        startButton.setLabel("Start Scrape");
        startButton.addCssClass("suggested-action");
        startButton.onClicked(() -> {
            String url = urlEntry.getText().trim();
            if (url.isEmpty()) {
                statusLabel.setLabel("Enter a URL");
                return;
            }
            try {
                java.util.Map<String, String> options = new java.util.HashMap<>();
                options.put("depth", String.valueOf((int) depthSpin.getValue()));
                Download download = downloadManager.createWebsiteDownload(new java.net.URI(url),
                        java.nio.file.Path.of(downloadManager.getGlobalSettings()
                                .getDefaultDownloadDirectory().toString()), options);
                downloadManager.queueDownload(download);
                refresh();
                scraper.close();
            } catch (java.net.URISyntaxException e) {
                statusLabel.setLabel("Invalid URL");
            }
        });

        box.append(new org.gnome.gtk.Label("Website URL:"));
        box.append(urlEntry);
        box.append(new org.gnome.gtk.Label("Depth:"));
        box.append(depthSpin);
        box.append(statusLabel);
        org.gnome.gtk.Box buttons = new org.gnome.gtk.Box(org.gnome.gtk.Orientation.HORIZONTAL, 8);
        buttons.setHalign(org.gnome.gtk.Align.END);
        buttons.append(cancelButton);
        buttons.append(startButton);
        box.append(buttons);

        scraper.setChild(box);
        scraper.present();
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
        if (!searchText.isEmpty() && (download.getName() == null
                || !download.getName().toLowerCase().contains(searchText))) {
            return false;
        }
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
        double totalUpSpeed = 0;
        int totalSeeders = 0;
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
                totalUpSpeed += download.getUploadSpeed();
                totalSeeders += download.getSeeders();
                anyActive = true;
            }
            row++;
        }

        infoLabel.setLabel(downloads.size() + " download(s)");
        downSpeedLabel.setLabel(formatSize((long) totalDownSpeed) + "/s");
        upSpeedLabel.setLabel(totalUpSpeed > 0 ? formatSize((long) totalUpSpeed) + "/s" : "—");
        dhtStatusLabel.setLabel(totalSeeders > 0 ? "DHT: " + totalSeeders + " seed(s)" : "DHT: —");
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

    private void loadDetailTabs() {
        // Trackers
        trackersStore.clear();
        List<List<String>> trackers = downloadManager.getDownloadTrackers(selectedDownload);
        int tier = 0;
        for (List<String> urls : trackers) {
            for (String url : urls) {
                TreeIter iter = new TreeIter();
                trackersStore.append(iter);
                setStr(trackersStore, iter, 0, url);
                setStr(trackersStore, iter, 1, "tier " + tier);
                setStr(trackersStore, iter, 2, "—");
                setStr(trackersStore, iter, 3, "—");
                setStr(trackersStore, iter, 4, "—");
                setStr(trackersStore, iter, 5, "—");
            }
            tier++;
        }

        // Peers
        peersStore.clear();
        List<Map<String, Object>> peers = downloadManager.getDownloadPeers(selectedDownload);
        for (Map<String, Object> peer : peers) {
            TreeIter iter = new TreeIter();
            peersStore.append(iter);
            setStr(peersStore, iter, 0, String.valueOf(peer.getOrDefault("peerId", "—")));
            setStr(peersStore, iter, 1, String.valueOf(peer.getOrDefault("downloadSpeed", "—")));
            setStr(peersStore, iter, 2, String.valueOf(peer.getOrDefault("ip", "—"))
                    + ":" + peer.getOrDefault("port", ""));
            setStr(peersStore, iter, 3, String.valueOf(peer.getOrDefault("peChoking", false)));
        }

        // Files
        filesStore.clear();
        List<Map<String, Object>> files = downloadManager.getDownloadFiles(selectedDownload);
        for (Map<String, Object> file : files) {
            TreeIter iter = new TreeIter();
            filesStore.append(iter);
            setBool(filesStore, iter, 0, true);
            setStr(filesStore, iter, 1, String.valueOf(file.getOrDefault("path", "—")));
            setStr(filesStore, iter, 2, formatSize(parseLong(file.get("length"), 0)));
            setStr(filesStore, iter, 3, String.valueOf(progressPercent(
                    parseLong(file.get("completedLength"), 0), parseLong(file.get("length"), 1))));
            setStr(filesStore, iter, 4, "—");
        }
    }

    private static long parseLong(Object value, long fallback) {
        try {
            return value != null ? Long.parseLong(value.toString()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double progressPercent(long done, long total) {
        return total > 0 ? done * 100.0 / total : 0;
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
        infoHashValue.setLabel(selectedDownload.getInfoHash() != null ? selectedDownload.getInfoHash() : "—");
        folderValue.setLabel(selectedDownload.getDestination() != null ? selectedDownload.getDestination().toString() : "—");
        etaValue.setLabel(formatEta(selectedDownload));
        downloadedValue.setLabel(formatSize(selectedDownload.getDownloaded()));
        connectionsValue.setLabel(String.valueOf(selectedDownload.getConnectionCount()));
        seedsPeersValue.setLabel(selectedDownload.getSeeders() > 0
                ? selectedDownload.getSeeders() + " seed(s)"
                : "—");
        loadDetailTabs();
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

    private static void setBool(ListStore store, TreeIter iter, int column, boolean value) {
        Value v = new Value().init(Types.BOOLEAN);
        v.setBoolean(value);
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
