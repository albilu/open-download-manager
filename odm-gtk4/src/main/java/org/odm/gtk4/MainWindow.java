package org.odm.gtk4;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Button;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.PopoverMenuBar;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeModel;
import org.javagi.base.Out;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeView;

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
    private static final int HISTORY_WINDOW_SIZE = 500;
    private static final List<String> DOWNLOAD_COLUMN_LABELS = List.of(
            "#", "Status", "Name", "Completed", "Size", "Progress", "Elapsed",
            "Left", "Down Speed", "Up Speed", "Retry", "Start Date", "End Date", "Type");
    private static final Download.Status[] ALWAYS_VISIBLE_STATUSES = {
        Download.Status.CREATED, Download.Status.QUEUED, Download.Status.PAUSED,
        Download.Status.STARTING, Download.Status.CONNECTING, Download.Status.DOWNLOADING
    };

    private record RefreshSnapshot(List<Download> downloads, int totalCount,
            java.util.Map<Download.Status, Integer> statusCounts) {
    }
    private final ApplicationWindow window;
    private final ListStore statusStore;
    private final ListStore categoryStore;
    private final ListStore downloadsStore;
    private final TreeView statusTreeview;
    private final TreeView categoryTreeview;
    private final TreeView downloadsTreeview;
    private final GestureClick downloadContextClick;
    private final Label infoLabel;
    private final Label downSpeedLabel;
    private final Label upSpeedLabel;
    private final Label dhtStatusLabel;
    private final Spinner activitySpinner;
    private final ProgressBar infoProgressBar;
    private final Label totalSizeValue;
    private final Label addedOnValue;
    private final Label infoHashValue;
    private final Button folderOpenButton;
    private final Label folderValue;
    private final Image engineIcon;
    private final Label engineValue;
    private final Label etaValue;
    private final Label downloadedValue;
    private final Label connectionsValue;
    private final Label seedsPeersValue;
    private final org.gnome.gtk.Switch torSwitch;
    private final org.gnome.gtk.SearchEntry searchEntry;
    private final PopoverMenuBar menuBar;
    private final org.gnome.gtk.Widget leftPanelWidget;
    private final org.gnome.gtk.Widget infoPanelWidget;
    private final DownloadManager downloadManager;

    private final DownloadListPresenter listPresenter;
    final DetailTabsPresenter detailTabsPresenter;
    private final java.util.concurrent.ExecutorService backgroundExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean refreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshAgain =
            new java.util.concurrent.atomic.AtomicBoolean();

    // Listeners registered with core services; kept as fields so the window
    // can detach them on final close instead of leaking refresh work forever
    private DownloadListener windowDownloadListener;
    private org.manager.download.action.AfterCompletionActionListener windowCompletionListener;
    private org.manager.clipboard.ClipboardServiceListener windowClipboardListener;
    /** Optional app-provided exit sequence (graceful shutdown UI). */
    private Runnable finalCloseDelegate;
    private Download selectedDownload;
    private List<Download> selectedDownloads = List.of();
    /** Guard so menu actions register on the window only once. */
    private boolean menuActionsRegistered;
    private volatile boolean trayAvailable;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final ListStore filesStore;
    private final ListStore globalProgressStore;
    private final org.tor.TorService torService;
    private final org.manager.schedule.ScheduleManager scheduleManager;
    private final java.util.concurrent.atomic.AtomicLong torToggleEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean torDesiredRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<org.tor.TorLeakChecker> torLeakChecker =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** Builder reference kept for window-state persistence from menu actions. */
    private final GtkBuilder uiBuilder;

    public MainWindow(Application app, DownloadManager downloadManager, org.tor.TorService torService,
            org.manager.schedule.ScheduleManager scheduleManager) {
        this.downloadManager = downloadManager;
        this.torService = torService;
        this.scheduleManager = scheduleManager;

        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        this.uiBuilder = builder;

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
        this.globalProgressStore = Widgets.require(builder, "global_progress_store", ListStore.class);
        this.infoHashValue = Widgets.require(builder, "info_hash_v1_value", Label.class);
        this.folderOpenButton = Widgets.require(builder, "folder_open_button", Button.class);
        this.folderValue = Widgets.require(builder, "folder_value", Label.class);
        this.engineIcon = Widgets.require(builder, "engine_icon", Image.class);
        this.engineValue = Widgets.require(builder, "engine_value", Label.class);
        this.etaValue = Widgets.require(builder, "eta_value", Label.class);
        this.downloadedValue = Widgets.require(builder, "downloaded_value", Label.class);
        this.connectionsValue = Widgets.require(builder, "connections_value", Label.class);
        this.seedsPeersValue = Widgets.require(builder, "seeds_peers_value", Label.class);

        this.listPresenter = new DownloadListPresenter(statusStore, categoryStore,
                downloadsStore, globalProgressStore, statusTreeview, categoryTreeview,
                this::refresh);
        this.detailTabsPresenter = new DetailTabsPresenter(downloadManager, trackersStore,
                peersStore, filesStore, () -> selectedDownload);
        this.backgroundExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "odm-window-fetch");
            t.setDaemon(true);
            return t;
        });
        if (app != null) {
            window.setApplication(app);
        }

        restoreWindowState(builder);
        window.onCloseRequest(() -> {
            boolean toTray = downloadManager.getGlobalSettings().getBooleanProperty("ui.systemTray", false);
            if (toTray && trayAvailable) {
                saveWindowState(builder); // persist geometry; destroy() skips this handler
                window.setVisible(false);
                return true; // suppress the close; tray keeps the app running
            }
            saveWindowState(builder);
            removeWindowListeners();
            if (finalCloseDelegate != null) {
                // Hand the exit sequence to the app (graceful shutdown with
                // a progress dialog); it disposes the window when done
                window.setVisible(false);
                finalCloseDelegate.run();
                return true; // suppress the default close
            }
            return false; // allow close
        });

        statusTreeview.getSelection().onChanged(this::onStatusSelectionChanged);
        categoryTreeview.getSelection().onChanged(this::onCategorySelectionChanged);
        downloadsTreeview.getSelection().setMode(SelectionMode.MULTIPLE);
        downloadsTreeview.getSelection().onChanged(this::onDownloadSelectionChanged);
        downloadsTreeview.onRowActivated((path, column) -> onPropertiesClicked());

        // Torrent per-file selection: toggle a row -> apply aria2 select-file
        Widgets.require(builder, "files_selected_renderer", org.gnome.gtk.CellRendererToggle.class)
                .onToggled(this::onFileSelectionToggled);

        this.downloadContextClick = new GestureClick();
        downloadContextClick.setButton(3);
        // TreeView installs its own click gestures. Capture the secondary
        // press before a child renderer can consume it, then retarget the
        // selection to the row beneath the pointer.
        downloadContextClick.setPropagationPhase(PropagationPhase.CAPTURE);
        downloadContextClick.onPressed((nPress, x, y) -> showContextMenu(x, y));
        downloadsTreeview.addController(downloadContextClick);
        var contextKey = new EventControllerKey();
        contextKey.onKeyPressed((keyval, keycode, state) -> {
            boolean keyboardMenu = keyval == org.gnome.gdk.Gdk.KEY_Menu
                    || (keyval == org.gnome.gdk.Gdk.KEY_F10
                    && state.contains(org.gnome.gdk.ModifierType.SHIFT_MASK));
            if (keyboardMenu) {
                showContextMenuForSelection();
            }
            return keyboardMenu;
        });
        downloadsTreeview.addController(contextKey);

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
        folderOpenButton.onClicked(() -> openSelected("folder"));
        this.searchEntry = Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        searchEntry.onSearchChanged(this::onSearchChanged);
        // tor_switch: wired below
        this.torSwitch = Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        torSwitchSet(torService.isRunning());
        torSwitch.onStateSet(this::onTorToggled);
        this.menuBar = Widgets.require(builder, "menu_bar", PopoverMenuBar.class);
        this.leftPanelWidget = Widgets.require(builder, "left_panel", org.gnome.gtk.Widget.class);
        this.infoPanelWidget = Widgets.require(builder, "info_panel_box", org.gnome.gtk.Widget.class);
        menuBar.setMenuModel(buildMainMenu());
        AccessibilitySupport.label(statusTreeview, "Download status filters");
        AccessibilitySupport.label(categoryTreeview, "Download category filters");
        AccessibilitySupport.label(downloadsTreeview, "Downloads");
        AccessibilitySupport.label(searchEntry, "Search downloads");
        AccessibilitySupport.label(torSwitch, "Global Tor routing");
        AccessibilitySupport.label(menuBar, "Application menu");
        AccessibilitySupport.label(infoProgressBar, "Selected download progress");
        AccessibilitySupport.label(folderOpenButton, "Open selected download folder");
        Widgets.require(builder, "status_label", Label.class).setMnemonicWidget(statusTreeview);
        Widgets.require(builder, "category_label", Label.class).setMnemonicWidget(categoryTreeview);

        windowDownloadListener = new DownloadListener() {
            @Override public void onDownloadStart(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadPause(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadResume(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadComplete(Download d) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadError(Download d, String errorMessage) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadCanceled(Download d) { listPresenter.scheduleRefresh(); }
        };
        downloadManager.addDownloadListener(windowDownloadListener);

        // Surface after-completion action results (e.g. antivirus threats)
        // in the info bar; callbacks may arrive from worker threads.
        windowCompletionListener = new org.manager.download.action.AfterCompletionActionListener() {
            @Override
            public void onActionStart(Download d, org.manager.download.action.AfterCompletionAction a) {
                // no-op
            }

            @Override
            public void onActionComplete(Download d, org.manager.download.action.AfterCompletionAction a) {
                if (a instanceof org.manager.download.action.AntivirusCheckAction av) {
                    UiThread.marshal(() -> {
                        if (av.isThreatDetected()) {
                            AccessibilitySupport.status(infoLabel,
                                    "THREAT DETECTED in " + d.getName()
                                            + " — scan result: " + av.getScanResult(),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warning("Antivirus threat detected in " + d.getName()
                                    + ": " + av.getScanResult());
                        } else {
                            AccessibilitySupport.status(infoLabel,
                                    "Antivirus scan completed for " + d.getName());
                        }
                    });
                }
            }

            @Override
            public void onActionError(Download d, org.manager.download.action.AfterCompletionAction a,
                    String errorMessage, org.manager.download.action.AfterCompletionAction.Severity severity) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Completion action failed for " + d.getName() + ": " + errorMessage);
            }

            @Override
            public void onAllActionsComplete(Download d,
                    java.util.List<org.manager.download.action.AfterCompletionAction> successful,
                    java.util.List<org.manager.download.action.AfterCompletionAction> failed) {
                // no-op
            }
        };
        downloadManager.addAfterCompletionActionListener(windowCompletionListener);

        // Clipboard detection flow: in silent mode core creates QUEUED
        // downloads on its own; otherwise pop the new-download dialog with
        // the detected URL prefilled. Callbacks arrive on monitor threads,
        // so everything widget-touching goes through UiThread.
        try {
            windowClipboardListener = new org.manager.clipboard.ClipboardServiceListener() {
                @Override
                public void onUrlsDetected(java.util.List<java.net.URI> urls, String content) {
                    // handled by the silent/auto paths in ClipboardService
                }

                @Override
                public void onConfirmationRequired(java.util.List<java.net.URI> urls, String content) {
                    if (urls == null || urls.isEmpty()) {
                        return;
                    }
                    UiThread.marshal(() -> {
                        NewDownloadDialog dialog = new NewDownloadDialog(window, downloadManager,
                                () -> UiThread.marshal(MainWindow.this::refresh));
                        dialog.prefillUrl(urls.get(0).toString());
                        if (urls.size() > 1) {
                            LOGGER.info(urls.size() + " URLs detected; offering the first, "
                                    + "use Import from Clipboard for all");
                        }
                        dialog.present();
                    });
                }
            };
            downloadManager.getClipboardService().addServiceListener(windowClipboardListener);
            // Restore the persisted silent-mode flag into the core service
            applyClipboardSilentToCore(downloadManager.getGlobalSettings()
                    .getBooleanProperty("ui.clipboardSilent", false));
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Clipboard service listener registration failed", e);
        }

        refresh();
    }

    public void present() {
        window.present();
        downloadsTreeview.grabFocus();
    }

    /**
     * Installs an override for the app-exit sequence. When set, the final
     * close (window close or File &gt; Exit) hides the window and runs this
     * delegate instead of ending immediately — OdmApplication uses it to
     * show the shutdown progress dialog while the core shuts down
     * gracefully. The delegate must eventually call {@link #dispose()}.
     *
     * @param delegate the exit sequence, or null to restore default behavior
     */
    public void setFinalCloseDelegate(Runnable delegate) {
        this.finalCloseDelegate = delegate;
    }

    /** Controls whether closing to the notification area can keep the app reachable. */
    public void setTrayAvailable(boolean available) {
        this.trayAvailable = available;
    }

    /** Really destroys the window (bypasses the close-request handler). */
    public void dispose() {
        shutdownBackgroundWork();
        window.destroy();
    }

    /**
     * Stops the window-owned executors (detail-tab fetches and background
     * I/O). Idempotent; part of every real teardown path.
     */
    private void shutdownBackgroundWork() {
        torDesiredRunning.set(false);
        torToggleEpoch.incrementAndGet();
        shutdownTorLeakChecker();
        if (contextMenu != null) {
            contextMenu.dispose();
            contextMenu = null;
        }
        detailTabsPresenter.shutdown();
        backgroundExecutor.shutdown();
    }

    /**
     * Detaches every listener this window registered with core services.
     * Called from the final close path; without it the manager keeps
     * dispatching refresh work to a dead window forever.
     */
    private void removeWindowListeners() {
        shutdownBackgroundWork();
        if (windowDownloadListener != null) {
            downloadManager.removeDownloadListener(windowDownloadListener);
            windowDownloadListener = null;
        }
        if (windowCompletionListener != null) {
            downloadManager.removeAfterCompletionActionListener(windowCompletionListener);
            windowCompletionListener = null;
        }
        if (windowClipboardListener != null) {
            try {
                downloadManager.getClipboardService().removeServiceListener(windowClipboardListener);
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.WARNING,
                        "Failed to remove clipboard service listener", e);
            }
            windowClipboardListener = null;
        }
    }

    private void onAddClicked() {
        new NewDownloadDialog(window, downloadManager, () -> UiThread.marshal(this::refresh)).present();
    }

    private void onNewMediaClicked() {
        new NewMediaDialog(window, downloadManager, () -> UiThread.marshal(this::refresh)).present();
    }

    /**
     * Pushes the silent-mode flag into the core clipboard service so
     * detected URLs are placed in QUEUED status instead of popping dialogs.
     */
    private void applyClipboardSilentToCore(boolean silent) {
        try {
            org.manager.clipboard.ClipboardSettings current =
                    downloadManager.getClipboardService().getSettings();
            downloadManager.updateClipboardSettings(current.copy().setSilentMode(silent));
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING,
                    "Failed to apply clipboard silent mode", e);
        }
    }

    private void onSettingsClicked() {
        new SettingsDialog(window, downloadManager, scheduleManager, active -> {
            applyTorPreference(active);
            // Rebuild settings-backed actions (subtitles, antivirus, custom)
            // so changes apply without requiring a restart or re-selection.
            setCompletionAction(CompletionActionPolicy.forChoice(
                    completionActionKey(), downloadManager.getGlobalSettings()));
        }).present();
    }

    /** Applies a Tor preference changed in Settings while preserving any explicit proxy selected there. */
    private void applyTorPreference(boolean active) {
        torSwitchSet(active);
        if (active) {
            onTorToggled(true);
        } else {
            torDesiredRunning.set(false);
            torToggleEpoch.incrementAndGet();
            shutdownTorLeakChecker();
            torService.stop();
            downloadManager.applyGlobalSettingsToActiveDownloads();
            LOGGER.info("Tor stopped; Settings proxy preference retained");
        }
    }

    private void onSearchChanged() {
        listPresenter.setSearchText(searchEntry.getText().strip().toLowerCase());
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

    /** Requires an explicit destructive confirmation and captures the target
     * before showing the asynchronous dialog so a later selection change
     * cannot delete a different download's files. */
    private void confirmDeleteWithFiles(Download target) {
        if (target == null) {
            return;
        }
        org.gnome.gtk.MessageDialog confirmation = new org.gnome.gtk.MessageDialog();
        confirmation.setTransientFor(window);
        confirmation.setModal(true);
        confirmation.setMarkup("<b>Delete this download and its files?</b>");
        confirmation.formatSecondaryText("This permanently removes files for \"%s\"."
                .formatted(target.getName()));
        int cancelResponse = org.gnome.gtk.ResponseType.CANCEL.getValue();
        int acceptResponse = org.gnome.gtk.ResponseType.ACCEPT.getValue();
        confirmation.addButton("Cancel", cancelResponse);
        org.gnome.gtk.Widget deleteButton =
                confirmation.addButton("Delete Files", acceptResponse);
        deleteButton.addCssClass("destructive-action");
        confirmation.setDefaultResponse(cancelResponse);
        confirmation.onResponse(response -> {
            confirmation.close();
            if (response != acceptResponse) {
                return;
            }
            downloadManager.cancelDownload(target, true).whenComplete((ignored, error) ->
                    UiThread.marshal(() -> {
                        if (error != null) {
                            AccessibilitySupport.status(infoLabel,
                                    "Could not delete download files: "
                                            + failureMessage(error),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                        }
                        refresh();
                    }));
        });
        confirmation.present();
    }

    private void onPropertiesClicked() {
        onDownloadSelectionChanged();
        if (!selectedDownloads.isEmpty()) {
            new PropertyDialog(window, downloadManager, selectedDownloads).present();
        }
    }

    /** Context menu built once and reused; the actions re-read the selection at click time. */
    private PopupMenu contextMenu;

    private void showContextMenu(double x, double y) {
        Out<org.gnome.gtk.TreeViewColumn> column = new Out<>();
        TreePath path = pathAtWidgetPosition(downloadsTreeview, (int) x, (int) y, column);
        if (path == null) {
            return;
        }
        selectContextTarget(downloadsTreeview, path, column.get());
        onDownloadSelectionChanged();
        if (selectedDownload == null) return;
        showContextMenuAt((int) x, (int) y);
    }

    /**
     * Resolves a gesture position to a row. GestureClick reports widget
     * coordinates, while TreeView row lookup expects bin-window coordinates;
     * the difference is the header offset and is most visible on row zero.
     */
    static TreePath pathAtWidgetPosition(TreeView tree, int widgetX, int widgetY,
            Out<org.gnome.gtk.TreeViewColumn> column) {
        Out<Integer> binX = new Out<>();
        Out<Integer> binY = new Out<>();
        tree.convertWidgetToBinWindowCoords(widgetX, widgetY, binX, binY);
        Out<TreePath> path = new Out<>();
        if (!tree.getPathAtPos(binX.get(), binY.get(), path, column,
                new Out<>(), new Out<>())) {
            return null;
        }
        return path.get();
    }

    private void showContextMenuForSelection() {
        onDownloadSelectionChanged();
        if (selectedDownload == null) {
            return;
        }
        Out<TreePath> path = new Out<>();
        Out<org.gnome.gtk.TreeViewColumn> column = new Out<>();
        downloadsTreeview.getCursor(path, column);
        var area = new org.gnome.gdk.Rectangle();
        if (path.get() != null) {
            downloadsTreeview.getCellArea(path.get(), column.get(), area);
        }
        showContextMenuAt(area.readX() + Math.max(1, area.readWidth() / 2),
                area.readY() + Math.max(1, area.readHeight() / 2));
    }

    /**
     * Makes an unselected row the sole context target while preserving an
     * already-selected multi-row group when the click lands inside it.
     */
    static void selectContextTarget(TreeView tree, TreePath path,
            org.gnome.gtk.TreeViewColumn column) {
        TreeSelection selection = tree.getSelection();
        if (!selection.pathIsSelected(path)) {
            selection.unselectAll();
            tree.setCursor(path, column, false);
        }
        tree.grabFocus();
    }

    private void showContextMenuAt(int x, int y) {
        // 1:1 port of download_context_menu from the original glade
        if (contextMenu == null) {
            contextMenu = new PopupMenu()
                    .add("Open", () -> openSelected("file"))
                    .add("Open Folder", () -> openSelected("folder"))
                    .separator()
                    .add("Pause", this::onPauseClicked)
                    .add("Resume", this::onResumeClicked)
                    .add("Start", () -> downloadManager.startDownload(selectedDownload))
                    .separator()
                    .add("Copy Magnet URI", this::copyMagnetUri)
                    .add("Change Destination…", this::changeDestination)
                    .add("Verify Data", this::verifyData)
                    .add("Properties", this::onPropertiesClicked)
                    .separator()
                    .add("Delete", this::onDeleteClicked)
                    .add("Delete with Files", () ->
                            confirmDeleteWithFiles(selectedDownload));
        }
        contextMenu.popupAt(downloadsTreeview, x, y);
    }

    /** Copies the selected download's magnet URI (or builds one from its info hash). */
    private void copyMagnetUri() {
        if (selectedDownload == null) {
            return;
        }
        String magnet = null;
        try {
            if ("magnet".equals(selectedDownload.getUri().getScheme())) {
                magnet = selectedDownload.getUri().toString();
            }
        } catch (Exception ignored) {
            // no uri
        }
        if (magnet == null && selectedDownload.getInfoHash() != null) {
            magnet = "magnet:?xt=urn:btih:" + selectedDownload.getInfoHash();
        }
        if (magnet != null) {
            downloadsTreeview.getClipboard().setText(magnet);
            AccessibilitySupport.status(infoLabel, "Magnet URI copied");
        }
    }

    /** Changes the destination folder of the selected download. */
    private void changeDestination() {
        if (selectedDownload == null) {
            return;
        }
        Download targetDownload = selectedDownload;
        if (!canChangeDestination(targetDownload)) {
            AccessibilitySupport.status(infoLabel,
                    "Destination can only be changed before a download starts");
            return;
        }
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        dialog.setTitle("Select new destination");
        dialog.selectFolder(window, null, result -> {
            try {
                org.gnome.gio.File folder = dialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null
                        && canChangeDestination(targetDownload)) {
                    targetDownload.setDestination(java.nio.file.Path.of(folder.getPath().toString()));
                    UiThread.marshal(this::refresh);
                }
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "Destination change cancelled or failed", e);
            }
        });
    }

    private static boolean canChangeDestination(Download download) {
        return download != null
                && (download.getStatus() == Download.Status.CREATED
                        || download.getStatus() == Download.Status.QUEUED)
                && download.getGid() == null
                && download.getAttemptGeneration() == 0
                && download.getOutputPaths().isEmpty();
    }

    /** Requests an integrity re-check of the selected download (aria2). */
    private void verifyData() {
        if (selectedDownload == null) {
            return;
        }
        if (selectedDownload.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setOption("check-integrity", "true");
            downloadManager.changeSettings(selectedDownload);
            AccessibilitySupport.status(infoLabel, "Integrity check requested");
        }
    }

    /**
     * Builds the full main menu as a Gio.Menu — 1:1 port of the original
     * menu bar (File/Edit/View/Download/Help with submenus, toggles, and
     * radio items), shown in GTK4's classic horizontal PopoverMenuBar.
     */
    private org.gnome.gio.Menu buildMainMenu() {
        registerMenuActions();
        org.gnome.gio.Menu menu = new org.gnome.gio.Menu();

        // File
        org.gnome.gio.Menu file = new org.gnome.gio.Menu();
        file.append("New Download", "win.new-download");
        file.append("New Media Download", "win.new-media");
        file.append("New Website Scrape", "win.scrape");
        org.gnome.gio.Menu batch = new org.gnome.gio.Menu();
        batch.append("Import from Clipboard", "win.import-clipboard");
        batch.append("Import URL Sequence", "win.import-sequence");
        batch.append("Import from Text File", "win.import-file");
        batch.append("Import from HTML File", "win.import-html");
        batch.append("Import Links from Remote", "win.import-remote-html");
        batch.append("Export Download List", "win.export-file");
        file.appendSubmenu("Batch Process", batch);
        file.append("Offline Mode", "win.offline");
        file.append("Exit", "win.quit");
        menu.appendSubmenu("_File", file);

        // Edit
        org.gnome.gio.Menu edit = new org.gnome.gio.Menu();
        edit.append("Clipboard Monitoring", "win.clipboard-monitoring");
        edit.append("Silent Mode", "win.clipboard-silent");
        org.gnome.gio.Menu completion = new org.gnome.gio.Menu();
        completion.append("None", "win.completion::none");
        completion.append("Notify (sound)", "win.completion::notify");
        completion.append("Antivirus Scan", "win.completion::antivirus");
        completion.append("Download Subtitles", "win.completion::subtitles");
        completion.append("Suspend", "win.completion::suspend");
        completion.append("Shutdown", "win.completion::shutdown");
        completion.append("Custom…", "win.completion::custom");
        edit.appendSubmenu("Completion Actions", completion);
        org.gnome.gio.Menu schedule = new org.gnome.gio.Menu();
        schedule.append("Always", "win.schedule::always");
        schedule.append("Business Hours", "win.schedule::business");
        schedule.append("Night Hours", "win.schedule::night");
        schedule.append("Weekends", "win.schedule::weekend");
        schedule.append("Weekdays", "win.schedule::weekday");
        schedule.append("Never (paused)", "win.schedule::never");
        edit.appendSubmenu("Schedule", schedule);
        edit.append("New Tor Identity", "win.tor-new-identity");
        edit.append("Preferences", "win.preferences");
        menu.appendSubmenu("_Edit", edit);

        // View
        org.gnome.gio.Menu view = new org.gnome.gio.Menu();
        view.append("Left Panel", "win.left-panel");
        view.append("Info Panel", "win.info-panel");
        org.gnome.gio.Menu columns = new org.gnome.gio.Menu();
        for (int i = 0; i < DOWNLOAD_COLUMN_LABELS.size(); i++) {
            columns.append(DOWNLOAD_COLUMN_LABELS.get(i), "win.col-" + i);
        }
        view.appendSubmenu("Columns", columns);
        menu.appendSubmenu("_View", view);

        // Download
        org.gnome.gio.Menu download = new org.gnome.gio.Menu();
        download.append("Open", "win.open-file");
        download.append("Open Folder", "win.open-folder");
        download.append("Force Download", "win.force-download");
        download.append("Pause All", "win.pause-all");
        download.append("Resume All", "win.resume-all");
        download.append("Delete", "win.delete");
        download.append("Delete with Files", "win.delete-with-files");
        download.append("Remove All Finished", "win.remove-finished");
        download.append("Properties", "win.properties");
        menu.appendSubmenu("_Download", download);

        // Help
        org.gnome.gio.Menu help = new org.gnome.gio.Menu();
        help.append("Statistics", "win.statistics");
        help.append("Donation", "win.donation");
        help.append("About", "win.about");
        menu.appendSubmenu("_Help", help);

        return menu;
    }

    /** Registers all menu actions on the window (ApplicationWindow is an ActionMap). */
    private void registerMenuActions() {
        if (menuActionsRegistered) {
            return;
        }
        menuActionsRegistered = true;

        // File
        addAction("new-download", this::onAddClicked);
        addAction("new-media", this::onNewMediaClicked);
        addAction("scrape", this::onScraperClicked);
        addAction("import-clipboard", () -> downloadManager.importFromClipboard()
                .thenRun(() -> UiThread.marshal(this::refresh)));
        addAction("import-sequence", () -> new ImportSequenceDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh)).present());
        addAction("import-file", () -> ImportListDialog.chooseAndPresent(window, downloadManager,
                () -> UiThread.marshal(this::refresh)));
        addAction("import-html", this::onImportHtml);
        addAction("import-remote-html", this::onImportRemoteHtml);
        addAction("export-file", this::onExportList);
        addStatefulAction("offline",
                downloadManager.getGlobalSettings().getBooleanProperty("ui.offline", false),
                active -> {
                    downloadManager.getGlobalSettings().setProperty("ui.offline", String.valueOf(active));
                    downloadManager.getGlobalSettings().save();
                    if (active) {
                        downloadManager.pauseAllDownloads();
                    } else {
                        downloadManager.resumeAllDownloads();
                    }
                    UiThread.marshal(this::refresh);
                });
        // File -> Exit performs a normal exit (destroy() bypasses the
        // close-request handler), so geometry must be saved explicitly first.
        addAction("quit", () -> {
            saveWindowState(uiBuilder);
            removeWindowListeners();
            if (finalCloseDelegate != null) {
                window.setVisible(false);
                finalCloseDelegate.run();
            } else {
                window.destroy();
            }
        });

        // Edit
        addStatefulAction("clipboard-monitoring", downloadManager.isClipboardMonitoringEnabled(),
                downloadManager::setClipboardMonitoringEnabled);
        addStatefulAction("clipboard-silent",
                downloadManager.getGlobalSettings().getBooleanProperty("ui.clipboardSilent", false),
                active -> {
                    downloadManager.getGlobalSettings().setProperty("ui.clipboardSilent",
                            String.valueOf(active));
                    downloadManager.getGlobalSettings().save();
                    applyClipboardSilentToCore(active);
                });
        addRadioAction("completion", completionActionKey(), this::onCompletionActionChosen);
        // Rebuild the completion action from the persisted key: GTK only
        // fires radio-action activate on user selection, not at creation.
        onCompletionActionChosen(completionActionKey(), false);
        addRadioAction("schedule",
                downloadManager.getGlobalSettings().getProperty("scheduler.preset", "always"),
                this::applySchedulePreset);
        addAction("preferences", this::onSettingsClicked);
        addAction("tor-new-identity", this::onTorNewIdentity);

        // View
        addStatefulAction("left-panel", true, leftPanelWidget::setVisible);
        addStatefulAction("info-panel", true, infoPanelWidget::setVisible);
        var columns = downloadsTreeview.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            final int index = i;
            addStatefulAction("col-" + i, true, active -> columns.get(index).setVisible(active));
        }

        // Download
        addAction("open-file", () -> openSelected("file"));
        addAction("open-folder", () -> openSelected("folder"));
        addAction("force-download", () -> {
            onDownloadSelectionChanged();
            if (selectedDownload != null) {
                downloadManager.startDownload(selectedDownload);
            }
        });
        addAction("pause-all", () -> downloadManager.pauseAllDownloads()
                .thenRun(() -> UiThread.marshal(this::refresh)));
        addAction("resume-all", () -> downloadManager.resumeAllDownloads()
                .thenRun(() -> UiThread.marshal(this::refresh)));
        addAction("delete", this::onDeleteClicked);
        addAction("delete-with-files", () -> {
            onDownloadSelectionChanged();
            if (selectedDownload != null) {
                confirmDeleteWithFiles(selectedDownload);
            }
        });
        addAction("remove-finished", () -> downloadManager.pruneCompletedDownloads(java.time.Duration.ZERO)
                .thenRun(() -> UiThread.marshal(this::refresh)));
        addAction("properties", this::onPropertiesClicked);

        // Help
        addAction("statistics", this::showStatistics);
        addAction("donation", () -> org.gnome.gtk.Gtk.showUri(window,
                "https://github.com/albilu/odm", 0));
        addAction("about", () -> AboutDialogPresenter.present(window));
    }

    private void addAction(String name, Runnable handler) {
        org.gnome.gio.SimpleAction action = new org.gnome.gio.SimpleAction(name, null);
        action.onActivate(parameter -> handler.run());
        window.addAction(action);
    }

    private void addStatefulAction(String name, boolean initial,
            java.util.function.Consumer<Boolean> onToggle) {
        org.gnome.gio.SimpleAction action = org.gnome.gio.SimpleAction.stateful(name, null,
                org.gnome.glib.Variant.boolean_(initial));
        action.onActivate(parameter -> {
            boolean newState = !action.getState().getBoolean();
            action.setState(org.gnome.glib.Variant.boolean_(newState));
            onToggle.accept(newState);
        });
        window.addAction(action);
    }

    private void addRadioAction(String name, String initial,
            java.util.function.Consumer<String> onChoice) {
        org.gnome.gio.SimpleAction action = org.gnome.gio.SimpleAction.stateful(name, null,
                org.gnome.glib.Variant.string(initial));
        action.onActivate(parameter -> {
            String choice = parameter != null ? parameter.dupString(new org.javagi.base.Out<>()) : initial;
            action.setState(org.gnome.glib.Variant.string(choice));
            onChoice.accept(choice);
        });
        window.addAction(action);
    }

    private String completionActionKey() {
        return downloadManager.getGlobalSettings().getProperty("ui.completionAction", "none");
    }

    private void onCompletionActionChosen(String choice) {
        onCompletionActionChosen(choice, true);
    }

    /**
     * @param choice the completion action key
     * @param interactive when true, choosing "custom" with no stored command
     *                    opens the command-entry prompt; the startup rebuild
     *                    passes false
     */
    private void onCompletionActionChosen(String choice, boolean interactive) {
        downloadManager.getGlobalSettings().setProperty("ui.completionAction", choice);
        downloadManager.getGlobalSettings().save();
        if ("custom".equals(choice) && interactive) {
            promptForCustomCommand();
            return;
        }
        setCompletionAction(
                CompletionActionPolicy.forChoice(choice, downloadManager.getGlobalSettings()));
    }

    /**
     * Asks for (or reuses) the custom completion command and installs the
     * {@link org.manager.download.action.ExecuteCommandAction}. The command
     * persists as {@code ui.completionCommand} so it survives restarts; the
     * startup rebuild (no user gesture) silently reuses the stored value.
     */
    private void promptForCustomCommand() {
        String saved = downloadManager.getGlobalSettings().getProperty("ui.completionCommand", "");
        if (saved.isBlank()) {
            org.gnome.gtk.Window prompt = new org.gnome.gtk.Window();
            prompt.setTitle("Custom completion command");
            prompt.setModal(true);
            prompt.setTransientFor(window);
            prompt.setDefaultSize(520, -1);

            org.gnome.gtk.Box box = new org.gnome.gtk.Box(org.gnome.gtk.Orientation.VERTICAL, 10);
            box.setMarginTop(10);
            box.setMarginBottom(10);
            box.setMarginStart(10);
            box.setMarginEnd(10);

            org.gnome.gtk.Label help = new org.gnome.gtk.Label(
                    "Command to run on completion. Variables: "
                    + "{file_path} {filename} {dir} {url} {id} {gid}");
            help.setWrap(true);
            box.append(help);

            org.gnome.gtk.Entry entry = new org.gnome.gtk.Entry();
            entry.setPlaceholderText("mv {file_path} /tmp");
            box.append(entry);

            org.gnome.gtk.Box buttons = new org.gnome.gtk.Box(org.gnome.gtk.Orientation.HORIZONTAL, 6);
            buttons.setHalign(org.gnome.gtk.Align.END);
            org.gnome.gtk.Button cancel = org.gnome.gtk.Button.withLabel("Cancel");
            org.gnome.gtk.Button ok = org.gnome.gtk.Button.withLabel("Save");
            ok.addCssClass("suggested-action");
            buttons.append(cancel);
            buttons.append(ok);
            box.append(buttons);

            prompt.setChild(box);
            Runnable apply = () -> {
                String command = entry.getText().strip();
                if (!command.isBlank()) {
                    downloadManager.getGlobalSettings().setProperty("ui.completionCommand", command);
                    downloadManager.getGlobalSettings().save();
                    setCompletionAction(new org.manager.download.action.ExecuteCommandAction(command));
                } else {
                    // Empty command: leave no completion action configured
                    setCompletionAction(null);
                }
                prompt.close();
            };
            cancel.onClicked(() -> prompt.close());
            ok.onClicked(apply::run);
            entry.onActivate(apply::run);
            prompt.present();
        } else {
            setCompletionAction(new org.manager.download.action.ExecuteCommandAction(saved));
        }
    }

    /** Opens the selected download's file or its folder with xdg-open. */
    private void openSelected(String what) {
        onDownloadSelectionChanged();
        if (selectedDownload == null || selectedDownload.getDestination() == null) {
            return;
        }
        try {
            java.nio.file.Path target = "folder".equals(what)
                    ? selectedDownload.getDestination()
                    : selectedDownload.getPrimaryOutputPath();
            if (target == null) {
                return;
            }
            new ProcessBuilder("xdg-open", target.toString()).inheritIO().start();
        } catch (Exception e) {
            LOGGER.warning("Failed to open " + what + ": " + e.getMessage());
        }
    }

    /** Imports links found in a local HTML file. */
    private void onImportHtml() {
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        dialog.setTitle("Select HTML file");
        dialog.open(window, null, result -> {
            try {
                org.gnome.gio.File file = dialog.openFinish(result);
                if (file == null || file.getPath() == null) {
                    return;
                }
                java.nio.file.Path path = java.nio.file.Path.of(file.getPath().toString());
                // File I/O and regex over arbitrarily large documents run OFF
                // the GTK main loop; only the result goes back to the UI
                CompletableFuture.supplyAsync(
                        () -> HtmlImportExport.importHtmlFile(path, downloadManager),
                        backgroundExecutor).thenAccept(count -> {
                    if (count == null || count < 0) {
                        return;
                    }
                    UiThread.marshal(() -> {
                        AccessibilitySupport.status(infoLabel,
                                "Imported " + count + " link(s) from HTML");
                        refresh();
                    });
                });
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "HTML import cancelled or failed", e);
            }
        });
    }

    /** Fetches a remote HTML page and imports the links found in it. */
    private void onImportRemoteHtml() {
        org.gnome.gtk.Window prompt = new org.gnome.gtk.Window();
        prompt.setTitle("Import Links from Remote");
        prompt.setModal(true);
        prompt.setTransientFor(window);
        prompt.setDefaultSize(560, -1);

        org.gnome.gtk.Box box = new org.gnome.gtk.Box(
                org.gnome.gtk.Orientation.VERTICAL, 10);
        box.setMarginTop(16);
        box.setMarginBottom(16);
        box.setMarginStart(16);
        box.setMarginEnd(16);

        org.gnome.gtk.Label help = new org.gnome.gtk.Label(
                "Enter an HTTP(S) page. Up to 1,000 links are imported; "
                + "relative links use the page's final address after redirects.");
        help.setWrap(true);
        org.gnome.gtk.Entry sourceEntry = new org.gnome.gtk.Entry();
        sourceEntry.setPlaceholderText("https://example.com/downloads.html");
        AccessibilitySupport.label(sourceEntry, "Remote HTML page URL");
        org.gnome.gtk.Label status = new org.gnome.gtk.Label("");

        org.gnome.gtk.Button cancel = org.gnome.gtk.Button.withLabel("Cancel");
        org.gnome.gtk.Button importButton = org.gnome.gtk.Button.withLabel("Import Links");
        importButton.addCssClass("suggested-action");
        Runnable startImport = () -> {
            org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
            if (settings.getBooleanProperty("ui.offline", false)) {
                AccessibilitySupport.status(status,
                        "Remote import is unavailable while Offline Mode is enabled",
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                return;
            }
            final java.net.URI source;
            try {
                source = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(
                        sourceEntry.getText().strip());
                if (!("http".equalsIgnoreCase(source.getScheme())
                        || "https".equalsIgnoreCase(source.getScheme()))) {
                    throw new IllegalArgumentException("Only HTTP(S) pages are supported");
                }
            } catch (Exception invalid) {
                AccessibilitySupport.status(status, "Enter a valid HTTP(S) page URL",
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                return;
            }
            String proxy = settings.isGlobalProxyEnabled()
                    ? settings.getGlobalProxyAddress() : null;
            importButton.setSensitive(false);
            sourceEntry.setSensitive(false);
            AccessibilitySupport.status(status, "Fetching page and importing links…");
            CompletableFuture.supplyAsync(() -> HtmlImportExport.importRemoteHtml(
                    source, downloadManager, proxy), backgroundExecutor)
                    .whenComplete((count, error) -> UiThread.marshal(() -> {
                        if (error != null || count == null || count < 0) {
                            importButton.setSensitive(true);
                            sourceEntry.setSensitive(true);
                            AccessibilitySupport.status(status,
                                    "Could not import this remote HTML page",
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            return;
                        }
                        AccessibilitySupport.status(infoLabel,
                                "Imported " + count + " link(s) from remote HTML");
                        refresh();
                        prompt.close();
                    }));
        };
        cancel.onClicked(prompt::close);
        importButton.onClicked(startImport::run);
        sourceEntry.onActivate(startImport::run);

        box.append(help);
        box.append(sourceEntry);
        box.append(status);
        org.gnome.gtk.Box buttons = new org.gnome.gtk.Box(
                org.gnome.gtk.Orientation.HORIZONTAL, 8);
        buttons.setHalign(org.gnome.gtk.Align.END);
        buttons.append(cancel);
        buttons.append(importButton);
        box.append(buttons);
        prompt.setChild(box);
        prompt.present();
        sourceEntry.grabFocus();
    }

    /** Exports all download URLs to a text file. */
    private void onExportList() {
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        dialog.setTitle("Export download list");
        dialog.setInitialName("odm-downloads.txt");
        dialog.save(window, null, result -> {
            try {
                org.gnome.gio.File file = dialog.saveFinish(result);
                if (file == null || file.getPath() == null) {
                    return;
                }
                java.nio.file.Path path = java.nio.file.Path.of(file.getPath().toString());
                // Snapshot the URL list on the GTK thread (model access),
                // write the file off it
                String contents = HtmlImportExport.exportText(downloadManager.getAllDownloads());
                CompletableFuture.runAsync(() -> {
                    try {
                        HtmlImportExport.writeText(path, contents);
                        UiThread.marshal(() -> AccessibilitySupport.status(
                                infoLabel, "Exported download list"));
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.FINE, "Export failed", e);
                    }
                }, backgroundExecutor);
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "Export cancelled or failed", e);
            }
        });
    }

    /** Shows a small statistics dialog (counts by status, total sizes). */
    private void showStatistics() {
        StatisticsPresenter.Stats st = StatisticsPresenter.aggregate(downloadManager.getAllDownloads());
        org.gnome.gtk.MessageDialog stats = new org.gnome.gtk.MessageDialog();
        stats.setTransientFor(window);
        stats.setModal(true);
        stats.setMarkup("<b>Download Statistics</b>");
        stats.formatSecondaryText("Total: " + st.total() + "\nActive: " + st.active()
                + "\nQueued/paused: " + st.queued() + "\nFinished: " + st.finished()
                + "\nErrors: " + st.errors() + "\n\nDownloaded: " + DownloadFormats.size(st.doneSize())
                + " / " + DownloadFormats.size(st.totalSize()));
        stats.present();
    }

    private void applySchedulePreset(String preset) {
        scheduleManager.setGlobalPresetSchedule(preset);
        downloadManager.getGlobalSettings().setProperty("scheduler.preset", preset);
        // A selected preset replaces a previously configured hour grid;
        // otherwise startup gives the stale grid precedence and silently
        // loses the user's latest menu choice.
        downloadManager.getGlobalSettings().setProperty("scheduler.grid", "");
        downloadManager.getGlobalSettings().setProperty("scheduler.enabled", "true");
        downloadManager.getGlobalSettings().save();
        scheduleManager.start().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.log(Level.WARNING, "Failed to start scheduler for preset " + preset, error);
                UiThread.marshal(() -> AccessibilitySupport.status(
                        infoLabel, "Could not start download scheduler"));
            } else {
                LOGGER.info("Schedule preset applied: " + preset);
            }
        });
    }

    private void setCompletionAction(org.manager.download.action.AfterCompletionAction action) {
        downloadManager.setGlobalAfterCompletionAction(action);
        LOGGER.info("After-completion action set to: "
                + (action == null ? "none" : action.getClass().getSimpleName()));
    }

    private boolean onTorToggled(boolean active) {
        long epoch = torToggleEpoch.incrementAndGet();
        torDesiredRunning.set(active);
        if (active) {
            torService.start().whenComplete((ok, error) -> {
                if (epoch != torToggleEpoch.get() || !torDesiredRunning.get()) {
                    // The user switched Tor off while startup was pending.
                    // A late successful start must not resurrect the proxy.
                    if (Boolean.TRUE.equals(ok)) {
                        torService.stop();
                    }
                    return;
                }
                if (Boolean.TRUE.equals(ok)) {
                    LOGGER.info("Tor started; downloads can route via SOCKS5 127.0.0.1:9050");
                    downloadManager.getGlobalSettings().setGlobalProxyEnabled(true);
                    downloadManager.getGlobalSettings().setGlobalProxyAddress(
                            "socks5h://127.0.0.1:" + torService.getSocksPort());
                    downloadManager.getGlobalSettings().setProperty("tor.enabled", "true");
                    downloadManager.getGlobalSettings().save();
                    // Reconfigure running downloads to use the new proxy
                    downloadManager.applyGlobalSettingsToActiveDownloads();
                    verifyTorCircuit(epoch);
                } else {
                    LOGGER.warning("Tor failed to start"
                            + (error != null ? ": " + error.getMessage() : ""));
                    downloadManager.getGlobalSettings().setProperty("tor.enabled", "false");
                    // Keep the dead SOCKS endpoint enabled so a failed Tor
                    // launch cannot turn an intended private transfer into a
                    // direct one.
                    downloadManager.getGlobalSettings().setGlobalProxyEnabled(true);
                    downloadManager.getGlobalSettings().setGlobalProxyAddress(
                            "socks5h://127.0.0.1:" + torService.getSocksPort());
                    downloadManager.getGlobalSettings().save();
                    downloadManager.applyGlobalSettingsToActiveDownloads();
                    torSwitchSet(false);
                }
            });
        } else {
            shutdownTorLeakChecker();
            downloadManager.getGlobalSettings().setGlobalProxyEnabled(false);
            downloadManager.getGlobalSettings().setProperty("tor.enabled", "false");
            downloadManager.getGlobalSettings().save();
            // Clear the proxy from running downloads
            downloadManager.applyGlobalSettingsToActiveDownloads();
            torService.stop();
            LOGGER.info("Tor stopped");
        }
        return false; // let the switch apply its new state
    }

    /**
     * Runs the Tor leak checker (IP + DNS + exit-node verification) in the
     * background once Tor reports ready and surfaces the verdict in the info
     * bar. This is the user-facing wiring of TorLeakChecker: without it the
     * SOCKS port being open says nothing about actual circuit health.
     */
    private void verifyTorCircuit(long epoch) {
        org.tor.TorLeakChecker checker = new org.tor.TorLeakChecker(
                "127.0.0.1", torService.getSocksPort(), 5000, 5000);
        org.tor.TorLeakChecker previous = torLeakChecker.getAndSet(checker);
        if (previous != null) {
            previous.shutdown();
        }
        checker.performLeakCheck()
                .whenComplete((result, error) -> {
                    try {
                        checker.shutdown();
                    } catch (Exception ignore) {
                        // shutdown is best-effort
                    }
                    torLeakChecker.compareAndSet(checker, null);
                    if (epoch != torToggleEpoch.get() || !torDesiredRunning.get()) {
                        return;
                    }
                    String message;
                    if (error != null) {
                        message = "Tor leak check failed: " + error.getMessage();
                    } else if (result == null) {
                        message = "Tor leak check returned no result";
                    } else {
                        message = (result.isSecure ? "Tor secure — " : "TOR LEAK CHECK FAILED — ")
                                + result.message;
                    }
                    LOGGER.info("Tor leak check: " + message);
                    UiThread.marshal(() -> AccessibilitySupport.status(infoLabel, message));
                });
    }

    private void shutdownTorLeakChecker() {
        org.tor.TorLeakChecker checker = torLeakChecker.getAndSet(null);
        if (checker != null) {
            try {
                checker.shutdown();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to stop Tor leak checker", e);
            }
        }
    }

    /**
     * Requests a new Tor circuit (NEWNYM) through the control port. Wired to
     * the "New Tor Identity" menu action; no-op with an info-bar notice when
     * Tor is not running or the control port is unavailable.
     */
    private void onTorNewIdentity() {
        if (!torService.isRunning()) {
            AccessibilitySupport.status(infoLabel, "Tor is not running");
            return;
        }
        int controlPort;
        try {
            controlPort = torService.getControlPort();
        } catch (Exception e) {
            controlPort = 9051;
        }
        org.tor.TorController controller = new org.tor.TorController(
                "127.0.0.1", controlPort, "", 5000);
        controller.connect()
                .thenCompose(connected -> connected
                        ? controller.changeIp()
                        : CompletableFuture.completedFuture(false))
                .whenComplete((changed, error) -> {
                    String message;
                    if (error != null) {
                        message = "New Tor identity failed: " + error.getMessage();
                    } else if (Boolean.TRUE.equals(changed)) {
                        message = "New Tor identity requested";
                    } else {
                        message = "New Tor identity unavailable (control port closed?)";
                    }
                    LOGGER.info(message);
                    try {
                        controller.disconnect();
                    } catch (Exception ignore) {
                        // best-effort
                    }
                    UiThread.marshal(() -> AccessibilitySupport.status(infoLabel, message));
                });
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
        java.util.concurrent.atomic.AtomicReference<Download> pendingDownload =
                new java.util.concurrent.atomic.AtomicReference<>();
        startButton.onClicked(() -> {
            String url = urlEntry.getText().trim();
            if (url.isEmpty()) {
                AccessibilitySupport.status(statusLabel, "Enter a URL");
                return;
            }
            try {
                Download download = pendingDownload.get();
                if (download == null) {
                    java.util.Map<String, String> options = new java.util.HashMap<>();
                    options.put("depth", String.valueOf((int) depthSpin.getValue()));
                    download = downloadManager.createWebsiteDownload(new java.net.URI(url),
                            java.nio.file.Path.of(downloadManager.getGlobalSettings()
                                    .getDefaultDownloadDirectory().toString()), options);
                    pendingDownload.set(download);
                }
                startButton.setSensitive(false);
                AccessibilitySupport.status(statusLabel, "Adding website scrape to queue…");
                downloadManager.queueDownload(download).whenComplete((ignored, error) ->
                        UiThread.marshal(() -> {
                            if (error == null) {
                                pendingDownload.set(null);
                                refresh();
                                scraper.close();
                            } else {
                                startButton.setSensitive(true);
                                AccessibilitySupport.status(statusLabel,
                                        "Could not add to queue: " + failureMessage(error)
                                                + ". Press Start Scrape to retry.",
                                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            }
                        }));
            } catch (Exception e) {
                AccessibilitySupport.status(statusLabel, "Invalid request: " + failureMessage(e),
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
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

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void onStatusSelectionChanged() {
        // Re-entrant guard: rebuildFilterStore re-selects the filter row,
        // which fires "changed" again — without the guard this recurses
        // infinitely (store clear + re-select + refresh loop).
        if (listPresenter.isRestoringSelection()) {
            return;
        }
        selectRow(statusTreeview.getSelection(), (path, index) -> {
            if (listPresenter.selectStatusFilterAt(index)) {
                refresh();
            }
        });
    }

    private void onCategorySelectionChanged() {
        if (listPresenter.isRestoringSelection()) {
            return;
        }
        selectRow(categoryTreeview.getSelection(), (path, index) -> {
            if (listPresenter.selectCategoryAt(index)) {
                refresh();
            }
        });
    }

    private void onDownloadSelectionChanged() {
        Out<TreeModel> model = new Out<>();
        org.gnome.glib.List<TreePath> paths =
                downloadsTreeview.getSelection().getSelectedRows(model);
        List<Integer> indexes = paths == null ? List.of() : paths.stream()
                .map(TreePath::getIndices)
                .filter(indices -> indices != null && indices.length > 0)
                .map(indices -> indices[0])
                .toList();
        selectedDownloads = listPresenter.rowsAt(indexes);
        selectedDownload = selectedDownloads.isEmpty()
                ? null : selectedDownloads.getFirst();
        updateInfoPanel();
    }

    SelectionMode downloadSelectionMode() {
        return downloadsTreeview.getSelection().getMode();
    }

    PropagationPhase downloadContextClickPhase() {
        return downloadContextClick.getPropagationPhase();
    }

    static List<String> downloadColumnLabels() {
        return DOWNLOAD_COLUMN_LABELS;
    }

    int mainMenuTopLevelCount() {
        org.gnome.gio.MenuModel model = menuBar.getMenuModel();
        return model == null ? 0 : model.getNItems();
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

    /**
     * Refreshes the download view and side-band labels from the current
     * repository state. GTK thread only.
     */
    private void refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            refreshAgain.set(true);
            return;
        }
        String selectedId = selectedDownload != null ? selectedDownload.getId() : null;
        try {
            CompletableFuture.supplyAsync(() -> loadRefreshSnapshot(selectedId), backgroundExecutor)
                .whenComplete((snapshot, error) -> UiThread.marshal(() -> {
                    try {
                        if (error != null) {
                            LOGGER.log(Level.WARNING, "Failed to refresh download list", error);
                            return;
                        }
                        applyRefresh(snapshot);
                    } finally {
                        refreshInFlight.set(false);
                        if (refreshAgain.getAndSet(false)) {
                            refresh();
                        }
                    }
                }));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            refreshInFlight.set(false);
        }
    }

    /** Loads a bounded recent-history window plus bounded active/queued
     * status slices. Repository-wide status counts remain O(1) via indices. */
    private RefreshSnapshot loadRefreshSnapshot(String selectedId) {
        java.util.LinkedHashMap<String, Download> visible = new java.util.LinkedHashMap<>();
        addRefreshRows(visible, downloadManager.getDownloads(0, HISTORY_WINDOW_SIZE));
        for (Download.Status status : ALWAYS_VISIBLE_STATUSES) {
            addRefreshRows(visible,
                    downloadManager.getDownloadsByStatus(status, 0, HISTORY_WINDOW_SIZE));
        }
        if (selectedId != null) {
            Download selected = downloadManager.getDownload(selectedId);
            if (selected != null) {
                visible.putIfAbsent(selected.getId(), selected);
            }
        }
        java.util.EnumMap<Download.Status, Integer> statusCounts =
                new java.util.EnumMap<>(Download.Status.class);
        for (Download.Status status : Download.Status.values()) {
            statusCounts.put(status, downloadManager.getDownloadCountByStatus(status));
        }
        return new RefreshSnapshot(new java.util.ArrayList<>(visible.values()),
                downloadManager.getDownloadCount(), statusCounts);
    }

    private static void addRefreshRows(java.util.Map<String, Download> target,
            List<Download> rows) {
        if (rows == null) {
            return;
        }
        for (Download download : rows) {
            if (download != null) {
                target.putIfAbsent(download.getId(), download);
            }
        }
    }

    /** Applies an already-fetched repository snapshot on the GTK thread. */
    private void applyRefresh(RefreshSnapshot snapshot) {
        DownloadListPresenter.RefreshSummary summary = listPresenter.refresh(
                snapshot.downloads(), snapshot.totalCount(), snapshot.statusCounts());
        if (summary.structureChanged()) {
            // GtkTreeSelection emits no useful row when the model was just
            // cleared, so explicitly invalidate the pointer used by actions.
            downloadsTreeview.getSelection().unselectAll();
            selectedDownload = null;
            selectedDownloads = List.of();
        }
        infoLabel.setLabel(summary.totalCount() + " download(s)");
        downSpeedLabel.setLabel(DownloadFormats.size(summary.downBytesPerSec()) + "/s");
        upSpeedLabel.setLabel(summary.upBytesPerSec() > 0
                ? DownloadFormats.size(summary.upBytesPerSec()) + "/s" : "—");
        dhtStatusLabel.setLabel(summary.totalSeeders() > 0
                ? "DHT: " + summary.totalSeeders() + " seed(s)" : "DHT: —");
        activitySpinner.setSpinning(summary.anyActive());
        updateInfoPanel();
    }

    /**
     * Handles a torrent-file checkbox toggle in the Files tab: flips the row
     * and pushes the selected file indexes to aria2 via the
     * {@code select-file} option. Active torrents are paused around the
     * change because aria2 applies select-file reliably only while paused.
     * aria2 offers skip/include only — no per-file priority.
     */
    private void onFileSelectionToggled(String pathStr) {
        Download download = selectedDownload;
        if (download == null || !(download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings)) {
            return;
        }

        // Flip the toggled row
        TreeIter iter = new TreeIter();
        if (!filesStore.getIterFromString(iter, pathStr)) {
            return;
        }
        boolean newValue = !ListStoreCells.getBoolean(filesStore, iter, 0);
        ListStoreCells.setBoolean(filesStore, iter, 0, newValue);

        // Collect stable aria2 indexes; the visible row order may be sorted.
        java.util.List<Integer> selectedIndexes = new java.util.ArrayList<>();
        TreeIter walk = new TreeIter();
        if (filesStore.getIterFirst(walk)) {
            do {
                if (ListStoreCells.getBoolean(filesStore, walk, 0)) {
                    selectedIndexes.add(ListStoreCells.getInt(filesStore, walk, 5));
                }
            } while (filesStore.iterNext(walk));
        }
        if (selectedIndexes.isEmpty()) {
            LOGGER.warning("Refusing to deselect every file of " + download.getName());
            ListStoreCells.setBoolean(filesStore, iter, 0, true);
            return;
        }

        String selectFile = selectedIndexes.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        aria2Settings.setOption("select-file", selectFile);

        boolean wasActive = download.getStatus() == Download.Status.DOWNLOADING
                && download.getGid() != null;
        // The pause/change/resume chain performs aria2 RPC round trips; run
        // it off the GTK thread instead of blocking the main loop on join().
        CompletableFuture.runAsync(() -> {
            if (wasActive) {
                downloadManager.pauseDownload(download).join();
            }
        }, backgroundExecutor)
                .thenCompose(v -> downloadManager.changeSettings(download))
                .handle((v, e) -> {
                    if (e != null) {
                        LOGGER.log(java.util.logging.Level.WARNING,
                                "Failed to apply file selection to " + download.getName(), e);
                    }
                    return null;
                })
                .thenCompose(v -> wasActive
                        ? downloadManager.resumeDownload(download)
                        : CompletableFuture.completedFuture(null));
    }

    private void updateInfoPanel() {
        if (selectedDownload == null) {
            infoProgressBar.setFraction(0);
            infoProgressBar.setText(ProgressPresentation.percentage(0));
            totalSizeValue.setLabel("—");
            addedOnValue.setLabel("—");
            infoHashValue.setLabel("—");
            folderValue.setLabel("—");
            folderOpenButton.setSensitive(false);
            engineIcon.clear();
            engineValue.setLabel("—");
            etaValue.setLabel("—");
            downloadedValue.setLabel("—");
            connectionsValue.setLabel("—");
            seedsPeersValue.setLabel("—");
            detailTabsPresenter.load();
            return;
        }
        infoProgressBar.setFraction(ProgressPresentation.fraction(selectedDownload.getProgress()));
        infoProgressBar.setText(ProgressPresentation.percentage(selectedDownload.getProgress()));
        totalSizeValue.setLabel(DownloadFormats.size(selectedDownload.getSize()));
        addedOnValue.setLabel(selectedDownload.getCreatedAt() != null
                ? DownloadFormats.DATE_FORMAT.format(selectedDownload.getCreatedAt()) : "—");
        infoHashValue.setLabel(selectedDownload.getInfoHash() != null ? selectedDownload.getInfoHash() : "—");
        folderValue.setLabel(selectedDownload.getDestination() != null ? selectedDownload.getDestination().toString() : "—");
        folderOpenButton.setSensitive(selectedDownload.getDestination() != null);
        engineIcon.setFromIconName(DownloadEnginePresentation.iconName(selectedDownload.getType()));
        engineValue.setLabel(DownloadEnginePresentation.displayName(selectedDownload.getType()));
        etaValue.setLabel(DownloadFormats.eta(selectedDownload));
        downloadedValue.setLabel(DownloadFormats.size(selectedDownload.getDownloaded()));
        connectionsValue.setLabel(String.valueOf(selectedDownload.getConnectionCount()));
        seedsPeersValue.setLabel(selectedDownload.getSeeders() > 0
                ? selectedDownload.getSeeders() + " seed(s)"
                : "—");
        detailTabsPresenter.load();
    }

}
