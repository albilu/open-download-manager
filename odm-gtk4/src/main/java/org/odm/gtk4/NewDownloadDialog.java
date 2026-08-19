package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * New Download dialog — 1:1 GTK4 port of new-download.glade. Same widget ids,
 * same layout (Download / Files / Options tabs), same options surface
 * (connections, retry, speed limits, HTTP headers, proxy with auth, Tor,
 * start-automatically, move-torrent-to-draft). All signals connected
 * programmatically.
 */
public class NewDownloadDialog {

    private static final Logger LOGGER = Logger.getLogger(NewDownloadDialog.class.getName());
    private static final String[] PROXY_TYPES = {"None", "HTTP", "SOCKS4", "SOCKS5"};

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;

    private final Entry urlEntry;
    private final Button torrentFileChooser;
    private final Button saveFolderChooser;
    private final Label diskSpaceLabel;
    private final Entry filenameEntry;
    private final SpinButton maxConnectionsSpin;
    private final SpinButton retryLimitSpin;
    private final SpinButton maxDownloadSpeedSpin;
    private final SpinButton maxUploadSpeedSpin;
    private final SpinButton retryAfterSpin;
    private final Entry referrerEntry;
    private final Entry cookieEntry;
    private final Entry userAgentEntry;
    private final DropDown proxyTypeCombo;
    private final Entry proxyHostEntry;
    private final SpinButton proxyPortSpin;
    private final Entry proxyUsernameEntry;
    private final Entry proxyPasswordEntry;
    private final Switch torSwitch;
    private final CheckButton startAutomaticallyCheck;
    private final CheckButton moveTorrentCheck;

    private Path selectedTorrentFile;
    private Path destinationFolder;

    public NewDownloadDialog(Window parent, DownloadManager downloadManager, Runnable onDownloadQueued) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;

        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        this.dialog = Widgets.require(builder, "new_download_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "url_entry", Entry.class);
        this.torrentFileChooser = Widgets.require(builder, "torrent_file_chooser", Button.class);
        this.saveFolderChooser = Widgets.require(builder, "save_folder_chooser", Button.class);
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);
        this.filenameEntry = Widgets.require(builder, "filename_entry", Entry.class);
        this.maxConnectionsSpin = Widgets.require(builder, "max_connections_spin", SpinButton.class);
        this.retryLimitSpin = Widgets.require(builder, "retry_limit_spin", SpinButton.class);
        this.maxDownloadSpeedSpin = Widgets.require(builder, "max_download_speed_spin", SpinButton.class);
        this.maxUploadSpeedSpin = Widgets.require(builder, "max_upload_speed_spin", SpinButton.class);
        this.retryAfterSpin = Widgets.require(builder, "retry_after", SpinButton.class);
        this.referrerEntry = Widgets.require(builder, "referrer", Entry.class);
        this.cookieEntry = Widgets.require(builder, "cookie", Entry.class);
        this.userAgentEntry = Widgets.require(builder, "user_agent", Entry.class);
        this.proxyTypeCombo = Widgets.require(builder, "proxy_type_combo", DropDown.class);
        this.proxyHostEntry = Widgets.require(builder, "proxy_host_entry", Entry.class);
        this.proxyPortSpin = Widgets.require(builder, "proxy_port_spin", SpinButton.class);
        this.proxyUsernameEntry = Widgets.require(builder, "proxy_username_entry", Entry.class);
        this.proxyPasswordEntry = Widgets.require(builder, "proxy_password_entry", Entry.class);
        this.torSwitch = Widgets.require(builder, "tor_switch", Switch.class);
        this.startAutomaticallyCheck = Widgets.require(builder, "start_automatically_check", CheckButton.class);
        this.moveTorrentCheck = Widgets.require(builder, "move_torrent_check", CheckButton.class);

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        saveFolderChooser.setLabel(currentDefaultDirectory());
        updateDiskSpace(currentDefaultDirectory());

