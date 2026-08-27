package org.odm.gtk4;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.gnome.gobject.Value;
import org.javagi.base.Out;
import org.javagi.gobject.types.Types;
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
    private final org.gnome.gtk.Widget leftPanelWidget;
    private final org.gnome.gtk.Widget infoPanelWidget;
    private final DownloadManager downloadManager;

    private final DownloadListPresenter listPresenter;
    private final DetailTabsPresenter detailTabsPresenter;

    // Listeners registered with core services; kept as fields so the window
    // can detach them on final close instead of leaking refresh work forever
    private DownloadListener windowDownloadListener;
    private org.manager.download.action.AfterCompletionActionListener windowCompletionListener;
    private org.manager.clipboard.ClipboardServiceListener windowClipboardListener;
    /** Optional app-provided exit sequence (graceful shutdown UI). */
    private Runnable finalCloseDelegate;
    private Download selectedDownload;
    /** Guard so menu actions register on the window only once. */
    private boolean menuActionsRegistered;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final ListStore filesStore;
    private final ListStore globalProgressStore;
    private final org.tor.TorService torService;
    private final org.manager.schedule.ScheduleManager scheduleManager;
    private org.manager.download.action.AfterCompletionAction completionAction;
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
        this.folderValue = Widgets.require(builder, "folder_value", Label.class);
        this.etaValue = Widgets.require(builder, "eta_value", Label.class);
        this.downloadedValue = Widgets.require(builder, "downloaded_value", Label.class);
        this.connectionsValue = Widgets.require(builder, "connections_value", Label.class);
        this.seedsPeersValue = Widgets.require(builder, "seeds_peers_value", Label.class);

        this.listPresenter = new DownloadListPresenter(statusStore, categoryStore,
                downloadsStore, globalProgressStore, statusTreeview, categoryTreeview,
                this::refresh);
        this.detailTabsPresenter = new DetailTabsPresenter(downloadManager, trackersStore,
                peersStore, filesStore, () -> selectedDownload);
        if (app != null) {
            window.setApplication(app);
        }

        restoreWindowState(builder);
        window.onCloseRequest(() -> {
            boolean toTray = downloadManager.getGlobalSettings().getBooleanProperty("ui.systemTray", false);
            if (toTray) {
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
        downloadsTreeview.getSelection().onChanged(this::onDownloadSelectionChanged);
        downloadsTreeview.onRowActivated((path, column) -> onPropertiesClicked());

        // Torrent per-file selection: toggle a row -> apply aria2 select-file
        Widgets.require(builder, "files_selected_renderer", org.gnome.gtk.CellRendererToggle.class)
                .onToggled(this::onFileSelectionToggled);

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
        this.leftPanelWidget = Widgets.require(builder, "left_panel", org.gnome.gtk.Widget.class);
        this.infoPanelWidget = Widgets.require(builder, "info_panel_box", org.gnome.gtk.Widget.class);
        menuButton.setMenuModel(buildMainMenu());

        windowDownloadListener = new DownloadListener() {
            @Override public void onDownloadStart(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadPause(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadResume(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadComplete(Download d) {
                executeCompletionAction(d);
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
                            infoLabel.setLabel("THREAT DETECTED in " + d.getName()
                                    + " — scan result: " + av.getScanResult());
                            LOGGER.warning("Antivirus threat detected in " + d.getName()
                                    + ": " + av.getScanResult());
                        } else {
                            infoLabel.setLabel("Antivirus scan completed for " + d.getName());
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

    /** Really destroys the window (bypasses the close-request handler). */
    public void dispose() {
        window.destroy();
    }

    /**
     * Detaches every listener this window registered with core services.
     * Called from the final close path; without it the manager keeps
     * dispatching refresh work to a dead window forever.
     */
    private void removeWindowListeners() {
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
        new SettingsDialog(window, downloadManager, scheduleManager).present();
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

    private void onPropertiesClicked() {
        onDownloadSelectionChanged();
        if (selectedDownload != null) {
            new PropertyDialog(window, downloadManager, selectedDownload).present();
        }
    }

    /** Context menu built once and reused; the actions re-read the selection at click time. */
    private PopupMenu contextMenu;

    private void showContextMenu() {
        onDownloadSelectionChanged();
        if (selectedDownload == null) return;
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
                            downloadManager.cancelDownload(selectedDownload, true));
        }
        contextMenu.popup();
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
            infoLabel.setLabel("Magnet URI copied");
        }
    }

    /** Changes the destination folder of the selected download. */
    private void changeDestination() {
        if (selectedDownload == null) {
            return;
        }
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        dialog.setTitle("Select new destination");
        dialog.selectFolder(window, null, result -> {
            try {
                org.gnome.gio.File folder = dialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null && selectedDownload != null) {
                    selectedDownload.setDestination(
                            java.nio.file.Path.of(folder.getPath().toString()));
                    downloadManager.changeSettings(selectedDownload);
                    UiThread.marshal(this::refresh);
                }
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "Destination change cancelled or failed", e);
            }
        });
    }

    /** Requests an integrity re-check of the selected download (aria2). */
    private void verifyData() {
        if (selectedDownload == null) {
            return;
        }
        if (selectedDownload.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setOption("check-integrity", "true");
            downloadManager.changeSettings(selectedDownload);
            infoLabel.setLabel("Integrity check requested");
        }
    }

    /**
     * Builds the full main menu as a Gio.Menu — 1:1 port of the original
     * menu bar (File/Edit/View/Download/Help with submenus, toggles, and
     * radio items), shown from the toolbar's MenuButton.
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
        batch.append("Export Download List", "win.export-file");
        file.appendSubmenu("Batch Process", batch);
        file.append("Offline Mode", "win.offline");
        file.append("Exit", "win.quit");
        menu.appendSubmenu("File", file);

        // Edit
        org.gnome.gio.Menu edit = new org.gnome.gio.Menu();
        edit.append("Clipboard Monitoring", "win.clipboard-monitoring");
        edit.append("Silent Mode", "win.clipboard-silent");
        org.gnome.gio.Menu completion = new org.gnome.gio.Menu();
        completion.append("None", "win.completion::none");
        completion.append("Notify (sound)", "win.completion::notify");
        completion.append("Antivirus Scan", "win.completion::antivirus");
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
        menu.appendSubmenu("Edit", edit);

        // View
        org.gnome.gio.Menu view = new org.gnome.gio.Menu();
        view.append("Left Panel", "win.left-panel");
        view.append("Info Panel", "win.info-panel");
        String[] columnLabels = {"#", "Name", "Completed", "Size", "Progress", "Elapsed",
                "Left", "Down Speed", "Up Speed", "Retry", "Start Date", "End Date", "Type"};
        org.gnome.gio.Menu columns = new org.gnome.gio.Menu();
        for (int i = 0; i < columnLabels.length; i++) {
            columns.append(columnLabels[i], "win.col-" + i);
        }
        view.appendSubmenu("Columns", columns);
        menu.appendSubmenu("View", view);

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
        menu.appendSubmenu("Download", download);

        // Help
        org.gnome.gio.Menu help = new org.gnome.gio.Menu();
        help.append("Statistics", "win.statistics");
        help.append("Donation", "win.donation");
        help.append("About", "win.about");
        menu.appendSubmenu("Help", help);

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
        addAction("import-file", () -> new ImportListDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh)).present());
        addAction("import-html", this::onImportHtml);
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
                downloadManager.cancelDownload(selectedDownload, true);
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
                    : selectedDownload.getDestination().resolve(selectedDownload.getName());
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
                        DetailTabsPresenter.FETCH_EXECUTOR).thenAccept(count -> {
                    if (count == null || count < 0) {
                        return;
                    }
                    UiThread.marshal(() -> {
                        infoLabel.setLabel("Imported " + count + " link(s) from HTML");
                        refresh();
                    });
                });
            } catch (Exception e) {
                LOGGER.log(java.util.logging.Level.FINE, "HTML import cancelled or failed", e);
            }
        });
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
                        UiThread.marshal(() -> infoLabel.setLabel("Exported download list"));
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.FINE, "Export failed", e);
                    }
                }, DetailTabsPresenter.FETCH_EXECUTOR);
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
        downloadManager.getGlobalSettings().save();
        LOGGER.info("Schedule preset applied: " + preset);
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
        // Single mechanism: register through the core facade (idempotent per
        // instance, additive with per-download actions set elsewhere such as
        // the new-download dialog) and execute through it as well
        downloadManager.addAfterCompletionAction(download, completionAction);
        downloadManager.executeAfterCompletionActions(download)
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
                    // Reconfigure running downloads to use the new proxy
                    downloadManager.applyGlobalSettingsToActiveDownloads();
                    verifyTorCircuit();
                } else {
                    LOGGER.warning("Tor failed to start");
                    UiThread.marshal(() -> torSwitchSet(false));
                }
            });
        } else {
            torService.stop();
            downloadManager.getGlobalSettings().setGlobalProxyEnabled(false);
            // Clear the proxy from running downloads
            downloadManager.applyGlobalSettingsToActiveDownloads();
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
    private void verifyTorCircuit() {
        TorLeakHolder.checker = new org.tor.TorLeakChecker(
                "127.0.0.1", torService.getSocksPort(), 5000, 5000);
        TorLeakHolder.checker.performLeakCheck()
                .whenComplete((result, error) -> {
                    try {
                        if (TorLeakHolder.checker != null) {
                            TorLeakHolder.checker.shutdown();
                        }
                    } catch (Exception ignore) {
                        // shutdown is best-effort
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
                    UiThread.marshal(() -> infoLabel.setLabel(message));
                });
    }

    /** Holder for the transient leak-checker instance. */
    private static final class TorLeakHolder {

        static volatile org.tor.TorLeakChecker checker;
    }

    /**
     * Requests a new Tor circuit (NEWNYM) through the control port. Wired to
     * the "New Tor Identity" menu action; no-op with an info-bar notice when
     * Tor is not running or the control port is unavailable.
     */
    private void onTorNewIdentity() {
        if (!torService.isRunning()) {
            infoLabel.setLabel("Tor is not running");
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
                    UiThread.marshal(() -> infoLabel.setLabel(message));
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
        startButton.onClicked(() -> {
            String url = urlEntry.getText().trim();
            if (url.isEmpty()) {
                infoLabel.setLabel("Enter a URL");
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
                infoLabel.setLabel("Invalid URL");
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
        selectRow(downloadsTreeview.getSelection(), (path, index) -> {
            selectedDownload = listPresenter.rowAt(index);
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

    /**
     * Refreshes the download view and side-band labels from the current
     * repository state. GTK thread only.
     */
    private void refresh() {
        DownloadListPresenter.RefreshSummary summary =
                listPresenter.refresh(downloadManager.getAllDownloads());
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
        Value current = new Value().init(Types.BOOLEAN);
        filesStore.getValue(iter, 0, current);
        boolean newValue = !current.getBoolean();
        current.unset();
        ListStoreCells.setBoolean(filesStore, iter, 0, newValue);

        // Collect all selected indexes (row order == getDownloadFiles order)
        java.util.List<Integer> selectedIndexes = new java.util.ArrayList<>();
        int index = 0;
        TreeIter walk = new TreeIter();
        if (filesStore.getIterFirst(walk)) {
            do {
                Value v = new Value().init(Types.BOOLEAN);
                filesStore.getValue(walk, 0, v);
                if (v.getBoolean()) {
                    selectedIndexes.add(index);
                }
                v.unset();
                index++;
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
        }, DetailTabsPresenter.FETCH_EXECUTOR)
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
        totalSizeValue.setLabel(DownloadFormats.size(selectedDownload.getSize()));
        addedOnValue.setLabel(selectedDownload.getCreatedAt() != null
                ? DownloadFormats.DATE_FORMAT.format(selectedDownload.getCreatedAt()) : "—");
        infoHashValue.setLabel(selectedDownload.getInfoHash() != null ? selectedDownload.getInfoHash() : "—");
        folderValue.setLabel(selectedDownload.getDestination() != null ? selectedDownload.getDestination().toString() : "—");
        etaValue.setLabel(DownloadFormats.eta(selectedDownload));
        downloadedValue.setLabel(DownloadFormats.size(selectedDownload.getDownloaded()));
        connectionsValue.setLabel(String.valueOf(selectedDownload.getConnectionCount()));
        seedsPeersValue.setLabel(selectedDownload.getSeeders() > 0
                ? selectedDownload.getSeeders() + " seed(s)"
                : "—");
        detailTabsPresenter.load();
    }

}
