package org.odm.gtk4;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.TreeIter;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;

/**
 * Spike main window: proves the java-gi loop for ODM. Loads the GTK4 .ui via
 * GtkBuilder, mirrors the DownloadManager state into list stores, and receives
 * core events exclusively through UiThread.marshal (the old UI crashed
 * precisely because progress events hit GTK from poller threads).
 */
public class MainWindow {

    private static final Logger LOGGER = Logger.getLogger(MainWindow.class.getName());

    private final ApplicationWindow window;
    private final ListStore categoriesStore;
    private final ListStore downloadsStore;
    private final Label statusLabel;
    private final DownloadManager downloadManager;

    public MainWindow(Application app, DownloadManager downloadManager) {
        this.downloadManager = downloadManager;

        GtkBuilder builder;
        try {
            builder = GtkBuilder.fromString(readUiResource(), -1);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse main-window.ui", e);
        }

        this.window = (ApplicationWindow) builder.getObject("main_window");
        this.categoriesStore = (ListStore) builder.getObject("categories_store");
        this.downloadsStore = (ListStore) builder.getObject("downloads_store");
        this.statusLabel = (Label) builder.getObject("status_label");
        window.setApplication(app);

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

    /** Rebuilds both stores from manager state. GTK thread only. */
    private void refresh() {
        List<Download> downloads = downloadManager.getAllDownloads();

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

    private void setRow(ListStore store, TreeIter iter, int column, String value) {
        Value v = new Value().init(Types.STRING);
        v.setString(value);
        store.setValue(iter, column, v);
        v.unset();
    }

    private void setRow(ListStore store, TreeIter iter, int column, int value) {
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

    private static String readUiResource() {
        try (InputStream in = MainWindow.class.getResourceAsStream("/ui/main-window.ui")) {
            if (in == null) {
                throw new IllegalStateException("Missing /ui/main-window.ui resource");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read main-window.ui", e);
        }
    }
}