        torrentFileChooser.onClicked(this::onChooseTorrent);
        saveFolderChooser.onClicked(this::onChooseFolder);
        Widgets.require(builder, "new_download_cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "new_download_start_button", Button.class).onClicked(this::onStart);
        torSwitch.onStateSet(state -> {
            // Tor enabled -> route through the local Tor SOCKS proxy
            return false; // let the switch apply its new state
        });
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
                    torrentFileChooser.setLabel(selectedTorrentFile.getFileName().toString());
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
                    saveFolderChooser.setLabel(destinationFolder.toString());
                    updateDiskSpace(destinationFolder.toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Folder selection cancelled or failed", e);
            }
        });
    }

    private void updateDiskSpace(String dir) {
        try {
            long free = new java.io.File(dir).getUsableSpace();
            diskSpaceLabel.setLabel(String.format("%.2f GB free", free / (1024.0 * 1024 * 1024)));
        } catch (Exception e) {
            diskSpaceLabel.setLabel("");
        }
    }

    private void onStart() {
        try {
            Download download = createDownload();
            applyOptions(download);
            if (startAutomaticallyCheck.getActive()) {
                downloadManager.queueDownload(download);
            }
            // Unchecked "start automatically": createDownload already registered
            // the download in the repository; leaving it unqueued keeps it
            // unstarted in the list.
            LOGGER.info("Queued new download: " + download.getName());
            if (onDownloadQueued != null) {
                onDownloadQueued.run();
            }
            dialog.close();
        } catch (IllegalArgumentException e) {
            LOGGER.warning("New download rejected: " + e.getMessage());
            urlEntry.getStyleContext().addClass("error");
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
        // Proxy: explicit proxy fields, or Tor SOCKS
        if (torSwitch.getActive()) {
            download.setUseProxy(true);
            download.setProxyAddress("socks5://127.0.0.1:9050");
        } else if (proxyTypeCombo.getSelected() > 0 && !proxyHostEntry.getText().isBlank()) {
            StringBuilder proxy = new StringBuilder();
            String type = PROXY_TYPES[(int) proxyTypeCombo.getSelected()].toLowerCase();
            proxy.append(type).append("://");
            if (!proxyUsernameEntry.getText().isBlank()) {
                proxy.append(proxyUsernameEntry.getText().trim());
                if (!proxyPasswordEntry.getText().isEmpty()) {
                    proxy.append(':').append(proxyPasswordEntry.getText());
                }
                proxy.append('@');
            }
            proxy.append(proxyHostEntry.getText().trim());
            proxy.append(':').append((int) proxyPortSpin.getValue());
            download.setUseProxy(true);
            download.setProxyAddress(proxy.toString());
        }

        if (download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            aria2Settings.setMaxConnectionPerServer((int) maxConnectionsSpin.getValue());
            int downKb = (int) maxDownloadSpeedSpin.getValue();
            if (downKb > 0) {
                aria2Settings.setOption("max-download-limit", String.valueOf(downKb * 1024L));
            }
            int upKb = (int) maxUploadSpeedSpin.getValue();
            if (upKb > 0) {
                aria2Settings.setOption("max-upload-limit", String.valueOf(upKb * 1024L));
            }
            int retryLimit = (int) retryLimitSpin.getValue();
            if (retryLimit > 0) {
                aria2Settings.setOption("max-tries", String.valueOf(retryLimit));
            }
            int retryAfter = (int) retryAfterSpin.getValue();
            if (retryAfter > 0) {
                aria2Settings.setOption("retry-wait", String.valueOf(retryAfter));
            }
            if (!referrerEntry.getText().isBlank()) {
                aria2Settings.setOption("referer", referrerEntry.getText().trim());
            }
            if (!cookieEntry.getText().isBlank()) {
                aria2Settings.setOption("header", "Cookie: " + cookieEntry.getText().trim());
            }
            if (!userAgentEntry.getText().isBlank()) {
                aria2Settings.setOption("user-agent", userAgentEntry.getText().trim());
            }
        }
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }
}
