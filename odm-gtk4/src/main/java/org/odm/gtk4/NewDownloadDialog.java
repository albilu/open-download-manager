package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * New Download dialog (GTK4 port). Creates a download from a URL or a local
 * torrent/metalink file, with per-download aria2 options, and queues it.
 * All signals are connected programmatically — a typo'd handler name is a
 * compile error here, not a silently dead button.
 */
public class NewDownloadDialog {

    private static final Logger LOGGER = Logger.getLogger(NewDownloadDialog.class.getName());

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;

    private final Entry urlEntry;
    private final Label torrentFileLabel;
    private final Label folderLabel;
    private final SpinButton maxConnectionsSpin;
    private final SpinButton maxSpeedSpin;
    private final CheckButton useProxyCheck;
    private final Entry proxyEntry;
    private final Label errorLabel;

    private Path selectedTorrentFile;
    private Path destinationFolder;

    public NewDownloadDialog(Window parent, DownloadManager downloadManager, Runnable onDownloadQueued) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;

        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        this.dialog = Widgets.require(builder, "new_download_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "url_entry", Entry.class);
        this.torrentFileLabel = Widgets.require(builder, "torrent_file_label", Label.class);
        this.folderLabel = Widgets.require(builder, "folder_label", Label.class);
        this.maxConnectionsSpin = Widgets.require(builder, "max_connections_spin", SpinButton.class);
        this.maxSpeedSpin = Widgets.require(builder, "max_speed_spin", SpinButton.class);
        this.useProxyCheck = Widgets.require(builder, "use_proxy_check", CheckButton.class);
        this.proxyEntry = Widgets.require(builder, "proxy_entry", Entry.class);
        this.errorLabel = Widgets.require(builder, "error_label", Label.class);

        dialog.setTransientFor(parent);
        folderLabel.setLabel(currentDefaultDirectory());

        Widgets.require(builder, "torrent_choose_button", Button.class)
                .onClicked(this::onChooseTorrent);
        Widgets.require(builder, "folder_choose_button", Button.class)
                .onClicked(this::onChooseFolder);
        Widgets.require(builder, "cancel_button", Button.class)
                .onClicked(dialog::close);
        Widgets.require(builder, "start_button", Button.class)
                .onClicked(this::onStart);
        useProxyCheck.onToggled(() -> proxyEntry.setSensitive(useProxyCheck.getActive()));
    }

    public void present() {
        dialog.present();
    }

    private void onChooseTorrent() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select torrent or metalink file");
        fileDialog.open(dialog, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    selectedTorrentFile = Path.of(file.getPath().toString());
                    torrentFileLabel.setLabel(selectedTorrentFile.getFileName().toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Torrent file selection cancelled or failed", e);
            }
        });
    }

    private void onChooseFolder() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select destination folder");
        fileDialog.selectFolder(dialog, null, result -> {
            try {
                File folder = fileDialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null) {
                    destinationFolder = Path.of(folder.getPath().toString());
                    folderLabel.setLabel(destinationFolder.toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Folder selection cancelled or failed", e);
            }
        });
    }

    private void onStart() {
        try {
            Download download = createDownload();
            applyOptions(download);
            downloadManager.queueDownload(download);
            LOGGER.info("Queued new download: " + download.getName());
            if (onDownloadQueued != null) {
                onDownloadQueued.run();
            }
            dialog.close();
        } catch (IllegalArgumentException e) {
            errorLabel.setLabel(e.getMessage());
            errorLabel.setVisible(true);
        }
    }

    private Download createDownload() {
        Path destination = destinationFolder != null ? destinationFolder
                : Path.of(currentDefaultDirectory());

        if (selectedTorrentFile != null) {
            return downloadManager.createTorrentDownload(selectedTorrentFile, destination);
        }

        String url = urlEntry.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Enter a URL or choose a torrent/metalink file.");
        }
        try {
            return downloadManager.createDownload(new URI(url), destination);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
    }

    private void applyOptions(Download download) {
        download.setUseProxy(useProxyCheck.getActive());
        if (useProxyCheck.getActive()) {
            String proxy = proxyEntry.getText().trim();
            if (proxy.isEmpty()) {
                throw new IllegalArgumentException("Proxy enabled but no proxy address given.");
            }
            download.setProxyAddress(proxy);
        }
        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setMaxConnectionPerServer((int) maxConnectionsSpin.getValue());
            int speedKb = (int) maxSpeedSpin.getValue();
            if (speedKb > 0) {
                // aria2 expects bytes per second
                aria2Settings.setOption("max-download-limit", String.valueOf(speedKb * 1024L));
            }
        }
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }
}
