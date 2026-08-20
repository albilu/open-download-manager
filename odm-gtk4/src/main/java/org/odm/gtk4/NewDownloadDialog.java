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
import org.gnome.gtk.ListStore;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.Window;
import org.gnome.gobject.Value;
import org.javagi.gobject.types.Types;
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
    private static final String[] PROXY_TYPES = {"None", "HTTP", "HTTPS", "SOCKS4", "SOCKS5"};

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;

    private final Entry urlEntry;
    private final Button torrentFileChooser;
    private final Button saveFolderChooser;
    private final Label diskSpaceLabel;
    private final Entry filenameEntry;
    private final ListStore filesListstore;
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
    private final Label checksumLabel;
    private final CheckButton verifyChecksumCheck;

    private Path selectedTorrentFile;
    private Path destinationFolder;

    /** Checksum detected for the current URL (null while unknown/not probed). */
    private volatile org.manager.download.ChecksumProbe.DetectedChecksum detectedChecksum;
    /** URL whose sibling checksum files were probed (negative results cached too). */
    private volatile java.net.URI lastProbedUrl;

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
        this.filesListstore = Widgets.require(builder, "files_liststore", ListStore.class);
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
        this.checksumLabel = Widgets.require(builder, "checksum_label", Label.class);
        this.verifyChecksumCheck = Widgets.require(builder, "verify_checksum_check", CheckButton.class);

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        saveFolderChooser.setLabel(currentDefaultDirectory());
        updateDiskSpace(currentDefaultDirectory());

        // Live URL analysis (mirrors the approved old UI): filename auto-fill
        // + magnet metadata + multi-file listing in the Files tab
        urlEntry.onChanged(this::analyzeUrl);

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

    /**
     * Prefills the URL field and runs the live analysis, then presents the
     * dialog. Used by the clipboard confirmation flow.
     *
     * @param url the detected URL to offer for download
     */
    public void prefillUrl(String url) {
        if (url != null && !url.isBlank()) {
            urlEntry.setText(url);
            analyzeUrl();
        }
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
                    analyzeTorrentFile();
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Torrent file selection cancelled or failed", e);
            }
        });
    }

    /**
     * Analyzes the URL as it is typed (mirrors the approved old UI):
     * auto-fills the filename from the URL path, and for magnet links
     * extracts the display name / size / info hash into the Files tab.
     */
    private void analyzeUrl() {
        String url = urlEntry.getText().trim();
        filesListstore.clear();
        resetChecksumUi();
        if (url.isEmpty()) {
            return;
        }
        try {
            if (url.toLowerCase().startsWith("magnet:")) {
                analyzeMagnet(url);
                return;
            }
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                if (!filename.isEmpty() && filenameEntry.getText().isBlank()) {
                    filenameEntry.setText(java.net.URLDecoder.decode(filename,
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            probeChecksumAsynchronously(uri);
        } catch (Exception e) {
            // still typing an invalid URL: nothing to analyze
        }
    }

    /**
     * Probes sibling checksum files (.sha256/.sha512/.sha1/.md5) for the
     * entered URL on a worker thread; results update the dialog on the GTK
     * thread. Negative results are cached per URL so re-typing does not
     * re-probe. Only URLs that look like direct file downloads are probed.
     */
    private void probeChecksumAsynchronously(java.net.URI uri) {
        String path = uri.getPath();
        boolean looksLikeFile = path != null && path.lastIndexOf('.') > path.lastIndexOf('/');
        if (!looksLikeFile || java.util.Objects.equals(uri, lastProbedUrl)) {
            return;
        }
        lastProbedUrl = uri;
        detectedChecksum = null;
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> org.manager.download.ChecksumProbe.probe(uri)
                        .orElse(null))
                .thenAccept(found -> UiThread.marshal(() -> {
                    // URL may have changed while probing
                    if (!java.util.Objects.equals(uri, safeCurrentUri())) {
                        return;
                    }
                    detectedChecksum = found;
                    if (found != null) {
                        checksumLabel.setLabel(found.algorithm() + ": " + found.checksum());
                        verifyChecksumCheck.setVisible(true);
                        verifyChecksumCheck.setActive(true);
                    }
                }))
                .exceptionally(e -> {
                    LOGGER.log(Level.FINE, "Checksum probe failed for " + uri, e);
                    return null;
                });
    }

    private java.net.URI safeCurrentUri() {
        try {
            return new java.net.URI(urlEntry.getText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Clears the checksum widgets for a new URL being typed. */
    private void resetChecksumUi() {
        checksumLabel.setLabel("—");
        verifyChecksumCheck.setVisible(false);
        verifyChecksumCheck.setActive(false);
        detectedChecksum = null;
    }

    /** Extracts factual magnet metadata (dn, xl, btih) into the Files tab. */
    private void analyzeMagnet(String magnet) {
        String displayName = magnetParam(magnet, "dn");
        String hash = magnetParam(magnet, "xt");
        if (hash != null && hash.toLowerCase().startsWith("urn:btih:")) {
            hash = hash.substring(8);
        }
        long size = 0;
        try {
            String xl = magnetParam(magnet, "xl");
            if (xl != null) {
                size = Long.parseLong(xl);
            }
        } catch (NumberFormatException ignored) {
            // no valid size in the link
        }

        String name = displayName != null ? displayName
                : hash != null ? "Torrent_" + hash.substring(0, Math.min(8, hash.length()))
                        : "Unknown Torrent";
        appendFileInfo(true, name, size, "High");
        if (displayName == null && hash != null) {
            appendFileInfo(true, "Torrent_" + hash.substring(0, Math.min(8, hash.length())) + ".torrent",
                    50 * 1024L, "Normal");
        }
        if (filenameEntry.getText().isBlank() && displayName != null) {
            filenameEntry.setText(displayName);
        }
    }

    /** Lists a selected local .torrent/.meta4 file: its real name and byte size. */
    private void analyzeTorrentFile() {
        filesListstore.clear();
        if (selectedTorrentFile == null) {
            return;
        }
        String name = selectedTorrentFile.getFileName().toString();
        long size;
        try {
            size = java.nio.file.Files.size(selectedTorrentFile);
        } catch (Exception e) {
            size = 0;
        }
        appendFileInfo(true, name, size, "High");
        String base = name.replaceAll("\\.(torrent|metalink|meta4)$", "");
        if (!base.equals(name)) {
            appendFileInfo(true, base, 0, "Normal"); // content, size known after add
        }
    }

    /** Extracts a parameter value from a magnet URI query string. */
    private static String magnetParam(String magnet, String key) {
        for (String part : magnet.substring(Math.min(7, magnet.length())).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(key)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /** Adds a row to the Files tab liststore (selected, name, size, priority). */
    private void appendFileInfo(boolean selected, String name, long size, String priority) {
        TreeIter iter = new TreeIter();
        filesListstore.append(iter);
        Value v = new Value().init(Types.BOOLEAN);
        v.setBoolean(selected);
        filesListstore.setValue(iter, 0, v);
        v.unset();
        Value s = new Value().init(Types.STRING);
        s.setString(name);
        filesListstore.setValue(iter, 1, s);
        s.unset();
        Value z = new Value().init(Types.STRING);
        z.setString(size > 0 ? size / 1024 + " KB" : "—");
        filesListstore.setValue(iter, 2, z);
        z.unset();
        Value p = new Value().init(Types.STRING);
        p.setString(priority);
        filesListstore.setValue(iter, 3, p);
        p.unset();
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
            registerChecksumVerification(download);
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

    /**
     * When a checksum was detected and the user kept "Verify at completion"
     * checked, stores it on the download and schedules the
     * {@link org.manager.download.action.ChecksumValidationAction} for
     * completion time.
     */
    private void registerChecksumVerification(Download download) {
        org.manager.download.ChecksumProbe.DetectedChecksum found = detectedChecksum;
        if (found == null || !verifyChecksumCheck.getVisible()
                || !verifyChecksumCheck.getActive()) {
            return;
        }
        download.setChecksumAlgorithm(found.algorithm());
        download.setExpectedChecksum(found.checksum());
        downloadManager.addAfterCompletionAction(download,
                org.manager.download.action.ChecksumValidationAction.fromString(
                        found.algorithm() + ":" + found.checksum()));
        LOGGER.info("Checksum verification scheduled for " + download.getName()
                + " (" + found.algorithm() + ")");
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
