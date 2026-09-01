package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(NewDownloadDialog.class);
    private static final int FILE_SELECTED_COLUMN = 0;
    private static final int FILE_NAME_COLUMN = 1;
    private static final int FILE_SIZE_TEXT_COLUMN = 2;
    private static final int FILE_PRIORITY_TEXT_COLUMN = 3;
    private static final int FILE_SIZE_SORT_COLUMN = 4;
    private static final int FILE_PRIORITY_SORT_COLUMN = 5;

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;

    private final Entry urlEntry;
    private final PathChooserButton torrentFileChooser;
    private final PathChooserButton saveFolderChooser;
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
    private final Button startButton;
    /** Reused after a queue rejection so Retry cannot create duplicate rows. */
    private Download pendingDownload;

    private Path selectedTorrentFile;
    private Path destinationFolder;
    private boolean updatingFilenameSuggestion;
    private boolean filenameEditedByUser;

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
        Button torrentFileButton = Widgets.require(builder, "torrent_file_chooser", Button.class);
        MenuButton saveFolderButton = Widgets.require(builder, "save_folder_chooser", MenuButton.class);
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

        AccessibilitySupport.label(urlEntry, "Download URL");
        AccessibilitySupport.label(torrentFileButton, "Choose torrent or Metalink descriptor");
        AccessibilitySupport.label(saveFolderButton, "Download destination folder");
        AccessibilitySupport.label(filenameEntry, "Output filename");
        AccessibilitySupport.label(proxyTypeCombo, "Proxy type");
        AccessibilitySupport.label(proxyHostEntry, "Proxy host");
        AccessibilitySupport.label(proxyPortSpin, "Proxy port");
        AccessibilitySupport.label(proxyUsernameEntry, "Proxy username");
        AccessibilitySupport.label(proxyPasswordEntry, "Proxy password");
        AccessibilitySupport.label(torSwitch, "Route this download through Tor");
        AccessibilitySupport.label(maxConnectionsSpin, "Maximum connections");
        AccessibilitySupport.label(retryLimitSpin, "Retry limit");
        AccessibilitySupport.label(maxDownloadSpeedSpin, "Maximum download speed in KB per second");
        AccessibilitySupport.label(maxUploadSpeedSpin, "Maximum upload speed in KB per second");
        AccessibilitySupport.label(retryAfterSpin, "Seconds before retry");
        AccessibilitySupport.label(referrerEntry, "HTTP referrer");
        AccessibilitySupport.label(cookieEntry, "HTTP cookie header");
        AccessibilitySupport.label(userAgentEntry, "HTTP user agent");
        AccessibilitySupport.label(startAutomaticallyCheck, "Start automatically");
        AccessibilitySupport.label(moveTorrentCheck, "Move descriptor to drafts");
        AccessibilitySupport.label(verifyChecksumCheck, "Verify checksum at completion");

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        loadGlobalDefaults();

        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.torrentFileChooser = PathChooserButton.forFile(torrentFileButton, dialog,
                "Select torrent or metalink file", null, path -> {
                    selectedTorrentFile = path;
                    analyzeTorrentFile();
                });
        this.saveFolderChooser = PathChooserButton.forFolder(saveFolderButton, dialog,
                "Select destination folder", defaultDestination, path -> {
                    destinationFolder = path;
                    updateDiskSpace(path.toString());
                });
        this.destinationFolder = defaultDestination;
        updateDiskSpace(defaultDestination.toString());

        // Live URL analysis (mirrors the approved old UI): filename auto-fill
        // + magnet metadata + multi-file listing in the Files tab
        filenameEntry.onChanged(() -> {
            if (!updatingFilenameSuggestion) {
                filenameEditedByUser = true;
            }
        });
        urlEntry.onChanged(this::analyzeUrl);

        Widgets.require(builder, "new_download_cancel_button", Button.class).onClicked(dialog::close);
        this.startButton = Widgets.require(builder, "new_download_start_button", Button.class);
        startButton.onClicked(this::onStart);
        torSwitch.onStateSet(state -> {
            // Tor enabled -> route through the local Tor SOCKS proxy
            return false; // let the switch apply its new state
        });
    }

    public void present() {
        dialog.present();
        urlEntry.grabFocus();
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
            java.net.URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url);
            if (Download.Protocol.fromUri(uri) == Download.Protocol.MAGNET) {
                analyzeMagnet(url);
                return;
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                if (!filename.isEmpty() && filenameEntry.getText().isBlank()) {
                    setFilenameSuggestion(java.net.URLDecoder.decode(filename,
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
        String proxy = downloadManager.getGlobalSettings().isGlobalProxyEnabled()
                ? downloadManager.getGlobalSettings().getGlobalProxyAddress() : null;
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> org.manager.download.ChecksumProbe.probe(uri, proxy)
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
                    LOGGER.debug("Checksum probe failed", e);
                    return null;
                });
    }

    private java.net.URI safeCurrentUri() {
        return org.manager.clipboard.UrlDetector.normalizeAndValidate(urlEntry.getText())
                .orElse(null);
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
            setFilenameSuggestion(displayName);
        }
    }

    private void setFilenameSuggestion(String suggestion) {
        if (filenameEditedByUser || suggestion == null || suggestion.isBlank()) {
            return;
        }
        updatingFilenameSuggestion = true;
        try {
            filenameEntry.setText(suggestion);
        } finally {
            updatingFilenameSuggestion = false;
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
        appendFileInfo(filesListstore, selected, name, size, priority);
    }

    static void appendFileInfo(ListStore store, boolean selected, String name, long size,
            String priority) {
        TreeIter iter = new TreeIter();
        store.append(iter);
        long normalizedSize = Math.max(0, size);
        ListStoreCells.setBoolean(store, iter, FILE_SELECTED_COLUMN, selected);
        ListStoreCells.setString(store, iter, FILE_NAME_COLUMN, name);
        ListStoreCells.setString(store, iter, FILE_SIZE_TEXT_COLUMN,
                normalizedSize > 0 ? normalizedSize / 1024 + " KB" : "—");
        ListStoreCells.setString(store, iter, FILE_PRIORITY_TEXT_COLUMN, priority);
        ListStoreCells.setLong(store, iter, FILE_SIZE_SORT_COLUMN, normalizedSize);
        ListStoreCells.setInt(store, iter, FILE_PRIORITY_SORT_COLUMN,
                prioritySortKey(priority));
    }

    static int prioritySortKey(String priority) {
        if (priority == null) {
            return 0;
        }
        return switch (priority.toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> 1;
            case "normal" -> 2;
            case "high" -> 3;
            default -> 0;
        };
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
            Download download = pendingDownload;
            if (download == null) {
                download = createDownload();
                applyRequestedFilename(download);
                applyOptions(download);
                registerChecksumVerification(download);
            }
            if (!startAutomaticallyCheck.getActive()) {
                finishSubmission(download, false);
                return;
            }

            pendingDownload = download;
            startButton.setSensitive(false);
            AccessibilitySupport.status(diskSpaceLabel, "Adding download to queue…");
            Download submitted = download;
            downloadManager.queueDownload(download).whenComplete((ignored, error) ->
                    UiThread.marshal(() -> {
                        if (error == null) {
                            pendingDownload = null;
                            finishSubmission(submitted, true);
                        } else {
                            startButton.setSensitive(true);
                            AccessibilitySupport.status(diskSpaceLabel,
                                    "Could not add to queue: " + rootMessage(error)
                                            + ". Press Start to retry.",
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warn("Queue rejected new download", error);
                        }
                    }));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("New download rejected: " + e.getMessage());
            urlEntry.getStyleContext().addClass("error");
            AccessibilitySupport.status(diskSpaceLabel, "Cannot add download: " + e.getMessage(),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    private void finishSubmission(Download download, boolean queued) {
        LOGGER.info((queued ? "Queued" : "Created") + " new download: " + download.getName());
        if (onDownloadQueued != null) {
            onDownloadQueued.run();
        }
        dialog.close();
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void applyRequestedFilename(Download download) {
        if (!filenameEditedByUser) {
            return;
        }
        String requested = filenameEntry.getText().strip();
        download.setRequestedFileName(requested);
        if (!requested.isEmpty()) {
            download.setName(requested);
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
            Path descriptor = moveTorrentCheck.getActive()
                    ? moveDescriptorToDrafts(selectedTorrentFile)
                    : selectedTorrentFile;
            if (Download.Protocol.fromPath(descriptor) == Download.Protocol.METALINK) {
                return downloadManager.createMetaLinkDownload(descriptor.toUri(), destination);
            }
            return downloadManager.createTorrentDownload(descriptor, destination);
        }

        String url = urlEntry.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Enter a URL or choose a torrent/metalink file.");
        }
        return downloadManager.createDownload(
                org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url), destination);
    }

    private Path moveDescriptorToDrafts(Path source) {
        try {
            Path drafts = org.manager.util.OdmPaths.dataDirectory().resolve("drafts");
            java.nio.file.Files.createDirectories(drafts);
            Path target = drafts.resolve(source.getFileName());
            if (java.nio.file.Files.exists(target)) {
                String name = source.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String extension = dot > 0 ? name.substring(dot) : "";
                target = drafts.resolve(base + "-" + java.util.UUID.randomUUID() + extension);
            }
            return java.nio.file.Files.move(source, target);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not move the descriptor to ODM drafts: "
                    + e.getMessage(), e);
        }
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        maxConnectionsSpin.setValue(settings.getIntProperty("aria2.maxConnections", 8));
        retryLimitSpin.setValue(settings.getIntProperty("aria2.maxTries", 5));
        maxDownloadSpeedSpin.setValue(settings.getIntProperty("aria2.maxDownloadSpeedKb", 0));
        maxUploadSpeedSpin.setValue(settings.getIntProperty("aria2.maxUploadSpeedKb", 0));
        retryAfterSpin.setValue(settings.getIntProperty("aria2.retryWait", 0));
        referrerEntry.setText(settings.getProperty("aria2.referer", ""));
        cookieEntry.setText(settings.getProperty("aria2.cookie", ""));
        userAgentEntry.setText(settings.getProperty("aria2.userAgent", ""));
        startAutomaticallyCheck.setActive(
                settings.getBooleanProperty("ui.startAutomatically", true));
        moveTorrentCheck.setActive(settings.getBooleanProperty("ui.moveTorrent", false));
        torSwitch.setActive(settings.getBooleanProperty("tor.enabled", false));

        DialogOptions.ProxyFields proxy = settings.isGlobalProxyEnabled()
                ? DialogOptions.parseProxy(settings.getGlobalProxyAddress())
                : DialogOptions.ProxyFields.none();
        proxyTypeCombo.setSelected(proxy.typeIndex());
        proxyHostEntry.setText(proxy.host());
        proxyPortSpin.setValue(proxy.port());
        proxyUsernameEntry.setText(proxy.username());
        proxyPasswordEntry.setText(proxy.password());
    }

    private void applyOptions(Download download) {
        // Proxy: explicit proxy fields, or Tor SOCKS (shared assembly)
        DialogOptions.applyProxy(download, torSwitch.getActive(),
                (int) proxyTypeCombo.getSelected(),
                proxyHostEntry.getText(), (int) proxyPortSpin.getValue(),
                proxyUsernameEntry.getText(), proxyPasswordEntry.getText());

        // Uniform option handling via the engine-neutral seam: the shared
        // "max connections" field drives segmentation for every engine —
        // aria2 per-server connections, yt-dlp concurrent fragments, HTTrack
        // sockets (-c); curl intentionally stays single-connection as the
        // plain fallback engine.
        DialogOptions.applyCommon(download.getSettings(),
                (int) maxConnectionsSpin.getValue(),
                (int) maxDownloadSpeedSpin.getValue(),
                (int) maxUploadSpeedSpin.getValue(),
                (int) retryLimitSpin.getValue(),
                (int) retryAfterSpin.getValue(),
                referrerEntry.getText(), userAgentEntry.getText(), cookieEntry.getText());
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }
}
