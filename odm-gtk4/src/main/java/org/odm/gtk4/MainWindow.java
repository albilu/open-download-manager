package org.odm.gtk4;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Button;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
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
 * Main window (GTK4 port). Mirrors DownloadManager state into list stores and
 * receives core events exclusively through UiThread.marshal — the old UI
 * SIGSEGVed because progress events hit GTK from poller threads.
 *
 * Row-to-download mapping is index-based over a snapshot taken at each
 * refresh (rows are rebuilt in getAllDownloads() order).
 */
public class MainWindow {

    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());

    private final ApplicationWindow window;
    private final ListStore categoriesStore;
    private final ListStore downloadsStore;
    private final TreeView downloadsView;
    private final Label statusLabel;
    private final DownloadManager downloadManager;

    /** Row order of the downloads store at the last refresh. */
    private List<Download> rowSnapshot = new ArrayList<>();
    private Download selectedDownload;

    public MainWindow(Application app, DownloadManager downloadManager) {
        this.downloadManager = downloadManager;

        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");

        this.window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        this.categoriesStore = Widgets.require(builder, "categories_store", ListStore.class);
        this.downloadsStore = Widgets.require(builder, "downloads_store", ListStore.class);
        this.downloadsView = Widgets.require(builder, "downloads_view", TreeView.class);
        this.statusLabel = Widgets.require(builder, "status_label", Label.class);
        window.setApplication(app);

        downloadsView.getSelection().onChanged(this::onSelectionChanged);

        Widgets.require(builder, "add_button", Button.class).onClicked(this::onAddClicked);
        Widgets.require(builder, "pause_button", Button.class).onClicked(this::onPauseClicked);
        Widgets.require(builder, "resume_button", Button.class).onClicked(this::onResumeClicked);
        Widgets.require(builder, "cancel_button", Button.class).onClicked(this::onCancelClicked);
        Widgets.require(builder, "import_button", Button.class).onClicked(this::onImportClicked);
        Widgets.require(builder, "settings_button", Button.class).onClicked(this::onSettingsClicked);
        Widgets.require(builder, "about_button", Button.class).onClicked(() -> AboutDialogPresenter.present(window));

        downloadManager.addDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                    long totalBytes, float speed) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadPause(Download download) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadResume(Download download) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadComplete(Download download) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                UiThread.marshal(MainWindow.this::refresh);
            }

            @Override
            public void onDownloadCanceled(Download download) {
                UiThread.marshal(MainWindow.this::refresh);
            }
        });

        refresh();
    }

    public void present() {
        window.present();
    }

    private void onAddClicked() {
        new NewDownloadDialog(window, downloadManager, () -> UiThread.marshal(this::refresh)).present();
    }

    private void onImportClicked() {
        new ImportListDialog(window, downloadManager, () -> UiThread.marshal(this::refresh)).present();
    }

    private void onSettingsClicked() {
        new SettingsDialog(window, downloadManager).present();
    }

    private void onPauseClicked() {
        if (selectedDownload != null) {
            downloadManager.pauseDownload(selectedDownload);
        }
    }

    private void onResumeClicked() {
        if (selectedDownload != null) {
            downloadManager.resumeDownload(selectedDownload);
        }
    }

    private void onCancelClicked() {
        if (selectedDownload != null) {
            downloadManager.cancelDownload(selectedDownload, false);
        }
    }

    private void onSelectionChanged() {
        TreeSelection selection = downloadsView.getSelection();
        TreeIter iter = new TreeIter();
        Out<TreeModel> model = new Out<>();
        selectedDownload = null;
        if (selection.getSelected(model, iter)) {
            TreePath path = model.get().getPath(iter);
            int[] indices = path.getIndices();
            if (indices != null && indices.length > 0 && indices[0] < rowSnapshot.size()) {
                selectedDownload = rowSnapshot.get(indices[0]);
            }
        }
    }

    /** Rebuilds both stores from manager state. GTK thread only. */
    private void refresh() {
        List<Download> downloads = downloadManager.getAllDownloads();
        rowSnapshot = List.copyOf(downloads);

        downloadsStore.clear();
        int active = 0;
        int finished = 0;
        for (Download download : downloads) {
            TreeIter iter = new TreeIter();
            downloadsStore.append(iter);
            setRow(downloadsStore, iter, 0, download.getName());
            setRow(downloadsStore, iter, 1, formatSize(download.getSize()));
            setRow(downloadsStore, iter, 2, (int) download.getProgress()); // progress is 0-100
            setRow(downloadsStore, iter, 3, formatSize((long) download.getSpeed()) + "/s");
            setRow(downloadsStore, iter, 4, String.valueOf(download.getStatus()));
            if (download.getStatus() == Download.Status.DOWNLOADING) {
                active++;
            }
            if (download.getStatus() == Download.Status.COMPLETED) {
                finished++;
            }
        }

        categoriesStore.clear();
        TreeIter all = new TreeIter();
        categoriesStore.append(all);
        setRow(categoriesStore, all, 0, "All Status");
        setRow(categoriesStore, all, 1, downloads.size());
        TreeIter activeRow = new TreeIter();
        categoriesStore.append(activeRow);
        setRow(categoriesStore, activeRow, 0, "Active");
        setRow(categoriesStore, activeRow, 1, active);
        TreeIter finishedRow = new TreeIter();
        categoriesStore.append(finishedRow);
        setRow(categoriesStore, finishedRow, 0, "Finished");
        setRow(categoriesStore, finishedRow, 1, finished);

        statusLabel.setLabel(downloads.size() + " download(s), " + active + " active");
    }

    private static void setRow(ListStore store, TreeIter iter, int column, String value) {
        Value v = new Value().init(Types.STRING);
        v.setString(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private static void setRow(ListStore store, TreeIter iter, int column, int value) {
        Value v = new Value().init(Types.INT);
        v.setInt(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / 1048576.0);
    }
}
