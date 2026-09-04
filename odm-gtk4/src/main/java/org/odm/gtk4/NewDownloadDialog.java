package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererCombo;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.Window;
import org.manager.download.DescriptorImport;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * New Download dialog with Download, Files, and per-download engine/network
 * option tabs. This explicit user action queues immediately; background
 * automatic-start and descriptor-trash policies stay in Settings.
 */
public class NewDownloadDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewDownloadDialog.class);
    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;

    private final Entry urlEntry;
    private final PathChooserButton torrentFileChooser;
    private final PathChooserButton saveFolderChooser;
    private final Label diskSpaceLabel;
    private final Entry filenameEntry;
    private final TreeStore filesListstore;
    private final TreeView filesTreeview;
    private final Label filesStatusLabel;
    private final CheckButton selectAllFilesCheck;
    private final Notebook optionsNotebook;
    private final java.util.Map<String, TreeRowReference> fileRows = new java.util.HashMap<>();
    private final SpinButton maxConnectionsSpin;
    private final SpinButton retryLimitSpin;
    private final SpinButton maxDownloadSpeedSpin;
    private final SpinButton maxUploadSpeedSpin;
    private final SpinButton retryAfterSpin;
    private final Entry referrerEntry;
    private final Entry cookieEntry;
    private final Entry userAgentEntry;
    private final Label sftpHostKeyLabel;
    private final Entry sftpHostKeyEntry;
    private final DropDown proxyTypeCombo;
    private final Entry proxyHostEntry;
    private final SpinButton proxyPortSpin;
    private final Entry proxyUsernameEntry;
    private final Entry proxyPasswordEntry;
    private final Switch torSwitch;
    private final Label checksumLabel;
    private final CheckButton verifyChecksumCheck;
    private final Button startButton;
    private final SpinnerActivity activity;
    /** Reused after a queue rejection so Retry cannot create duplicate rows. */
    private Download pendingDownload;

    private Path selectedTorrentFile;
    private Path destinationFolder;
    private boolean updatingFilenameSuggestion;
    private boolean filenameEditedByUser;
    private boolean updatingSelectAll;
    private boolean previewLoading;
    private boolean submissionInFlight;
    private long previewEpoch;
    private URI loadedPreviewSource;

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
        this.filesListstore = Widgets.require(builder, "files_liststore", TreeStore.class);
        this.filesTreeview = Widgets.require(builder, "files_treeview", TreeView.class);
        this.filesStatusLabel = Widgets.require(builder, "files_status_label", Label.class);
        this.selectAllFilesCheck = Widgets.require(builder,
                "select_all_files_check", CheckButton.class);
        this.optionsNotebook = Widgets.require(builder, "options_notebook", Notebook.class);
        this.maxConnectionsSpin = Widgets.require(builder, "max_connections_spin", SpinButton.class);
        this.retryLimitSpin = Widgets.require(builder, "retry_limit_spin", SpinButton.class);
        this.maxDownloadSpeedSpin = Widgets.require(builder, "max_download_speed_spin", SpinButton.class);
        this.maxUploadSpeedSpin = Widgets.require(builder, "max_upload_speed_spin", SpinButton.class);
        this.retryAfterSpin = Widgets.require(builder, "retry_after", SpinButton.class);
        this.referrerEntry = Widgets.require(builder, "referrer", Entry.class);
        this.cookieEntry = Widgets.require(builder, "cookie", Entry.class);
        this.userAgentEntry = Widgets.require(builder, "user_agent", Entry.class);
        this.sftpHostKeyLabel = Widgets.require(builder, "sftp_host_key_label", Label.class);
        this.sftpHostKeyEntry = Widgets.require(builder, "sftp_host_key_entry", Entry.class);
        this.proxyTypeCombo = Widgets.require(builder, "proxy_type_combo", DropDown.class);
        this.proxyHostEntry = Widgets.require(builder, "proxy_host_entry", Entry.class);
        this.proxyPortSpin = Widgets.require(builder, "proxy_port_spin", SpinButton.class);
        this.proxyUsernameEntry = Widgets.require(builder, "proxy_username_entry", Entry.class);
        this.proxyPasswordEntry = Widgets.require(builder, "proxy_password_entry", Entry.class);
        this.torSwitch = Widgets.require(builder, "tor_switch", Switch.class);
        this.checksumLabel = Widgets.require(builder, "checksum_label", Label.class);
        this.verifyChecksumCheck = Widgets.require(builder, "verify_checksum_check", CheckButton.class);
        this.startButton = Widgets.require(builder, "new_download_start_button", Button.class);
        this.activity = new SpinnerActivity(
                Widgets.require(builder, "new_download_spinner", Spinner.class));

        AccessibilitySupport.label(urlEntry, "Download URL");
        AccessibilitySupport.label(torrentFileButton, "Choose torrent or Metalink descriptor");
        AccessibilitySupport.label(saveFolderButton, "Download destination folder");
        AccessibilitySupport.label(filenameEntry, "Output filename");
        AccessibilitySupport.label(filesTreeview, "Files in the torrent or Metalink");
        AccessibilitySupport.label(selectAllFilesCheck, "Select every descriptor file");
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
        AccessibilitySupport.label(sftpHostKeyEntry, "Expected SFTP host key digest");
        sftpHostKeyEntry.setTooltipText(
                "Expected server public-key digest: sha-1=<40 hex digits> or md5=<32 hex digits>. "
                + "Leaving it blank disables aria2 host-key verification.");
        AccessibilitySupport.label(verifyChecksumCheck, "Verify checksum at completion");

        dialog.setTransientFor(parent);

        StringList proxyTypes = new StringList(new String[0]);
        for (String type : DialogOptions.PROXY_TYPES) {
            proxyTypes.append(type);
        }
        proxyTypeCombo.setModel(proxyTypes);

        ListStore priorityStore = Widgets.require(builder, "file_priority_store", ListStore.class);
        for (String priority : new String[]{FileTreeSupport.PRIORITY_HIGH,
                FileTreeSupport.PRIORITY_NORMAL, FileTreeSupport.PRIORITY_LOW}) {
            TreeIter iter = new TreeIter();
            priorityStore.append(iter);
            ListStoreCells.setString(priorityStore, iter, 0, priority);
        }
        filesTreeview.setExpanderColumn(Widgets.require(builder,
                "new_files_name_column", org.gnome.gtk.TreeViewColumn.class));
        Widgets.require(builder, "selected_renderer", CellRendererToggle.class)
                .onToggled(this::onFileSelectionToggled);
        Widgets.require(builder, "file_priority", CellRendererCombo.class)
                .onChanged((path, priorityIter) -> {
                    String priority = ListStoreCells.getString(priorityStore, priorityIter, 0);
                    FileTreeSupport.setPriority(filesListstore, path, priority);
                });
        selectAllFilesCheck.onToggled(this::onSelectAllFilesToggled);
        optionsNotebook.onSwitchPage((page, pageNumber) -> {
            if (pageNumber == 1) {
                requestCurrentFilePreview();
            }
        });

        loadGlobalDefaults();

        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.torrentFileChooser = PathChooserButton.forFile(torrentFileButton, dialog,
                "Select torrent or metalink file", null, path -> {
                    selectedTorrentFile = path;
                    if (!urlEntry.getText().isBlank()) {
                        urlEntry.setText("");
                    }
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
        sftpHostKeyEntry.onChanged(() ->
                sftpHostKeyEntry.getStyleContext().removeClass("error"));
        urlEntry.onChanged(() -> {
            urlEntry.getStyleContext().removeClass("error");
            analyzeUrl();
        });

        Widgets.require(builder, "new_download_cancel_button", Button.class)
                .onClicked(this::closeDialog);
        dialog.onCloseRequest(() -> {
            releaseFilePreviewRows();
            activity.dispose();
            return false;
        });
        startButton.onClicked(this::onStart);
        torSwitch.onStateSet(state -> {
            // Tor enabled -> route through the local Tor SOCKS proxy
            return false; // let the switch apply its new state
        });
    }

    public void present() {
        dialog.present();
        ClipboardUrlPrefill.populate(dialog, urlEntry, uri -> true);
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
        }
    }

    /** Updates filename/checksum hints and defers descriptor work until the Files tab is used. */
    private void analyzeUrl() {
        String url = urlEntry.getText().trim();
        invalidateFilePreview();
        if (!url.isEmpty() && selectedTorrentFile != null) {
            selectedTorrentFile = null;
            torrentFileChooser.clear();
        }
        resetChecksumUi();
        if (url.isEmpty()) {
            setSftpHostKeyVisible(false);
            filesStatusLabel.setLabel(
                    "Enter a torrent, magnet, or Metalink source to inspect its files.");
            return;
        }
        try {
            java.net.URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url);
            Download.Protocol protocol = Download.Protocol.fromUri(uri);
            setSftpHostKeyVisible(protocol == Download.Protocol.SFTP);
            if (protocol == Download.Protocol.MAGNET) {
                analyzeMagnet(url);
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                if (!filename.isEmpty() && filenameEntry.getText().isBlank()) {
                    setFilenameSuggestion(java.net.URLDecoder.decode(filename,
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (protocol == Download.Protocol.TORRENT
                    || protocol == Download.Protocol.MAGNET
                    || protocol == Download.Protocol.METALINK) {
                filesStatusLabel.setLabel(optionsNotebook.getCurrentPage() == 1
                        ? "Loading file metadata…"
                        : "Open the Files tab to load selectable file metadata.");
                if (optionsNotebook.getCurrentPage() == 1) {
                    requestCurrentFilePreview();
                }
            } else {
                showSingleUrlFile(uri);
                probeChecksumAsynchronously(uri);
            }
        } catch (Exception e) {
            setSftpHostKeyVisible(false);
            filesStatusLabel.setLabel("Enter a valid download URL or magnet link.");
        }
    }

    private void setSftpHostKeyVisible(boolean visible) {
        sftpHostKeyLabel.setVisible(visible);
        sftpHostKeyEntry.setVisible(visible);
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
        java.util.concurrent.CompletableFuture<Void> probe = java.util.concurrent.CompletableFuture
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
        activity.track(probe);
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

    /** Uses magnet display metadata for the filename while peers resolve the real file list. */
    private void analyzeMagnet(String magnet) {
        String displayName = magnetParam(magnet, "dn");
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

    /** Starts a real descriptor-content preview after a local file is selected. */
    private void analyzeTorrentFile() {
        invalidateFilePreview();
        if (selectedTorrentFile == null) {
            return;
        }
        filesStatusLabel.setLabel("Loading file metadata…");
        requestCurrentFilePreview();
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

    private void requestCurrentFilePreview() {
        URI source = currentFilePreviewSource();
        if (source == null) {
            return;
        }
        Download.Protocol protocol = Download.Protocol.fromUri(source);
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.MAGNET
                && protocol != Download.Protocol.METALINK) {
            return;
        }
        if (source.equals(loadedPreviewSource) && !FileTreeSupport.allIndexes(filesListstore).isEmpty()) {
            return;
        }

        long epoch = ++previewEpoch;
        FileTreeSupport.clear(filesListstore, fileRows);
        updateSelectAll(false, false, false);
        filesStatusLabel.setLabel(protocol == Download.Protocol.MAGNET
                ? "Retrieving magnet metadata from peers…"
                : "Reading descriptor files…");
        previewLoading = true;
        refreshStartSensitivity();
        CompletableFuture<java.util.List<org.manager.download.DownloadFileInfo>> preview =
                downloadManager.previewDownloadFiles(source, selectedPreviewProxy());
        if (preview == null) {
            preview = CompletableFuture.completedFuture(java.util.List.of());
        }
        activity.track(preview).whenComplete((files, error) -> UiThread.marshal(() -> {
            if (epoch != previewEpoch) {
                return;
            }
            previewLoading = false;
            if (error != null) {
                filesStatusLabel.setLabel("Could not load file metadata: " + rootMessage(error)
                        + ". Starting the download will include all files.");
                LOGGER.warn("File metadata preview failed for " + source, error);
                refreshStartSensitivity();
                return;
            }
            showPreviewFiles(source, files);
        }));
    }

    private String selectedPreviewProxy() {
        return DialogOptions.selectedProxyAddress(torSwitch.getActive(),
                (int) proxyTypeCombo.getSelected(), proxyHostEntry.getText(),
                (int) proxyPortSpin.getValue(), proxyUsernameEntry.getText(),
                proxyPasswordEntry.getText());
    }

    private URI currentFilePreviewSource() {
        if (selectedTorrentFile != null) {
            return selectedTorrentFile.toUri();
        }
        return safeCurrentUri();
    }

    private void showPreviewFiles(URI source,
            java.util.List<org.manager.download.DownloadFileInfo> files) {
        java.util.List<FileTreeSupport.Entry> rows = new java.util.ArrayList<>();
        long totalSize = 0;
        for (org.manager.download.DownloadFileInfo file : files) {
            rows.add(new FileTreeSupport.Entry(true, file.path(), file.length(), 0,
                    file.index(), FileTreeSupport.PRIORITY_NORMAL));
            totalSize += file.length();
        }
        boolean changed = FileTreeSupport.reconcile(filesListstore, fileRows, rows, null);
        if (changed) {
            FileTreeSupport.expandTopLevel(filesTreeview, filesListstore);
        }
        loadedPreviewSource = source;
        boolean available = !rows.isEmpty();
        updateSelectAll(available, available, false);
        filesStatusLabel.setLabel(available
                ? rows.size() + " file(s), " + DownloadFormats.size(totalSize)
                        + " — uncheck files you do not want."
                : "No selectable files were found in this source.");
        refreshStartSensitivity();
    }

    private void showSingleUrlFile(URI uri) {
        String path = uri.getPath();
        String name = path == null || path.isBlank()
                ? uri.getHost() : DetailTabsPresenter.fileName(path);
        showPreviewFiles(uri, java.util.List.of(
                new org.manager.download.DownloadFileInfo(1, name, 0)));
        selectAllFilesCheck.setSensitive(false);
        filesStatusLabel.setLabel("Single-file download");
    }

    private void onFileSelectionToggled(String path) {
        if (!FileTreeSupport.toggleSelection(filesListstore, path)) {
            return;
        }
        updateSelectionSummary();
    }

    private void onSelectAllFilesToggled() {
        if (updatingSelectAll) {
            return;
        }
        FileTreeSupport.selectAll(filesListstore, selectAllFilesCheck.getActive());
        updateSelectionSummary();
    }

    private void updateSelectionSummary() {
        int selected = FileTreeSupport.selectedIndexes(filesListstore).size();
        int total = FileTreeSupport.allIndexes(filesListstore).size();
        updateSelectAll(total > 0 && selected == total, total > 0,
                selected > 0 && selected < total);
        filesStatusLabel.setLabel(selected == 0 && total > 0
                ? "Select at least one file to start the download."
                : selected + " of " + total + " file(s) selected");
        refreshStartSensitivity();
    }

    private void updateSelectAll(boolean active, boolean sensitive, boolean inconsistent) {
        updatingSelectAll = true;
        try {
            selectAllFilesCheck.setActive(active);
            selectAllFilesCheck.setInconsistent(inconsistent);
            selectAllFilesCheck.setSensitive(sensitive);
        } finally {
            updatingSelectAll = false;
        }
    }

    private void invalidateFilePreview() {
        previewEpoch++;
        previewLoading = false;
        loadedPreviewSource = null;
        FileTreeSupport.clear(filesListstore, fileRows);
        updateSelectAll(false, false, false);
        // A superseded asynchronous preview will deliberately ignore its
        // completion callback. Restore the button here so editing the source
        // cannot leave Start disabled forever.
        refreshStartSensitivity();
    }

    /** Keeps Start unavailable while work is active or a descriptor selects no files. */
    private void refreshStartSensitivity() {
        boolean hasPreviewFiles = loadedPreviewSource != null
                && !FileTreeSupport.allIndexes(filesListstore).isEmpty();
        boolean hasValidSelection = !hasPreviewFiles
                || !FileTreeSupport.selectedIndexes(filesListstore).isEmpty();
        startButton.setSensitive(!previewLoading && !submissionInFlight && hasValidSelection);
    }

    private void releaseFilePreviewRows() {
        previewEpoch++;
        FileTreeSupport.freeReferences(fileRows);
    }

    private void closeDialog() {
        releaseFilePreviewRows();
        activity.dispose();
        dialog.close();
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
            pendingDownload = download;
            submissionInFlight = true;
            refreshStartSensitivity();
            AccessibilitySupport.status(diskSpaceLabel, "Adding download to queue…");
            Download submitted = download;
            activity.track(downloadManager.queueDownload(download)).whenComplete((ignored, error) ->
                    UiThread.marshal(() -> {
                        if (error == null) {
                            pendingDownload = null;
                            submissionInFlight = false;
                            finishSubmission(submitted);
                        } else {
                            submissionInFlight = false;
                            refreshStartSensitivity();
                            AccessibilitySupport.status(diskSpaceLabel,
                                    "Could not add to queue: " + rootMessage(error)
                                            + ". Press Start to retry.",
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warn("Queue rejected new download", error);
                        }
                    }));
        } catch (IllegalArgumentException e) {
            submissionInFlight = false;
            refreshStartSensitivity();
            LOGGER.warn("New download rejected: " + e.getMessage());
            if (!sftpHostKeyEntry.hasCssClass("error")) {
                urlEntry.getStyleContext().addClass("error");
            }
            AccessibilitySupport.status(diskSpaceLabel, "Cannot add download: " + e.getMessage(),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    private void finishSubmission(Download download) {
        LOGGER.info("Queued new download: " + download.getName());
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
            boolean trashOriginal = downloadManager.getGlobalSettings()
                    .getBooleanProperty("ui.moveTorrent", false);
            return DescriptorImport.create(downloadManager, selectedTorrentFile,
                    destination, trashOriginal);
        }

        String url = urlEntry.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Enter a URL or choose a torrent/metalink file.");
        }
        return downloadManager.createDownload(
                org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url), destination);
    }

    private void loadGlobalDefaults() {
        org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
        org.manager.download.DownloadSettingsFactory.NetworkDefaults network =
                org.manager.download.DownloadSettingsFactory.NetworkDefaults.from(settings);
        maxConnectionsSpin.setValue(network.maxConnections());
        retryLimitSpin.setValue(network.maxRetries());
        maxDownloadSpeedSpin.setValue(network.downloadLimitKb());
        maxUploadSpeedSpin.setValue(network.uploadLimitKb());
        retryAfterSpin.setValue(network.retryDelaySeconds());
        referrerEntry.setText(network.referer());
        cookieEntry.setText(network.cookie());
        userAgentEntry.setText(network.userAgent());
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

        if (download.getProtocol() == Download.Protocol.SFTP
                && download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            try {
                aria2Settings.setSshHostKeyDigest(sftpHostKeyEntry.getText());
            } catch (IllegalArgumentException invalidDigest) {
                sftpHostKeyEntry.getStyleContext().addClass("error");
                throw invalidDigest;
            }
        }

        applyFileChoices(download);
    }

    /** Applies the checked descriptor indexes and persists the displayed priorities. */
    private void applyFileChoices(Download download) {
        if (!(download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings)
                || !java.util.Objects.equals(currentFilePreviewSource(), loadedPreviewSource)) {
            return;
        }
        Download.Protocol protocol = download.getProtocol();
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.MAGNET
                && protocol != Download.Protocol.METALINK) {
            return;
        }
        java.util.List<Integer> all = FileTreeSupport.allIndexes(filesListstore);
        java.util.List<Integer> selected = FileTreeSupport.selectedIndexes(filesListstore);
        if (all.isEmpty()) {
            return;
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Select at least one file to download.");
        }
        if (selected.size() == all.size()) {
            aria2Settings.setSelectedFiles(null);
        } else {
            aria2Settings.setSelectedFiles(encodeFileSelection(selected));
        }
        aria2Settings.setFilePriorities(FileTreeSupport.priorities(filesListstore));
    }

    static String encodeFileSelection(java.util.List<Integer> indexes) {
        return indexes.stream().filter(index -> index != null && index > 0)
                .distinct().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString()
                : org.manager.util.OdmPaths.downloadDirectory().toString();
    }
}
